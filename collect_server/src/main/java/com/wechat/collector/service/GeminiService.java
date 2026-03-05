package com.wechat.collector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Gemini AI 服务类
 * 
 * 功能描述：
 * 1. 封装 Google Gemini API 调用，提供统一的文本分析接口
 * 2. 基础文本分析：调用 Gemini API 进行通用文本分析，返回文本结果
 * 3. 情绪分析（简单版）：分析文本是否包含负面情绪，返回布尔值
 * 4. 情绪分析（详细版）：分析文本情绪，返回结构化结果：
 *    - 情绪标签：positive/negative/neutral
 *    - 情绪分数：0-100（越高越负面）
 *    - 置信度：0-100
 * 5. 错误处理：API调用失败时提供降级方案
 * 
 * 使用场景：
 * - 情绪分析：分析客户消息的情感倾向
 * - 购买意向分析：判断客户购买意向强度
 * - 其他文本分析任务
 * 
 * 依赖：
 * - Google Gemini API：需要配置 API Key
 * - 模型：gemini-2.5-flash
 * 
 * 配置：
 * - API Key：通过构造函数传入
 * - 超时设置：连接超时30秒，读取超时60秒
 */
public class GeminiService implements AiService {
    private static final Logger logger = LoggerFactory.getLogger(GeminiService.class);
    
    private final String apiKey;
    private final ObjectMapper objectMapper;
    
    // Gemini 2.5 Flash 的 token 限制（大约值，实际以API返回为准）
    // 输入token限制：约1,000,000 tokens
    private static final long MAX_INPUT_TOKENS = 1000000L;
    
    // 估算token：中文字符大约1个token，英文单词大约0.75个token
    // 这里简化处理：1个字符 = 1个token（保守估算）
    private static final double TOKEN_ESTIMATE_RATIO = 1.0;
    
    /**
     * 构造函数
     * 
     * @param apiKey Gemini API密钥
     */
    public GeminiService(String apiKey) {
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 估算文本的token数量
     * 简化估算：1个字符 ≈ 1个token（保守估算）
     * 
     * @param text 文本内容
     * @return 估算的token数量
     */
    @Override
    public long estimateTokenCount(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        // 保守估算：按字符数计算（中文字符和英文都按1个token计算）
        return (long) (text.length() * TOKEN_ESTIMATE_RATIO);
    }
    
    /**
     * 获取最大输入token限制
     * 
     * @return 最大token数量
     */
    @Override
    public long getMaxInputTokens() {
        return MAX_INPUT_TOKENS;
    }
    
    /**
     * 获取AI服务名称
     * 
     * @return 服务名称
     */
    @Override
    public String getServiceName() {
        return "Gemini";
    }
    
    /**
     * 发送请求到 Gemini API（返回原始JSON响应）
     * 包含重试机制：对于429错误（频率限制），会自动重试，最多重试3次，使用指数退避策略
     * 
     * @param content 请求内容（提示词）
     * @return Gemini 返回的原始JSON响应字符串
     * @throws Exception 请求失败时抛出异常
     */
    @Override
    public String requestRawJson(String content) throws Exception {
        return requestRawJsonWithRetry(content, 3);
    }
    
    /**
     * 带重试机制的请求方法
     * 对于429错误（频率限制），会自动重试，使用指数退避策略
     * 
     * @param content 请求内容（提示词）
     * @param maxRetries 最大重试次数
     * @return Gemini 返回的原始JSON响应字符串
     * @throws Exception 请求失败时抛出异常
     */
    private String requestRawJsonWithRetry(String content, int maxRetries) throws Exception {
        Exception lastException = null;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return doRequest(content);
            } catch (Exception e) {
                lastException = e;
                String errorMsg = e.getMessage();
                
                // 检查是否是429错误（频率限制）
                boolean isRateLimit = errorMsg != null && errorMsg.contains("429");
                
                if (isRateLimit && attempt < maxRetries) {
                    // 指数退避：第一次等待2秒，第二次等待4秒，第三次等待8秒
                    int waitSeconds = (int) Math.pow(2, attempt + 1);
                    logger.warn("Gemini API 请求频率限制（429），等待 {} 秒后重试（第 {}/{} 次）", 
                        waitSeconds, attempt + 1, maxRetries);
                    try {
                        Thread.sleep(waitSeconds * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new Exception("重试等待被中断", ie);
                    }
                    continue; // 重试
                } else {
                    // 非429错误，或已达到最大重试次数，直接抛出异常
                    throw e;
                }
            }
        }
        
        // 理论上不会到达这里，但为了编译通过
        if (lastException != null) {
            throw lastException;
        }
        throw new Exception("请求失败：未知错误");
    }
    
