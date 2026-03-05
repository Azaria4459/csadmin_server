package com.wechat.collector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.collector.config.DatabaseManager;
import com.wechat.collector.model.Conversation;
import com.wechat.collector.repository.ConversationRepository;
import com.wechat.collector.repository.SentimentRepository;
import com.wechat.collector.service.GeminiService.SentimentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 情绪分析API服务类
 * 
 * 功能描述：
 * 1. 按日期分析情绪：分析指定日期的所有群聊记录
 * 2. Token限制处理：根据Gemini的token限制动态调整消息数量
 * 3. 请求记录保存：保存每次分析的完整请求和响应记录
 * 4. 测试模式：支持测试模式，只分析少量记录并返回原始JSON
 * 
 * 使用场景：
 * - 通过HTTP接口手动触发情绪分析
 * - 按日期批量分析历史聊天记录
 * 
 * 依赖：
 * - DatabaseManager：数据库连接管理
 * - ConversationRepository：会话数据访问
 * - GeminiService：Gemini AI服务
 * - SentimentRepository：情绪分析数据访问
 */
public class SentimentAnalysisApiService {
    private static final Logger logger = LoggerFactory.getLogger(SentimentAnalysisApiService.class);
    
    private final DatabaseManager databaseManager;
    private final ConversationRepository conversationRepository;
    private final AiService aiService;
    private final SentimentRepository sentimentRepository;
    private final ObjectMapper objectMapper;
    
    // 测试模式下分析的消息数量
    private static final int TEST_MESSAGE_COUNT = 3;
    
