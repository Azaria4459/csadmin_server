package com.wechat.collector.service;

import com.wechat.collector.config.AppConfig;
import com.wechat.collector.model.WeChatMessage;
import com.wechat.collector.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 消息收集服务
 * 
 * 功能描述：
 * 1. 定时从企业微信拉取聊天消息（通过 WeChatSdkService）
 * 2. 增量拉取：基于 seq 顺序号，只拉取新消息
 * 3. 批量保存：将拉取的消息批量保存到数据库（wechat_message 表）
 * 4. 自动更新会话汇总：保存消息时自动更新 wechat_conversation 表
 * 5. 去重处理：基于 msgid 唯一性，自动跳过重复消息
 * 6. 统计信息：记录拉取和保存的消息数量
 * 
 * 使用场景：
 * - 作为系统核心服务，持续收集企业微信聊天记录
 * - 为其他分析服务（情绪分析、意向识别等）提供数据源
 * 
 * 依赖：
 * - WeChatSdkService：企业微信SDK服务
 * - MessageRepository：消息数据访问层
 * - wechat_message 表：消息主表
 * - wechat_conversation 表：会话汇总表
 * 
 * 执行频率：
 * - 默认每30秒执行一次（可配置）
 * - 每次拉取数量由配置决定（pullLimit）
 */
public class MessageCollectorService {
    private static final Logger logger = LoggerFactory.getLogger(MessageCollectorService.class);
    
    private final WeChatSdkService weChatSdkService;
    private final MessageRepository messageRepository;
    private final int pullLimit;
    
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    public MessageCollectorService(WeChatSdkService weChatSdkService, 
                                   MessageRepository messageRepository,
                                   AppConfig config) {
        this.weChatSdkService = weChatSdkService;
        this.messageRepository = messageRepository;
        this.pullLimit = config.getWechat().getPullLimit();
    }
    
    /**
     * 启动定时任务
     */
    public void start() {
        if (running.get()) {
            logger.warn("Message collector is already running");
            return;
        }
        
        logger.info("Starting message collector service...");
        
        running.set(true);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // 立即执行一次
        scheduler.submit(() -> collectMessagesWithResult(null));
        
        // 每隔30秒执行一次
        scheduler.scheduleWithFixedDelay(
            () -> collectMessagesWithResult(null),
            30,
            30,
            TimeUnit.SECONDS
        );
        
        logger.info("Message collector service started");
    }
    
    /**
     * 停止定时任务
     */
    public void stop() {
        if (!running.get()) {
            return;
        }
        
        logger.info("Stopping message collector service...");
        
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
        
        logger.info("Message collector service stopped");
    }
    
    /**
     * 收集消息主逻辑
     *
     * @return 本次新增入库的消息条数，异常或未拉取时返回 0
     */
    private int collectMessages() {
        CollectionResult result = collectMessagesWithResult(null);
        return result != null ? result.getSavedCount() : 0;
    }

    /**
     * 收集消息主逻辑（包含微信接口请求参数和返回内容）
     *
     * @param customSeq 自定义 seq，如果为 null 则使用全局最大 seq
     * @return 收集结果，包含本次新增入库的消息条数、微信接口请求参数和返回内容
     */
    private CollectionResult collectMessagesWithResult(Long customSeq) {
        if (!running.get()) {
            return CollectionResult.error("Collector not running");
        }
        
        try {
            logger.debug("Starting to collect messages...");
            
            // 获取上次拉取的最大 seq（如果未指定自定义 seq）
            long lastSeq = customSeq != null ? customSeq : messageRepository.getMaxSeq();
            if (customSeq != null) {
                logger.info("Using custom seq: {} (global max seq: {})", customSeq, messageRepository.getMaxSeq());
            } else {
                logger.debug("Last seq: {}", lastSeq);
            }
            
            // 构建微信接口请求参数
            Map<String, Object> wechatRequest = new HashMap<>();
            wechatRequest.put("seq", lastSeq);
            wechatRequest.put("limit", pullLimit);
            
            // 拉取消息（包含原始 JSON 响应）
            FetchResult fetchResult = weChatSdkService.fetchChatDataWithResponse(lastSeq, pullLimit);
            
            if (!fetchResult.isSuccess()) {
                logger.error("Failed to fetch chat data: errorCode={}, errorMessage={}", 
                    fetchResult.getErrorCode(), fetchResult.getErrorMessage());
                return CollectionResult.error(fetchResult.getErrorMessage());
            }
            
            List<WeChatMessage> messages = fetchResult.getMessages();
            String rawJsonResponse = fetchResult.getRawJsonResponse();
            
            if (messages.isEmpty()) {
                logger.debug("No new messages to collect");
                return new CollectionResult(0, 0, wechatRequest, rawJsonResponse);
            }
            
            // 保存到数据库
            int savedCount = messageRepository.batchInsert(messages);
            
            if (savedCount > 0) {
                logger.info("Successfully saved {} new messages (total fetched: {})", savedCount, messages.size());
                
                // 统计信息
                long totalCount = messageRepository.count();
                logger.info("Total messages in database: {}", totalCount);
            } else {
                logger.debug("No new messages saved (all duplicates)");
            }
            
            return new CollectionResult(savedCount, messages.size(), wechatRequest, rawJsonResponse);
        } catch (Exception e) {
            logger.error("Error collecting messages", e);
            return CollectionResult.error("Exception: " + e.getMessage());
        }
    }
    
