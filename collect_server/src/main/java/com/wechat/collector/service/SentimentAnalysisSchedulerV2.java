package com.wechat.collector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.collector.config.AppConfig;
import com.wechat.collector.config.DatabaseManager;
import com.wechat.collector.repository.SentimentRepository;
import com.wechat.collector.service.GeminiService.SentimentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 情绪分析定时任务服务（重构版）
 * 
 * 功能描述：
 * 1. 定时任务读取从配置日期开始的聊天记录
 * 2. 按照Gemini/Deepseek发送请求的最大token数量，读取聊天记录
 * 3. 如果聊天记录是图片或其他资源文件没有文字则只用标记即可
 * 4. 聊天记录还需要根据wechat_member标记聊天记录那些是客户，哪些是员工
 * 5. 发去情绪分析的聊天记录不能重复发送（检查sentiment_analysis_request表）
 * 6. 聊天记录不能截取，当聊天记录超过了token上限的时候，减少一条聊天记录的发送
 * 
 * 使用场景：
 * - 定时分析历史聊天记录的情绪
 * - 为情绪波动图提供数据
 * 
 * 依赖：
 * - DatabaseManager：数据库连接管理
 * - AiService：AI服务（Gemini或Deepseek）
 * - SentimentRepository：情绪分析数据访问
 */
public class SentimentAnalysisSchedulerV2 {
    private static final Logger logger = LoggerFactory.getLogger(SentimentAnalysisSchedulerV2.class);
    
    private final DatabaseManager databaseManager;
    private final AiService aiService;
    private final SentimentRepository sentimentRepository;
    private final ObjectMapper objectMapper;
    
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // 配置：开始分析的日期（默认2026-01-07）
    private LocalDate startDate;
    
    // 配置：执行间隔（小时）
    private int intervalHours = 24;
    
    public SentimentAnalysisSchedulerV2(DatabaseManager databaseManager, 
                                       AiService aiService,
                                       AppConfig.AiConfig aiConfig) {
        this.databaseManager = databaseManager;
        this.aiService = aiService;
        this.sentimentRepository = new SentimentRepository(databaseManager);
        this.objectMapper = new ObjectMapper();
        
        // 从配置读取开始日期，默认2026-01-07
        String startDateStr = System.getProperty("sentiment.analysis.start.date", "2026-01-07");
        try {
            this.startDate = LocalDate.parse(startDateStr);
        } catch (Exception e) {
            logger.warn("解析开始日期失败，使用默认日期2026-01-07: {}", startDateStr, e);
            this.startDate = LocalDate.of(2026, 1, 7);
        }
        
        logger.info("情绪分析定时任务初始化完成，开始日期: {}", this.startDate);
    }
    