    /**
     * 构造函数
     * 
     * @param databaseManager 数据库管理器
     * @param geminiService Gemini AI服务
     */
    public SentimentAnalysisApiService(DatabaseManager databaseManager, AiService aiService) {
        this.databaseManager = databaseManager;
        this.conversationRepository = new ConversationRepository(databaseManager);
        this.aiService = aiService;
        this.sentimentRepository = new SentimentRepository(databaseManager);
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 分析指定日期的情绪
     * 
     * @param dateStr 日期字符串，格式：yyyy/MM/dd 或 yyyy-MM-dd
     * @param testMode 是否为测试模式
     * @return 分析结果
     */
    public AnalysisResult analyzeByDate(String dateStr, boolean testMode) {
        logger.info("开始按日期分析情绪: date={}, testMode={}", dateStr, testMode);
        
        AnalysisResult result = new AnalysisResult();
        result.setDate(dateStr);
        result.setTestMode(testMode);
        
        try {
            // 解析日期
            java.sql.Date analysisDate = parseDate(dateStr);
            if (analysisDate == null) {
                result.setSuccess(false);
                result.setErrorMessage("日期格式错误，请使用 yyyy/MM/dd 或 yyyy-MM-dd 格式");
                return result;
            }
            
            // 查询当天的所有群聊会话
            List<String> conversationIds = getConversationsByDate(analysisDate);
            logger.info("找到 {} 个群聊会话需要分析", conversationIds.size());
            result.setTotalConversations(conversationIds.size());
            
            // 测试模式：如果没有会话，返回一个示例请求格式
            if (testMode && conversationIds.isEmpty()) {
                logger.info("测试模式：没有找到会话，返回示例请求格式");
                Map<String, Object> testResult = new HashMap<>();
                testResult.put("conversationId", null);
                testResult.put("conversationName", "示例");
                testResult.put("messageCount", 0);
                testResult.put("geminiRequestJson", buildGeminiRequestJson("示例消息：这是一条测试消息\n"));
                testResult.put("geminiResponseJson", null);
                testResult.put("note", "该日期没有群聊会话，这是示例请求格式");
                result.getTestResults().add(testResult);
            }
            
            // 测试模式：只处理前几条
            if (testMode && conversationIds.size() > TEST_MESSAGE_COUNT) {
                conversationIds = conversationIds.subList(0, TEST_MESSAGE_COUNT);
                logger.info("测试模式：只分析前 {} 个会话", TEST_MESSAGE_COUNT);
            }
            
            // 分析每个会话
            for (int i = 0; i < conversationIds.size(); i++) {
                String conversationId = conversationIds.get(i);
                try {
                    analyzeConversation(analysisDate, conversationId, result, testMode);
                    result.setSuccessCount(result.getSuccessCount() + 1);
                    
                    // 在请求之间添加延迟，避免频率过快（最后一个请求不需要延迟）
                    if (i < conversationIds.size() - 1) {
                        // 每次请求之间延迟1秒，避免触发频率限制
                        Thread.sleep(1000L);
                    }
                } catch (Exception e) {
                    logger.error("分析会话失败: conversationId={}", conversationId, e);
                    result.setFailureCount(result.getFailureCount() + 1);
                    
                    // 即使失败也要延迟，避免连续失败请求过快
                    if (i < conversationIds.size() - 1) {
                        try {
                            Thread.sleep(1000L);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
            
            result.setSuccess(true);
            logger.info("按日期分析情绪完成: 总数={}, 成功={}, 失败={}", 
                result.getTotalConversations(), result.getSuccessCount(), result.getFailureCount());
            
        } catch (Exception e) {
            logger.error("按日期分析情绪失败", e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
        }
        
        return result;
    }
    
    /**
     * 分析单个会话
     * 
     * @param analysisDate 分析日期
     * @param conversationId 会话ID
     * @param result 分析结果（用于收集结果）
     * @param testMode 是否为测试模式
     */
    private void analyzeConversation(java.sql.Date analysisDate, String conversationId, 
                                    AnalysisResult result, boolean testMode) throws Exception {
        // 获取会话信息
        Conversation conversation = conversationRepository.getConversationById(conversationId);
        if (conversation == null) {
            logger.warn("会话不存在: conversationId={}", conversationId);
            return;
        }
        
        // 获取当天的消息
        List<ConversationMessage> messages = getMessagesByDate(conversationId, analysisDate);
        
        // 测试模式：输出查询到的聊天记录详情
        if (testMode) {
            logger.info("====== 会话聊天记录详情 ======");
            logger.info("会话ID: {}", conversationId);
            logger.info("会话名称: {}", conversation.getName());
            logger.info("查询日期: {}", analysisDate);
            logger.info("消息总数: {}", messages.size());
            
            if (!messages.isEmpty()) {
                logger.info("====== 消息列表 ======");
                for (int i = 0; i < messages.size(); i++) {
                    ConversationMessage msg = messages.get(i);
                    logger.info("消息 #{}: msgid={}, msgtype={}, from_user={}, nick_name={}, msgtime={}, content={}",
                        i + 1,
                        msg.getMsgid(),
                        msg.getMsgtype(),
                        msg.getFromUser(),
                        msg.getNickName(),
                        formatTime(msg.getMsgtime()),
                        msg.getContent() != null ? (msg.getContent().length() > 200 ? msg.getContent().substring(0, 200) + "..." : msg.getContent()) : "null"
                    );
                }
                logger.info("====== 消息列表结束 ======");
            } else {
                logger.info("该会话在指定日期没有消息");
            }
            logger.info("====== 会话聊天记录详情结束 ======\n");
        }
        
        // 测试模式：即使没有消息，也要返回请求格式
        if (messages.isEmpty()) {
            logger.debug("会话当天没有消息: conversationId={}, date={}", conversationId, analysisDate);
            if (testMode) {
                // 测试模式下，即使没有消息也返回空的请求格式
                String emptyMessagesText = "";
                String geminiRequestJson = buildGeminiRequestJson(emptyMessagesText);
                
                Map<String, Object> testResult = new HashMap<>();
                testResult.put("conversationId", conversationId);
                testResult.put("conversationName", conversation != null ? conversation.getName() : conversationId);
                testResult.put("messageCount", 0);
                testResult.put("geminiRequestJson", geminiRequestJson);
                testResult.put("geminiResponseJson", null);
                testResult.put("note", "该会话当天没有消息");
                result.getTestResults().add(testResult);
            }
            return;
        }
        
        // 根据token限制调整消息数量
        List<ConversationMessage> adjustedMessages = adjustMessagesByTokenLimit(messages);
        
        // 构建用于分析的文本
        String messagesText = buildMessagesText(adjustedMessages);
        
        // 检查消息文本是否有实际内容（去掉时间戳和用户名后）
        if (isMessagesTextEmpty(messagesText)) {
            logger.warn("会话消息内容全部为空，跳过分析: conversationId={}", conversationId);
            if (testMode) {
                Map<String, Object> testResult = new HashMap<>();
                testResult.put("conversationId", conversationId);
                testResult.put("conversationName", conversation.getName());
                testResult.put("messageCount", adjustedMessages.size());
                testResult.put("geminiRequestJson", null);
                testResult.put("geminiResponseJson", null);
                testResult.put("note", "所有消息内容为空，无法分析");
                result.getTestResults().add(testResult);
            }
            return;
        }
        
        // 构建提示词
        String prompt = buildAnalysisPrompt(messagesText);
        
        // 构建请求JSON（仅用于日志记录，Gemini需要完整JSON，Deepseek只需要提示词）
        String geminiRequestJson = buildGeminiRequestJson(messagesText);
        
        // 记录AI请求参数（格式化输出）
        logger.info("=== {}请求开始 ===", aiService.getServiceName());
        logger.info("会话ID: {}", conversationId);
        logger.info("会话名称: {}", conversation.getName());
        logger.info("消息数量: {}", adjustedMessages.size());
        logger.info("--- 请求JSON ---");
        logger.info("{}", formatJson(geminiRequestJson));
        logger.info("--- 发送的聊天内容（前500字符） ---");
        logger.info("{}", messagesText.length() > 500 ? messagesText.substring(0, 500) + "..." : messagesText);
        logger.info("=== {}请求结束 ===\n", aiService.getServiceName());
        
        // 调用AI分析（传入构建好的提示词）
        String geminiResponseJson = null;
        SentimentResult sentimentResult = null;
        String status = "success";
        String errorMessage = null;
        
        try {
            geminiResponseJson = aiService.requestRawJson(prompt);
            
            // 记录AI返回结果（格式化输出）
            logger.info("=== {}响应开始 ===", aiService.getServiceName());
            logger.info("会话ID: {}", conversationId);
            logger.info("响应长度: {} 字节", geminiResponseJson != null ? geminiResponseJson.length() : 0);
            logger.info("--- 响应JSON ---");
            logger.info("{}", formatJson(geminiResponseJson));
            logger.info("=== {}响应结束 ===\n", aiService.getServiceName());
            
            sentimentResult = parseSentimentResult(geminiResponseJson);
            
            if (sentimentResult != null) {
                logger.info("=== 解析结果 ===");
                logger.info("会话ID: {}", conversationId);
                logger.info("情绪标签: {}", sentimentResult.getLabel());
                logger.info("情绪分数: {}", sentimentResult.getScore());
                logger.info("置信度: {}", sentimentResult.getConfidence());
                logger.info("=== 解析结果结束 ===\n");
            } else {
                logger.warn("解析结果为空: conversationId={}", conversationId);
            }
        } catch (Exception e) {
            logger.error("=== {}调用失败 ===", aiService.getServiceName());
            logger.error("会话ID: {}", conversationId);
            logger.error("错误信息: {}", e.getMessage(), e);
            logger.error("=== 错误信息结束 ===\n");
            status = "failed";
            errorMessage = e.getMessage();
        }
        
        // 提取负面内容（如果分析成功且为负面情绪）
        String negativeContent = null;
        if (sentimentResult != null && "negative".equals(sentimentResult.getLabel())) {
            negativeContent = extractNegativeContent(geminiResponseJson, sentimentResult);
        }
        
        // 保存请求记录（只有非测试模式或测试模式下成功的情况才保存）
        if (!testMode || geminiResponseJson != null) {
            sentimentRepository.saveAnalysisRequest(
                analysisDate.toString(),
                conversationId,
                aiService.getServiceName().toLowerCase(), // aiService: gemini/deepseek
                messagesText,
                geminiRequestJson,
                geminiResponseJson,
                sentimentResult != null ? sentimentResult.getLabel() : null,
                sentimentResult != null ? sentimentResult.getScore() : null,
                sentimentResult != null ? sentimentResult.getConfidence() : null,
                negativeContent,
                adjustedMessages.size(),
                status,
                errorMessage
            );
        }
        
        // 测试模式：添加到结果中（无论成功或失败都添加）
        if (testMode) {
            Map<String, Object> testResult = new HashMap<>();
            testResult.put("conversationId", conversationId);
            testResult.put("conversationName", conversation.getName());
            testResult.put("messageCount", adjustedMessages.size());
            testResult.put("geminiRequestJson", geminiRequestJson);
            testResult.put("geminiResponseJson", geminiResponseJson);
            if (errorMessage != null) {
                testResult.put("error", errorMessage);
            }
            result.getTestResults().add(testResult);
        }
    }
    
    /**
     * 解析日期字符串
     * 支持 yyyy/MM/dd 和 yyyy-MM-dd 格式
     * 
     * @param dateStr 日期字符串
     * @return Date对象，解析失败返回null
     */
    private java.sql.Date parseDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return null;
        }
        
        // 统一替换分隔符为 -
        String normalizedDate = dateStr.replace('/', '-');
        
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            java.util.Date date = sdf.parse(normalizedDate);
            return new java.sql.Date(date.getTime());
        } catch (Exception e) {
            logger.error("日期解析失败: dateStr={}", dateStr, e);
            return null;
        }
    }
    
    /**
     * 查询指定日期的所有群聊会话
     * 
     * @param date 日期
     * @return 会话ID列表
     */
    private List<String> getConversationsByDate(java.sql.Date date) {
        List<String> conversationIds = new ArrayList<>();
        
        // 计算时间范围（当天的开始和结束时间戳）
        // 使用LocalDate来准确计算当天的开始时间（00:00:00）
        java.time.LocalDate localDate = date.toLocalDate();
        java.time.LocalDateTime startDateTime = localDate.atStartOfDay(); // 当天00:00:00
        java.time.LocalDateTime endDateTime = localDate.plusDays(1).atStartOfDay(); // 次日00:00:00
        
        // 转换为时间戳（毫秒）
        long startTime = startDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTime = endDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        
        logger.debug("查询日期范围: date={}, startTime={}, endTime={}", date, startTime, endTime);
        
        // 简化SQL查询，直接查询群聊消息（conversation_type='group'）
        // 使用 COLLATE utf8mb4_unicode_ci 避免字符集排序规则冲突
        String sql = "SELECT DISTINCT m.conversation_id " +
                "FROM wechat_message m " +
                "INNER JOIN wechat_conversation c ON m.conversation_id COLLATE utf8mb4_unicode_ci = c.conversation_id COLLATE utf8mb4_unicode_ci " +
                "WHERE c.conversation_type = 'group' " +
                "AND m.msgtime >= ? AND m.msgtime < ? " +
                "ORDER BY m.conversation_id";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setLong(1, startTime);
            stmt.setLong(2, endTime);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    conversationIds.add(rs.getString("conversation_id"));
                }
            }
            
            logger.debug("查询到 {} 个群聊会话", conversationIds.size());
            
        } catch (SQLException e) {
            logger.error("查询指定日期的群聊会话失败: date={}", date, e);
        }
        
        return conversationIds;
    }
    
