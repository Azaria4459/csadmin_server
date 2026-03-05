<?php

namespace Modules\Conversation\Jobs;

use Illuminate\Bus\Queueable;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Bus\Dispatchable;
use Illuminate\Queue\InteractsWithQueue;
use Illuminate\Queue\SerializesModels;
use Illuminate\Support\Facades\Log;
use Modules\Conversation\Models\WeChatChatRecord;
use Modules\Conversation\Models\WeChatChatRecordItem;
use Modules\Conversation\Services\ChatRecordMediaService;

/**
 * 下载聊天记录媒体文件的异步任务
 * 
 * 用途：异步下载聊天记录中的图片、视频、音频、文件等媒体资源
 * 
 * 使用方式：
 *   DownloadChatRecordMediaJob::dispatch($chatRecordId);
 */
class DownloadChatRecordMediaJob implements ShouldQueue
{
    use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;

    /**
     * 聊天记录ID
     *
     * @var int
     */
    protected int $chatRecordId;

    /**
     * 任务最大尝试次数
     *
     * @var int
     */
    public $tries = 3;

    /**
     * 任务超时时间（秒）
     *
     * @var int
     */
    public $timeout = 300;

    /**
     * 创建任务实例
     *
     * @param int $chatRecordId 聊天记录ID
     * @return void
     */
    public function __construct(int $chatRecordId)
    {
        $this->chatRecordId = $chatRecordId;
    }

    /**
     * 执行任务
     *
     * @return void
     */
    public function handle(): void
    {
        Log::info('开始下载聊天记录媒体文件', [
            'chatrecord_id' => $this->chatRecordId,
        ]);

        try {
            // 获取聊天记录
            $chatRecord = WeChatChatRecord::find($this->chatRecordId);
            
            if (!$chatRecord) {
                Log::error('聊天记录不存在', [
                    'chatrecord_id' => $this->chatRecordId,
                ]);
                return;
            }

            // 获取需要下载的媒体条目
            $items = WeChatChatRecordItem::where('chatrecord_id', $this->chatRecordId)
                ->where('media_status', WeChatChatRecordItem::MEDIA_STATUS_PENDING)
                ->get();

            if ($items->isEmpty()) {
                Log::info('没有需要下载的媒体文件，标记为完成', [
                    'chatrecord_id' => $this->chatRecordId,
                ]);
                
                // 没有媒体文件，直接标记为完成
                $chatRecord->markAsCompleted($chatRecord->item_count);
                return;
            }

            Log::info('找到待下载的媒体文件', [
                'chatrecord_id' => $this->chatRecordId,
                'count' => $items->count(),
            ]);

            // 创建媒体下载服务
            $mediaService = app(ChatRecordMediaService::class);

            $successCount = 0;
            $failedCount = 0;

            // 逐个下载媒体文件
            foreach ($items as $item) {
                try {
                    $success = $mediaService->downloadMediaForItem($item);
                    
                    if ($success) {
                        $successCount++;
                        Log::info('媒体文件下载成功', [
                            'item_id' => $item->id,
                            'type' => $item->item_type,
                        ]);
                    } else {
                        $failedCount++;
                        Log::warning('媒体文件下载失败', [
                            'item_id' => $item->id,
                            'type' => $item->item_type,
                        ]);
                    }
                } catch (\Exception $e) {
                    $failedCount++;
                    Log::error('媒体文件下载异常', [
                        'item_id' => $item->id,
                        'type' => $item->item_type,
                        'error' => $e->getMessage(),
                    ]);
                }
            }

            Log::info('聊天记录媒体文件下载完成', [
                'chatrecord_id' => $this->chatRecordId,
                'success_count' => $successCount,
                'failed_count' => $failedCount,
            ]);

            // 标记聊天记录为已完成
            // 注意：即使有部分媒体文件下载失败，也标记为完成（用户可以看到已下载的部分）
            if ($successCount > 0 || $failedCount > 0) {
                $chatRecord->markAsCompleted($chatRecord->item_count);
                Log::info('聊天记录已标记为完成', [
                    'chatrecord_id' => $this->chatRecordId,
                ]);
            }

        } catch (\Exception $e) {
            Log::error('下载聊天记录媒体文件失败', [
                'chatrecord_id' => $this->chatRecordId,
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            // 重新抛出异常，让队列系统重试
            throw $e;
        }
    }

    /**
     * 任务失败处理
     *
     * @param \Throwable $exception
     * @return void
     */
    public function failed(\Throwable $exception): void
    {
        Log::error('下载聊天记录媒体文件任务失败', [
            'chatrecord_id' => $this->chatRecordId,
            'error' => $exception->getMessage(),
            'trace' => $exception->getTraceAsString(),
        ]);

        // 更新聊天记录状态为失败
        try {
            $chatRecord = WeChatChatRecord::find($this->chatRecordId);
            if ($chatRecord) {
                $chatRecord->markAsFailed('媒体文件下载失败: ' . $exception->getMessage());
            }
        } catch (\Exception $e) {
            Log::error('更新聊天记录状态失败', [
                'chatrecord_id' => $this->chatRecordId,
                'error' => $e->getMessage(),
            ]);
        }
    }
}

