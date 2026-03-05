package com.wechat.collector.service;

import com.wechat.collector.config.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * 购买意向分析服务
 * 
 * 功能描述：
 * 1. 混合评分算法：结合关键词匹配（60分）和AI分析（40分）计算购买意向分数（0-100）
 * 2. 关键词匹配：使用 IntentKeywordService 检测购买意向关键词，计算基础分数
 * 3. AI分析：使用 Gemini AI 分析对话内容，判断购买意向强度
 * 4. 意向等级划分：
 *    - high：80-100分（高意向）
 *    - medium：50-79分（中意向）
 *    - low：0-49分（低意向）
 * 5. 自动创建销售机会：当意向分数>=80时，自动在 sales_opportunity 表中创建记录
 * 6. 更新会话表：将分析结果存储到 wechat_conversation 表
 * 
 * 使用场景：
 * - 自动识别高意向客户，减少漏单
 * - 为销售团队提供客户优先级排序
 * - 跟踪客户从咨询到成交的转化过程
 * 
 * 依赖：
 * - DatabaseManager：数据库连接管理
 * - IntentKeywordService：购买意向关键词服务
 * - GeminiService：Gemini AI服务
 * - wechat_conversation 表：会话表
 * - sales_opportunity 表：销售机会表
 */
public class IntentAnalysisService {
    private static final Logger logger = LoggerFactory.getLogger(IntentAnalysisService.class);
    
    private final DatabaseManager databaseManager;
    private final IntentKeywordService intentKeywordService;
    private final GeminiService geminiService;
    
    /**
     * 构造函数
     * 
     * @param databaseManager 数据库管理器
     * @param intentKeywordService 意向关键词服务
     * @param geminiService Gemini AI服务
     */
    public IntentAnalysisService(DatabaseManager databaseManager,
                                IntentKeywordService intentKeywordService,
                                GeminiService geminiService) {
        this.databaseManager = databaseManager;
        this.intentKeywordService = intentKeywordService;
        this.geminiService = geminiService;
    }
    
    /**
     * 分析会话的购买意向
     * 
     * @param conversationId 会话ID
     * @param messagesText 消息文本（用于分析）
     * @return 意向分析结果
     */
    public IntentAnalysisResult analyzeIntent(String conversationId, String messagesText) {
        IntentAnalysisResult result = new IntentAnalysisResult();
        
        // 1. 关键词匹配（60分）
        int keywordScore = intentKeywordService.calculateKeywordScore(messagesText);
        Map<String, Integer> matchedKeywords = intentKeywordService.matchKeywords(messagesText);
        
        // 2. AI分析（40分）
        int aiScore = 0;
        try {
            aiScore = analyzeIntentWithAI(messagesText);
        } catch (Exception e) {
            logger.error("AI购买意向分析失败: conversationId={}", conversationId, e);
            // AI分析失败时，仅使用关键词分数
        }
        
        // 3. 计算总分（0-100）
        int totalScore = Math.min(keywordScore + aiScore, 100);
        
        // 4. 确定意向等级
        String intentLevel = determineIntentLevel(totalScore);
        
        result.setIntentScore(totalScore);
        result.setIntentLevel(intentLevel);
        result.setKeywordScore(keywordScore);
        result.setAiScore(aiScore);
        result.setMatchedKeywords(new ArrayList<>(matchedKeywords.keySet()));
        
        return result;
    }
    
    /**
     * 使用AI分析购买意向（返回0-40分）
     */
    private int analyzeIntentWithAI(String text) throws Exception {
        String prompt = "请分析以下聊天消息的购买意向强度，返回0-100的分数（分数越高表示购买意向越强）。" +
                "只返回数字，不要其他文字。\n\n" +
                "聊天消息：\n" + text;
        
        String result = geminiService.request(prompt);
        
        if (result != null && !result.isEmpty()) {
            try {
                // 尝试提取数字
                String numberStr = result.replaceAll("[^0-9]", "");
                if (!numberStr.isEmpty()) {
                    int score = Integer.parseInt(numberStr);
                    // 将0-100映射到0-40
                    return (int) (score * 0.4);
                }
            } catch (NumberFormatException e) {
                logger.debug("AI返回结果无法解析为数字: {}", result);
            }
        }
        
        return 0;
    }
    
