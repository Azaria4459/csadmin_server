package com.wechat.collector.repository;

import com.wechat.collector.config.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 情绪分析数据访问层
 * 负责存储和查询情绪分析结果
 */
public class SentimentRepository {
    private static final Logger logger = LoggerFactory.getLogger(SentimentRepository.class);
    
    private final DatabaseManager databaseManager;
    
    /**
     * 构造函数
     * 
     * @param databaseManager 数据库管理器
     */
    public SentimentRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }
    
    /**
     * 更新消息的情绪分析结果
     * 
     * @param messageId 消息ID
     * @param sentimentScore 情绪分数
     * @param sentimentLabel 情绪标签
     * @param sentimentConfidence 置信度
     * @param sensitiveKeywords 敏感词（逗号分隔）
     * @return 是否更新成功
     */
    public boolean updateMessageSentiment(Long messageId, Double sentimentScore, 
                                         String sentimentLabel, Double sentimentConfidence,
                                         String sensitiveKeywords) {
        String sql = "UPDATE wechat_message " +
                "SET sentiment_score = ?, sentiment_label = ?, sentiment_confidence = ?, " +
                "sentiment_analyzed_at = NOW(), sensitive_keywords = ? " +
                "WHERE id = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            int paramIndex = 1;
            if (sentimentScore != null) {
                stmt.setDouble(paramIndex++, sentimentScore);
            } else {
                stmt.setNull(paramIndex++, Types.DECIMAL);
            }
            
            stmt.setString(paramIndex++, sentimentLabel);
            
            if (sentimentConfidence != null) {
                stmt.setDouble(paramIndex++, sentimentConfidence);
            } else {
                stmt.setNull(paramIndex++, Types.DECIMAL);
            }
            
            stmt.setString(paramIndex++, sensitiveKeywords);
            stmt.setLong(paramIndex++, messageId);
            
            int affected = stmt.executeUpdate();
            return affected > 0;
            
        } catch (SQLException e) {
            logger.error("更新消息情绪分析结果失败: messageId={}", messageId, e);
            return false;
        }
    }
    
    /**
     * 更新会话的情绪分析统计
     * 
     * @param conversationId 会话ID
     * @param sentimentScore 情绪分数
     * @param sentimentLabel 情绪标签
     * @param isNegative 是否为负面情绪
     * @return 是否更新成功
     */
    public boolean updateConversationSentiment(String conversationId, Double sentimentScore, 
                                              String sentimentLabel, boolean isNegative) {
        String sql = "UPDATE wechat_conversation " +
                "SET last_sentiment_score = ?, last_sentiment_label = ?";
        
        if (isNegative) {
            sql += ", negative_sentiment_count = negative_sentiment_count + 1, " +
                   "last_negative_sentiment_time = ?";
        }
        
        sql += " WHERE conversation_id = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            int paramIndex = 1;
            if (sentimentScore != null) {
                stmt.setDouble(paramIndex++, sentimentScore);
            } else {
                stmt.setNull(paramIndex++, Types.DECIMAL);
            }
            
            stmt.setString(paramIndex++, sentimentLabel);
            
            if (isNegative) {
                stmt.setLong(paramIndex++, System.currentTimeMillis());
            }
            
            stmt.setString(paramIndex++, conversationId);
            
            int affected = stmt.executeUpdate();
            return affected > 0;
            
        } catch (SQLException e) {
            logger.error("更新会话情绪分析统计失败: conversationId={}", conversationId, e);
            return false;
        }
    }
    
    /**
     * 创建情绪预警记录
     * 
     * @param conversationId 会话ID
     * @param messageId 消息ID
     * @param alertType 预警类型
     * @param sentimentScore 情绪分数
     * @param sentimentLabel 情绪标签
     * @param sensitiveKeywords 敏感词
     * @param messageSummary 消息摘要
     * @return 预警记录ID
     */
    public Long createEmotionAlert(String conversationId, Long messageId, String alertType,
                                  Double sentimentScore, String sentimentLabel,
                                  String sensitiveKeywords, String messageSummary) {
        String sql = "INSERT INTO emotion_alert " +
                "(conversation_id, message_id, alert_type, sentiment_score, sentiment_label, " +
                "sensitive_keywords, message_summary, alert_sent, handled) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, 0, 0)";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            
            stmt.setString(1, conversationId);
            if (messageId != null) {
                stmt.setLong(2, messageId);
            } else {
                stmt.setNull(2, Types.BIGINT);
            }
            stmt.setString(3, alertType);
            if (sentimentScore != null) {
                stmt.setDouble(4, sentimentScore);
            } else {
                stmt.setNull(4, Types.DECIMAL);
            }
            stmt.setString(5, sentimentLabel);
            stmt.setString(6, sensitiveKeywords);
            stmt.setString(7, messageSummary);
            
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
            }
            
            return null;
            
        } catch (SQLException e) {
            logger.error("创建情绪预警记录失败: conversationId={}", conversationId, e);
            return null;
        }
    }
    
    /**
     * 标记预警已发送
     * 
     * @param alertId 预警ID
     * @return 是否更新成功
     */
    public boolean markAlertSent(Long alertId) {
        String sql = "UPDATE emotion_alert SET alert_sent = 1, alert_sent_time = NOW() WHERE id = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setLong(1, alertId);
            int affected = stmt.executeUpdate();
            return affected > 0;
            
        } catch (SQLException e) {
            logger.error("标记预警已发送失败: alertId={}", alertId, e);
            return false;
        }
    }
    
    /**
     * 获取会话的情绪分析历史
     * 
     * @param conversationId 会话ID
     * @param limit 限制数量
     * @return 情绪分析历史记录
     */
    public List<SentimentHistory> getSentimentHistory(String conversationId, int limit) {
        List<SentimentHistory> history = new ArrayList<>();
        
        String sql = "SELECT id, msgtime, sentiment_score, sentiment_label, sentiment_confidence, " +
                "sensitive_keywords, sentiment_analyzed_at " +
                "FROM wechat_message " +
                "WHERE conversation_id = ? AND sentiment_analyzed_at IS NOT NULL " +
                "ORDER BY sentiment_analyzed_at DESC " +
                "LIMIT ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, conversationId);
            stmt.setInt(2, limit);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    SentimentHistory item = new SentimentHistory();
                    item.setMessageId(rs.getLong("id"));
                    item.setMsgtime(rs.getLong("msgtime"));
                    item.setSentimentScore(rs.getDouble("sentiment_score"));
                    item.setSentimentLabel(rs.getString("sentiment_label"));
                    item.setSentimentConfidence(rs.getDouble("sentiment_confidence"));
                    item.setSensitiveKeywords(rs.getString("sensitive_keywords"));
                    item.setAnalyzedAt(rs.getTimestamp("sentiment_analyzed_at"));
                    history.add(item);
                }
            }
            
        } catch (SQLException e) {
            logger.error("获取情绪分析历史失败: conversationId={}", conversationId, e);
        }
        
        return history;
    }
    
    /**
     * 保存情绪分析请求记录
     * 
     * @param analysisDate 分析日期
     * @param conversationId 会话ID
     * @param aiService 使用的AI服务名称（gemini/deepseek）
     * @param requestContent 发送给AI的完整聊天内容
     * @param aiRequestJson 发送给AI的完整JSON请求
     * @param aiResponseJson AI返回的完整JSON响应
     * @param sentimentLabel 情绪标签
     * @param sentimentScore 情绪分数
     * @param sentimentConfidence 置信度
     * @param negativeContent 容易导致用户情绪降低的内容
     * @param messageCount 本次分析的消息条数
     * @param status 状态：success/failed
     * @param errorMessage 错误信息
     * @return 请求记录ID
     */
    public Long saveAnalysisRequest(String analysisDate, String conversationId, String aiService,
                                    String requestContent, String aiRequestJson, 
                                    String aiResponseJson, String sentimentLabel,
                                    Double sentimentScore, Double sentimentConfidence,
                                    String negativeContent, int messageCount,
                                    String status, String errorMessage) {
        String sql = "INSERT INTO sentiment_analysis_request " +
                "(analysis_date, conversation_id, ai_service, request_content, ai_request_json, ai_response_json, " +
                "sentiment_label, sentiment_score, sentiment_confidence, negative_content, message_count, " +
                "status, error_message) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            
            int paramIndex = 1;
            stmt.setDate(paramIndex++, java.sql.Date.valueOf(analysisDate));
            stmt.setString(paramIndex++, conversationId);
            stmt.setString(paramIndex++, aiService);
            stmt.setString(paramIndex++, requestContent);
            stmt.setString(paramIndex++, aiRequestJson);
            stmt.setString(paramIndex++, aiResponseJson);
            stmt.setString(paramIndex++, sentimentLabel);
            
            if (sentimentScore != null) {
                stmt.setDouble(paramIndex++, sentimentScore);
            } else {
                stmt.setNull(paramIndex++, Types.DECIMAL);
            }
            
            if (sentimentConfidence != null) {
                stmt.setDouble(paramIndex++, sentimentConfidence);
            } else {
                stmt.setNull(paramIndex++, Types.DECIMAL);
            }
            
            stmt.setString(paramIndex++, negativeContent);
            stmt.setInt(paramIndex++, messageCount);
            stmt.setString(paramIndex++, status);
            stmt.setString(paramIndex++, errorMessage);
            
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                try (ResultSet rs = stmt.getGeneratedKeys()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                }
            }
            
            return null;
            
        } catch (SQLException e) {
            logger.error("保存情绪分析请求记录失败: conversationId={}", conversationId, e);
            return null;
        }
    }
    
    /**
     * 情绪分析历史记录内部类
     */
    public static class SentimentHistory {
        private Long messageId;
        private Long msgtime;
        private Double sentimentScore;
        private String sentimentLabel;
        private Double sentimentConfidence;
        private String sensitiveKeywords;
        private Timestamp analyzedAt;
        
        // Getters and Setters
        public Long getMessageId() {
            return messageId;
        }
        
        public void setMessageId(Long messageId) {
            this.messageId = messageId;
        }
        
        public Long getMsgtime() {
            return msgtime;
        }
        
        public void setMsgtime(Long msgtime) {
            this.msgtime = msgtime;
        }
        
        public Double getSentimentScore() {
            return sentimentScore;
        }
        
        public void setSentimentScore(Double sentimentScore) {
            this.sentimentScore = sentimentScore;
        }
        
        public String getSentimentLabel() {
            return sentimentLabel;
        }
        
        public void setSentimentLabel(String sentimentLabel) {
            this.sentimentLabel = sentimentLabel;
        }
        
        public Double getSentimentConfidence() {
            return sentimentConfidence;
        }
        
        public void setSentimentConfidence(Double sentimentConfidence) {
            this.sentimentConfidence = sentimentConfidence;
        }
        
        public String getSensitiveKeywords() {
            return sensitiveKeywords;
        }
        
        public void setSensitiveKeywords(String sensitiveKeywords) {
            this.sensitiveKeywords = sensitiveKeywords;
        }
        
        public Timestamp getAnalyzedAt() {
            return analyzedAt;
        }
        
        public void setAnalyzedAt(Timestamp analyzedAt) {
            this.analyzedAt = analyzedAt;
        }
    }
}

