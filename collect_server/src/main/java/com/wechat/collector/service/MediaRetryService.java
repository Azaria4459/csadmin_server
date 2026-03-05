package com.wechat.collector.service;

import com.wechat.collector.model.WeChatMessage;
import com.wechat.collector.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 媒体文件下载重试服务
 * 
 * 功能描述：
 * 1. 异步重试失败的媒体文件下载
 * 2. 支持延迟重试（避免立即重试导致资源浪费）
 * 3. 限制重试次数，避免无限重试
 * 4. 记录重试状态，便于追踪
 * 
 * 使用场景：
 * - 当媒体文件下载超时或失败时，自动加入重试队列
 * - 定时任务扫描未下载的资源，自动重试
 * 
 * 依赖：
 * - MediaProcessService：媒体处理服务
 * - MessageRepository：消息数据访问层
 */
public class MediaRetryService {
    private static final Logger logger = LoggerFactory.getLogger(MediaRetryService.class);
    
    private final MediaProcessService mediaProcessService;
    private final MessageRepository messageRepository;
    
    // 重试队列：存储需要重试的消息
    private final BlockingQueue<RetryTask> retryQueue;
    
    // 重试执行器：异步执行重试任务
    private final ScheduledExecutorService retryExecutor;
    
    // 重试工作线程池：执行实际的重试操作
    private final ExecutorService workerExecutor;
    
    // 重试配置
    private static final int MAX_RETRY_COUNT = 3; // 最大重试次数
    private static final long INITIAL_RETRY_DELAY = 60; // 初始重试延迟（秒）
    private static final int MAX_QUEUE_SIZE = 1000; // 最大队列大小
    
    // 统计信息
    private final AtomicInteger totalRetries = new AtomicInteger(0);
    private final AtomicInteger successRetries = new AtomicInteger(0);
    private final AtomicInteger failedRetries = new AtomicInteger(0);
    
