package com.wechat.collector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.collector.config.DatabaseManager;
import com.wechat.collector.model.Conversation;
import com.wechat.collector.model.WechatMember;
import com.wechat.collector.repository.ConversationRepository;
import com.wechat.collector.repository.MessageRepository;
import com.wechat.collector.repository.WechatMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * 会话提醒服务
 * 
 * 功能描述：
 * 1. 检测超时未回复的会话：当客户（type=2）发送消息后，检查是否有员工（type=1）回复
 * 2. 多时间点提醒：在10分钟、20分钟、30分钟时自动发送飞书提醒
 * 3. 智能匹配管理员：基于 users 表的 managed_employees 字段，匹配负责该群的管理员
 * 4. 提醒内容：包含群名称、客户名称、等待时间、最后发言内容
 * 5. 避免重复提醒：每个时间点只提醒一次
 * 
 * 使用场景：
 * - 减少因未及时回复导致的客户流失
 * - 提升客服响应效率
 * - 建立SLA管理体系
 * 
 * 依赖：
 * - DatabaseManager：数据库连接管理
 * - ConversationRepository：会话数据访问
 * - MessageRepository：消息数据访问
 * - WechatMemberRepository：成员数据访问
 * - FeishuService：飞书通知服务
 * - users 表：管理员用户表
 * - wechat_conversation 表：会话表
 * - wechat_member 表：成员表（区分员工和客户）
 * 
 * 执行频率：
 * - 由 ConversationReminderScheduler 每分钟触发一次
 */
public class ConversationReminderService {
    private static final Logger logger = LoggerFactory.getLogger(ConversationReminderService.class);
    
    private final DatabaseManager databaseManager;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final WechatMemberRepository memberRepository;
    private final FeishuService feishuService;
    private final ObjectMapper objectMapper;
    
    // 提醒时间间隔（分钟）
    private static final int[] REMINDER_INTERVALS = {10, 20, 30};
    
    /**
     * 构造函数
     * 
     * @param databaseManager 数据库管理器
     * @param feishuService 飞书服务
     */
    public ConversationReminderService(DatabaseManager databaseManager, FeishuService feishuService) {
        this.databaseManager = databaseManager;
        this.conversationRepository = new ConversationRepository(databaseManager);
        this.messageRepository = new MessageRepository(databaseManager);
        this.memberRepository = new WechatMemberRepository(databaseManager);
        this.feishuService = feishuService;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 检查并发送提醒
     * 查找所有最后发言是type=2用户的会话，检查是否需要发送提醒
     */
    public void checkAndSendReminders() {
        logger.info("开始检查会话提醒...");
        
        try {
            // 查询所有最后发言是type=2用户的会话
            List<ReminderInfo> reminderList = findConversationsNeedingReminder();
            
            logger.info("找到 {} 个需要检查的会话", reminderList.size());
            
            for (ReminderInfo info : reminderList) {
                try {
                    processReminder(info);
                } catch (Exception e) {
                    logger.error("处理提醒失败: conversationId={}", info.getConversationId(), e);
                }
            }
            
            logger.info("会话提醒检查完成");
            
        } catch (Exception e) {
            logger.error("检查会话提醒失败", e);
        }
    }
    
    /**
     * 查找需要提醒的会话
     * 最后发言是type=2的用户，且没有type=1的员工回复
     * 
     * @return 提醒信息列表
     */
    private List<ReminderInfo> findConversationsNeedingReminder() {
        List<ReminderInfo> reminderList = new ArrayList<>();
        
        // SQL查询：查找最后发言是type=2用户的会话
        // 使用 COLLATE utf8mb4_unicode_ci 避免字符集排序规则冲突
        String sql = "SELECT " +
                "c.conversation_id, " +
                "c.name, " +
                "c.remark_name, " +
                "c.last_message_time, " +
                "c.last_message_sender, " +
                "c.last_message_type, " +
                "c.last_message_content, " +
                "wm.type as sender_type " +
                "FROM wechat_conversation c " +
                "INNER JOIN wechat_member wm ON c.last_message_sender COLLATE utf8mb4_unicode_ci = wm.account_name COLLATE utf8mb4_unicode_ci " +
                "WHERE wm.type = 2 " +  // 最后发言是type=2的用户
                "AND c.last_message_time IS NOT NULL " +
                "ORDER BY c.last_message_time DESC";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                String conversationId = rs.getString("conversation_id");
                String name = rs.getString("name");
                String remarkName = rs.getString("remark_name");
                long lastMessageTime = rs.getLong("last_message_time");
                String lastMessageSender = rs.getString("last_message_sender");
                String lastMessageType = rs.getString("last_message_type");
                String lastMessageContent = rs.getString("last_message_content");
                
                // 检查是否有type=1的员工回复
                boolean hasEmployeeReply = checkHasEmployeeReply(conversationId, lastMessageTime);
                
                if (!hasEmployeeReply) {
                    // 计算距离最后发言的时间（分钟）
                    long currentTime = System.currentTimeMillis();
                    long elapsedMinutes = (currentTime - lastMessageTime) / (1000 * 60);
                    
                    // 检查是否需要发送提醒（10分钟、20分钟、30分钟）
                    for (int interval : REMINDER_INTERVALS) {
                        // 在指定时间点前后1分钟内发送提醒（避免重复发送）
                        if (elapsedMinutes >= interval && elapsedMinutes < interval + 1) {
                            ReminderInfo info = new ReminderInfo();
                            info.setConversationId(conversationId);
                            // 群名称优先级：name -> remark_name -> conversation_id
                            info.setConversationName(getConversationDisplayName(name, remarkName, conversationId));
                            info.setLastMessageSender(lastMessageSender);
                            info.setLastMessageType(lastMessageType);
                            info.setLastMessageContent(lastMessageContent);
                            info.setLastMessageTime(lastMessageTime);
                            info.setElapsedMinutes(elapsedMinutes);
                            info.setReminderInterval(interval);
                            
                            reminderList.add(info);
                            break; // 只添加一次
                        }
                    }
                }
            }
            
        } catch (SQLException e) {
            logger.error("查询需要提醒的会话失败", e);
        }
        
