package com.wechat.collector.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.collector.config.DatabaseManager;
import com.wechat.collector.model.WeChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 消息数据访问层
 * 负责与数据库交互
 */
public class MessageRepository {
    private static final Logger logger = LoggerFactory.getLogger(MessageRepository.class);
    
    private final DatabaseManager databaseManager;
    private final ConversationRepository conversationRepository;
    private final ObjectMapper objectMapper;
    
    public MessageRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
        this.conversationRepository = new ConversationRepository(databaseManager);
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 获取最大的 seq 值
     * 用于下次拉取消息
     */
    public long getMaxSeq() {
        String sql = "SELECT COALESCE(MAX(seq), 0) FROM wechat_message";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            if (rs.next()) {
                long maxSeq = rs.getLong(1);
                logger.debug("Current max seq: {}", maxSeq);
                return maxSeq;
            }
            return 0;
            
        } catch (SQLException e) {
            logger.error("Failed to get max seq", e);
            throw new RuntimeException("Failed to get max seq", e);
        }
    }
    
    /**
     * 插入消息记录
     * 如果 msgid 已存在则跳过
     * 同时更新会话汇总表
     * 
     * @param message 消息对象
     * @return 是否插入成功
     */
    public boolean insert(WeChatMessage message) {
        // 在保存前生成会话ID和会话类型
        message.generateConversationInfo();
        
        // media_url 字段不再写入，通过 resource_id 关联 wechat_resource 表获取
        String sql = "INSERT INTO wechat_message " +
                "(msgid, seq, msgtime, action, roomid, conversation_id, conversation_type, from_user, to_list, msgtype, content, resource_id, raw_json) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE msgid = msgid"; // 忽略重复记录
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, message.getMsgid());
            stmt.setLong(2, message.getSeq());
            stmt.setLong(3, message.getMsgtime());
            stmt.setString(4, message.getAction());
            stmt.setString(5, message.getRoomid());
            stmt.setString(6, message.getConversationId());
            stmt.setString(7, message.getConversationType());
            stmt.setString(8, message.getFromUser());
            stmt.setString(9, message.getToList());
            stmt.setString(10, message.getMsgtype());
            stmt.setString(11, message.getContent());
            if (message.getResourceId() != null) {
                stmt.setInt(12, message.getResourceId());
            } else {
                stmt.setNull(12, java.sql.Types.INTEGER);
            }
            stmt.setString(13, message.getRawJson());
            
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.debug("Saved message: msgid={}, conversation_id={}, conversation_type={}", 
                    message.getMsgid(), message.getConversationId(), message.getConversationType());
                
                // 同步更新会话汇总表
                updateConversationSummary(message);
            }
            
            return affected > 0;
            
        } catch (SQLException e) {
            // 如果是重复键错误，记录debug日志即可
            if (e.getErrorCode() == 1062) {
                logger.debug("Message already exists: {}", message.getMsgid());
                return false;
            }
            logger.error("Failed to insert message: {}", message.getMsgid(), e);
            throw new RuntimeException("Failed to insert message", e);
        }
    }
    
    /**
     * 批量插入消息
     */
    public int batchInsert(List<WeChatMessage> messages) {
        int successCount = 0;
        
        for (WeChatMessage message : messages) {
            try {
                if (insert(message)) {
                    successCount++;
                }
            } catch (Exception e) {
                logger.error("Failed to insert message in batch: {}", message.getMsgid(), e);
            }
        }
        
        return successCount;
    }
    
    /**
     * 检查消息是否已存在
     */
    public boolean exists(String msgid) {
        String sql = "SELECT COUNT(1) FROM wechat_message WHERE msgid = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, msgid);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
            
        } catch (SQLException e) {
            logger.error("Failed to check message existence: {}", msgid, e);
        }
        
        return false;
    }
    
    /**
     * 获取消息总数
     */
    public long count() {
        String sql = "SELECT COUNT(*) FROM wechat_message";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            if (rs.next()) {
                return rs.getLong(1);
            }
            
        } catch (SQLException e) {
            logger.error("Failed to count messages", e);
        }
        
        return 0;
    }
    
    /**
     * 更新会话汇总表
     * 当新消息插入时调用
     * 
     * @param message 新插入的消息
     */
    private void updateConversationSummary(WeChatMessage message) {
        try {
            // 提取参与人员
            Set<String> participants = extractParticipants(message);
            
            // 更新会话汇总表
            conversationRepository.upsertConversation(
                message.getConversationId(),
                message.getConversationType(),
                message.getRoomid(),
                message.getMsgid(),
                message.getMsgtime(),
                message.getMsgtype(),
                message.getContent(),
                message.getFromUser(),
                participants
            );
        } catch (Exception e) {
            // 更新会话汇总表失败不影响消息插入，只记录错误日志
            logger.error("Failed to update conversation summary for message: {}", 
                message.getMsgid(), e);
        }
    }
    
    /**
     * 提取消息的参与人员
     * 包括发送者和接收者
     * 
     * @param message 消息对象
     * @return 参与人员集合
     */
    private Set<String> extractParticipants(WeChatMessage message) {
        Set<String> participants = new HashSet<>();
        
        // 添加发送者
        if (message.getFromUser() != null && !message.getFromUser().isEmpty()) {
            participants.add(message.getFromUser());
        }
        
        // 添加接收者
        if (message.getToList() != null && !message.getToList().isEmpty()) {
            try {
                JsonNode toListNode = objectMapper.readTree(message.getToList());
                if (toListNode.isArray()) {
                    for (JsonNode node : toListNode) {
                        String userId = node.asText();
                        if (userId != null && !userId.isEmpty()) {
                            participants.add(userId);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to parse toList JSON: {}", message.getToList(), e);
            }
        }
        
        return participants;
    }
    
    /**
     * 根据 msgid 查找消息
     * 
     * @param msgid 消息ID
     * @return 消息对象，不存在返回null
     */
    public WeChatMessage findByMsgid(String msgid) {
        if (msgid == null || msgid.isEmpty()) {
            return null;
        }
        
        String sql = "SELECT msgid, seq, msgtime, action, roomid, conversation_id, conversation_type, " +
                "from_user, to_list, msgtype, content, resource_id, raw_json " +
                "FROM wechat_message WHERE msgid = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, msgid);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    WeChatMessage message = new WeChatMessage();
                    message.setMsgid(rs.getString("msgid"));
                    message.setSeq(rs.getLong("seq"));
                    message.setMsgtime(rs.getLong("msgtime"));
                    message.setAction(rs.getString("action"));
                    message.setRoomid(rs.getString("roomid"));
                    message.setConversationId(rs.getString("conversation_id"));
                    message.setConversationType(rs.getString("conversation_type"));
                    message.setFromUser(rs.getString("from_user"));
                    message.setToList(rs.getString("to_list"));
                    message.setMsgtype(rs.getString("msgtype"));
                    message.setContent(rs.getString("content"));
                    Integer resourceId = rs.getObject("resource_id") != null ? rs.getInt("resource_id") : null;
                    message.setResourceId(resourceId);
                    message.setRawJson(rs.getString("raw_json"));
                    return message;
                }
            }
            
        } catch (SQLException e) {
            logger.error("Failed to find message by msgid: {}", msgid, e);
        }
        
        return null;
    }
    
    /**
     * 更新消息的 resource_id
     * 用于异步处理媒体文件后更新数据库中的资源ID
     * 支持重试机制，因为异步处理可能在消息保存之前完成
     * 
     * @param msgid 消息ID
     * @param resourceId 资源ID
     * @return 是否更新成功
     */
    public boolean updateResourceId(String msgid, Integer resourceId) {
        if (msgid == null || msgid.isEmpty()) {
            logger.warn("msgid is null or empty, skip update resource_id");
            return false;
        }
        
        // 最多重试3次，每次间隔1秒（因为消息可能还没保存到数据库）
        int maxRetries = 3;
        int retryDelayMs = 1000;
        
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            boolean success = tryUpdateResourceId(msgid, resourceId);
            
            if (success) {
                if (attempt > 1) {
                    logger.info("Updated resource_id after {} attempts: msgid={}, resource_id={}", 
                        attempt, msgid, resourceId);
                }
                return true;
            }
            
            // 如果不是最后一次尝试，等待后重试
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("Interrupted while waiting to retry update resource_id: msgid={}", msgid);
                    return false;
                }
            }
        }
        
        logger.warn("Failed to update resource_id after {} attempts: msgid={}, resource_id={}", 
            maxRetries, msgid, resourceId);
        return false;
    }
    
    /**
     * 尝试更新 resource_id（单次尝试）
     * 
     * @param msgid 消息ID
     * @param resourceId 资源ID
     * @return 是否更新成功
     */
    private boolean tryUpdateResourceId(String msgid, Integer resourceId) {
        String sql = "UPDATE wechat_message SET resource_id = ? WHERE msgid = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            if (resourceId != null) {
                stmt.setInt(1, resourceId);
            } else {
                stmt.setNull(1, java.sql.Types.INTEGER);
            }
            stmt.setString(2, msgid);
            
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.info("Updated resource_id for message: msgid={}, resource_id={}", msgid, resourceId);
                return true;
            } else {
                // 检查消息是否存在（用于调试）
                boolean exists = exists(msgid);
                if (exists) {
                    // 消息存在但 resource_id 可能已经是目标值，检查一下
                    String checkSql = "SELECT resource_id FROM wechat_message WHERE msgid = ?";
                    try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                        checkStmt.setString(1, msgid);
                        try (ResultSet rs = checkStmt.executeQuery()) {
                            if (rs.next()) {
                                Integer currentResourceId = rs.getObject("resource_id") != null 
                                    ? rs.getInt("resource_id") : null;
                                if (currentResourceId != null && currentResourceId.equals(resourceId)) {
                                    logger.debug("Resource_id already set to target value: msgid={}, resource_id={}", 
                                        msgid, resourceId);
                                    return true; // 已经是目标值，视为成功
                                }
                            }
                        }
                    }
                    logger.warn("Message exists but update failed (possibly already has different resource_id): msgid={}", msgid);
                } else {
                    logger.debug("Message not found in database yet: msgid={} (will retry)", msgid);
                }
                return false;
            }
            
        } catch (SQLException e) {
            logger.error("Failed to update resource_id for message: msgid={}", msgid, e);
            return false;
        }
    }
    
    /**
     * 获取 ConversationRepository 实例
     * 供外部使用（如 API 层）
     * 
     * @return ConversationRepository实例
     */
    public ConversationRepository getConversationRepository() {
        return conversationRepository;
    }
}