    /**
     * 确定意向等级
     */
    private String determineIntentLevel(int score) {
        if (score >= 80) {
            return "high";
        } else if (score >= 50) {
            return "medium";
        } else {
            return "low";
        }
    }
    
    /**
     * 保存意向分析结果到数据库
     */
    public boolean saveIntentAnalysis(String conversationId, String customerAccount, 
                                     String employeeAccount, IntentAnalysisResult result) {
        // 更新会话表
        String sql1 = "UPDATE wechat_conversation " +
                "SET intent_score = ?, intent_level = ?, intent_keywords = ?, " +
                "intent_analyzed_at = NOW(), last_intent_update_time = ? " +
                "WHERE conversation_id = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql1)) {
            
            stmt.setInt(1, result.getIntentScore());
            stmt.setString(2, result.getIntentLevel());
            
            String keywordsStr = String.join(",", result.getMatchedKeywords());
            stmt.setString(3, keywordsStr);
            stmt.setLong(4, System.currentTimeMillis());
            stmt.setString(5, conversationId);
            
            stmt.executeUpdate();
            
            // 如果分数>=80，创建销售机会
            if (result.getIntentScore() >= 80) {
                createSalesOpportunity(conversationId, customerAccount, employeeAccount, result);
            }
            
            return true;
            
        } catch (SQLException e) {
            logger.error("保存意向分析结果失败: conversationId={}", conversationId, e);
            return false;
        }
    }
    
    /**
     * 创建销售机会
     */
    private void createSalesOpportunity(String conversationId, String customerAccount,
                                       String employeeAccount, IntentAnalysisResult result) {
        String sql = "INSERT INTO sales_opportunity " +
                "(conversation_id, customer_account, employee_account, intent_score, intent_level, " +
                "intent_keywords, status, detected_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'potential', NOW()) " +
                "ON DUPLICATE KEY UPDATE " +
                "intent_score = VALUES(intent_score), " +
                "intent_level = VALUES(intent_level), " +
                "intent_keywords = VALUES(intent_keywords), " +
                "update_time = NOW()";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, conversationId);
            stmt.setString(2, customerAccount);
            stmt.setString(3, employeeAccount);
            stmt.setInt(4, result.getIntentScore());
            stmt.setString(5, result.getIntentLevel());
            stmt.setString(6, String.join(",", result.getMatchedKeywords()));
            
            stmt.executeUpdate();
            
            logger.info("创建销售机会: conversationId={}, score={}, level={}", 
                conversationId, result.getIntentScore(), result.getIntentLevel());
            
        } catch (SQLException e) {
            logger.error("创建销售机会失败: conversationId={}", conversationId, e);
        }
    }
    
    /**
     * 意向分析结果内部类
     */
    public static class IntentAnalysisResult {
        private int intentScore; // 0-100
        private String intentLevel; // high/medium/low
        private int keywordScore; // 关键词分数
        private int aiScore; // AI分数
        private List<String> matchedKeywords; // 匹配到的关键词
        
        // Getters and Setters
        public int getIntentScore() {
            return intentScore;
        }
        
        public void setIntentScore(int intentScore) {
            this.intentScore = intentScore;
        }
        
        public String getIntentLevel() {
            return intentLevel;
        }
        
        public void setIntentLevel(String intentLevel) {
            this.intentLevel = intentLevel;
        }
        
        public int getKeywordScore() {
            return keywordScore;
        }
        
        public void setKeywordScore(int keywordScore) {
            this.keywordScore = keywordScore;
        }
        
        public int getAiScore() {
            return aiScore;
        }
        
        public void setAiScore(int aiScore) {
            this.aiScore = aiScore;
        }
        
        public List<String> getMatchedKeywords() {
            return matchedKeywords;
        }
        
        public void setMatchedKeywords(List<String> matchedKeywords) {
            this.matchedKeywords = matchedKeywords;
        }
    }
}

