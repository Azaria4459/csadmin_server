<?php

declare(strict_types=1);

namespace Modules\Conversation\Commands;

use Illuminate\Console\Command;
use Modules\Conversation\Models\WeChatMessage;
use Modules\Conversation\Models\WeChatChatRecordItem;
use Modules\Conversation\Models\WeChatResource;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;

/**
 * 迁移旧的媒体数据到资源表
 * 将 wechat_message 和 wechat_chatrecord_item 中的 media_url 迁移到 wechat_resource 表
 */
class MigrateMediaToResourceCommand extends Command
{
    /**
     * 命令名称和签名
     *
     * @var string
     */
    protected $signature = 'resource:migrate-media {--table= : 指定要迁移的表：message/chatrecord_item/all} {--limit=100 : 每次处理的记录数}';

    /**
     * 命令描述
     *
     * @var string
     */
    protected $description = '将现有的 media_url 数据迁移到 wechat_resource 资源表';

    /**
     * 执行命令
     *
     * @return int
     */
    public function handle(): int
    {
        $table = $this->option('table') ?? 'all';
        $limit = (int) $this->option('limit');

        $this->info("开始迁移媒体数据到资源表...");
        $this->info("处理表: {$table}");
        $this->info("每批次数量: {$limit}");
        $this->newLine();

        try {
            $totalMigrated = 0;

            if ($table === 'message' || $table === 'all') {
                $this->info("正在迁移 wechat_message 表...");
                $migrated = $this->migrateWeChatMessage($limit);
                $totalMigrated += $migrated;
                $this->info("✓ wechat_message 表迁移完成: {$migrated} 条记录");
                $this->newLine();
            }

            if ($table === 'chatrecord_item' || $table === 'all') {
                $this->info("正在迁移 wechat_chatrecord_item 表...");
                $migrated = $this->migrateWeChatChatRecordItem($limit);
                $totalMigrated += $migrated;
                $this->info("✓ wechat_chatrecord_item 表迁移完成: {$migrated} 条记录");
                $this->newLine();
            }

            $this->info("迁移完成!");
            $this->info("总计迁移: {$totalMigrated} 条记录");

            // 显示统计信息
            $this->showStatistics();

            return 0;

        } catch (\Exception $e) {
            $this->error("迁移失败: {$e->getMessage()}");
            Log::error('媒体数据迁移失败', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);
            return 1;
        }
    }

    /**
     * 迁移 wechat_message 表的媒体数据
     *
     * @param int $limit 每批次数量
     * @return int 迁移的记录数
     */
    private function migrateWeChatMessage(int $limit): int
    {
        $totalMigrated = 0;
        $hasMore = true;

        while ($hasMore) {
            // 查询需要迁移的记录（media_url 不为空且 resource_id 为空）
            $messages = WeChatMessage::withoutGlobalScopes()
                ->whereNotNull('media_url')
                ->where('media_url', '!=', '')
                ->whereNull('resource_id')
                ->limit($limit)
                ->get();

            if ($messages->isEmpty()) {
                $hasMore = false;
                break;
            }

            $batchCount = 0;

            foreach ($messages as $message) {
                try {
                    $resource = $this->createResourceFromMessage($message);
                    
                    if ($resource) {
                        // 更新消息的 resource_id
                        $message->resource_id = $resource->id;
                        $message->save();
                        $batchCount++;
                        
                        $this->line("  ✓ 消息 ID:{$message->id} → 资源 ID:{$resource->id}");
                    }
                } catch (\Exception $e) {
                    $this->warn("  ✗ 消息 ID:{$message->id} 迁移失败: {$e->getMessage()}");
                    Log::warning('消息迁移失败', [
                        'message_id' => $message->id,
                        'error' => $e->getMessage(),
                    ]);
                }
            }

            $totalMigrated += $batchCount;
            $this->info("  批次完成: {$batchCount} 条记录");
        }

        return $totalMigrated;
    }

    /**
     * 迁移 wechat_chatrecord_item 表的媒体数据
     *
     * @param int $limit 每批次数量
     * @return int 迁移的记录数
     */
    private function migrateWeChatChatRecordItem(int $limit): int
    {
        $totalMigrated = 0;
        $hasMore = true;

        while ($hasMore) {
            // 查询需要迁移的记录
            $items = WeChatChatRecordItem::whereNotNull('media_url')
                ->where('media_url', '!=', '')
                ->whereNull('resource_id')
                ->limit($limit)
                ->get();

            if ($items->isEmpty()) {
                $hasMore = false;
                break;
            }

            $batchCount = 0;

            foreach ($items as $item) {
                try {
                    $resource = $this->createResourceFromChatRecordItem($item);
                    
                    if ($resource) {
                        // 更新条目的 resource_id
                        $item->resource_id = $resource->id;
                        $item->save();
                        $batchCount++;
                        
                        $this->line("  ✓ 条目 ID:{$item->id} → 资源 ID:{$resource->id}");
                    }
                } catch (\Exception $e) {
                    $this->warn("  ✗ 条目 ID:{$item->id} 迁移失败: {$e->getMessage()}");
                    Log::warning('聊天记录条目迁移失败', [
                        'item_id' => $item->id,
                        'error' => $e->getMessage(),
                    ]);
                }
            }

            $totalMigrated += $batchCount;
            $this->info("  批次完成: {$batchCount} 条记录");
        }

        return $totalMigrated;
    }