    /**
     * 执行实际的HTTP请求
     * 
     * @param content 请求内容（提示词）
     * @return Gemini 返回的原始JSON响应字符串
     * @throws Exception 请求失败时抛出异常
     */
    private String doRequest(String content) throws Exception {
        // 对 API key 进行 URL 编码（参照 PHP 版本的 urlencode）
        String encodedApiKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.toString());
        String apiUrl = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=" + encodedApiKey;
        
        // 构建请求数据
        Map<String, Object> data = new HashMap<>();
        Map<String, Object>[] contents = new Map[1];
        contents[0] = new HashMap<>();
        
        Map<String, Object>[] parts = new Map[1];
        parts[0] = new HashMap<>();
        parts[0].put("text", content);
        
        contents[0].put("parts", parts);
        data.put("contents", contents);
        
        // 添加 generationConfig 强制返回 JSON 格式
        Map<String, Object> generationConfig = new HashMap<>();
        generationConfig.put("response_mime_type", "application/json");
        data.put("generationConfig", generationConfig);
        
        String postData = objectMapper.writeValueAsString(data);
        
        HttpURLConnection connection = null;
        try {
            URL requestUrl = new URL(apiUrl);
            connection = (HttpURLConnection) requestUrl.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setDoInput(true);
            connection.setConnectTimeout(30000); // 30秒连接超时
            connection.setReadTimeout(60000); // 60秒读取超时
            
            // 发送POST数据
            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = postData.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }
            
            // 读取响应
            int responseCode = connection.getResponseCode();
            StringBuilder response = new StringBuilder();
            
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                        responseCode >= 200 && responseCode < 300 
                            ? connection.getInputStream() 
                            : connection.getErrorStream(),
                        StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
            
            String responseStr = response.toString();
            
            if (responseCode >= 200 && responseCode < 300) {
                return responseStr;
            } else {
                logger.error("Gemini API 请求失败: code={}, response={}", responseCode, responseStr);
                throw new Exception("Gemini API 请求失败: " + responseCode);
            }
            
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * 发送请求到 Gemini API
     * 
     * @param content 请求内容（提示词）
     * @return Gemini 返回的文本内容
     * @throws Exception 请求失败时抛出异常
     */
    public String request(String content) throws Exception {
        String responseStr = requestRawJson(content);
        
        // 解析响应
        JsonNode apiResult = objectMapper.readTree(responseStr);
        
        // 提取文本内容
        JsonNode candidatesNode = apiResult.get("candidates");
        if (candidatesNode != null && candidatesNode.isArray() && candidatesNode.size() > 0) {
            JsonNode firstCandidate = candidatesNode.get(0);
            JsonNode contentNode = firstCandidate.get("content");
            if (contentNode != null) {
                JsonNode partsNode = contentNode.get("parts");
                if (partsNode != null && partsNode.isArray() && partsNode.size() > 0) {
                    JsonNode firstPart = partsNode.get(0);
                    JsonNode textNode = firstPart.get("text");
                    if (textNode != null) {
                        return textNode.asText();
                    }
                }
            }
        }
        
        logger.warn("Gemini API 响应格式异常: {}", responseStr);
        return "";
    }
    
    /**
     * 分析文本情绪
     * 
     * @param text 要分析的文本
     * @return 是否有负面情绪（true=有负面情绪，false=无负面情绪）
     * @throws Exception 分析失败时抛出异常
     */
    public boolean analyzeSentiment(String text) throws Exception {
        String prompt = "请分析以下聊天消息是否包含负面情绪（如愤怒、不满、抱怨、失望等）。" +
                "如果有负面情绪，请回答：是\n" +
                "如果没有负面情绪，请回答：否\n\n" +
                "聊天消息：\n" + text;
        
        String result = request(prompt);
        
        // 判断结果
        if (result != null) {
            String resultLower = result.toLowerCase().trim();
            return resultLower.contains("是") || 
                   resultLower.contains("yes") || 
                   resultLower.contains("负面") ||
                   resultLower.contains("negative");
        }
        
        return false;
    }
    
