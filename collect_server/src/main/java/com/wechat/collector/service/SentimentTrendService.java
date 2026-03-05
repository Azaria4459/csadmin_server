package com.wechat.collector.service;

import com.wechat.collector.config.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 情绪波动数据服务
 * 
 * 功能描述：
 * 1. 获取会话的情绪波动数据（按时间范围）
 * 2. 返回格式化的数据供前端图表使用
 * 
 * 使用场景：
 * - 为情绪波动图提供数据
 */
public class SentimentTrendService {
    private static final Logger logger = LoggerFactory.getLogger(SentimentTrendService.class);
    
    private final DatabaseManager databaseManager;
    
    public SentimentTrendService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }
    
    /**
     * 获取会话的情绪波动数据
     * 
     * @param conversationId 会话ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return 情绪波动数据列表
     */
    public List<SentimentTrendData> getSentimentTrend(String conversationId, LocalDate startDate, LocalDate endDate) {
        List<SentimentTrendData> trendData = new ArrayList<>();
        
        String sql = "SELECT analysis_date, sentiment_label, sentiment_score " +
                "FROM sentiment_analysis_request " +
                "WHERE conversation_id = ? " +
                "AND analysis_date >= ? AND analysis_date <= ? " +
                "AND status = 'success' " +
                "AND sentiment_label IS NOT NULL " +
                "ORDER BY analysis_date ASC";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, conversationId);
            stmt.setDate(2, Date.valueOf(startDate));
            stmt.setDate(3, Date.valueOf(endDate));
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    SentimentTrendData data = new SentimentTrendData();
                    data.setDate(rs.getDate("analysis_date").toLocalDate());
                    data.setLabel(rs.getString("sentiment_label"));
                    
                    Double score = rs.getDouble("sentiment_score");
                    if (rs.wasNull()) {
                        score = null;
                    }
                    data.setScore(score);
                    
                    // 转换为y轴值：1=正面，0=中立，-1=负面
                    if (data.getLabel() != null) {
                        if ("positive".equals(data.getLabel())) {
                            data.setYValue(1);
                        } else if ("negative".equals(data.getLabel())) {
                            data.setYValue(-1);
                        } else {
                            data.setYValue(0); // neutral
                        }
                    } else {
                        // 如果没有标签，根据分数判断
                        if (score != null) {
                            if (score < 40) {
                                data.setYValue(1); // 正面
                            } else if (score > 60) {
                                data.setYValue(-1); // 负面
                            } else {
                                data.setYValue(0); // 中立
                            }
                        } else {
                            data.setYValue(0);
                        }
                    }
                    
                    trendData.add(data);
                }
            }
        } catch (SQLException e) {
            logger.error("获取情绪波动数据失败: conversationId={}, startDate={}, endDate={}", 
                conversationId, startDate, endDate, e);
        }
        
        return trendData;
    }
    
    /**
     * 获取会话的情绪波动数据（返回Map格式，便于前端使用）
     * 
     * @param conversationId 会话ID
     * @param startDate 开始日期
     * @param endDate 结束日期
     * @return Map格式的数据
     */
    public Map<String, Object> getSentimentTrendMap(String conversationId, LocalDate startDate, LocalDate endDate) {
        List<SentimentTrendData> trendData = getSentimentTrend(conversationId, startDate, endDate);
        
        Map<String, Object> result = new HashMap<>();
        result.put("conversationId", conversationId);
        result.put("startDate", startDate.toString());
        result.put("endDate", endDate.toString());
        result.put("data", trendData);
        result.put("count", trendData.size());
        
        return result;
    }
    
    /**
     * 情绪波动数据内部类
     */
    public static class SentimentTrendData {
        private LocalDate date;
        private String label;
        private Double score;
        private Integer yValue; // y轴值：1=正面，0=中立，-1=负面
        
        public LocalDate getDate() {
            return date;
        }
        
        public void setDate(LocalDate date) {
            this.date = date;
        }
        
        public String getLabel() {
            return label;
        }
        
        public void setLabel(String label) {
            this.label = label;
        }
        
        public Double getScore() {
            return score;
        }
        
        public void setScore(Double score) {
            this.score = score;
        }
        
        public Integer getYValue() {
            return yValue;
        }
        
        public void setYValue(Integer yValue) {
            this.yValue = yValue;
        }
    }
}