    /**
     * 从消息创建资源记录
     *
     * @param WeChatMessage $message 消息对象
     * @return WeChatResource|null
     */
    private function createResourceFromMessage(WeChatMessage $message): ?WeChatResource
    {
        // 解析 content 获取文件信息
        $content = is_string($message->content) ? json_decode($message->content, true) : $message->content;
        
        if (!$content) {
            return null;
        }

        $md5sum = null;
        $sdkfileid = null;
        $fileSize = 0;
        $fileExtension = null;
        $fileType = $message->msgtype;

        // 根据消息类型提取信息
        switch ($message->msgtype) {
            case 'image':
                $md5sum = $content['image']['md5sum'] ?? null;
                $sdkfileid = $content['image']['sdkfileid'] ?? null;
                $fileSize = $content['image']['filesize'] ?? 0;
                $fileExtension = 'jpg';
                $fileType = 'image';
                break;
            
            case 'voice':
                $md5sum = $content['voice']['md5sum'] ?? null;
                $sdkfileid = $content['voice']['sdkfileid'] ?? null;
                $fileSize = $content['voice']['filesize'] ?? 0;
                $fileExtension = 'amr';
                $fileType = 'voice';
                break;
            
            case 'video':
                $md5sum = $content['video']['md5sum'] ?? null;
                $sdkfileid = $content['video']['sdkfileid'] ?? null;
                $fileSize = $content['video']['filesize'] ?? 0;
                $fileExtension = 'mp4';
                $fileType = 'video';
                break;
            
            case 'file':
                $md5sum = $content['file']['md5sum'] ?? null;
                $sdkfileid = $content['file']['sdkfileid'] ?? null;
                $fileSize = $content['file']['filesize'] ?? 0;
                $fileExtension = $content['file']['fileext'] ?? 'bin';
                $fileType = 'file';
                break;
        }

        // 如果没有 md5sum 和 sdkfileid，使用 media_url 作为唯一标识
        if (!$md5sum && !$sdkfileid) {
            $md5sum = md5($message->media_url);
        }

        // 创建或获取资源
        $resource = WeChatResource::createOrGet([
            'md5sum' => $md5sum,
            'sdkfileid' => $sdkfileid,
            'file_type' => $fileType,
            'file_size' => $fileSize,
            'file_extension' => $fileExtension,
            'oss_url' => $message->media_url,
        ]);

        return $resource;
    }

    /**
     * 从聊天记录条目创建资源记录
     *
     * @param WeChatChatRecordItem $item 聊天记录条目对象
     * @return WeChatResource|null
     */
    private function createResourceFromChatRecordItem(WeChatChatRecordItem $item): ?WeChatResource
    {
        // 解析 content 获取文件信息
        $content = $item->getParsedContent();
        
        if (!$content) {
            return null;
        }

        $md5sum = $content['md5sum'] ?? null;
        $sdkfileid = $content['sdkfileid'] ?? null;
        $fileSize = $content['filesize'] ?? 0;
        
        // 根据条目类型确定文件类型和扩展名
        $fileType = 'file';
        $fileExtension = 'dat';

        switch ($item->item_type) {
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
                $fileExtension = $content['fileext'] ?? 'bin';
                break;
        }

        // 如果没有标识符，使用 media_url
        if (!$md5sum && !$sdkfileid) {
            $md5sum = md5($item->media_url);
        }

        // 创建或获取资源
        $resource = WeChatResource::createOrGet([
            'md5sum' => $md5sum,
            'sdkfileid' => $sdkfileid,
            'file_type' => $fileType,
            'file_size' => $fileSize,
            'file_extension' => $fileExtension,
            'oss_url' => $item->media_url,
        ]);

        return $resource;
    }

    /**
     * 显示统计信息
     *
     * @return void
     */
    private function showStatistics(): void
    {
        $this->newLine();
        $this->info("=== 资源表统计信息 ===");

        // 总资源数
        $totalResources = WeChatResource::where('status', WeChatResource::STATUS_NORMAL)->count();
        $this->info("总资源数: {$totalResources}");

        // 按类型统计
        $stats = WeChatResource::where('status', WeChatResource::STATUS_NORMAL)
            ->select('file_type', DB::raw('COUNT(*) as count'), DB::raw('SUM(file_size) as total_size'), DB::raw('SUM(download_count) as total_refs'))
            ->groupBy('file_type')
            ->get();

        $this->newLine();
        $this->info("按类型统计:");
        $this->table(
            ['类型', '文件数', '总大小', '引用次数'],
            $stats->map(function ($stat) {
                return [
                    $stat->file_type,
                    $stat->count,
                    $this->formatSize($stat->total_size),
                    $stat->total_refs,
                ];
            })
        );

        // 待迁移数量
        $pendingMessage = WeChatMessage::withoutGlobalScopes()
            ->whereNotNull('media_url')
            ->where('media_url', '!=', '')
            ->whereNull('resource_id')
            ->count();
        
        $pendingChatRecord = WeChatChatRecordItem::whereNotNull('media_url')
            ->where('media_url', '!=', '')
            ->whereNull('resource_id')
            ->count();

        $this->newLine();
        $this->info("待迁移数量:");
        $this->line("  wechat_message: {$pendingMessage}");
        $this->line("  wechat_chatrecord_item: {$pendingChatRecord}");
    }

    /**
     * 格式化文件大小
     *
     * @param int $bytes 字节数
     * @return string
     */
    private function formatSize(int $bytes): string
    {
        if ($bytes < 1024) {
            return $bytes . ' B';
        } elseif ($bytes < 1024 * 1024) {
            return round($bytes / 1024, 2) . ' KB';
        } elseif ($bytes < 1024 * 1024 * 1024) {
            return round($bytes / (1024 * 1024), 2) . ' MB';
        } else {
            return round($bytes / (1024 * 1024 * 1024), 2) . ' GB';
        }
    }
}