    /**
     * 详细分析文本情绪，返回分数和标签
     * 
     * @param text 要分析的文本
     * @return 情绪分析结果（包含分数、标签、置信度）
     * @throws Exception 分析失败时抛出异常
     */
    public SentimentResult analyzeSentimentDetailed(String text) throws Exception {
        String prompt = "请分析以下聊天消息的情绪，并返回JSON格式结果：\n" +
                "{\n" +
                "  \"label\": \"positive/negative/neutral\"（情绪标签：正面/负面/中性）,\n" +
                "  \"score\": 0-100（情绪分数，0最正面，100最负面）,\n" +
                "  \"confidence\": 0-100（置信度）\n" +
                "}\n\n" +
                "聊天消息：\n" + text;
        
        String result = request(prompt);
        
        SentimentResult sentimentResult = new SentimentResult();
        
        if (result != null && !result.isEmpty()) {
            try {
                // 尝试解析JSON
                JsonNode jsonNode = objectMapper.readTree(result);
                if (jsonNode.has("label")) {
                    sentimentResult.setLabel(jsonNode.get("label").asText());
                }
                if (jsonNode.has("score")) {
                    sentimentResult.setScore(jsonNode.get("score").asDouble());
                }
                if (jsonNode.has("confidence")) {
                    sentimentResult.setConfidence(jsonNode.get("confidence").asDouble());
                }
            } catch (Exception e) {
                // JSON解析失败，尝试从文本中提取
                logger.debug("JSON解析失败，尝试文本解析: {}", result);
                parseSentimentFromText(result, sentimentResult);
            }
        }
        
        // 如果没有解析到结果，使用默认值
        if (sentimentResult.getLabel() == null) {
            // 回退到简单分析
            boolean hasNegative = analyzeSentiment(text);
            sentimentResult.setLabel(hasNegative ? "negative" : "positive");
            sentimentResult.setScore(hasNegative ? 70.0 : 30.0);
            sentimentResult.setConfidence(60.0);
        }
        
        return sentimentResult;
    }
    
    /**
     * 从文本中解析情绪结果
     */
    private void parseSentimentFromText(String text, SentimentResult result) {
        String lowerText = text.toLowerCase();
        
        // 解析标签
        if (lowerText.contains("negative") || lowerText.contains("负面")) {
            result.setLabel("negative");
        } else if (lowerText.contains("positive") || lowerText.contains("正面")) {
            result.setLabel("positive");
        } else if (lowerText.contains("neutral") || lowerText.contains("中性")) {
            result.setLabel("neutral");
        }
        
        // 尝试提取分数
        java.util.regex.Pattern scorePattern = java.util.regex.Pattern.compile("(?:score|分数)[:：]?\\s*(\\d+(?:\\.\\d+)?)");
        java.util.regex.Matcher scoreMatcher = scorePattern.matcher(text);
        if (scoreMatcher.find()) {
            try {
                result.setScore(Double.parseDouble(scoreMatcher.group(1)));
            } catch (NumberFormatException e) {
                // 忽略
            }
        }
        
        // 尝试提取置信度
        java.util.regex.Pattern confPattern = java.util.regex.Pattern.compile("(?:confidence|置信度)[:：]?\\s*(\\d+(?:\\.\\d+)?)");
        java.util.regex.Matcher confMatcher = confPattern.matcher(text);
        if (confMatcher.find()) {
            try {
                result.setConfidence(Double.parseDouble(confMatcher.group(1)));
            } catch (NumberFormatException e) {
                // 忽略
            }
        }
    }
    
    /**
     * 情绪分析结果类
     */
    public static class SentimentResult {
        private String label; // positive/negative/neutral
        private Double score; // 0-100，越高越负面
        private Double confidence; // 0-100，置信度
        
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
        
        public Double getConfidence() {
            return confidence;
        }
        
        public void setConfidence(Double confidence) {
            this.confidence = confidence;
        }
    }
}

