package com.wechat.collector.service;

import com.wechat.collector.config.DatabaseManager;
import com.wechat.collector.model.Conversation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * 责任人统计服务
 * 负责计算和更新责任人的响应时间统计数据
 * 
 * 功能描述：
 * 1. 计算每个责任人负责的会话的响应时间统计数据（每个会话一条记录）
 * 2. 统计数据包括：首次响应时间、平均响应时间、超过10/20/30分钟未响应次数等
 * 3. 将统计数据保存到 responsible_user_statistics 表
 */
public class ResponsibleUserStatisticsService {
    private static final Logger logger = LoggerFactory.getLogger(ResponsibleUserStatisticsService.class);
    
    private final DatabaseManager databaseManager;
    
    /**
     * 构造函数
     * 
     * @param databaseManager 数据库管理器
     */
    public ResponsibleUserStatisticsService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }
    
    /**
     * 更新所有会话的统计数据（每个会话一条记录）
     * 
     * @return 更新的记录数
     */
    public int updateAllStatistics() {
        logger.info("开始更新责任人统计数据（所有会话）");
        
        // 获取所有有责任人的会话
        List<Conversation> conversations = getConversationsWithResponsibleUser();
        logger.info("找到 {} 个有责任人的会话", conversations.size());
        
        int updatedCount = 0;
        
        for (Conversation conversation : conversations) {
            try {
                updateStatisticsForConversation(conversation);
                updatedCount++;
            } catch (Exception e) {
                logger.error("更新责任人统计数据失败: conversation_id={}, responsible_user_id={}",
                        conversation.getConversationId(),
                        conversation.getResponsibleUserId(), e);
            }
        }
        
        logger.info("责任人统计数据更新完成: updated_count={}", updatedCount);
        return updatedCount;
    }
    
    /**
     * 获取所有有责任人的会话
     * 
     * @return 会话列表
     */
    private List<Conversation> getConversationsWithResponsibleUser() {
        List<Conversation> conversations = new ArrayList<>();
        
        String sql = "SELECT * FROM wechat_conversation " +
                "WHERE is_delete = 0 " +
                "AND responsible_user_id IS NOT NULL";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                conversations.add(mapRowToConversation(rs));
            }
            
        } catch (SQLException e) {
            logger.error("获取有责任人的会话失败", e);
            throw new RuntimeException("获取有责任人的会话失败", e);
        }
        
        return conversations;
    }
    
    /**
     * 更新指定会话的统计数据（统计所有消息）
     * 
     * @param conversation 会话对象
     */
    private void updateStatisticsForConversation(Conversation conversation) {
        Integer responsibleUserId = conversation.getResponsibleUserId();
        String conversationId = conversation.getConversationId();
        
        // 获取该会话的所有消息（按时间正序）
        List<MessageInfo> messages = getMessagesForConversation(conversationId);
        
        if (messages.isEmpty()) {
            // 如果没有消息，保存空统计数据
            saveStatistics(conversation, new StatisticsData());
            return;
        }
        
        // 获取责任人的 account_name（用于判断消息是否来自责任人）
        String responsibleAccountName = null;
        if (responsibleUserId != null) {
            responsibleAccountName = getAccountNameById(responsibleUserId);
        }
        
        // 获取消息发送者的类型（员工/用户）
        Map<String, Short> memberTypes = getMemberTypes(messages);
        
        // 计算统计数据
        StatisticsData statistics = calculateStatistics(messages, responsibleAccountName, memberTypes);
        
        // 保存统计数据
        saveStatistics(conversation, statistics);
    }
    
    /**
     * 获取会话的所有消息
     * 
     * @param conversationId 会话ID
     * @return 消息列表
     */
    private List<MessageInfo> getMessagesForConversation(String conversationId) {
        List<MessageInfo> messages = new ArrayList<>();
        
        String sql = "SELECT msgid, msgtime, from_user, msgtype " +
                "FROM wechat_message " +
                "WHERE conversation_id = ? " +
                "AND action = 'send' " +
                "ORDER BY msgtime ASC";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, conversationId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    MessageInfo message = new MessageInfo();
                    message.msgtime = rs.getLong("msgtime");
                    message.fromUser = rs.getString("from_user");
                    messages.add(message);
                }
            }
            
        } catch (SQLException e) {
            logger.error("获取消息列表失败: conversation_id={}", conversationId, e);
            throw new RuntimeException("获取消息列表失败", e);
        }
        
        return messages;
    }
    
    /**
     * 获取成员类型映射
     * 
     * @param messages 消息列表
     * @return 成员类型映射（account_name -> type）
     */
    private Map<String, Short> getMemberTypes(List<MessageInfo> messages) {
        Map<String, Short> memberTypes = new HashMap<>();
        
        if (messages.isEmpty()) {
            return memberTypes;
        }
        
        // 获取所有唯一的发送者
        Set<String> fromUsers = new HashSet<>();
        for (MessageInfo message : messages) {
            if (message.fromUser != null && !message.fromUser.isEmpty()) {
                fromUsers.add(message.fromUser);
            }
        }
        
        if (fromUsers.isEmpty()) {
            return memberTypes;
        }
        
        // 批量查询成员类型
        String placeholders = String.join(",", Collections.nCopies(fromUsers.size(), "?"));
        String sql = "SELECT account_name, type FROM wechat_member WHERE account_name IN (" + placeholders + ")";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            int index = 1;
            for (String fromUser : fromUsers) {
                stmt.setString(index++, fromUser);
            }
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String accountName = rs.getString("account_name");
                    Short type = rs.getShort("type");
                    memberTypes.put(accountName, type);
                }
            }
            
        } catch (SQLException e) {
            logger.error("获取成员类型失败", e);
            // 不抛出异常，继续处理，未找到的类型默认为用户（type != 1）
        }
        
        return memberTypes;
    }
    
    /**
     * 根据 member_id 获取 account_name
     * 
     * @param memberId 成员ID
     * @return account_name，如果不存在返回null
     */
    private String getAccountNameById(Integer memberId) {
        if (memberId == null) {
            return null;
        }
        
        String sql = "SELECT account_name FROM wechat_member WHERE id = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, memberId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("account_name");
                }
            }
            
        } catch (SQLException e) {
            logger.error("根据ID获取account_name失败: memberId={}", memberId, e);
        }
        
        return null;
    }
    
    /**
     * 计算统计数据
     * 
     * @param messages 消息列表
     * @param responsibleAccountName 责任人账号名称（account_name）
     * @param memberTypes 成员类型映射
     * @return 统计数据
     */
    private StatisticsData calculateStatistics(
            List<MessageInfo> messages,
            String responsibleAccountName,
            Map<String, Short> memberTypes) {
        
        StatisticsData statistics = new StatisticsData();
        
        Long pendingUserMessageTime = null; // 待响应的用户消息时间
        List<Double> responseTimes = new ArrayList<>();
        
        for (MessageInfo message : messages) {
            String fromUser = message.fromUser;
            long msgTime = message.msgtime;
            // 判断是否来自责任人：比较 account_name
            boolean isResponsible = (responsibleAccountName != null && responsibleAccountName.equals(fromUser));
            Short memberType = memberTypes.get(fromUser);
            boolean isEmployee = (memberType != null && memberType == 1);
            boolean isUserMessage = !isEmployee; // 用户消息：不是员工发送的消息
            
            if (isUserMessage) {
                // 用户消息
                statistics.totalUserMessages++;
                // 如果用户连续发送消息（补充说明），重置待响应时间
                pendingUserMessageTime = msgTime;
            } else if (isResponsible && pendingUserMessageTime != null) {
                // 这是责任人的响应消息，且前面有待响应的用户消息
                double responseTime = (msgTime - pendingUserMessageTime) / 1000.0; // 转换为秒
                
                // 记录首次响应时间
                if (statistics.firstResponseTime == null) {
                    statistics.firstResponseTime = (long) Math.round(responseTime);
                }
                
                // 记录响应时间（用于计算平均值）
                responseTimes.add(responseTime);
                
                // 统计超时次数
                if (responseTime > 30 * 60) { // 30分钟 = 1800秒
                    statistics.over30minCount++;
                }
                if (responseTime > 20 * 60) { // 20分钟 = 1200秒
                    statistics.over20minCount++;
                }
                if (responseTime > 10 * 60) { // 10分钟 = 600秒
                    statistics.over10minCount++;
                }
                
                pendingUserMessageTime = null; // 重置，响应完成
            }
        }
        
        // 计算平均响应时间
        if (!responseTimes.isEmpty()) {
            double sum = 0;
            for (Double time : responseTimes) {
                sum += time;
            }
            statistics.avgResponseTime = Math.round(sum / responseTimes.size() * 100.0) / 100.0; // 保留2位小数
            statistics.responseCount = responseTimes.size();
        }
        
        // 获取最后一条消息时间
        if (!messages.isEmpty()) {
            statistics.lastMessageTime = messages.get(messages.size() - 1).msgtime;
        }
        
        return statistics;
    }
    
    /**
     * 保存统计数据
     * 
     * @param conversation 会话对象
     * @param statistics 统计数据
     */
    private void saveStatistics(Conversation conversation, StatisticsData statistics) {
        String sql = "INSERT INTO responsible_user_statistics " +
                "(responsible_user_id, conversation_id, conversation_name, conversation_remark_name, " +
                " conversation_type, first_response_time, avg_response_time, response_count, " +
                " over_10min_count, over_20min_count, over_30min_count, total_user_messages, " +
                " last_message_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "  conversation_name = VALUES(conversation_name), " +
                "  conversation_remark_name = VALUES(conversation_remark_name), " +
                "  conversation_type = VALUES(conversation_type), " +
                "  first_response_time = VALUES(first_response_time), " +
                "  avg_response_time = VALUES(avg_response_time), " +
                "  response_count = VALUES(response_count), " +
                "  over_10min_count = VALUES(over_10min_count), " +
                "  over_20min_count = VALUES(over_20min_count), " +
                "  over_30min_count = VALUES(over_30min_count), " +
                "  total_user_messages = VALUES(total_user_messages), " +
                "  last_message_time = VALUES(last_message_time), " +
                "  update_time = CURRENT_TIMESTAMP";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            if (conversation.getResponsibleUserId() != null) {
                stmt.setInt(1, conversation.getResponsibleUserId());
            } else {
                stmt.setNull(1, Types.INTEGER);
            }
            stmt.setString(2, conversation.getConversationId());
            stmt.setString(3, conversation.getName());
            stmt.setString(4, conversation.getRemarkName());
            stmt.setString(5, conversation.getConversationType());
            setLongOrNull(stmt, 6, statistics.firstResponseTime);
            setDoubleOrNull(stmt, 7, statistics.avgResponseTime);
            stmt.setInt(8, statistics.responseCount);
            stmt.setInt(9, statistics.over10minCount);
            stmt.setInt(10, statistics.over20minCount);
            stmt.setInt(11, statistics.over30minCount);
            stmt.setInt(12, statistics.totalUserMessages);
            setLongOrNull(stmt, 13, statistics.lastMessageTime);
            
            int affected = stmt.executeUpdate();
            logger.debug("保存统计数据: conversation_id={}, affected={}",
                    conversation.getConversationId(), affected);
            
        } catch (SQLException e) {
            logger.error("保存统计数据失败: conversation_id={}", conversation.getConversationId(), e);
            throw new RuntimeException("保存统计数据失败", e);
        }
    }
    
    /**
     * 设置 Long 值或 NULL
     */
    private void setLongOrNull(PreparedStatement stmt, int index, Long value) throws SQLException {
        if (value != null) {
            stmt.setLong(index, value);
        } else {
            stmt.setNull(index, Types.BIGINT);
        }
    }
    
    /**
     * 设置 Double 值或 NULL
     */
    private void setDoubleOrNull(PreparedStatement stmt, int index, Double value) throws SQLException {
        if (value != null) {
            stmt.setDouble(index, value);
        } else {
            stmt.setNull(index, Types.DECIMAL);
        }
    }
    
    /**
     * 将 ResultSet 行映射为 Conversation 对象
     */
    private Conversation mapRowToConversation(ResultSet rs) throws SQLException {
        Conversation conversation = new Conversation();
        conversation.setId(rs.getLong("id"));
        conversation.setConversationId(rs.getString("conversation_id"));
        conversation.setConversationType(rs.getString("conversation_type"));
        conversation.setName(rs.getString("name"));
        conversation.setRemarkName(rs.getString("remark_name"));
        int responsibleUserId = rs.getInt("responsible_user_id");
        conversation.setResponsibleUserId(rs.wasNull() ? null : responsibleUserId);
        
        return conversation;
    }
    
    /**
     * 消息信息内部类
     */
    private static class MessageInfo {
        long msgtime;
        String fromUser;
    }
    
    /**
     * 统计数据内部类
     */
    private static class StatisticsData {
        Long firstResponseTime = null;
        Double avgResponseTime = null;
        int responseCount = 0;
        int over10minCount = 0;
        int over20minCount = 0;
        int over30minCount = 0;
        int totalUserMessages = 0;
        Long lastMessageTime = null;
    }
}

