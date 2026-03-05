<?php

declare(strict_types=1);

namespace Modules\Conversation\Commands;

use Illuminate\Console\Command;
use Modules\Conversation\Services\ResponsibleUserStatisticsService;

/**
 * 更新责任人统计数据命令
 * 定期执行此命令来更新责任人的响应时间统计数据
 */
class UpdateResponsibleUserStatisticsCommand extends Command
{
    /**
     * 命令名称和签名
     *
     * @var string
     */
    protected $signature = 'conversation:update-responsible-statistics 
                            {--date= : 指定要统计的日期（格式：Y-m-d），默认统计昨天}';

    /**
     * 命令描述
     *
     * @var string
     */
    protected $description = '更新责任人统计数据（响应时间统计）';

    /**
     * 统计服务
     *
     * @var ResponsibleUserStatisticsService
     */
    private ResponsibleUserStatisticsService $statisticsService;

    /**
     * 构造函数
     *
     * @param ResponsibleUserStatisticsService $statisticsService
     */
    public function __construct(ResponsibleUserStatisticsService $statisticsService)
    {
        parent::__construct();
        $this->statisticsService = $statisticsService;
    }

    /**
     * 执行命令
     *
     * @return int
     */
    public function handle(): int
    {
        // 获取要统计的日期（默认昨天）
        $date = $this->option('date');
        if (!$date) {
            $date = date('Y-m-d', strtotime('-1 day'));
        }

        // 验证日期格式
        if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $date)) {
            $this->error("日期格式错误，应为：Y-m-d（例如：2025-12-31）");
            return 1;
        }

        $this->info("开始更新责任人统计数据...");
        $this->info("统计日期: {$date}");
        $this->newLine();

        try {
            $startTime = microtime(true);
            
            $updatedCount = $this->statisticsService->updateStatisticsForDate($date);
            
            $endTime = microtime(true);
            $duration = round($endTime - $startTime, 2);

            $this->info("✓ 统计完成!");
            $this->info("更新记录数: {$updatedCount}");
            $this->info("耗时: {$duration} 秒");

            return 0;

        } catch (\Exception $e) {
            $this->error("统计失败: {$e->getMessage()}");
            \Log::error('更新责任人统计数据失败', [
                'date' => $date,
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);
            return 1;
        }
    }
}

