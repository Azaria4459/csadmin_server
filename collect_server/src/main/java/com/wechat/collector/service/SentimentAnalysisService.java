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
import java.util.ArrayList;
import java.util.List;

/**
 * 情绪分析服务
 * 
 * 功能描述：
 * 1. 定期检查活跃群聊（10分钟内有新消息），分析客户情绪
 * 2. 敏感词检测：在AI分析前先进行敏感词匹配，快速识别高风险对话
 * 3. AI情绪分析：使用 Gemini AI 详细分析消息情绪，返回：
 *    - 情绪标签：positive/negative/neutral
 *    - 情绪分数：0-100（越高越负面）
 *    - 置信度：0-100
 * 4. 结果存储：
 *    - 更新 wechat_message 表的情绪分析字段
 *    - 更新 wechat_conversation 表的情绪统计字段
 *    - 创建 emotion_alert 预警记录
 * 5. 自动预警：检测到负面情绪或敏感词时，自动发送飞书提醒给负责的管理员
 * 6. 管理员匹配：基于 users 表的 managed_employees 字段匹配负责的管理员
 * 
 * 使用场景：
 * - 实时监控客户情绪，提前预警客户流失风险
 * - 识别需要紧急处理的投诉和不满
 * - 为客服质量分析提供数据支撑
 * 
 * 依赖：
 * - DatabaseManager：数据库连接管理
 * - ConversationRepository：会话数据访问
 * - GeminiService：Gemini AI服务
 * - FeishuService：飞书通知服务
 * - SensitiveKeywordService：敏感词检测服务
 * - SentimentRepository：情绪分析数据访问
 * 
 * 执行频率：
 * - 由 SentimentAnalysisScheduler 每10分钟触发一次
 */
