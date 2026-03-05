<?php

declare(strict_types=1);

namespace Modules\Conversation\Services;

use Modules\Conversation\Models\WeChatChatRecordItem;
use Modules\Conversation\Models\WeChatResource;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Log;

/**
 * 聊天记录媒体文件下载服务
 * 负责调用 collect_server 下载媒体文件并上传到 OSS
 */
class ChatRecordMediaService
{
    /**
     * collect_server 服务地址
     * 
     * @var string
     */
    private string $collectServerUrl;

    /**
     * 构造函数
     * 
     * @param string|null $collectServerUrl collect_server 服务地址，默认为 http://localhost:7070
     */
    public function __construct(?string $collectServerUrl = null)
    {
        $this->collectServerUrl = $collectServerUrl ?? env('COLLECT_SERVER_URL', 'http://localhost:7070');
    }

    /**
     * 下载单个媒体文件
     * 先检查资源表是否已存在，如果存在则复用，不存在才下载
     * 
     * @param WeChatChatRecordItem $item 聊天记录条目
     * @return bool 是否成功
     */
    public function downloadMediaForItem(WeChatChatRecordItem $item): bool
    {
        if (!$item->needsMediaDownload()) {
            return true;
        }

        try {
            // 解析content获取sdkfileid和md5sum
            $content = $item->getParsedContent();
            if (!$content || !isset($content['sdkfileid'])) {
                Log::warning('聊天记录条目缺少 sdkfileid', ['item_id' => $item->id]);
                $item->markMediaAsFailed();
                return false;
            }

            $sdkFileId = $content['sdkfileid'];
            $md5sum = $content['md5sum'] ?? null;
            $fileSize = $content['filesize'] ?? 0;
            
            // 确定文件类型和扩展名
            list($fileType, $fileExtension) = $this->getFileTypeAndExtension($item->item_type, $content);

            // 1. 先检查资源表是否已存在该文件
            $existingResource = WeChatResource::findByIdentifier($md5sum, $sdkFileId);
            
            if ($existingResource) {
                // 资源已存在，直接复用
                Log::info('资源已存在，复用现有资源', [
                    'item_id' => $item->id,
                    'resource_id' => $existingResource->id,
                    'oss_url' => $existingResource->oss_url,
                ]);

                // 关联资源ID
                $item->resource_id = $existingResource->id;
                $item->markMediaAsDownloaded($existingResource->oss_url);

                // 增加引用计数
                $existingResource->incrementDownloadCount();

                return true;
            }

            // 2. 资源不存在，调用 collect_server 下载并上传
            Log::info('下载聊天记录媒体文件', [
                'item_id' => $item->id,
                'item_type' => $item->item_type,
                'sdkfileid' => $sdkFileId,
                'fileType' => $fileType,
                'fileExtension' => $fileExtension,
            ]);

            $response = Http::timeout(60)->post($this->collectServerUrl . '/chatrecord/download-media', [
                'sdkfileid' => $sdkFileId,
                'md5sum' => $md5sum,  // 传递 md5sum 用于去重检查
                'fileType' => $fileType,
                'fileExtension' => $fileExtension,
            ]);

            if (!$response->successful()) {
                Log::error('调用 collect_server 下载接口失败', [
                    'item_id' => $item->id,
                    'status' => $response->status(),
                    'body' => $response->body(),
                ]);
                $item->markMediaAsFailed();
                return false;
            }

            $result = $response->json();

            if (!isset($result['success']) || !$result['success']) {
                Log::error('下载媒体文件失败', [
                    'item_id' => $item->id,
                    'message' => $result['message'] ?? '未知错误',
                ]);
                $item->markMediaAsFailed();
                return false;
            }

            $ossUrl = $result['url'] ?? null;
            if (!$ossUrl) {
                Log::error('返回数据中缺少 url 字段', ['item_id' => $item->id]);
                $item->markMediaAsFailed();
                return false;
            }

            $fromCache = $result['fromCache'] ?? false;
            $existingResourceId = $result['resourceId'] ?? null;

            // 3. 创建或关联资源记录
            if ($fromCache && $existingResourceId) {
                // collect_server 从缓存返回的，使用已存在的资源ID
                Log::info('使用 collect_server 缓存的资源', [
                    'item_id' => $item->id,
                    'resource_id' => $existingResourceId,
                    'url' => $ossUrl,
                ]);

                $item->resource_id = $existingResourceId;
                $item->markMediaAsDownloaded($ossUrl);
                
                // 增加引用计数（注意：collect_server 已经增加过一次）
                // 这里不需要再增加，因为 collect_server 已经处理了
            } else {
                // 新下载的资源，创建资源记录
                $resource = WeChatResource::createOrGet([
                    'md5sum' => $md5sum,
                    'sdkfileid' => $sdkFileId,
                    'file_type' => $fileType,
                    'file_size' => $fileSize,
                    'file_extension' => $fileExtension,
                    'oss_url' => $ossUrl,
                ]);

                $item->resource_id = $resource->id;
                $item->markMediaAsDownloaded($ossUrl);

                Log::info('聊天记录媒体文件下载成功', [
                    'item_id' => $item->id,
                    'resource_id' => $resource->id,
                    'url' => $ossUrl,
                    'from_cache' => false,
                ]);
            }

            return true;

        } catch (\Exception $e) {
            Log::error('下载聊天记录媒体文件异常', [
                'item_id' => $item->id,
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            $item->markMediaAsFailed();
            return false;
        }
    }

    /**
     * 批量下载聊天记录的所有媒体文件
     * 
     * @param int $chatRecordId 聊天记录ID
     * @return array 返回下载结果统计
     */
    public function downloadAllMediaForChatRecord(int $chatRecordId): array
    {
        $items = WeChatChatRecordItem::where('chatrecord_id', $chatRecordId)
                                      ->where('media_status', WeChatChatRecordItem::MEDIA_STATUS_PENDING)
                                      ->get();

        $successCount = 0;
        $failCount = 0;
        $totalCount = $items->count();

        Log::info('开始批量下载聊天记录媒体文件', [
            'chatrecord_id' => $chatRecordId,
            'total_count' => $totalCount,
        ]);

        foreach ($items as $item) {
            if ($this->downloadMediaForItem($item)) {
                $successCount++;
            } else {
                $failCount++;
            }
        }

        Log::info('批量下载聊天记录媒体文件完成', [
            'chatrecord_id' => $chatRecordId,
            'success' => $successCount,
            'failed' => $failCount,
            'total' => $totalCount,
        ]);

        return [
            'success' => $successCount,
            'failed' => $failCount,
            'total' => $totalCount,
        ];
    }

    /**
     * 根据条目类型获取文件类型和扩展名
     * 
     * @param string $itemType 条目类型
     * @param array $content 内容数据
     * @return array [fileType, fileExtension]
     */
    private function getFileTypeAndExtension(string $itemType, array $content): array
    {
        $fileType = 'file';
        $fileExtension = '';

        switch ($itemType) {
            case 'ChatRecordImage':
                $fileType = 'image';
                $fileExtension = 'jpg';
                break;
            
            case 'ChatRecordVoice':
                $fileType = 'voice';
                $fileExtension = 'amr';
                break;
            
            case 'ChatRecordVideo':
                $fileType = 'video';
                $fileExtension = 'mp4';
                break;
            
            case 'ChatRecordFile':
                $fileType = 'file';
                // 尝试从content中获取文件名
                if (isset($content['filename'])) {
                    $filename = $content['filename'];
                    $ext = pathinfo($filename, PATHINFO_EXTENSION);
                    if ($ext) {
                        $fileExtension = $ext;
                    }
                }
                if (!$fileExtension) {
                    $fileExtension = 'bin';
                }
                break;
            
            default:
                $fileType = 'file';
                $fileExtension = 'dat';
                break;
        }

        return [$fileType, $fileExtension];
    }
}

