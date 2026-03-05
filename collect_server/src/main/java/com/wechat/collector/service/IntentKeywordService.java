package com.wechat.collector.service;

import com.wechat.collector.config.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 购买意向关键词服务
 * 
 * 功能描述：
 * 1. 从数据库加载购买意向关键词配置（支持缓存，5分钟TTL）
 * 2. 检测文本中的购买意向关键词，支持多类别：
 *    - price：价格相关（如"价格"、"多少钱"、"报价"）
 *    - specification：规格相关（如"规格"、"尺寸"、"配置"）
 *    - purchase：购买相关（如"购买"、"下单"、"买"）
 *    - discount：优惠相关（如"优惠"、"折扣"、"活动"）
 * 3. 计算关键词匹配分数（最高60分，用于购买意向评分）
 * 4. 按类别和权重管理关键词
 * 
 * 使用场景：
 * - 购买意向分析中的关键词匹配部分
 * - 识别客户咨询中的购买信号
 * - 为购买意向评分提供基础分数
 * 
 * 依赖：
 * - DatabaseManager：数据库连接管理
 * - intent_keywords 表：购买意向关键词配置表
 */
public class IntentKeywordService {
    private static final Logger logger = LoggerFactory.getLogger(IntentKeywordService.class);
    
    private final DatabaseManager databaseManager;
    private List<IntentKeyword> keywordCache;
    private long cacheUpdateTime = 0;
    private static final long CACHE_TTL = 5 * 60 * 1000; // 缓存5分钟
    
    /**
     * 构造函数
     * 
     * @param databaseManager 数据库管理器
     */
    public IntentKeywordService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }
    
    /**
     * 检测文本中的购买意向关键词
     * 
     * @param text 要检测的文本
     * @return 匹配到的关键词及其权重
     */
    public Map<String, Integer> matchKeywords(String text) {
        Map<String, Integer> matched = new HashMap<>();
        
        if (text == null || text.isEmpty()) {
            return matched;
        }
        
        List<IntentKeyword> keywords = getKeywords();
        String lowerText = text.toLowerCase();
        
        for (IntentKeyword keyword : keywords) {
            if (!keyword.isEnabled()) {
                continue;
            }
            
            if (lowerText.contains(keyword.getKeyword().toLowerCase())) {
                matched.put(keyword.getKeyword(), keyword.getWeight());
            }
        }
        
        return matched;
    }
    
    /**
     * 计算关键词匹配的分数（最高60分）
     * 
     * @param text 要检测的文本
     * @return 关键词匹配分数（0-60）
     */
    public int calculateKeywordScore(String text) {
        Map<String, Integer> matched = matchKeywords(text);
        int score = 0;
        
        // 按类别分组计算分数
        Map<String, Integer> categoryScores = new HashMap<>();
        List<IntentKeyword> keywords = getKeywords();
        
        for (IntentKeyword keyword : keywords) {
            if (matched.containsKey(keyword.getKeyword())) {
                String category = keyword.getCategory();
                int weight = keyword.getWeight();
                
                // 每个类别取最高权重
                categoryScores.put(category, 
                    Math.max(categoryScores.getOrDefault(category, 0), weight));
            }
        }
        
        // 累加各类别分数，但不超过60分
        for (int weight : categoryScores.values()) {
            score += weight;
        }
        
        return Math.min(score, 60);
    }
    
    /**
     * 获取所有启用的关键词
     * 
     * @return 关键词列表
     */
    private List<IntentKeyword> getKeywords() {
        long currentTime = System.currentTimeMillis();
        
        // 检查缓存是否过期
        if (keywordCache == null || (currentTime - cacheUpdateTime) > CACHE_TTL) {
            keywordCache = loadKeywordsFromDatabase();
            cacheUpdateTime = currentTime;
        }
        
        return keywordCache;
    }
    
    /**
     * 从数据库加载关键词
     * 
     * @return 关键词列表
     */
    private List<IntentKeyword> loadKeywordsFromDatabase() {
        List<IntentKeyword> keywords = new ArrayList<>();
        
        String sql = "SELECT id, keyword, category, weight, enabled " +
                "FROM intent_keywords " +
                "WHERE enabled = 1 " +
                "ORDER BY weight DESC, id ASC";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                IntentKeyword keyword = new IntentKeyword();
                keyword.setId(rs.getInt("id"));
                keyword.setKeyword(rs.getString("keyword"));
                keyword.setCategory(rs.getString("category"));
                keyword.setWeight(rs.getInt("weight"));
                keyword.setEnabled(rs.getInt("enabled") == 1);
                keywords.add(keyword);
            }
            
            logger.debug("加载了 {} 个购买意向关键词", keywords.size());
            
        } catch (SQLException e) {
            logger.error("加载购买意向关键词失败", e);
        }
        
        return keywords;
    }
    
    /**
     * 按类别获取关键词
     * 
     * @param category 类别
     * @return 关键词列表
     */
    public List<IntentKeyword> getKeywordsByCategory(String category) {
        List<IntentKeyword> allKeywords = getKeywords();
        List<IntentKeyword> filtered = new ArrayList<>();
        
        for (IntentKeyword keyword : allKeywords) {
            if (keyword.getCategory().equals(category)) {
                filtered.add(keyword);
            }
        }
        
        return filtered;
    }
    
    /**
     * 刷新缓存
     */
    public void refreshCache() {
        keywordCache = null;
        cacheUpdateTime = 0;
    }
    
    /**
     * 购买意向关键词内部类
     */
    public static class IntentKeyword {
        private int id;
        private String keyword;
        private String category;
        private int weight;
        private boolean enabled;
        
        // Getters and Setters
        public int getId() {
            return id;
        }
        
        public void setId(int id) {
            this.id = id;
        }
        
        public String getKeyword() {
            return keyword;
        }
        
        public void setKeyword(String keyword) {
            this.keyword = keyword;
        }
        
        public String getCategory() {
            return category;
        }
        
        public void setCategory(String category) {
            this.category = category;
        }
        
        public int getWeight() {
            return weight;
        }
        
        public void setWeight(int weight) {
            this.weight = weight;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}