        return reminderList;
    }
    
    /**
     * 检查是否有type=1的员工回复
     * 
     * @param conversationId 会话ID
     * @param lastMessageTime 最后消息时间
     * @return 是否有员工回复
     */
    private boolean checkHasEmployeeReply(String conversationId, long lastMessageTime) {
        // 使用 COLLATE utf8mb4_unicode_ci 避免字符集排序规则冲突
        String sql = "SELECT COUNT(1) " +
                "FROM wechat_message m " +
                "INNER JOIN wechat_member wm ON m.from_user COLLATE utf8mb4_unicode_ci = wm.account_name COLLATE utf8mb4_unicode_ci " +
                "WHERE m.conversation_id = ? " +
                "AND m.msgtime > ? " +
                "AND wm.type = 1";  // type=1的员工
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, conversationId);
            stmt.setLong(2, lastMessageTime);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
            
        } catch (SQLException e) {
            logger.error("检查员工回复失败: conversationId={}", conversationId, e);
        }
        
        return false;
    }
    
    /**
     * 处理提醒
     * 根据 users 表的 managed_employees 字段，给负责该群的管理员发送飞书提醒
     * 
     * @param info 提醒信息
     */
    private void processReminder(ReminderInfo info) {
        // 获取负责该群的管理员列表（从 users 表）
        List<AdminUser> admins = getManagedAdmins(info.getConversationId());
        
        if (admins.isEmpty()) {
            logger.warn("没有管理员负责该群，跳过提醒: conversationId={}", info.getConversationId());
            return;
        }
        
        // 获取发送者的显示名称
        // 优先级：nick_name -> remark_name -> account_name
        WechatMember sender = memberRepository.findByAccountName(info.getLastMessageSender());
        String senderName = getUserDisplayName(sender, info.getLastMessageSender());
        
        // 构建提醒消息
        String message = buildReminderMessage(info, senderName);
        
        // 发送给所有负责该群的管理员
        int successCount = 0;
        int totalAdmins = admins.size();
        
        for (AdminUser admin : admins) {
            String openId = admin.getOpenId();
            
            if (openId != null && !openId.isEmpty()) {
                if (feishuService.sendTextMessage(openId, message)) {
                    successCount++;
                    logger.debug("发送飞书提醒成功: userId={}, username={}, openId={}", 
                        admin.getUserId(), admin.getUsername(), openId);
                } else {
                    logger.warn("发送飞书消息失败: userId={}, username={}, openId={}", 
                        admin.getUserId(), admin.getUsername(), openId);
                }
            } else {
                logger.warn("管理员没有飞书open_id，跳过提醒: userId={}, username={}", 
                    admin.getUserId(), admin.getUsername());
            }
        }
        
        logger.info("提醒发送完成: conversationId={}, 群名称={}, 负责管理员数={}, 成功发送={}", 
            info.getConversationId(), info.getConversationName(), totalAdmins, successCount);
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
     * 获取用户显示名称
     * 优先级：nick_name -> remark_name -> account_name
     * 
     * @param member 微信成员对象
     * @param defaultName 默认名称（account_name）
     * @return 显示名称
     */
    private String getUserDisplayName(WechatMember member, String defaultName) {
        if (member == null) {
            return defaultName != null ? defaultName : "未知用户";
        }
        
        // 优先显示 nick_name
        if (member.getNickName() != null && !member.getNickName().trim().isEmpty()) {
            return member.getNickName();
        }
        
        // 其次显示 remark_name
        if (member.getRemarkName() != null && !member.getRemarkName().trim().isEmpty()) {
            return member.getRemarkName();
        }
        
        // 最后显示 account_name
        return member.getAccountName() != null && !member.getAccountName().trim().isEmpty()
            ? member.getAccountName()
            : defaultName != null ? defaultName : "未知用户";
    }
    
    /**
     * 获取会话显示名称
     * 优先级：name -> remark_name -> conversation_id
     * 
     * @param name 会话名称
     * @param remarkName 备注名称
     * @param conversationId 会话ID
     * @return 显示名称
     */
    private String getConversationDisplayName(String name, String remarkName, String conversationId) {
        // 优先显示 name
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }
        
        // 其次显示 remark_name
        if (remarkName != null && !remarkName.trim().isEmpty()) {
            return remarkName;
        }
        
        // 最后显示 conversation_id
        return conversationId != null ? conversationId : "未知会话";
    }
    
    /**
     * 构建提醒消息
     * 
     * @param info 提醒信息
     * @param senderName 发送者名称
     * @return 提醒消息内容
     */
    private String buildReminderMessage(ReminderInfo info, String senderName) {
        StringBuilder message = new StringBuilder();
        message.append("【\n");
        message.append("群名称：").append(info.getConversationName()).append("\n");
        message.append("用户：").append(senderName).append("\n");
        message.append("消息时间：").append(formatMessageTime(info.getLastMessageTime())).append("\n");
        message.append("已等待：").append(info.getReminderInterval()).append("分钟\n");
        
        // 根据消息类型显示内容
        String msgType = info.getLastMessageType();
        if ("text".equals(msgType)) {
            // 文本消息，显示前100个字符
            String content = extractTextContent(info.getLastMessageContent());
            if (content != null && !content.isEmpty()) {
                String preview = content.length() > 100 ? content.substring(0, 100) + "..." : content;
                message.append("最后发言：").append(preview);
            }
        } else {
            // 其他类型消息，只显示类型
            String typeName = getMessageTypeName(msgType);
            message.append("最后发言：").append(typeName);
        }
        
        return message.toString();
    }
    
    /**
     * 格式化消息时间
     * 将时间戳（毫秒）转换为可读的时间字符串
     * 
     * @param timestamp 时间戳（毫秒）
     * @return 格式化的时间字符串，格式：YYYY-MM-DD HH:mm:ss
     */
    private String formatMessageTime(long timestamp) {
        if (timestamp <= 0) {
            return "未知时间";
        }
        
        try {
            java.util.Date date = new java.util.Date(timestamp);
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return sdf.format(date);
        } catch (Exception e) {
            logger.debug("格式化消息时间失败: timestamp={}", timestamp, e);
            return "时间格式错误";
        }
    }
    
    /**
     * 从消息内容中提取文本
     * 
     * @param contentJson 消息内容JSON字符串
     * @return 文本内容
     */
    private String extractTextContent(String contentJson) {
        if (contentJson == null || contentJson.isEmpty()) {
            return null;
        }
        
        try {
            JsonNode contentNode = objectMapper.readTree(contentJson);
            if (contentNode.has("text")) {
                return contentNode.get("text").asText();
            }
        } catch (Exception e) {
            logger.debug("解析消息内容失败: {}", contentJson, e);
        }
        
        return null;
    }
    
    /**
     * 获取消息类型的中文名称
     * 
     * @param msgType 消息类型
     * @return 中文名称
     */
    private String getMessageTypeName(String msgType) {
        if (msgType == null) {
            return "未知类型";
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
     * 提醒信息内部类
     */
    private static class ReminderInfo {
        private String conversationId;
        private String conversationName;
        private String lastMessageSender;
        private String lastMessageType;
        private String lastMessageContent;
        private long lastMessageTime;
        private long elapsedMinutes;
        private int reminderInterval;
        
        // Getters and Setters
        public String getConversationId() {
            return conversationId;
        }
        
        public void setConversationId(String conversationId) {
            this.conversationId = conversationId;
        }
        
        public String getConversationName() {
            return conversationName;
        }
        
        public void setConversationName(String conversationName) {
            this.conversationName = conversationName;
        }
        
        public String getLastMessageSender() {
            return lastMessageSender;
        }
        
        public void setLastMessageSender(String lastMessageSender) {
            this.lastMessageSender = lastMessageSender;
        }
        
        public String getLastMessageType() {
            return lastMessageType;
        }
        
        public void setLastMessageType(String lastMessageType) {
            this.lastMessageType = lastMessageType;
        }
        
        public String getLastMessageContent() {
            return lastMessageContent;
        }
        
        public void setLastMessageContent(String lastMessageContent) {
            this.lastMessageContent = lastMessageContent;
        }
        
        public long getLastMessageTime() {
            return lastMessageTime;
        }
        
        public void setLastMessageTime(long lastMessageTime) {
            this.lastMessageTime = lastMessageTime;
        }
        
        public long getElapsedMinutes() {
            return elapsedMinutes;
        }
        
        public void setElapsedMinutes(long elapsedMinutes) {
            this.elapsedMinutes = elapsedMinutes;
        }
        
        public int getReminderInterval() {
            return reminderInterval;
        }
        
        public void setReminderInterval(int reminderInterval) {
            this.reminderInterval = reminderInterval;
        }
    }
}