    /**
     * 启动定时任务
     */
    public void start() {
        if (running.get()) {
            logger.warn("情绪分析定时任务已在运行");
            return;
        }
        
        logger.info("启动情绪分析定时任务...");
        running.set(true);
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sentiment-analysis-scheduler-v2");
            t.setDaemon(true);
            return t;
        });
        
        // 立即执行一次
        scheduler.submit(this::analyzeSentiment);
        
        // 每隔指定小时执行一次
        scheduler.scheduleWithFixedDelay(
            this::analyzeSentiment,
            intervalHours,
            intervalHours,
            TimeUnit.HOURS
        );
        
        logger.info("情绪分析定时任务已启动，执行间隔: {} 小时", intervalHours);
    }
    
    /**
     * 停止定时任务
     */
    public void stop() {
        if (!running.get()) {
            return;
        }
        
        logger.info("停止情绪分析定时任务...");
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
        
        logger.info("情绪分析定时任务已停止");
    }
    
    /**
     * 执行情绪分析
     */
    private void analyzeSentiment() {
        if (!running.get()) {
            return;
        }
        
        try {
            logger.info("开始执行情绪分析定时任务...");
            
            // 获取当前日期
            LocalDate today = LocalDate.now();
            
            // 从开始日期到今天，逐日分析
            LocalDate currentDate = startDate;
            int analyzedCount = 0;
            int skippedCount = 0;
            int errorCount = 0;
            
            while (!currentDate.isAfter(today)) {
                try {
                    boolean analyzed = analyzeDate(currentDate);
                    if (analyzed) {
                        analyzedCount++;
                    } else {
                        skippedCount++;
                    }
                } catch (Exception e) {
                    errorCount++;
                    logger.error("分析日期失败: date={}", currentDate, e);
                }
                
                currentDate = currentDate.plusDays(1);
            }
            
            logger.info("情绪分析定时任务完成: 分析={}, 跳过={}, 错误={}", 
                analyzedCount, skippedCount, errorCount);
            
        } catch (Exception e) {
            logger.error("情绪分析定时任务执行失败", e);
        }
    }
    
    /**
     * 分析指定日期的情绪
     * 
     * @param date 日期
     * @return 是否进行了分析（false表示跳过）
     */
    private boolean analyzeDate(LocalDate date) {
        logger.info("分析日期: {}", date);
        
        // 查询该日期所有有消息的会话
        List<String> conversationIds = getConversationsByDate(date);
        
        if (conversationIds.isEmpty()) {
            logger.debug("日期 {} 没有会话需要分析", date);
            return false;
        }
        
        logger.info("日期 {} 找到 {} 个会话需要分析", date, conversationIds.size());
        
        int successCount = 0;
        int skippedCount = 0;
        int errorCount = 0;
        
        for (String conversationId : conversationIds) {
            try {
                // 检查是否已经分析过（不重复发送）
                if (isAlreadyAnalyzed(conversationId, date)) {
                    skippedCount++;
                    continue;
                }
                
                // 分析该会话
                analyzeConversation(conversationId, date);
                successCount++;
                
            } catch (Exception e) {
                errorCount++;
                logger.error("分析会话失败: conversationId={}, date={}", conversationId, date, e);
            }
        }
        
        logger.info("日期 {} 分析完成: 成功={}, 跳过={}, 错误={}", 
            date, successCount, skippedCount, errorCount);
        
        return successCount > 0;
    }
    
    /**
     * 检查会话是否已经分析过
     * 
     * @param conversationId 会话ID
     * @param date 日期
     * @return 是否已分析
     */
    private boolean isAlreadyAnalyzed(String conversationId, LocalDate date) {
        String sql = "SELECT COUNT(*) as cnt FROM sentiment_analysis_request " +
                "WHERE conversation_id = ? AND analysis_date = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, conversationId);
            stmt.setDate(2, java.sql.Date.valueOf(date));
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("cnt") > 0;
                }
            }
        } catch (SQLException e) {
            logger.error("检查是否已分析失败: conversationId={}, date={}", conversationId, date, e);
        }
        
        return false;
    }
    
    /**
     * 分析单个会话
     * 
     * @param conversationId 会话ID
     * @param date 日期
     */
    private void analyzeConversation(String conversationId, LocalDate date) {
        logger.debug("分析会话: conversationId={}, date={}", conversationId, date);
        
        // 获取该会话当天的所有消息
        List<ConversationMessage> messages = getMessagesByDate(conversationId, date);
        
        if (messages.isEmpty()) {
            logger.debug("会话当天没有消息: conversationId={}, date={}", conversationId, date);
            return;
        }
        
        // 根据token限制调整消息数量（不截取消息内容，减少消息条数）
        List<ConversationMessage> adjustedMessages = adjustMessagesByTokenLimit(messages);
        
        if (adjustedMessages.isEmpty()) {
            logger.warn("调整后消息为空: conversationId={}, date={}", conversationId, date);
            return;
        }
        
        // 构建用于分析的文本（标记客户/员工）
        String messagesText = buildMessagesText(adjustedMessages);
        
        // 检查消息文本是否有实际内容
        if (isMessagesTextEmpty(messagesText)) {
            logger.warn("会话消息内容全部为空，跳过分析: conversationId={}", conversationId);
            return;
        }
        
        // 构建提示词
        String prompt = buildAnalysisPrompt(messagesText);
        
        // 调用AI分析
        String responseJson = null;
        SentimentResult sentimentResult = null;
        String status = "success";
        String errorMessage = null;
        
        try {
            responseJson = aiService.requestRawJson(prompt);
            sentimentResult = parseSentimentResult(responseJson);
        } catch (Exception e) {
            logger.error("AI分析失败: conversationId={}, date={}", conversationId, date, e);
            status = "failed";
            errorMessage = e.getMessage();
        }
        
        // 保存分析结果
        sentimentRepository.saveAnalysisRequest(
            date.toString(),
            conversationId,
            aiService.getServiceName().toLowerCase(), // aiService: gemini/deepseek
            messagesText,
            null, // aiRequestJson
            responseJson,
            sentimentResult != null ? sentimentResult.getLabel() : null,
            sentimentResult != null ? sentimentResult.getScore() : null,
            sentimentResult != null ? sentimentResult.getConfidence() : null,
            null, // negativeContent
            adjustedMessages.size(),
            status,
            errorMessage
        );
        
        logger.debug("会话分析完成: conversationId={}, date={}, status={}", 
            conversationId, date, status);
    }
    
    /**
     * 查询指定日期有消息的会话
     */
    private List<String> getConversationsByDate(LocalDate date) {
        List<String> conversationIds = new ArrayList<>();
        
        // 计算时间范围
        long startTime = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTime = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        
        String sql = "SELECT DISTINCT conversation_id " +
                "FROM wechat_message " +
                "WHERE msgtime >= ? AND msgtime < ? " +
                "AND action = 'send'";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setLong(1, startTime);
            stmt.setLong(2, endTime);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    conversationIds.add(rs.getString("conversation_id"));
                }
            }
        } catch (SQLException e) {
            logger.error("查询会话失败: date={}", date, e);
        }
        
        return conversationIds;
    }
    
    /**
     * 获取指定会话在指定日期的消息
     */
    private List<ConversationMessage> getMessagesByDate(String conversationId, LocalDate date) {
        List<ConversationMessage> messages = new ArrayList<>();
        
        // 计算时间范围
        long startTime = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTime = date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        
        String sql = "SELECT m.msgid, m.msgtime, m.from_user, m.msgtype, m.content, " +
                "wm.nick_name, wm.type " +
                "FROM wechat_message m " +
                "LEFT JOIN wechat_member wm ON m.from_user COLLATE utf8mb4_unicode_ci = wm.account_name COLLATE utf8mb4_unicode_ci " +
                "WHERE m.conversation_id = ? " +
                "AND m.action = 'send' " +
                "AND m.msgtime >= ? AND m.msgtime < ? " +
                "ORDER BY m.msgtime ASC";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, conversationId);
            stmt.setLong(2, startTime);
            stmt.setLong(3, endTime);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ConversationMessage message = new ConversationMessage();
                    message.setMsgid(rs.getString("msgid"));
                    message.setMsgtime(rs.getLong("msgtime"));
                    message.setFromUser(rs.getString("from_user"));
                    message.setMsgtype(rs.getString("msgtype"));
                    message.setContent(rs.getString("content"));
                    message.setNickName(rs.getString("nick_name"));
                    message.setUserType(rs.getShort("type"));
                    
                    messages.add(message);
                }
            }
        } catch (SQLException e) {
            logger.error("获取消息失败: conversationId={}, date={}", conversationId, date, e);
        }
        
        return messages;
    }
    
    /**
     * 根据token限制调整消息数量
     * 不截断消息内容，如果内容过多则减少消息条数
     */
    private List<ConversationMessage> adjustMessagesByTokenLimit(List<ConversationMessage> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        
        // 构建提示词前缀（用于计算token）
        String promptPrefix = buildAnalysisPrompt("").replace("聊天消息：\n", "");
        
        long promptTokens = aiService.estimateTokenCount(promptPrefix);
        long maxInputTokens = aiService.getMaxInputTokens();
        long availableTokens = maxInputTokens - promptTokens - 1000; // 预留1000 tokens
        
        // 从最后一条消息开始，逐步添加消息，直到接近token限制
        List<ConversationMessage> adjustedMessages = new ArrayList<>();
        long totalTokens = 0;
        
        // 从最后一条消息开始倒序添加（保留最近的对话上下文）
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage message = messages.get(i);
            String messageText = formatMessageForAnalysis(message);
            long messageTokens = aiService.estimateTokenCount(messageText);
            
            if (totalTokens + messageTokens <= availableTokens) {
                adjustedMessages.add(0, message); // 添加到开头保持时间顺序
                totalTokens += messageTokens;
            } else {
                // 如果添加这条消息会超过限制，停止添加
                break;
            }
        }
        
        if (adjustedMessages.size() < messages.size()) {
            logger.debug("Token限制调整: 原始消息数={}, 调整后消息数={}, 使用tokens={}, 可用tokens={}", 
                messages.size(), adjustedMessages.size(), totalTokens, availableTokens);
        }
        
        return adjustedMessages;
    }
    
    /**
     * 格式化消息用于分析（标记客户/员工）
     */
    private String formatMessageForAnalysis(ConversationMessage message) {
        String senderName = message.getNickName() != null && !message.getNickName().isEmpty()
            ? message.getNickName()
            : message.getFromUser();
        
        // 标记客户/员工（type=1是员工，type=2是客户）
        String roleTag = "";
        if (message.getUserType() != null) {
            if (message.getUserType() == 1) {
                roleTag = "[员工]";
            } else if (message.getUserType() == 2) {
                roleTag = "[客户]";
            }
        }
        
        String messageContent = extractMessageContent(message);
        
        return String.format("[%s] %s%s: %s\n",
            formatTime(message.getMsgtime()),
            roleTag,
            senderName,
            messageContent
        );
    }
    
    /**
     * 构建用于分析的消息文本
     */
    private String buildMessagesText(List<ConversationMessage> messages) {
        StringBuilder sb = new StringBuilder();
        
        for (ConversationMessage message : messages) {
            sb.append(formatMessageForAnalysis(message));
        }
        
        return sb.toString();
    }
    
    /**
     * 提取消息内容（如果是图片或其他资源文件没有文字则只用标记）
     */
    private String extractMessageContent(ConversationMessage message) {
        String msgType = message.getMsgtype();
        String content = message.getContent();
        
        // 文本消息，提取text字段
        if ("text".equals(msgType) && content != null) {
            try {
                JsonNode contentNode = objectMapper.readTree(content);
                JsonNode textNode = contentNode.get("text");
                if (textNode != null) {
                    String text = textNode.asText();
                    if (text != null && !text.isEmpty()) {
                        return text;
                    }
                }
            } catch (Exception e) {
                logger.debug("解析消息内容失败: msgid={}", message.getMsgid(), e);
            }
        }
        
        // 其他类型消息，显示类型标记
        return getMessageTypeName(msgType);
    }
    
    /**
     * 获取消息类型的中文名称
     */
    private String getMessageTypeName(String msgType) {
        if (msgType == null) {
            return "[未知类型]";
        }
        
        switch (msgType.toLowerCase()) {
            case "image":
                return "[图片]";
            case "voice":
                return "[语音]";
            case "video":
                return "[视频]";
            case "file":
                return "[文件]";
            case "emotion":
                return "[表情]";
            case "mixed":
                return "[混合消息]";
            default:
                return "[" + msgType + "]";
        }
    }
    
    /**
     * 检查消息文本是否为空
     */
    private boolean isMessagesTextEmpty(String messagesText) {
        if (messagesText == null || messagesText.isEmpty()) {
            return true;
        }
        
        // 去掉时间戳、用户名等格式字符后检查是否还有实际内容
        String cleaned = messagesText.replaceAll("\\[.*?\\]", "")
                .replaceAll(":", "")
                .replaceAll("\\s+", "")
                .trim();
        
        return cleaned.isEmpty();
    }
    
    /**
     * 构建分析提示词
     */
    private String buildAnalysisPrompt(String messagesText) {
        return "You are a sentiment analysis API. Analyze the customer service chat and return ONLY valid JSON, no other text.\n" +
                "Required JSON format: {\"label\":\"positive\",\"score\":50,\"confidence\":80}\n" +
                "Rules:\n" +
                "- label: \"positive\" or \"negative\" or \"neutral\"\n" +
                "- score: integer 0-100 (0=most positive, 100=most negative)\n" +
                "- confidence: integer 0-100\n\n" +
                "聊天消息：\n" + messagesText + "\n\n" +
                "{\"label\":";
    }
    
    /**
     * 解析AI返回的情绪分析结果
     */
    private SentimentResult parseSentimentResult(String responseJson) {
        if (responseJson == null || responseJson.isEmpty()) {
            return null;
        }
        
        try {
            // 尝试提取JSON部分
            String jsonStr = responseJson.trim();
            
            // 如果响应不是以{开头，尝试提取JSON部分
            if (!jsonStr.startsWith("{")) {
                int startIdx = jsonStr.indexOf("{");
                if (startIdx >= 0) {
                    jsonStr = jsonStr.substring(startIdx);
                }
            }
            
            // 如果响应不是以}结尾，尝试提取JSON部分
            if (!jsonStr.endsWith("}")) {
                int endIdx = jsonStr.lastIndexOf("}");
                if (endIdx >= 0) {
                    jsonStr = jsonStr.substring(0, endIdx + 1);
                }
            }
            
            JsonNode root = objectMapper.readTree(jsonStr);
            
            SentimentResult result = new SentimentResult();
            
            if (root.has("label")) {
                result.setLabel(root.get("label").asText());
            }
            if (root.has("score")) {
                result.setScore(root.get("score").asDouble());
            }
            if (root.has("confidence")) {
                result.setConfidence(root.get("confidence").asDouble());
            }
            
            return result;
            
        } catch (Exception e) {
            logger.error("解析情绪分析结果失败: responseJson={}", responseJson, e);
            return null;
        }
    }
    
    /**
     * 格式化时间戳
     */
    private String formatTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        return sdf.format(new java.util.Date(timestamp));
    }
    
    /**
     * 会话消息内部类
     */
    private static class ConversationMessage {
        private String msgid;
        private Long msgtime;
        private String fromUser;
        private String msgtype;
        private String content;
        private String nickName;
        private Short userType;
        
        // Getters and Setters
        public String getMsgid() { return msgid; }
        public void setMsgid(String msgid) { this.msgid = msgid; }
        
        public Long getMsgtime() { return msgtime; }
        public void setMsgtime(Long msgtime) { this.msgtime = msgtime; }
        
        public String getFromUser() { return fromUser; }
        public void setFromUser(String fromUser) { this.fromUser = fromUser; }
        
        public String getMsgtype() { return msgtype; }
        public void setMsgtype(String msgtype) { this.msgtype = msgtype; }
        
        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }
        
        public String getNickName() { return nickName; }
        public void setNickName(String nickName) { this.nickName = nickName; }
        
        public Short getUserType() { return userType; }
        public void setUserType(Short userType) { this.userType = userType; }
    }
}