    /**
     * 构造函数
     * 
     * @param mediaProcessService 媒体处理服务
     * @param messageRepository 消息数据访问层
     */
    public MediaRetryService(MediaProcessService mediaProcessService, 
                            MessageRepository messageRepository) {
        this.mediaProcessService = mediaProcessService;
        this.messageRepository = messageRepository;
        
        // 创建重试队列（有界队列，避免内存溢出）
        this.retryQueue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        
        // 创建重试调度器（单线程，用于延迟重试）
        this.retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "media-retry-scheduler");
            t.setDaemon(true);
            return t;
        });
        
        // 创建工作线程池（多线程，执行实际下载）
        int workerThreads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        this.workerExecutor = Executors.newFixedThreadPool(workerThreads, r -> {
            Thread t = new Thread(r, "media-retry-worker");
            t.setDaemon(true);
            return t;
        });
        
        logger.info("媒体重试服务初始化完成，工作线程数: {}", workerThreads);
    }
    
    /**
     * 启动重试服务
     */
    public void start() {
        logger.info("启动媒体重试服务...");
        
        // 启动重试调度器（每30秒处理一次重试队列）
        retryExecutor.scheduleWithFixedDelay(
            this::processRetryQueue,
            30, // 初始延迟30秒
            30, // 每30秒执行一次
            TimeUnit.SECONDS
        );
        
        logger.info("媒体重试服务已启动");
    }
    
    /**
     * 停止重试服务
     */
    public void stop() {
        logger.info("停止媒体重试服务...");
        
        retryExecutor.shutdown();
        workerExecutor.shutdown();
        
        try {
            if (!retryExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                retryExecutor.shutdownNow();
            }
            if (!workerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            retryExecutor.shutdownNow();
            workerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        logger.info("媒体重试服务已停止");
    }
    
    /**
     * 添加重试任务
     * 
     * @param message 需要重试的消息
     * @param retryCount 当前重试次数
     */
    public void addRetryTask(WeChatMessage message, int retryCount) {
        if (message == null) {
            return;
        }
        
        if (retryCount >= MAX_RETRY_COUNT) {
            logger.warn("消息已达到最大重试次数，放弃重试: msgid={}, retryCount={}", 
                message.getMsgid(), retryCount);
            failedRetries.incrementAndGet();
            return;
        }
        
        // 计算延迟时间（指数退避：60秒、120秒、240秒）
        long delaySeconds = INITIAL_RETRY_DELAY * (1L << retryCount);
        
        RetryTask task = new RetryTask(message, retryCount, System.currentTimeMillis() + delaySeconds * 1000);
        
        // 尝试加入队列
        if (retryQueue.offer(task)) {
            logger.info("添加重试任务: msgid={}, retryCount={}, delay={}秒", 
                message.getMsgid(), retryCount, delaySeconds);
        } else {
            logger.warn("重试队列已满，丢弃任务: msgid={}", message.getMsgid());
        }
    }
    
    /**
     * 处理重试队列
     * 检查队列中的任务，如果到了重试时间，则执行重试
     */
    private void processRetryQueue() {
        if (retryQueue.isEmpty()) {
            return;
        }
        
        logger.debug("处理重试队列，当前队列大小: {}", retryQueue.size());
        
        long currentTime = System.currentTimeMillis();
        int processedCount = 0;
        
        // 处理所有到期的任务
        while (!retryQueue.isEmpty()) {
            RetryTask task = retryQueue.peek();
            
            // 如果任务未到期，停止处理（队列按时间排序）
            if (task != null && task.getRetryTime() > currentTime) {
                break;
            }
            
            // 取出任务
            final RetryTask retryTask = retryQueue.poll();
            if (retryTask == null) {
                break;
            }
            
            // 异步执行重试
            workerExecutor.submit(() -> {
                retryDownload(retryTask);
            });
            
            processedCount++;
        }
        
        if (processedCount > 0) {
            logger.info("从重试队列中取出 {} 个任务进行重试", processedCount);
        }
    }
    
    /**
     * 执行重试下载
     * 
     * @param task 重试任务
     */
    private void retryDownload(RetryTask task) {
        WeChatMessage message = task.getMessage();
        int retryCount = task.getRetryCount();
        
        logger.info("开始重试下载: msgid={}, retryCount={}", message.getMsgid(), retryCount);
        totalRetries.incrementAndGet();
        
        try {
            // 从数据库重新加载消息（获取最新的 resource_id 状态）
            WeChatMessage latestMessage = messageRepository.findByMsgid(message.getMsgid());
            if (latestMessage == null) {
                logger.warn("消息不存在，跳过重试: msgid={}", message.getMsgid());
                return;
            }
            
            // 检查是否已经下载成功（resource_id 不为空）
            if (latestMessage.getResourceId() != null) {
                logger.info("消息资源已下载，跳过重试: msgid={}, resource_id={}", 
                    message.getMsgid(), latestMessage.getResourceId());
                return;
            }
            
            // 执行重试
            boolean success = mediaProcessService.processMedia(latestMessage);
            
            if (success) {
                logger.info("重试下载成功: msgid={}, retryCount={}", message.getMsgid(), retryCount);
                successRetries.incrementAndGet();
            } else {
                logger.warn("重试下载失败: msgid={}, retryCount={}", message.getMsgid(), retryCount);
                
                // 如果未达到最大重试次数，再次加入队列
                if (retryCount + 1 < MAX_RETRY_COUNT) {
                    addRetryTask(latestMessage, retryCount + 1);
                } else {
                    failedRetries.incrementAndGet();
                }
            }
            
        } catch (Exception e) {
            logger.error("重试下载异常: msgid={}, retryCount={}", message.getMsgid(), retryCount, e);
            
            // 如果未达到最大重试次数，再次加入队列
            if (retryCount + 1 < MAX_RETRY_COUNT) {
                addRetryTask(message, retryCount + 1);
            } else {
                failedRetries.incrementAndGet();
            }
        }
    }
    
    /**
     * 获取统计信息
     * 
     * @return 统计信息Map
     */
    public java.util.Map<String, Object> getStatistics() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("queueSize", retryQueue.size());
        stats.put("totalRetries", totalRetries.get());
        stats.put("successRetries", successRetries.get());
        stats.put("failedRetries", failedRetries.get());
        return stats;
    }
    
    /**
     * 重试任务内部类
     */
    private static class RetryTask {
        private final WeChatMessage message;
        private final int retryCount;
        private final long retryTime; // 重试时间戳（毫秒）
        
        public RetryTask(WeChatMessage message, int retryCount, long retryTime) {
            this.message = message;
            this.retryCount = retryCount;
            this.retryTime = retryTime;
        }
        
        public WeChatMessage getMessage() {
            return message;
        }
        
        public int getRetryCount() {
            return retryCount;
        }
        
        public long getRetryTime() {
            return retryTime;
        }
    }
}
