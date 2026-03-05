package com.wechat.collector.service;

import com.wechat.collector.config.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 敏感词检测服务
 * 
 * 功能描述：
 * 1. 从数据库加载敏感词配置（支持缓存，5分钟TTL）
 * 2. 检测文本中的敏感关键词，支持三种匹配模式：
 *    - contains：包含匹配（默认）
 *    - exact：精确匹配
 *    - regex：正则表达式匹配
 * 3. 按类别和严重程度管理敏感词
 * 4. 返回匹配到的敏感词及其严重程度
 * 
 * 使用场景：
 * - 在情绪分析前进行敏感词快速检测
 * - 识别高风险对话（投诉、退款等）
 * - 触发预警机制
 * 
 * 依赖：
 * - DatabaseManager：数据库连接管理
 * - sensitive_keywords 表：敏感词配置表
 */
public class SensitiveKeywordService {
    private static final Logger logger = LoggerFactory.getLogger(SensitiveKeywordService.class);
    
    private final DatabaseManager databaseManager;
    private List<SensitiveKeyword> keywordCache;
    private long cacheUpdateTime = 0;
    private static final long CACHE_TTL = 5 * 60 * 1000; // 缓存5分钟
    
    /**
     * 构造函数
     * 
     * @param databaseManager 数据库管理器
     */
    public SensitiveKeywordService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }
    
    /**
     * 检测文本中的敏感词
     * 
     * @param text 要检测的文本
     * @return 匹配到的敏感词列表
     */
    public List<MatchedKeyword> matchKeywords(String text) {
        if (text == null || text.isEmpty()) {
            return new ArrayList<>();
        }
        
        List<MatchedKeyword> matched = new ArrayList<>();
        List<SensitiveKeyword> keywords = getKeywords();
        
        for (SensitiveKeyword keyword : keywords) {
            if (!keyword.isEnabled()) {
                continue;
            }
            
            boolean matchedFlag = false;
            switch (keyword.getMatchMode()) {
                case "contains":
                    matchedFlag = text.contains(keyword.getKeyword());
                    break;
                case "exact":
                    matchedFlag = text.equals(keyword.getKeyword());
                    break;
                case "regex":
                    try {
                        Pattern pattern = Pattern.compile(keyword.getKeyword());
                        matchedFlag = pattern.matcher(text).find();
                    } catch (Exception e) {
                        logger.warn("正则表达式匹配失败: keyword={}, error={}", keyword.getKeyword(), e.getMessage());
                    }
                    break;
            }
            
            if (matchedFlag) {
                MatchedKeyword matchedKeyword = new MatchedKeyword();
                matchedKeyword.setKeyword(keyword.getKeyword());
                matchedKeyword.setCategory(keyword.getCategory());
                matchedKeyword.setSeverity(keyword.getSeverity());
                matched.add(matchedKeyword);
            }
        }
        
        return matched;
    }
    
    /**
     * 获取所有启用的敏感词
     * 
     * @return 敏感词列表
     */
    private List<SensitiveKeyword> getKeywords() {
        long currentTime = System.currentTimeMillis();
        
        // 检查缓存是否过期
        if (keywordCache == null || (currentTime - cacheUpdateTime) > CACHE_TTL) {
            keywordCache = loadKeywordsFromDatabase();
            cacheUpdateTime = currentTime;
        }
        
        return keywordCache;
    }
    
    /**
     * 从数据库加载敏感词
     * 
     * @return 敏感词列表
     */
    private List<SensitiveKeyword> loadKeywordsFromDatabase() {
        List<SensitiveKeyword> keywords = new ArrayList<>();
        
        String sql = "SELECT id, keyword, category, severity, enabled, match_mode " +
                "FROM sensitive_keywords " +
                "WHERE enabled = 1 " +
                "ORDER BY severity DESC, id ASC";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                SensitiveKeyword keyword = new SensitiveKeyword();
                keyword.setId(rs.getInt("id"));
                keyword.setKeyword(rs.getString("keyword"));
                keyword.setCategory(rs.getString("category"));
                keyword.setSeverity(rs.getInt("severity"));
                keyword.setEnabled(rs.getInt("enabled") == 1);
                keyword.setMatchMode(rs.getString("match_mode"));
                keywords.add(keyword);
            }
            
            logger.debug("加载了 {} 个敏感词", keywords.size());
            
        } catch (SQLException e) {
            logger.error("加载敏感词失败", e);
        }
        
        return keywords;
    }
    
    /**
     * 按类别获取敏感词
     * 
     * @param category 类别
     * @return 敏感词列表
     */
    public List<SensitiveKeyword> getKeywordsByCategory(String category) {
        List<SensitiveKeyword> allKeywords = getKeywords();
        List<SensitiveKeyword> filtered = new ArrayList<>();
        
        for (SensitiveKeyword keyword : allKeywords) {
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
     * 敏感词内部类
     */
    public static class SensitiveKeyword {
        private int id;
        private String keyword;
        private String category;
        private int severity;
        private boolean enabled;
        private String matchMode;
        
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
        
        public int getSeverity() {
            return severity;
        }
        
        public void setSeverity(int severity) {
            this.severity = severity;
        }
        
        public boolean isEnabled() {
            return enabled;
        }
        
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
        
        public String getMatchMode() {
            return matchMode;
        }
        
        public void setMatchMode(String matchMode) {
            this.matchMode = matchMode;
        }
    }
    
    /**
     * 匹配到的敏感词内部类
     */
    public static class MatchedKeyword {
        private String keyword;
        private String category;
        private int severity;
        
        // Getters and Setters
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
        
        public int getSeverity() {
            return severity;
        }
        
        public void setSeverity(int severity) {
            this.severity = severity;
        }
    }
}

