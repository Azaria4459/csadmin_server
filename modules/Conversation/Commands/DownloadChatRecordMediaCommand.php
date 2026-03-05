<?php

declare(strict_types=1);

namespace Modules\Conversation\Commands;

use Illuminate\Console\Command;
use Modules\Conversation\Models\WeChatChatRecord;
use Modules\Conversation\Services\ChatRecordMediaService;
use Illuminate\Support\Facades\Log;

/**
 * 下载聊天记录媒体文件命令
 * 用于异步下载聊天记录中的图片、语音、视频等媒体文件
 */
class DownloadChatRecordMediaCommand extends Command
{
    /**
     * 命令名称和签名
     *
     * @var string
     */
    protected $signature = 'chatrecord:download-media {chatrecord_id : 聊天记录ID}';

    /**
     * 命令描述
     *
     * @var string
     */
    protected $description = '下载聊天记录的媒体文件到OSS';

    /**
     * 执行命令
     *
     * @return int
     */
    public function handle(): int
    {
        $chatRecordId = (int) $this->argument('chatrecord_id');

        $this->info("开始下载聊天记录媒体文件: chatrecord_id={$chatRecordId}");
        Log::info('开始执行聊天记录媒体文件下载命令', ['chatrecord_id' => $chatRecordId]);

        try {
            // 检查聊天记录是否存在
            $chatRecord = WeChatChatRecord::find($chatRecordId);
            
            if (!$chatRecord) {
                $this->error("聊天记录不存在: ID={$chatRecordId}");
                Log::error('聊天记录不存在', ['chatrecord_id' => $chatRecordId]);
                return 1;
            }

            // 创建媒体下载服务
            $mediaService = new ChatRecordMediaService();

            // 批量下载媒体文件
            $result = $mediaService->downloadAllMediaForChatRecord($chatRecordId);

            $this->info("媒体文件下载完成:");
            $this->info("  成功: {$result['success']} 个");
            $this->info("  失败: {$result['failed']} 个");
            $this->info("  总计: {$result['total']} 个");

            Log::info('聊天记录媒体文件下载命令执行完成', [
                'chatrecord_id' => $chatRecordId,
                'result' => $result,
            ]);

            return 0;

        } catch (\Exception $e) {
            $this->error("下载失败: {$e->getMessage()}");
            Log::error('聊天记录媒体文件下载命令执行失败', [
                'chatrecord_id' => $chatRecordId,
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            return 1;
        }
    }
}