public class SentimentAnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(SentimentAnalysisService.class);
    
    private final DatabaseManager databaseManager;
    private final ConversationRepository conversationRepository;
    private final GeminiService geminiService;
    private final FeishuService feishuService;
    private final SensitiveKeywordService sensitiveKeywordService;
    private final SentimentRepository sentimentRepository;
    private final ObjectMapper objectMapper;
    
    // 检测时间窗口：10分钟（毫秒）
    private static final long TIME_WINDOW_MS = 10 * 60 * 1000;
    
    // 每个群聊获取的最近消息数量
    private static final int RECENT_MESSAGE_COUNT = 10;
    
    /**
     * 构造函数
     * 
     * @param databaseManager 数据库管理器
     * @param geminiService Gemini AI服务
     * @param feishuService 飞书服务
     */
    public SentimentAnalysisService(
            DatabaseManager databaseManager,
            GeminiService geminiService,
            FeishuService feishuService) {
        this.databaseManager = databaseManager;
        this.conversationRepository = new ConversationRepository(databaseManager);
        this.geminiService = geminiService;
        this.feishuService = feishuService;
        this.sensitiveKeywordService = new SensitiveKeywordService(databaseManager);
        this.sentimentRepository = new SentimentRepository(databaseManager);
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 执行情绪分析检查
     * 查找10分钟内有新消息的群聊，分析情绪并发送提醒
     */
    public void checkSentiment() {
        logger.info("开始情绪分析检查...");
        
        try {
            // 查找10分钟内有新消息的群聊
            List<String> activeConversations = findActiveGroupConversations();
            
            logger.info("找到 {} 个活跃群聊（10分钟内有新消息）", activeConversations.size());
            
            for (String conversationId : activeConversations) {
                try {
                    processConversation(conversationId);
                } catch (Exception e) {
                    logger.error("处理群聊情绪分析失败: conversationId={}", conversationId, e);
                }
            }
            
            logger.info("情绪分析检查完成");
            
        } catch (Exception e) {
            logger.error("情绪分析检查失败", e);
        }
    }
    
    /**
     * 查找10分钟内有新消息的群聊
     * 
     * @return 群聊会话ID列表
     */
    private List<String> findActiveGroupConversations() {
        List<String> conversationIds = new ArrayList<>();
        
        long currentTime = System.currentTimeMillis();
        long timeThreshold = currentTime - TIME_WINDOW_MS;
        
        String sql = "SELECT conversation_id " +
                "FROM wechat_conversation " +
                "WHERE conversation_type = 'group' " +
                "AND last_message_time >= ? " +
                "ORDER BY last_message_time DESC";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setLong(1, timeThreshold);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    conversationIds.add(rs.getString("conversation_id"));
                }
            }
            
        } catch (SQLException e) {
            logger.error("查询活跃群聊失败", e);
        }
        
        return conversationIds;
    }
    
    /**
     * 处理单个会话的情绪分析
     * 
     * @param conversationId 会话ID
     */
    private void processConversation(String conversationId) {
        // 获取会话信息
        Conversation conversation = conversationRepository.getConversationById(conversationId);
        if (conversation == null) {
            logger.warn("会话不存在: conversationId={}", conversationId);
            return;
        }
        
        // 获取最近10条消息
        List<ConversationMessage> recentMessages = getRecentMessages(conversationId, RECENT_MESSAGE_COUNT);
        
        if (recentMessages.isEmpty()) {
            logger.debug("会话没有最近消息: conversationId={}", conversationId);
            return;
        }
        
        // 构建用于分析的文本
        String messagesText = buildMessagesText(recentMessages);
        
        // 先进行敏感词检测
        List<SensitiveKeywordService.MatchedKeyword> matchedKeywords = 
            sensitiveKeywordService.matchKeywords(messagesText);
        
        // 获取最后一条消息（用于存储分析结果）
        ConversationMessage lastMessage = recentMessages.isEmpty() ? null : 
            recentMessages.get(recentMessages.size() - 1);
        
        // 调用 Gemini 详细分析情绪
        SentimentResult sentimentResult = null;
        boolean hasNegativeSentiment = false;
        try {
            sentimentResult = geminiService.analyzeSentimentDetailed(messagesText);
            hasNegativeSentiment = "negative".equals(sentimentResult.getLabel());
        } catch (Exception e) {
            logger.error("Gemini 情绪分析失败: conversationId={}", conversationId, e);
            // 如果AI分析失败，回退到简单分析
            try {
                hasNegativeSentiment = geminiService.analyzeSentiment(messagesText);
                if (hasNegativeSentiment) {
                    sentimentResult = new SentimentResult();
                    sentimentResult.setLabel("negative");
                    sentimentResult.setScore(70.0);
                    sentimentResult.setConfidence(60.0);
                } else {
                    sentimentResult = new SentimentResult();
                    sentimentResult.setLabel("positive");
                    sentimentResult.setScore(30.0);
                    sentimentResult.setConfidence(60.0);
                }
            } catch (Exception e2) {
                logger.error("回退分析也失败: conversationId={}", conversationId, e2);
                return;
            }
        }
        
        // 存储分析结果
        if (lastMessage != null && sentimentResult != null) {
            // 获取消息ID（需要从数据库查询）
            Long messageId = getMessageIdByMsgid(lastMessage.getMsgid());
            if (messageId != null) {
                // 构建敏感词字符串
                StringBuilder keywordsStr = new StringBuilder();
                for (SensitiveKeywordService.MatchedKeyword kw : matchedKeywords) {
                    if (keywordsStr.length() > 0) {
                        keywordsStr.append(",");
                    }
                    keywordsStr.append(kw.getKeyword());
                }
                
                // 更新消息的情绪分析结果
                sentimentRepository.updateMessageSentiment(
                    messageId,
                    sentimentResult.getScore(),
                    sentimentResult.getLabel(),
                    sentimentResult.getConfidence(),
                    keywordsStr.toString()
                );
            }
        }
        
        // 更新会话的情绪分析统计
        if (sentimentResult != null) {
            sentimentRepository.updateConversationSentiment(
                conversationId,
                sentimentResult.getScore(),
                sentimentResult.getLabel(),
                hasNegativeSentiment
            );
        }
        
        // 如果检测到负面情绪或敏感词，创建预警并发送提醒
        boolean needAlert = hasNegativeSentiment || !matchedKeywords.isEmpty();
        
        if (needAlert) {
            logger.info("检测到负面情绪或敏感词: conversationId={}, name={}, hasNegative={}, keywords={}", 
                conversationId, conversation.getName(), hasNegativeSentiment, matchedKeywords.size());
            
            // 创建预警记录
            String alertType = !matchedKeywords.isEmpty() ? "sensitive_keyword" : "negative_sentiment";
            String keywordsStr = matchedKeywords.stream()
                .map(SensitiveKeywordService.MatchedKeyword::getKeyword)
                .reduce((a, b) -> a + "," + b)
                .orElse("");
            
            Long messageId = lastMessage != null ? getMessageIdByMsgid(lastMessage.getMsgid()) : null;
            String messageSummary = buildMessageSummary(recentMessages);
            
            Long alertId = sentimentRepository.createEmotionAlert(
                conversationId,
                messageId,
                alertType,
                sentimentResult != null ? sentimentResult.getScore() : null,
                sentimentResult != null ? sentimentResult.getLabel() : null,
                keywordsStr,
                messageSummary
            );
            
            // 发送提醒给员工
            sendNegativeSentimentAlert(conversation, recentMessages, matchedKeywords);
            
            // 标记预警已发送
            if (alertId != null) {
                sentimentRepository.markAlertSent(alertId);
            }
        } else {
            logger.debug("未检测到负面情绪或敏感词: conversationId={}", conversationId);
        }
    }
    
    /**
     * 获取会话的最近N条消息
     * 
     * @param conversationId 会话ID
     * @param limit 消息数量
     * @return 消息列表
     */
    private List<ConversationMessage> getRecentMessages(String conversationId, int limit) {
        List<ConversationMessage> messages = new ArrayList<>();
        
        // 使用 COLLATE utf8mb4_unicode_ci 避免字符集排序规则冲突
        String sql = "SELECT m.msgid, m.msgtime, m.from_user, m.msgtype, m.content, " +
                "wm.nick_name, wm.type " +
                "FROM wechat_message m " +
                "LEFT JOIN wechat_member wm ON m.from_user COLLATE utf8mb4_unicode_ci = wm.account_name COLLATE utf8mb4_unicode_ci " +
                "WHERE m.conversation_id = ? " +
                "AND m.action = 'send' " +
                "ORDER BY m.msgtime DESC " +
                "LIMIT ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, conversationId);
            stmt.setInt(2, limit);
            
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
            logger.error("获取最近消息失败: conversationId={}", conversationId, e);
        }
        
        // 按时间正序排列（最早的在前）
        messages.sort((a, b) -> Long.compare(a.getMsgtime(), b.getMsgtime()));
        
        return messages;
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
            // 发送者名称
            String senderName = message.getNickName() != null && !message.getNickName().isEmpty()
                ? message.getNickName()
                : message.getFromUser();
            
            // 消息内容
            String messageContent = extractMessageContent(message);
            
            // 格式：[时间] 发送者: 内容
            sb.append(String.format("[%s] %s: %s\n",
                formatTime(message.getMsgtime()),
                senderName,
                messageContent
            ));
        }
        
        return sb.toString();
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
        
        // 文本消息，提取text字段
        if ("text".equals(msgType) && content != null) {
            try {
                JsonNode contentNode = objectMapper.readTree(content);
                JsonNode textNode = contentNode.get("text");
                if (textNode != null) {
                    return textNode.asText();
                }
            } catch (Exception e) {
                logger.debug("解析消息内容失败: msgid={}", message.getMsgid(), e);
            }
        }
        
        // 其他类型消息，显示类型
        return getMessageTypeName(msgType);
    }
    
    /**
     * 获取消息类型的中文名称
     * 
     * @param msgType 消息类型
     * @return 中文名称
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
     * 格式化时间戳
     * 
     * @param timestamp 时间戳（毫秒）
     * @return 格式化的时间字符串
     */
    private String formatTime(long timestamp) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss");
        return sdf.format(new java.util.Date(timestamp));
    }
    
    /**
     * 获取消息ID（通过msgid查询）
     */
    private Long getMessageIdByMsgid(String msgid) {
        String sql = "SELECT id FROM wechat_message WHERE msgid = ? LIMIT 1";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, msgid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("id");
                }
            }
        } catch (SQLException e) {
            logger.error("查询消息ID失败: msgid={}", msgid, e);
        }
        
        return null;
    }
    
    /**
     * 构建消息摘要
     */
    private String buildMessageSummary(List<ConversationMessage> messages) {
        StringBuilder summary = new StringBuilder();
        int count = Math.min(3, messages.size());
        for (int i = messages.size() - count; i < messages.size(); i++) {
            ConversationMessage msg = messages.get(i);
            String content = extractMessageContent(msg);
            if (summary.length() > 0) {
                summary.append(" | ");
            }
            summary.append(content.length() > 50 ? content.substring(0, 50) + "..." : content);
        }
        return summary.toString();
    }
    
    /**
     * 发送负面情绪提醒
     * 根据 users 表的 managed_employees 字段，给负责该群的管理员发送飞书提醒
     * 
     * @param conversation 会话信息
     * @param recentMessages 最近消息列表
     * @param matchedKeywords 匹配到的敏感词
     */
    private void sendNegativeSentimentAlert(Conversation conversation, List<ConversationMessage> recentMessages,
                                          List<SensitiveKeywordService.MatchedKeyword> matchedKeywords) {
        // 获取负责该群的管理员列表（从 users 表）
        List<AdminUser> admins = getManagedAdmins(conversation.getConversationId());
        
        if (admins.isEmpty()) {
            logger.warn("没有管理员负责该群，跳过提醒: conversationId={}", conversation.getConversationId());
            return;
        }
        
        // 构建提醒消息
        String message = buildNegativeSentimentMessage(conversation, recentMessages, matchedKeywords);
        
        // 发送给所有负责该群的管理员
        int successCount = 0;
        int totalAdmins = admins.size();
        
        for (AdminUser admin : admins) {
            String openId = admin.getOpenId();
            
            if (openId != null && !openId.isEmpty()) {
                if (feishuService.sendTextMessage(openId, message)) {
                    successCount++;
                    logger.debug("发送负面情绪提醒成功: userId={}, username={}, openId={}", 
                        admin.getUserId(), admin.getUsername(), openId);
                } else {
                    logger.warn("发送飞书消息失败: userId={}, username={}, openId={}", 
                        admin.getUserId(), admin.getUsername(), openId);
                }
            }
        }
        
        logger.info("负面情绪提醒发送完成: conversationId={}, 群名称={}, 负责管理员数={}, 成功={}", 
            conversation.getConversationId(), conversation.getName(), totalAdmins, successCount);
    }
    
    /**
     * 构建负面情绪提醒消息
     * 
     * @param conversation 会话信息
     * @param recentMessages 最近消息列表
     * @param matchedKeywords 匹配到的敏感词
     * @return 提醒消息内容
     */
    private String buildNegativeSentimentMessage(Conversation conversation, List<ConversationMessage> recentMessages,
                                                List<SensitiveKeywordService.MatchedKeyword> matchedKeywords) {
        StringBuilder message = new StringBuilder();
        message.append("【情绪预警提醒】\n");
        message.append("群名称：").append(conversation.getName() != null ? conversation.getName() : conversation.getConversationId()).append("\n");
        message.append("检测时间：").append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date())).append("\n");
        
        if (!matchedKeywords.isEmpty()) {
            message.append("敏感词：");
            for (SensitiveKeywordService.MatchedKeyword kw : matchedKeywords) {
                message.append(kw.getKeyword()).append(" ");
            }
            message.append("\n");
        }
        
        message.append("\n系统检测到该群聊可能存在负面情绪，请及时关注并处理。\n\n");
        message.append("最近消息摘要：\n");
        
        // 显示最近3条消息作为摘要
        int previewCount = Math.min(3, recentMessages.size());
        for (int i = recentMessages.size() - previewCount; i < recentMessages.size(); i++) {
            ConversationMessage msg = recentMessages.get(i);
            String senderName = msg.getNickName() != null && !msg.getNickName().isEmpty()
                ? msg.getNickName()
                : msg.getFromUser();
            String content = extractMessageContent(msg);
            
            message.append(String.format("[%s] %s: %s\n",
                formatTime(msg.getMsgtime()),
                senderName,
                content.length() > 50 ? content.substring(0, 50) + "..." : content
            ));
        }
        
        return message.toString();
    }
    
    /**
     * 获取需要接收提醒的管理员列表（从users表）
     * 根据managed_employees字段匹配群里的用户
     * 
     * @param conversationId 会话ID
     * @return 管理员信息列表（包含open_id和username）
     */
    private List<AdminUser> getManagedAdmins(String conversationId) {
        List<AdminUser> admins = new ArrayList<>();
        
        // 获取会话的参与人员
        Conversation conversation = conversationRepository.getConversationById(conversationId);
        if (conversation == null || conversation.getParticipants() == null) {
            return admins;
        }
        
        // 提取所有参与人员的账号名称
        List<String> participantAccounts = new ArrayList<>();
        try {
            JsonNode participantsNode = objectMapper.readTree(conversation.getParticipants());
            if (participantsNode.isArray()) {
                for (JsonNode node : participantsNode) {
                    String accountName = node.asText();
                    if (accountName != null && !accountName.isEmpty()) {
                        participantAccounts.add(accountName);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("解析会话参与人员失败: conversationId={}", conversationId, e);
            return admins;
        }
        
        if (participantAccounts.isEmpty()) {
            return admins;
        }
        
        // 查询 users 表，找出 managed_employees 包含这些参与人员的管理员
        // 同时要求管理员有 open_id
        String sql = "SELECT id, username, email, open_id, managed_employees " +
                "FROM users " +
                "WHERE open_id IS NOT NULL " +
                "AND open_id != '' " +
                "AND managed_employees IS NOT NULL";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                String managedEmployeesJson = rs.getString("managed_employees");
                
                if (managedEmployeesJson == null || managedEmployeesJson.isEmpty()) {
                    continue;
                }
                
                try {
                    // 解析 managed_employees JSON 数组
                    JsonNode managedNode = objectMapper.readTree(managedEmployeesJson);
                    
                    if (managedNode.isArray()) {
                        // 检查是否有管理的员工在群里
                        boolean hasMatch = false;
                        for (JsonNode employeeNode : managedNode) {
                            String employeeAccount = employeeNode.asText();
                            if (participantAccounts.contains(employeeAccount)) {
                                hasMatch = true;
                                break;
                            }
                        }
                        
                        if (hasMatch) {
                            AdminUser admin = new AdminUser();
                            admin.setUserId(rs.getInt("id"));
                            admin.setUsername(rs.getString("username"));
                            admin.setEmail(rs.getString("email"));
                            admin.setOpenId(rs.getString("open_id"));
                            admins.add(admin);
                            
                            logger.debug("找到负责该群的管理员: userId={}, username={}", 
                                admin.getUserId(), admin.getUsername());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("解析管理员的managed_employees失败: userId={}", rs.getInt("id"), e);
                }
            }
            
        } catch (SQLException e) {
            logger.error("查询管理员失败: conversationId={}", conversationId, e);
        }
        
        return admins;
    }
    
    /**
     * 管理员用户内部类
     */
    private static class AdminUser {
        private Integer userId;
        private String username;
        private String email;
        private String openId;
        
        public Integer getUserId() {
            return userId;
        }
        
        public void setUserId(Integer userId) {
            this.userId = userId;
        }
        
        public String getUsername() {
            return username;
        }
        
        public void setUsername(String username) {
            this.username = username;
        }
        
        public String getEmail() {
            return email;
        }
        
        public void setEmail(String email) {
            this.email = email;
        }
        
        public String getOpenId() {
            return openId;
        }
        
        public void setOpenId(String openId) {
            this.openId = openId;
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

