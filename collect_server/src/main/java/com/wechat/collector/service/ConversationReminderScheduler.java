package com.wechat.collector.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话提醒定时任务调度器
 * 
 * 功能描述：
 * 1. 定时调度会话提醒任务：每分钟自动触发一次超时未回复检查
 * 2. 线程池管理：使用单线程调度器，确保任务顺序执行
 * 3. 生命周期管理：提供启动和停止方法，支持优雅关闭
 * 4. 异常处理：捕获任务执行异常，避免影响调度器运行
 * 5. 状态管理：跟踪调度器启动状态，防止重复启动
 * 
 * 使用场景：
 * - 在 Application 启动时初始化并启动
 * - 持续监控客户消息，及时提醒员工回复
 * 
 * 依赖：
 * - ConversationReminderService：会话提醒服务
 * 
 * 执行频率：
 * - 每分钟执行一次
 * - 首次启动时立即执行一次
 */
public class ConversationReminderScheduler {
    private static final Logger logger = LoggerFactory.getLogger(ConversationReminderScheduler.class);
    
    private final ConversationReminderService reminderService;
    private final ScheduledExecutorService scheduler;
    private boolean started = false;
    
    /**
     * 构造函数
     * 
     * @param reminderService 提醒服务
     */
    public ConversationReminderScheduler(ConversationReminderService reminderService) {
        this.reminderService = reminderService;
        this.scheduler = Executors.newScheduledThreadPool(1);
    }
    
    /**
     * 启动定时任务
     * 每分钟执行一次检查
     */
    public synchronized void start() {
        if (started) {
            logger.warn("会话提醒定时任务调度器已启动，跳过");
            return;
        }
        
        logger.info("启动会话提醒定时任务...");
        
        // 每分钟执行一次检查
        scheduler.scheduleAtFixedRate(
            () -> {
                try {
                    reminderService.checkAndSendReminders();
                } catch (Exception e) {
                    logger.error("会话提醒定时任务执行失败", e);
                }
            },
            0,  // 立即执行
            1,  // 间隔1分钟
            TimeUnit.MINUTES
        );
        
        started = true;
        logger.info("会话提醒定时任务调度器启动成功，每分钟执行一次");
    }
    
    /**
     * 停止定时任务
     */
    public synchronized void stop() {
        if (!started) {
            logger.warn("会话提醒定时任务调度器未启动");
            return;
        }
        
        logger.info("停止会话提醒定时任务调度器...");
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
        logger.info("会话提醒定时任务调度器已停止");
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