    /**
     * 手动触发一次收集（异步，不等待拉取完成）
     */
    public void triggerCollection() {
        triggerCollection(null);
    }

    /**
     * 手动触发一次收集（异步，不等待拉取完成）
     *
     * @param customSeq 自定义 seq，如果为 null 则使用全局最大 seq
     */
    public void triggerCollection(Long customSeq) {
        if (scheduler != null && !scheduler.isShutdown()) {
            final Long seq = customSeq;
            scheduler.submit(() -> {
                if (seq != null) {
                    collectMessagesWithResult(seq);
                    // 忽略结果，保持异步行为
                } else {
                    collectMessages();
                }
            });
            logger.info("Manual collection triggered" + (customSeq != null ? " with custom seq: " + customSeq : ""));
        }
    }

    /**
     * 手动触发一次收集并同步等待拉取完成
     * 用于 /conversation/pull 等接口，确保本次请求真正执行企业微信拉取后再返回。
     *
     * @param timeoutSeconds 最大等待秒数，超时抛出 TimeoutException
     * @return 本次新增入库的消息条数
     * @throws TimeoutException     等待超时
     * @throws InterruptedException 等待被中断
     * @throws RuntimeException     调度器未就绪或拉取执行异常
     */
    public int triggerCollectionAndWait(long timeoutSeconds) throws TimeoutException, InterruptedException, RuntimeException {
        CollectionResult result = triggerCollectionAndWaitWithResult(timeoutSeconds);
        return result != null ? result.getSavedCount() : 0;
    }

    /**
     * 手动触发一次收集并同步等待拉取完成（包含微信接口请求参数和返回内容）
     * 用于 /conversation/pull 等接口，确保本次请求真正执行企业微信拉取后再返回。
     *
     * @param timeoutSeconds 最大等待秒数，超时抛出 TimeoutException
     * @return 收集结果，包含本次新增入库的消息条数、微信接口请求参数和返回内容
     * @throws TimeoutException     等待超时
     * @throws InterruptedException 等待被中断
     * @throws RuntimeException     调度器未就绪或拉取执行异常
     */
    public CollectionResult triggerCollectionAndWaitWithResult(long timeoutSeconds) throws TimeoutException, InterruptedException, RuntimeException {
        return triggerCollectionAndWaitWithResult(timeoutSeconds, null);
    }

    /**
     * 手动触发一次收集并同步等待拉取完成（包含微信接口请求参数和返回内容）
     * 用于 /conversation/pull 等接口，确保本次请求真正执行企业微信拉取后再返回。
     *
     * @param timeoutSeconds 最大等待秒数，超时抛出 TimeoutException
     * @param customSeq      自定义 seq，如果为 null 则使用全局最大 seq
     * @return 收集结果，包含本次新增入库的消息条数、微信接口请求参数和返回内容
     * @throws TimeoutException     等待超时
     * @throws InterruptedException 等待被中断
     * @throws RuntimeException     调度器未就绪或拉取执行异常
     */
    public CollectionResult triggerCollectionAndWaitWithResult(long timeoutSeconds, Long customSeq) throws TimeoutException, InterruptedException, RuntimeException {
        if (scheduler == null || scheduler.isShutdown()) {
            throw new RuntimeException("Message collector scheduler not available");
        }
        final Long seq = customSeq;
        Future<CollectionResult> future = scheduler.submit(() -> collectMessagesWithResult(seq));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause != null ? cause : e);
        }
    }
    
    /**
     * 检查服务是否运行中
     */
    public boolean isRunning() {
        return running.get();
    }
}

