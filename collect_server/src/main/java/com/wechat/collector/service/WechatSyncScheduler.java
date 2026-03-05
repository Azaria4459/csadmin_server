package com.wechat.collector.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 企业微信通讯录同步定时任务调度器
 * 
 * 功能描述：
 * 1. 定时调度通讯录同步任务：定期执行员工和外部联系人同步
 * 2. 可配置执行频率：支持配置同步间隔（默认每小时）
 * 3. 线程池管理：使用单线程调度器，确保任务顺序执行
 * 4. 生命周期管理：提供启动和停止方法，支持优雅关闭
 * 5. 异常处理：捕获任务执行异常，避免影响调度器运行
 * 6. 状态管理：跟踪调度器启动状态，防止重复启动
 * 
 * 使用场景：
 * - 在 Application 启动时初始化并启动
 * - 定期同步企业微信通讯录，确保成员信息最新
 * 
 * 依赖：
 * - WechatSyncService：通讯录同步服务
 * 
 * 执行频率：
 * - 默认每小时执行一次（可配置）
 * - 首次启动时立即执行一次（可选）
 */
public class WechatSyncScheduler {
    private static final Logger logger = LoggerFactory.getLogger(WechatSyncScheduler.class);
    
    private final WechatSyncService syncService;
    private final ScheduledExecutorService scheduler;
    private boolean started = false;
    
    /**
     * 构造函数
     * 
     * @param syncService 同步服务
     */
    public WechatSyncScheduler(WechatSyncService syncService) {
        this.syncService = syncService;
        this.scheduler = Executors.newScheduledThreadPool(2);
    }
    
    /**
     * 启动定时任务
     * 每天0点执行员工同步
     * 每天0点30分执行外部联系人同步
     */
    public synchronized void start() {
        if (started) {
            logger.warn("定时任务调度器已启动，跳过");
            return;
        }
        
        logger.info("启动企业微信通讯录同步定时任务...");
        
        // 计算距离下一个0点的延迟时间
        long initialDelay = calculateInitialDelay(0, 0);
        
        // 定时任务A：每天0点同步员工信息
        scheduler.scheduleAtFixedRate(
            () -> {
                try {
                    logger.info("定时任务A：开始同步员工信息");
                    WechatSyncService.SyncResult result = syncService.syncEmployees();
                    logger.info("定时任务A完成: {}", result);
                } catch (Exception e) {
                    logger.error("定时任务A执行失败", e);
                }
            },
            initialDelay,
            24 * 60 * 60 * 1000, // 24小时
            TimeUnit.MILLISECONDS
        );
        
        // 定时任务B：每天0点30分同步外部联系人信息
        long initialDelayB = calculateInitialDelay(0, 30);
        scheduler.scheduleAtFixedRate(
            () -> {
                try {
                    logger.info("定时任务B：开始同步外部联系人信息");
                    WechatSyncService.SyncResult result = syncService.syncExternalContacts();
                    logger.info("定时任务B完成: {}", result);
                } catch (Exception e) {
                    logger.error("定时任务B执行失败", e);
                }
            },
            initialDelayB,
            24 * 60 * 60 * 1000, // 24小时
            TimeUnit.MILLISECONDS
        );
        
        started = true;
        logger.info("定时任务调度器启动成功");
        logger.info("定时任务A（员工同步）将在 {} 毫秒后首次执行", initialDelay);
        logger.info("定时任务B（外部联系人同步）将在 {} 毫秒后首次执行", initialDelayB);
    }
    
    /**
     * 停止定时任务
     */
    public synchronized void stop() {
        if (!started) {
            logger.warn("定时任务调度器未启动");
            return;
        }
        
        logger.info("停止定时任务调度器...");
        scheduler.shutdown();
        
        try {
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        started = false;
        logger.info("定时任务调度器已停止");
    }
    
    /**
     * 计算距离下一个指定时间的延迟（毫秒）
     * 
     * @param hour 小时（0-23）
     * @param minute 分钟（0-59）
     * @return 延迟时间（毫秒）
     */
    private long calculateInitialDelay(int hour, int minute) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        java.util.Calendar next = java.util.Calendar.getInstance();
        
        next.set(java.util.Calendar.HOUR_OF_DAY, hour);
        next.set(java.util.Calendar.MINUTE, minute);
        next.set(java.util.Calendar.SECOND, 0);
        next.set(java.util.Calendar.MILLISECOND, 0);
        
        // 如果今天的时间已过，则设置为明天
        if (next.before(now)) {
            next.add(java.util.Calendar.DATE, 1);
        }
        
        return next.getTimeInMillis() - now.getTimeInMillis();
    }
    
    /**
     * 检查调度器是否已启动
     * 
     * @return 是否已启动
     */
    public boolean isStarted() {
        return started;
    }
}

