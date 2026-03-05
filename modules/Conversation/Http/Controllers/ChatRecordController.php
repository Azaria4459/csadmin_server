<?php

declare(strict_types=1);

namespace Modules\Conversation\Http\Controllers;

use Catch\Base\CatchController as Controller;
use Catch\Exceptions\FailedException;
use Illuminate\Http\Request;
use Modules\Conversation\Models\WeChatMessage;
use Modules\Conversation\Models\WeChatChatRecord;
use Modules\Conversation\Models\WeChatChatRecordItem;
use Modules\Conversation\Services\ChatRecordMediaService;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;

/**
 * 聊天记录控制器
 * 处理 chatrecord 类型消息的下载和查询
 */
class ChatRecordController extends Controller
{
    /**
     * 获取聊天记录详情
     * 如果未下载则从企业微信下载并存储，如果已下载则直接从数据库查询
     * 
     * 请求参数：
     * - message_id: 消息ID（必填）
     * 
     * @param Request $request HTTP请求对象
     * @return array 返回数据数组
     */
    public function show(Request $request)
    {
        try {
            // 获取消息ID
            $messageId = (int) $request->get('message_id');
            
            if (!$messageId) {
                throw new FailedException('消息ID不能为空', 10005);
            }

            // 查询消息（移除全局作用域）
            $message = WeChatMessage::withoutGlobalScopes()->find($messageId);
            
            if (!$message) {
                throw new FailedException('消息不存在', 10005);
            }

            // 验证消息类型
            if ($message->msgtype !== 'chatrecord') {
                throw new FailedException('消息类型错误，必须是 chatrecord 类型', 10005);
            }

            // 检查是否已有缓存的聊天记录
            $chatRecord = WeChatChatRecord::where('msgid', $message->msgid)->first();

            if ($chatRecord) {
                // 已有记录
                if ($chatRecord->isCompleted()) {
                    // 已下载完成，直接返回
                    return $this->getChatRecordData($chatRecord);
                } elseif ($chatRecord->isDownloading()) {
                    // 正在下载中
                    return [
                        'status' => 'downloading',
                        'message' => '聊天记录正在下载中，请稍后刷新',
                    ];
                } else {
                    // 下载失败或其他状态，重新触发下载
                    Log::info('聊天记录下载失败，重新触发下载', [
                        'chatrecord_id' => $chatRecord->id,
                        'old_status' => $chatRecord->download_status,
                    ]);
                    
                    // 重置所有失败的媒体条目为待下载状态
                    WeChatChatRecordItem::where('chatrecord_id', $chatRecord->id)
                        ->where('media_status', WeChatChatRecordItem::MEDIA_STATUS_FAILED)
                        ->update(['media_status' => WeChatChatRecordItem::MEDIA_STATUS_PENDING]);
                    
                    // 标记为下载中
                    $chatRecord->markAsDownloading();
                    
                    // 重新触发异步下载（只下载媒体文件，不重新解析聊天记录）
                    $this->downloadMediaFilesAsync($chatRecord->id);
                    
                    // 返回下载中状态（而不是立即返回数据）
                    return [
                        'status' => 'downloading',
                        'message' => '聊天记录正在重新下载中，请稍后刷新',
                    ];
                }
            } else {
                // 第一次访问，创建记录并下载
                $chatRecord = WeChatChatRecord::getOrCreate(
                    $message->id,
                    $message->msgid,
                    $message->conversation_id,
                    $message->from_user
                );

                $this->downloadChatRecord($chatRecord, $message);
                
                // 返回下载中状态
                return [
                    'status' => 'downloading',
                    'message' => '聊天记录正在下载中，请稍后刷新',
                ];
            }

        } catch (\Exception $e) {
            Log::error('获取聊天记录失败', [
                'message_id' => $request->get('message_id'),
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 下载并存储聊天记录
     *
     * @param WeChatChatRecord $chatRecord 聊天记录对象
     * @param WeChatMessage $message 消息对象
     * @return void
     */
    private function downloadChatRecord(WeChatChatRecord $chatRecord, WeChatMessage $message): void
    {
        try {
            // 标记为下载中
            $chatRecord->markAsDownloading();

            // 解析消息内容
            $content = is_string($message->content) ? json_decode($message->content, true) : $message->content;
            
            if (!isset($content['chatrecord']['item']) || !is_array($content['chatrecord']['item'])) {
                throw new \Exception('聊天记录数据格式错误');
            }

            $items = $content['chatrecord']['item'];

            // 开始事务
            DB::beginTransaction();

            // 清除旧的条目（如果有）
            WeChatChatRecordItem::where('chatrecord_id', $chatRecord->id)->delete();

            // 批量插入聊天记录条目
            foreach ($items as $index => $item) {
                $itemType = $item['type'] ?? 'Unknown';
                $itemContent = $item['content'] ?? null;
                $msgtime = $item['msgtime'] ?? null;
                $fromChatroom = $item['from_chatroom'] ?? false;

                // 判断是否需要下载媒体文件
                $needsMedia = in_array($itemType, [
                    'ChatRecordImage',
                    'ChatRecordVoice',
                    'ChatRecordVideo',
                    'ChatRecordFile',
                ]);

                WeChatChatRecordItem::create([
                    'chatrecord_id' => $chatRecord->id,
                    'item_index' => $index,
                    'item_type' => $itemType,
                    'content' => $itemContent,
                    'msgtime' => $msgtime,
                    'from_chatroom' => $fromChatroom,
                    'media_status' => $needsMedia ? WeChatChatRecordItem::MEDIA_STATUS_PENDING : WeChatChatRecordItem::MEDIA_STATUS_NONE,
                ]);
            }

            // 更新条目数量（保持 DOWNLOADING 状态，等待媒体下载完成）
            $chatRecord->item_count = count($items);
            $chatRecord->save();

            DB::commit();

            // 异步下载媒体文件（下载完成后在 Job 中标记为 COMPLETED）
            $this->downloadMediaFilesAsync($chatRecord->id);

        } catch (\Exception $e) {
            DB::rollBack();
            $chatRecord->markAsFailed($e->getMessage());
            
            Log::error('下载聊天记录失败', [
                'chatrecord_id' => $chatRecord->id,
                'error' => $e->getMessage(),
            ]);

            throw $e;
        }
    }

    /**
     * 获取聊天记录数据
     * 关联查询资源表获取 media_url
     *
     * @param WeChatChatRecord $chatRecord 聊天记录对象
     * @return array
     */
    private function getChatRecordData(WeChatChatRecord $chatRecord): array
    {
        // 加载关联的条目和资源
        $chatRecord->load(['items.resource']);

        // 格式化条目数据
        $items = $chatRecord->items->map(function ($item) {
            // 优先使用资源表的URL，如果没有则使用item表的media_url
            $mediaUrl = $item->resource ? $item->resource->oss_url : $item->media_url;
            
            return [
                'id' => $item->id,
                'type' => $item->item_type,
                'content' => $item->content,
                'msgtime' => $item->msgtime,
                'from_chatroom' => $item->from_chatroom,
                'media_url' => $mediaUrl,
                'media_status' => $item->media_status,
                'resource_id' => $item->resource_id,
            ];
        });

        return [
            'status' => 'completed',
            'chatrecord' => [
                'id' => $chatRecord->id,
                'msgid' => $chatRecord->msgid,
                'from_user' => $chatRecord->from_user,
                'item_count' => $chatRecord->item_count,
                'download_time' => $chatRecord->download_time,
                'items' => $items,
            ],
        ];
    }

    /**
     * 异步下载媒体文件
     * 使用 Laravel Queue 异步下载，不阻塞主请求
     *
     * @param int $chatRecordId 聊天记录ID
     * @return void
     */
    private function downloadMediaFilesAsync(int $chatRecordId): void
    {
        try {
            // 使用 Laravel Queue 异步执行下载任务
            // 不依赖 exec() 等系统函数，更可靠且易于管理
            \Modules\Conversation\Jobs\DownloadChatRecordMediaJob::dispatch($chatRecordId);
            
            Log::info('已触发异步媒体文件下载任务（Queue）', ['chatrecord_id' => $chatRecordId]);
            
        } catch (\Exception $e) {
            Log::error('触发异步媒体文件下载失败', [
                'chatrecord_id' => $chatRecordId,
                'error' => $e->getMessage(),
            ]);
        }
    }
}

