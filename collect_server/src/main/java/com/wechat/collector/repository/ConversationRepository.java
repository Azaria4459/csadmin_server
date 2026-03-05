package com.wechat.collector.repository;

import com.wechat.collector.config.DatabaseManager;
import com.wechat.collector.model.Conversation;
import com.wechat.collector.model.WechatMember;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 会话汇总数据访问层
 * 负责维护 wechat_conversation 表，提供高性能的会话查询功能
 */
public class ConversationRepository {
    private static final Logger logger = LoggerFactory.getLogger(ConversationRepository.class);
    
    private final DatabaseManager databaseManager;
    private final WechatMemberRepository memberRepository;
    
    /**
     * 构造函数
     * 
     * @param databaseManager 数据库管理器
     */
    public ConversationRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.memberRepository = new WechatMemberRepository(databaseManager);
    }
    
    /**
     * 插入或更新会话汇总信息
     * 当新消息到来时调用此方法更新会话统计
     * 同时检查并插入参与人员到 wechat_member 表
     * 
     * @param conversationId 会话ID
     * @param conversationType 会话类型：single/group
     * @param roomid 群聊ID（单聊为null）
     * @param msgid 消息ID
     * @param msgtime 消息时间戳
     * @param msgtype 消息类型
     * @param content 消息内容
     * @param sender 发送者
     * @param participants 参与人员集合
     * @return 是否操作成功
     */
    public boolean upsertConversation(
            String conversationId,
            String conversationType,
            String roomid,
            String msgid,
            Long msgtime,
            String msgtype,
            String content,
            String sender,
            Set<String> participants) {
        
        if (conversationId == null || conversationId.isEmpty()) {
            logger.warn("conversationId is null or empty, skip upsert");
            return false;
        }
        
        // 检查并插入参与人员到 wechat_member 表
        try {
            if (participants != null && !participants.isEmpty()) {
                int newMemberCount = memberRepository.ensureParticipantsExist(participants);
                if (newMemberCount > 0) {
                    logger.info("新会话自动插入了 {} 个新成员到 wechat_member 表", newMemberCount);
                }
            }
        } catch (Exception e) {
            // 插入成员失败不影响会话的更新，只记录错误日志
            logger.error("检查并插入参与人员到 wechat_member 表失败: conversationId={}", 
                    conversationId, e);
        }
        
        // 将参与人员集合转换为 JSON 数组格式
        String participantsJson = convertParticipantsToJson(participants);
        
        // 截取内容摘要（最多10000字符，足够存储完整的消息JSON）
        String contentSummary = truncateContent(content, 10000);
        
        // 如果是单聊，查找参与成员中的员工ID，设置为responsible_user_id
        Integer responsibleUserId = null;
        if ("single".equals(conversationType) && participants != null && !participants.isEmpty()) {
            responsibleUserId = findEmployeeIdFromParticipants(participants);
        }
        
        String sql = "INSERT INTO wechat_conversation " +
                "(conversation_id, conversation_type, roomid, message_count, first_message_time, last_message_time, " +
                " last_message_id, last_message_type, last_message_content, last_message_sender, " +
                " participants, participant_count, responsible_user_id) " +
                "VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "  message_count = message_count + 1, " +
                "  last_message_time = VALUES(last_message_time), " +
                "  last_message_id = VALUES(last_message_id), " +
                "  last_message_type = VALUES(last_message_type), " +
                "  last_message_content = VALUES(last_message_content), " +
                "  last_message_sender = VALUES(last_message_sender), " +
                "  participants = VALUES(participants), " +
                "  participant_count = VALUES(participant_count), " +
                "  responsible_user_id = COALESCE(responsible_user_id, VALUES(responsible_user_id))";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, conversationId);
            stmt.setString(2, conversationType);
            stmt.setString(3, roomid);
            stmt.setLong(4, msgtime);
            stmt.setLong(5, msgtime);
            stmt.setString(6, msgid);
            stmt.setString(7, msgtype);
            stmt.setString(8, contentSummary);
            stmt.setString(9, sender);
            stmt.setString(10, participantsJson);
            stmt.setInt(11, participants != null ? participants.size() : 0);
            if (responsibleUserId != null) {
                stmt.setInt(12, responsibleUserId);
            } else {
                stmt.setNull(12, Types.INTEGER);
            }
            
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.debug("Upserted conversation: conversationId={}, type={}", 
                    conversationId, conversationType);
            }
            
            return affected > 0;
            
        } catch (SQLException e) {
            logger.error("Failed to upsert conversation: {}", conversationId, e);
            return false;
        }
    }
    
    /**
     * 查询会话列表（分页）
     * 按最后消息时间倒序排列
     * 
     * @param page 页码，从1开始
     * @param pageSize 每页数量
     * @return 会话列表
     */
    public List<Conversation> getConversationList(int page, int pageSize) {
        List<Conversation> conversations = new ArrayList<>();
        
        int offset = (page - 1) * pageSize;
        
        String sql = "SELECT * FROM wechat_conversation " +
                "ORDER BY last_message_time DESC " +
                "LIMIT ? OFFSET ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, pageSize);
            stmt.setInt(2, offset);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    conversations.add(mapRowToConversation(rs));
                }
            }
            
            logger.debug("Found {} conversations (page={}, pageSize={})", 
                conversations.size(), page, pageSize);
            
        } catch (SQLException e) {
            logger.error("Failed to get conversation list", e);
            throw new RuntimeException("Failed to get conversation list", e);
        }
        
        return conversations;
    }
    
    /**
     * 搜索会话
     * 支持按参与人员、会话类型搜索
     * 
     * @param keyword 搜索关键词（匹配参与人员userid）
     * @param conversationType 会话类型过滤（null表示不过滤）
     * @param page 页码
     * @param pageSize 每页数量
     * @return 会话列表
     */
    public List<Conversation> searchConversations(
            String keyword, 
            String conversationType,
            int page, 
            int pageSize) {
        
        List<Conversation> conversations = new ArrayList<>();
        int offset = (page - 1) * pageSize;
        
        StringBuilder sqlBuilder = new StringBuilder("SELECT * FROM wechat_conversation WHERE 1=1 ");
        
        // 如果有关键词，搜索参与人员
        if (keyword != null && !keyword.trim().isEmpty()) {
            sqlBuilder.append("AND (JSON_CONTAINS(participants, ?) OR last_message_sender LIKE ?) ");
        }
        
        // 如果有会话类型过滤
        if (conversationType != null && !conversationType.trim().isEmpty()) {
            sqlBuilder.append("AND conversation_type = ? ");
        }
        
        sqlBuilder.append("ORDER BY last_message_time DESC LIMIT ? OFFSET ?");
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sqlBuilder.toString())) {
            
            int paramIndex = 1;
            
            if (keyword != null && !keyword.trim().isEmpty()) {
                // JSON_CONTAINS 需要完整的 JSON 值
                stmt.setString(paramIndex++, "\"" + keyword.trim() + "\"");
                stmt.setString(paramIndex++, "%" + keyword.trim() + "%");
            }
            
            if (conversationType != null && !conversationType.trim().isEmpty()) {
                stmt.setString(paramIndex++, conversationType);
            }
            
            stmt.setInt(paramIndex++, pageSize);
            stmt.setInt(paramIndex, offset);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    conversations.add(mapRowToConversation(rs));
                }
            }
            
            logger.debug("Found {} conversations by search (keyword={}, type={}, page={}, pageSize={})", 
                conversations.size(), keyword, conversationType, page, pageSize);
            
        } catch (SQLException e) {
            logger.error("Failed to search conversations", e);
            throw new RuntimeException("Failed to search conversations", e);
        }
        
        return conversations;
    }
    
    /**
     * 获取会话总数
     * 
     * @return 会话总数
     */
    public long getConversationCount() {
        String sql = "SELECT COUNT(*) FROM wechat_conversation";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            if (rs.next()) {
                return rs.getLong(1);
            }
            
        } catch (SQLException e) {
            logger.error("Failed to count conversations", e);
        }
        
        return 0;
    }
    
    /**
     * 根据会话ID获取会话详情
     * 
     * @param conversationId 会话ID
     * @return 会话对象，不存在返回null
     */
    public Conversation getConversationById(String conversationId) {
        String sql = "SELECT * FROM wechat_conversation WHERE conversation_id = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, conversationId);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapRowToConversation(rs);
                }
            }
            
        } catch (SQLException e) {
            logger.error("Failed to get conversation by id: {}", conversationId, e);
        }
        
        return null;
    }
    
    /**
     * 将 ResultSet 行映射为 Conversation 对象
     * 
     * @param rs ResultSet
     * @return Conversation对象
     * @throws SQLException SQL异常
     */
    private Conversation mapRowToConversation(ResultSet rs) throws SQLException {
        Conversation conversation = new Conversation();
        conversation.setId(rs.getLong("id"));
        conversation.setConversationId(rs.getString("conversation_id"));
        conversation.setConversationType(rs.getString("conversation_type"));
        conversation.setName(rs.getString("name"));
        conversation.setRemarkName(rs.getString("remark_name"));
        conversation.setRoomid(rs.getString("roomid"));
        conversation.setMessageCount(rs.getInt("message_count"));
        conversation.setFirstMessageTime(rs.getLong("first_message_time"));
        conversation.setLastMessageTime(rs.getLong("last_message_time"));
        conversation.setLastMessageId(rs.getString("last_message_id"));
        conversation.setLastMessageType(rs.getString("last_message_type"));
        conversation.setLastMessageContent(rs.getString("last_message_content"));
        conversation.setLastMessageSender(rs.getString("last_message_sender"));
        conversation.setParticipants(rs.getString("participants"));
        conversation.setParticipantCount(rs.getInt("participant_count"));
        conversation.setCreateTime(rs.getTimestamp("create_time"));
        conversation.setUpdateTime(rs.getTimestamp("update_time"));
        conversation.setIsDelete(rs.getShort("is_delete"));
        int responsibleUserId = rs.getInt("responsible_user_id");
        conversation.setResponsibleUserId(rs.wasNull() ? null : responsibleUserId);
        return conversation;
    }
    
    /**
     * 从参与人员集合中查找员工的ID
     * 用于单聊会话自动设置responsible_user_id
     * 
     * @param participants 参与人员集合（account_name列表）
     * @return 员工ID，如果未找到员工则返回null
     */
    private Integer findEmployeeIdFromParticipants(Set<String> participants) {
        if (participants == null || participants.isEmpty()) {
            return null;
        }
        
        // 遍历参与人员，查找类型为员工（type=1）的成员
        for (String accountName : participants) {
            try {
                WechatMember member = memberRepository.findByAccountName(accountName);
                if (member != null && member.getType() != null && member.getType() == 1) {
                    // 找到员工，返回其ID
                    return member.getId();
                }
            } catch (Exception e) {
                // 查询失败，继续查找下一个
                logger.debug("查询成员失败，跳过: accountName={}", accountName, e);
            }
        }
        
        // 未找到员工
        return null;
    }
    
    /**
     * 将参与人员集合转换为 JSON 数组格式字符串
     * 
     * @param participants 参与人员集合
     * @return JSON数组字符串，如 ["userid1", "userid2"]
     */
    private String convertParticipantsToJson(Set<String> participants) {
        if (participants == null || participants.isEmpty()) {
            return "[]";
        }
        
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (String participant : participants) {
            if (!first) {
                json.append(",");
            }
            json.append("\"").append(participant).append("\"");
            first = false;
        }
        json.append("]");
        
        return json.toString();
    }
    
    /**
     * 获取所有群聊会话（roomid不为空的会话）
     * 用于群聊名称同步任务
     * 
     * @return 群聊会话列表
     */
    public List<Conversation> getGroupConversations() {
        List<Conversation> conversations = new ArrayList<>();
        
        String sql = "SELECT * FROM wechat_conversation " +
                "WHERE roomid IS NOT NULL AND roomid != '' " +
                "ORDER BY last_message_time DESC";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                conversations.add(mapRowToConversation(rs));
            }
            
            logger.debug("Found {} group conversations", conversations.size());
            
        } catch (SQLException e) {
            logger.error("Failed to get group conversations", e);
            throw new RuntimeException("Failed to get group conversations", e);
        }
        
        return conversations;
    }
    
    /**
     * 更新会话名称
     * 
     * @param conversationId 会话ID
     * @param name 群聊名称
     * @return 是否更新成功
     */
    public boolean updateConversationName(String conversationId, String name) {
        if (conversationId == null || conversationId.isEmpty()) {
            logger.warn("conversationId is null or empty");
            return false;
        }
        
        if (name == null || name.isEmpty()) {
            logger.warn("name is null or empty");
            return false;
        }
        
        String sql = "UPDATE wechat_conversation SET name = ? WHERE conversation_id = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, name);
            stmt.setString(2, conversationId);
            
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.debug("Updated conversation name: conversationId={}, name={}", 
                    conversationId, name);
                return true;
            } else {
                logger.warn("No conversation found to update: conversationId={}", conversationId);
                return false;
            }
            
        } catch (SQLException e) {
            logger.error("Failed to update conversation name: conversationId={}", 
                conversationId, e);
            return false;
        }
    }
    
    /**
     * 更新会话备注名称
     * 
     * @param conversationId 会话ID
     * @param remarkName 备注名称
     * @return 是否更新成功
     */
    public boolean updateConversationRemarkName(String conversationId, String remarkName) {
        if (conversationId == null || conversationId.isEmpty()) {
            logger.warn("conversationId is null or empty");
            return false;
        }
        
        String sql = "UPDATE wechat_conversation SET remark_name = ? WHERE conversation_id = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, remarkName);
            stmt.setString(2, conversationId);
            
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.debug("Updated conversation remark name: conversationId={}, remarkName={}", 
                    conversationId, remarkName);
                return true;
            } else {
                logger.warn("No conversation found to update: conversationId={}", conversationId);
                return false;
            }
            
        } catch (SQLException e) {
            logger.error("Failed to update conversation remark name: conversationId={}", 
                conversationId, e);
            return false;
        }
    }
    
    /**
     * 截取内容摘要
     * 
     * @param content 完整内容
     * @param maxLength 最大长度
     * @return 截取后的内容
     */
    private String truncateContent(String content, int maxLength) {
        if (content == null) {
            return null;
        }
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }
}

