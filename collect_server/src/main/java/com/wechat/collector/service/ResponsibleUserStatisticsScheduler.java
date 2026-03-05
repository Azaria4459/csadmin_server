package com.wechat.collector.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 责任人统计调度器
 * 
 * 功能描述：
 * 1. 定时调度责任人统计任务：每日在指定时间执行统计数据更新
 * 2. 可配置执行时间：支持配置每日统计的具体时间点
 * 3. 线程池管理：使用单线程调度器，确保任务顺序执行
 * 4. 生命周期管理：提供启动和停止方法，支持优雅关闭
 * 5. 异常处理：捕获任务执行异常，避免影响调度器运行
 * 6. 状态管理：跟踪调度器启动状态，防止重复启动
 * 
 * 使用场景：
 * - 在 Application 启动时初始化并启动
 * - 定期更新所有会话的责任人统计数据（每个会话一条记录）
 * 
 * 依赖：
 * - ResponsibleUserStatisticsService：责任人统计服务
 * 
 * 执行频率：
 * - 默认每日执行一次（可配置具体时间，默认凌晨2点）
 * - 统计所有会话的数据（不区分日期）
 */
public class ResponsibleUserStatisticsScheduler {
    private static final Logger logger = LoggerFactory.getLogger(ResponsibleUserStatisticsScheduler.class);
    
    private final ResponsibleUserStatisticsService statisticsService;
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    /**
     * 每日执行时间（小时）
     */
    private final int dailyHour;
    
    /**
     * 每日执行时间（分钟）
     */
    private final int dailyMinute;
    
    /**
     * 构造函数
     * 
     * @param statisticsService 责任人统计服务
     * @param dailyHour 每日执行时间（小时，0-23），默认2点
     * @param dailyMinute 每日执行时间（分钟，0-59），默认0分
     */
    public ResponsibleUserStatisticsScheduler(
            ResponsibleUserStatisticsService statisticsService,
            int dailyHour,
            int dailyMinute) {
        this.statisticsService = statisticsService;
        this.dailyHour = dailyHour;
        this.dailyMinute = dailyMinute;
    }
    
    /**
     * 默认构造函数（使用默认执行时间：凌晨2点）
     * 
     * @param statisticsService 责任人统计服务
     */
    public ResponsibleUserStatisticsScheduler(ResponsibleUserStatisticsService statisticsService) {
        this(statisticsService, 2, 0);
    }
    
    /**
     * 启动定时任务
     */
    public void start() {
        if (running.get()) {
            logger.warn("ResponsibleUserStatisticsScheduler is already running");
            return;
        }
        
        logger.info("启动责任人统计调度器...");
        
        running.set(true);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // 计算到下次执行的延迟时间
        long initialDelay = calculateInitialDelay();
        
        // 调度任务：首次延迟后执行，然后每24小时执行一次
        scheduler.scheduleAtFixedRate(
            this::runStatistics,
            initialDelay,
            24 * 60 * 60, // 24小时
            TimeUnit.SECONDS
        );
        
        ZonedDateTime nextRun = ZonedDateTime.now(ZoneId.systemDefault())
            .plusSeconds(initialDelay);
        logger.info("责任人统计调度器已启动，首次执行时间: {}", 
            nextRun.toLocalDateTime().toString());
        logger.info("每日执行时间: {:02d}:{:02d}", dailyHour, dailyMinute);
    }
    
    /**
     * 停止定时任务
     */
    public void stop() {
        if (!running.get()) {
            return;
        }
        
        logger.info("停止责任人统计调度器...");
        
        running.set(false);
        
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        logger.info("责任人统计调度器已停止");
    }
    
    /**
     * 执行统计任务
     */
    private void runStatistics() {
        if (!running.get()) {
            return;
        }
        
        try {
            logger.info("========== 开始执行责任人统计任务 ==========");
            logger.info("执行时间: {}", ZonedDateTime.now(ZoneId.systemDefault()).toLocalDateTime());
            
            // 更新所有会话的统计数据
            int updatedCount = statisticsService.updateAllStatistics();
            
            logger.info("========== 责任人统计任务完成 ==========");
            logger.info("更新记录数: {}", updatedCount);
            
        } catch (Exception e) {
            logger.error("执行责任人统计任务失败", e);
        }
    }
    
    /**
     * 手动触发一次统计（用于测试）
     */
    public void triggerStatistics() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.submit(() -> {
                try {
                    logger.info("手动触发责任人统计");
                    int updatedCount = statisticsService.updateAllStatistics();
                    logger.info("手动统计完成: updatedCount={}", updatedCount);
                } catch (Exception e) {
                    logger.error("手动触发责任人统计失败", e);
                }
            });
            logger.info("已手动触发责任人统计任务");
        } else {
            logger.warn("调度器未运行，无法手动触发");
        }
    }
    
    /**
     * 计算到下次执行时间的延迟（秒）
     * 
     * @return 延迟秒数
     */
    private long calculateInitialDelay() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        ZonedDateTime nextRun = now.withHour(dailyHour)
                                   .withMinute(dailyMinute)
                                   .withSecond(0)
                                   .withNano(0);
        
        // 如果今天的执行时间已经过了，调整到明天
        if (now.compareTo(nextRun) > 0) {
            nextRun = nextRun.plusDays(1);
        }
        
        long delay = nextRun.toEpochSecond() - now.toEpochSecond();
        
        logger.debug("当前时间: {}", now.toLocalDateTime());
        logger.debug("下次执行: {}", nextRun.toLocalDateTime());
        logger.debug("延迟秒数: {}", delay);
        
        return delay;
    }
    
    /**
     * 检查调度器是否正在运行
     * 
     * @return 是否运行中
     */
    public boolean isRunning() {
        return running.get();
    }
}