    /**
     * 获取指定会话在指定日期的消息
     * 
     * @param conversationId 会话ID
     * @param date 日期
     * @return 消息列表（按时间正序）
     */
    private List<ConversationMessage> getMessagesByDate(String conversationId, java.sql.Date date) {
        List<ConversationMessage> messages = new ArrayList<>();
        
        // 计算时间范围（当天的开始和结束时间戳）
        java.time.LocalDate localDate = date.toLocalDate();
        java.time.LocalDateTime startDateTime = localDate.atStartOfDay();
        java.time.LocalDateTime endDateTime = localDate.plusDays(1).atStartOfDay();
        
        long startTime = startDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        long endTime = endDateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        
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
                    
                    String content = rs.getString("content");
                    message.setContent(content);
                    
                    // 记录前几条消息的详细信息（用于调试）
                    if (messages.size() < 3) {
                        logger.info("读取消息: msgid={}, msgtype={}, contentIsNull={}, contentLength={}, contentPreview={}", 
                            message.getMsgid(), 
                            message.getMsgtype(), 
                            content == null,
                            content != null ? content.length() : 0,
                            content != null && content.length() > 0 ? content.substring(0, Math.min(100, content.length())) : "null"
                        );
                    }
                    
                    message.setNickName(rs.getString("nick_name"));
                    message.setUserType(rs.getShort("type"));
                    
                    messages.add(message);
                }
            }
            
        } catch (SQLException e) {
            logger.error("获取指定日期的消息失败: conversationId={}, date={}", conversationId, date, e);
        }
        
        return messages;
    }
    
    /**
     * 根据token限制调整消息数量
     * 不截断消息内容，如果内容过多则减少消息条数
     * 
     * @param messages 原始消息列表
     * @return 调整后的消息列表
     */
    private List<ConversationMessage> adjustMessagesByTokenLimit(List<ConversationMessage> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        
        // 构建提示词（用于计算token，与实际发送的提示词保持一致）
        String promptPrefix = buildAnalysisPrompt("").replace("聊天消息：\n", "");
        
        long promptTokens = aiService.estimateTokenCount(promptPrefix);
        long maxInputTokens = aiService.getMaxInputTokens();
        long availableTokens = maxInputTokens - promptTokens - 1000; // 预留1000 tokens的安全边界
        
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
        
        // 如果所有消息都添加后仍然在限制内，返回所有消息
        if (adjustedMessages.size() == messages.size()) {
            return messages;
        }
        
        logger.debug("Token限制调整: 原始消息数={}, 调整后消息数={}, 使用tokens={}, 可用tokens={}", 
            messages.size(), adjustedMessages.size(), totalTokens, availableTokens);
        
        return adjustedMessages;
    }
    
    /**
     * 格式化消息用于分析
     * 
     * @param message 消息对象
     * @return 格式化后的消息文本
     */
    private String formatMessageForAnalysis(ConversationMessage message) {
        String senderName = message.getNickName() != null && !message.getNickName().isEmpty()
            ? message.getNickName()
            : message.getFromUser();
        
        String messageContent = extractMessageContent(message);
        
        return String.format("[%s] %s: %s\n",
            formatTime(message.getMsgtime()),
            senderName,
            messageContent
        );
    }
    
    /**
     * 构建用于分析的消息文本
     * 
     * @param messages 消息列表
     * @return 格式化的消息文本
     */
    private String buildMessagesText(List<ConversationMessage> messages) {
        StringBuilder sb = new StringBuilder();
        
        for (ConversationMessage message : messages) {
            sb.append(formatMessageForAnalysis(message));
        }
        
        return sb.toString();
    }
    
    /**
     * 构建分析提示词
     * 使用极简且严格的格式要求，确保 AI 返回纯 JSON
     * 技巧：在提示词开头和结尾都强调 JSON 格式，并在末尾直接以 JSON 开头
     * 
     * @param messagesText 消息文本
     * @return 完整的提示词
     */
    private String buildAnalysisPrompt(String messagesText) {
        // 使用极简格式，在开头就强调 JSON，减少AI产生额外文字的机会
        // 在最后直接以JSON开头，引导AI从JSON开始返回
        // 使用英文指令可能更有效，因为很多 AI 模型对英文指令响应更好
        return "You are a sentiment analysis API. Analyze the customer service chat and return ONLY valid JSON, no other text.\n" +
                "Required JSON format: {\"label\":\"positive\",\"score\":50,\"confidence\":80,\"negative_content\":\"\"}\n" +
                "Rules:\n" +
                "- label: \"positive\" or \"negative\" or \"neutral\"\n" +
                "- score: integer 0-100 (0=most positive, 100=most negative)\n" +
                "- confidence: integer 0-100\n" +
                "- negative_content: extract specific dialogue if negative, else empty string \"\"\n\n" +
                "Chat log:\n" + messagesText + "\n\n" +
                "{\"label\":";
    }
    
    /**
     * 构建发送给Gemini的JSON请求
     * 
     * @param messagesText 消息文本
     * @return JSON请求字符串
     */
    private String buildGeminiRequestJson(String messagesText) {
        try {
            String prompt = buildAnalysisPrompt(messagesText);
            
            Map<String, Object> data = new HashMap<>();
            Map<String, Object>[] contents = new Map[1];
            contents[0] = new HashMap<>();
            
            Map<String, Object>[] parts = new Map[1];
            parts[0] = new HashMap<>();
            parts[0].put("text", prompt);
            
            contents[0].put("parts", parts);
            data.put("contents", contents);
            
            // 添加 generationConfig 强制返回 JSON 格式
            Map<String, Object> generationConfig = new HashMap<>();
            generationConfig.put("response_mime_type", "application/json");
            data.put("generationConfig", generationConfig);
            
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            logger.error("构建Gemini请求JSON失败", e);
            return "{}";
        }
    }
    
    /**
     * 解析AI返回的情绪分析结果
     * 支持 Gemini 和 Deepseek 两种响应格式
     * 
     * @param responseJson AI返回的JSON响应
     * @return 情绪分析结果
     */
    private SentimentResult parseSentimentResult(String responseJson) {
        SentimentResult result = new SentimentResult();
        
        try {
            JsonNode apiResult = objectMapper.readTree(responseJson);
            String resultText = null;
            
            // 尝试解析 Gemini 格式：candidates[0].content.parts[0].text
            JsonNode candidatesNode = apiResult.get("candidates");
            if (candidatesNode != null && candidatesNode.isArray() && candidatesNode.size() > 0) {
                JsonNode firstCandidate = candidatesNode.get(0);
                JsonNode contentNode = firstCandidate.get("content");
                if (contentNode != null) {
                    JsonNode partsNode = contentNode.get("parts");
                    if (partsNode != null && partsNode.isArray() && partsNode.size() > 0) {
                        JsonNode firstPart = partsNode.get(0);
                        JsonNode textNode = firstPart.get("text");
                        if (textNode != null) {
                            resultText = textNode.asText();
                        }
                    }
                }
            }
            
            // 如果不是 Gemini 格式，尝试解析 Deepseek 格式：choices[0].message.content
            if (resultText == null) {
                JsonNode choicesNode = apiResult.get("choices");
                if (choicesNode != null && choicesNode.isArray() && choicesNode.size() > 0) {
                    JsonNode firstChoice = choicesNode.get(0);
                    JsonNode messageNode = firstChoice.get("message");
                    if (messageNode != null) {
                        JsonNode contentNode = messageNode.get("content");
                        if (contentNode != null) {
                            resultText = contentNode.asText();
                        }
                    }
                }
            }
            
            if (resultText != null && !resultText.isEmpty()) {
                // 清理文本：移除可能的 markdown 代码块标记
                String cleanedText = resultText.trim();
                
                // 移除开头的 markdown 代码块标记
                if (cleanedText.startsWith("```json")) {
                    cleanedText = cleanedText.substring(7);
                } else if (cleanedText.startsWith("```")) {
                    cleanedText = cleanedText.substring(3);
                }
                
                // 移除结尾的 markdown 代码块标记
                if (cleanedText.endsWith("```")) {
                    cleanedText = cleanedText.substring(0, cleanedText.length() - 3);
                }
                
                cleanedText = cleanedText.trim();
                
                // 尝试提取JSON对象（可能被包裹在文本中）
                // 尝试找到第一个 { 和最后一个 } 之间的内容
                int firstBrace = cleanedText.indexOf('{');
                int lastBrace = cleanedText.lastIndexOf('}');
                if (firstBrace >= 0 && lastBrace > firstBrace) {
                    cleanedText = cleanedText.substring(firstBrace, lastBrace + 1);
                }
                
                // 尝试解析JSON结果
                try {
                    JsonNode resultJson = objectMapper.readTree(cleanedText);
                    if (resultJson.has("label")) {
                        result.setLabel(resultJson.get("label").asText());
                    }
                    if (resultJson.has("score")) {
                        result.setScore(resultJson.get("score").asDouble());
                    }
                    if (resultJson.has("confidence")) {
                        result.setConfidence(resultJson.get("confidence").asDouble());
                    }
                    logger.debug("成功解析JSON格式情绪结果: label={}, score={}, confidence={}", 
                        result.getLabel(), result.getScore(), result.getConfidence());
                } catch (Exception e) {
                    // JSON解析失败，尝试从文本中提取
                    logger.warn("JSON解析失败，AI可能未返回JSON格式");
                    logger.warn("返回的文本内容: {}", resultText.length() > 500 ? resultText.substring(0, 500) + "..." : resultText);
                    logger.warn("清理后的文本: {}", cleanedText.length() > 500 ? cleanedText.substring(0, 500) + "..." : cleanedText);
                    parseSentimentFromText(cleanedText, result);
                    logger.info("文本解析结果: label={}, score={}, confidence={}", 
                        result.getLabel(), result.getScore(), result.getConfidence());
                }
            } else {
                logger.warn("无法从AI响应中提取文本内容");
            }
        } catch (Exception e) {
            logger.error("解析AI响应失败", e);
        }
        
        return result;
    }
    
    /**
     * 从文本中解析情绪结果
     */
    private void parseSentimentFromText(String text, SentimentResult result) {
        String lowerText = text.toLowerCase();
        
        // 解析标签
        if (lowerText.contains("negative") || lowerText.contains("负面")) {
            result.setLabel("negative");
        } else if (lowerText.contains("positive") || lowerText.contains("正面")) {
            result.setLabel("positive");
        } else if (lowerText.contains("neutral") || lowerText.contains("中性")) {
            result.setLabel("neutral");
        }
        
        // 尝试提取分数
        java.util.regex.Pattern scorePattern = java.util.regex.Pattern.compile("(?:score|分数)[:：]?\\s*(\\d+(?:\\.\\d+)?)");
        java.util.regex.Matcher scoreMatcher = scorePattern.matcher(text);
        if (scoreMatcher.find()) {
            try {
                result.setScore(Double.parseDouble(scoreMatcher.group(1)));
            } catch (NumberFormatException e) {
                // 忽略
            }
        }
        
        // 尝试提取置信度
        java.util.regex.Pattern confPattern = java.util.regex.Pattern.compile("(?:confidence|置信度)[:：]?\\s*(\\d+(?:\\.\\d+)?)");
        java.util.regex.Matcher confMatcher = confPattern.matcher(text);
        if (confMatcher.find()) {
            try {
                result.setConfidence(Double.parseDouble(confMatcher.group(1)));
            } catch (NumberFormatException e) {
                // 忽略
            }
        }
    }
    
    /**
     * 提取负面内容
     * 从AI响应中提取negative_content字段
     * 支持 Gemini 和 Deepseek 两种响应格式
     * 
     * @param responseJson AI响应JSON
     * @param sentimentResult 情绪分析结果
     * @return 负面内容
     */
    private String extractNegativeContent(String responseJson, SentimentResult sentimentResult) {
        try {
            JsonNode apiResult = objectMapper.readTree(responseJson);
            String resultText = null;
            
            // 尝试解析 Gemini 格式：candidates[0].content.parts[0].text
            JsonNode candidatesNode = apiResult.get("candidates");
            if (candidatesNode != null && candidatesNode.isArray() && candidatesNode.size() > 0) {
                JsonNode firstCandidate = candidatesNode.get(0);
                JsonNode contentNode = firstCandidate.get("content");
                if (contentNode != null) {
                    JsonNode partsNode = contentNode.get("parts");
                    if (partsNode != null && partsNode.isArray() && partsNode.size() > 0) {
                        JsonNode firstPart = partsNode.get(0);
                        JsonNode textNode = firstPart.get("text");
                        if (textNode != null) {
                            resultText = textNode.asText();
                        }
                    }
                }
            }
            
            // 如果不是 Gemini 格式，尝试解析 Deepseek 格式：choices[0].message.content
            if (resultText == null) {
                JsonNode choicesNode = apiResult.get("choices");
                if (choicesNode != null && choicesNode.isArray() && choicesNode.size() > 0) {
                    JsonNode firstChoice = choicesNode.get(0);
                    JsonNode messageNode = firstChoice.get("message");
                    if (messageNode != null) {
                        JsonNode contentNode = messageNode.get("content");
                        if (contentNode != null) {
                            resultText = contentNode.asText();
                        }
                    }
                }
            }
            
            if (resultText != null) {
                try {
                    JsonNode resultJson = objectMapper.readTree(resultText);
                    if (resultJson.has("negative_content")) {
                        return resultJson.get("negative_content").asText();
                    }
                } catch (Exception e) {
                    // 忽略JSON解析错误
                }
            }
        } catch (Exception e) {
            logger.debug("提取负面内容失败", e);
        }
        return null;
    }
    
    /**
     * 提取消息内容
     * 
     * @param message 消息对象
     * @return 消息内容文本
     */
    private String extractMessageContent(ConversationMessage message) {
        String msgType = message.getMsgtype();
        String content = message.getContent();
        
        // 记录原始消息信息（用于调试）
        logger.debug("提取消息内容: msgid={}, msgType={}, contentLength={}", 
            message.getMsgid(), msgType, content != null ? content.length() : 0);
        
        // 文本消息，提取text.content字段
        if ("text".equals(msgType) && content != null && !content.isEmpty()) {
            try {
                JsonNode contentNode = objectMapper.readTree(content);
                
                // 消息JSON结构：{ "text": { "content": "实际文字内容" } }
                // 需要提取 text.content 字段
                JsonNode textNode = contentNode.get("text");
                if (textNode != null && textNode.isObject()) {
                    JsonNode textContentNode = textNode.get("content");
                    if (textContentNode != null) {
                        String textContent = textContentNode.asText();
                        if (textContent != null && !textContent.isEmpty()) {
                            logger.debug("成功提取文本内容: msgid={}, textLength={}", 
                                message.getMsgid(), textContent.length());
                            return textContent;
                        }
                    }
                }
                
                logger.debug("text或text.content字段不存在: msgid={}", message.getMsgid());
            } catch (Exception e) {
                logger.debug("解析消息内容失败: msgid={}, content={}", message.getMsgid(), content, e);
            }
        }
        
        // 其他类型消息，显示类型
        String typeName = getMessageTypeName(msgType);
        logger.debug("非文本消息或内容为空: msgType={}, typeName={}", msgType, typeName);
        return typeName;
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
     * 检查消息文本是否为空（只包含时间戳和用户名，没有实际内容）
     * 
     * @param messagesText 消息文本
     * @return 是否为空
     */
    private boolean isMessagesTextEmpty(String messagesText) {
        if (messagesText == null || messagesText.trim().isEmpty()) {
            return true;
        }
        
        // 检查是否只包含时间戳、用户名和类型标签（如[图片]、[表情]等）
        // 移除所有时间戳格式 [HH:mm:ss]
        String withoutTime = messagesText.replaceAll("\\[\\d{2}:\\d{2}:\\d{2}\\]", "");
        // 移除所有用户名（假设用户名后跟冒号）
        String withoutNames = withoutTime.replaceAll("[^:\\n]+:", "");
        // 移除所有换行和空格
        String trimmed = withoutNames.replaceAll("[\\s\\n]+", "");
        
        // 如果剩下的都是类型标签（如[图片][语音]），则认为没有实际文本内容
        boolean isEmpty = trimmed.isEmpty() || trimmed.matches("^(\\[[^\\]]+\\])+$");
        
        if (isEmpty) {
            logger.debug("消息文本无实际内容: {}", messagesText.substring(0, Math.min(200, messagesText.length())));
        }
        
        return isEmpty;
    }
    
    /**
     * 格式化时间戳
     */
    private String formatTime(long timestamp) {
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");
        return sdf.format(new java.util.Date(timestamp));
    }
    
    /**
     * 格式化JSON字符串，使其更易读
     * 
     * @param jsonString 原始JSON字符串
     * @return 格式化后的JSON字符串
     */
    private String formatJson(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return "null";
        }
        
        try {
            Object json = objectMapper.readValue(jsonString, Object.class);
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(json);
        } catch (Exception e) {
            // 如果格式化失败，返回原始字符串
            return jsonString;
        }
    }
    
    /**
     * 分析结果类
     */
    public static class AnalysisResult {
        private String date;
        private boolean testMode;
        private boolean success;
        private String errorMessage;
        private int totalConversations;
        private int successCount;
        private int failureCount;
        private List<Map<String, Object>> testResults = new ArrayList<>(); // 测试模式下的结果
        
        // Getters and Setters
        public String getDate() {
            return date;
        }
        
        public void setDate(String date) {
            this.date = date;
        }
        
        public boolean isTestMode() {
            return testMode;
        }
        
        public void setTestMode(boolean testMode) {
            this.testMode = testMode;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public void setSuccess(boolean success) {
            this.success = success;
        }
        
        public String getErrorMessage() {
            return errorMessage;
        }
        
        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
        
        public int getTotalConversations() {
            return totalConversations;
        }
        
        public void setTotalConversations(int totalConversations) {
            this.totalConversations = totalConversations;
        }
        
        public int getSuccessCount() {
            return successCount;
        }
        
        public void setSuccessCount(int successCount) {
            this.successCount = successCount;
        }
        
        public int getFailureCount() {
            return failureCount;
        }
        
        public void setFailureCount(int failureCount) {
            this.failureCount = failureCount;
        }
        
        public List<Map<String, Object>> getTestResults() {
            return testResults;
        }
        
        public void setTestResults(List<Map<String, Object>> testResults) {
            this.testResults = testResults;
        }
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
        public String getMsgid() {
            return msgid;
        }
        
        public void setMsgid(String msgid) {
            this.msgid = msgid;
        }
        
        public Long getMsgtime() {
            return msgtime;
        }
        
        public void setMsgtime(Long msgtime) {
            this.msgtime = msgtime;
        }
        
        public String getFromUser() {
            return fromUser;
        }
        
        public void setFromUser(String fromUser) {
            this.fromUser = fromUser;
        }
        
        public String getMsgtype() {
            return msgtype;
        }
        
        public void setMsgtype(String msgtype) {
            this.msgtype = msgtype;
        }
        
        public String getContent() {
            return content;
        }
        
        public void setContent(String content) {
            this.content = content;
        }
        
        public String getNickName() {
            return nickName;
        }
        
        public void setNickName(String nickName) {
            this.nickName = nickName;
        }
        
        public Short getUserType() {
            return userType;
        }
        
        public void setUserType(Short userType) {
            this.userType = userType;
        }
    }
}

