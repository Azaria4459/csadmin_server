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
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Deepseek AI 服务类
 * 
 * 功能描述：
 * 封装 Deepseek API 调用，提供统一的文本分析接口
 * 参照 PHP 版本的 Deepseek.php 实现
 * 
 * 使用场景：
 * - 情绪分析：分析客户消息的情感倾向
 * - 其他文本分析任务
 * 
 * 依赖：
 * - Deepseek API：需要配置 API Key
 * - 模型：deepseek-chat
 * 
 * 配置：
 * - API Key：通过构造函数传入
 * - 超时设置：连接超时30秒，读取超时60秒
 */
public class DeepseekService implements AiService {
    private static final Logger logger = LoggerFactory.getLogger(DeepseekService.class);
    
    private final String apiKey;
    private final String apiUrl;
    private final ObjectMapper objectMapper;
    
    // Deepseek Chat 的 token 限制
    // 输入token限制：约32,000 tokens
    private static final long MAX_INPUT_TOKENS = 32000L;
    
    // 估算token：中文字符大约1个token，英文单词大约0.75个token
    // 这里简化处理：1个字符 = 1个token（保守估算）
    private static final double TOKEN_ESTIMATE_RATIO = 1.0;
    
    /**
     * 构造函数
     * 
     * @param apiKey Deepseek API密钥
     */
    public DeepseekService(String apiKey) {
        this.apiKey = apiKey;
        this.apiUrl = "https://api.deepseek.com/v1/chat/completions";
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
        return "Deepseek";
    }
    
    /**
     * 发送请求到 Deepseek API（返回原始JSON响应）
     * 参照 PHP 版本的实现
     * 
     * @param content 请求内容（提示词）
     * @return Deepseek 返回的原始JSON响应字符串
     * @throws Exception 请求失败时抛出异常
     */
    @Override
    public String requestRawJson(String content) throws Exception {
        // 构建请求数据（参照 PHP 版本）
        Map<String, Object> data = new HashMap<>();
        data.put("model", "deepseek-chat");
        
        Map<String, Object>[] messages = new Map[1];
        messages[0] = new HashMap<>();
        messages[0].put("role", "user");
        messages[0].put("content", content);
        data.put("messages", messages);
        
        // 降低 temperature 以提高输出稳定性，强制返回 JSON 格式
        data.put("temperature", 0.1);
        data.put("max_tokens", 8000);
        
        // 注意：Deepseek API 目前不支持 response_format 参数
        // 需要在提示词中明确要求返回 JSON 格式
        
        String postData = objectMapper.writeValueAsString(data);
        
        HttpURLConnection connection = null;
        try {
            URL requestUrl = new URL(apiUrl);
            connection = (HttpURLConnection) requestUrl.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
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
                // 记录详细的错误信息
                logger.error("Deepseek API 请求失败: code={}", responseCode);
                logger.error("Deepseek API 错误响应: {}", responseStr.length() > 1000 ? responseStr.substring(0, 1000) + "..." : responseStr);
                logger.error("Deepseek API 请求URL: {}", apiUrl);
                logger.error("Deepseek API 请求数据: {}", postData.length() > 500 ? postData.substring(0, 500) + "..." : postData);
                throw new Exception("Deepseek API 请求失败: code=" + responseCode + ", response=" + responseStr);
            }
            
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * 发送请求到 Deepseek API
     * 
     * @param content 请求内容（提示词）
     * @return Deepseek 返回的文本内容
     * @throws Exception 请求失败时抛出异常
     */
    public String request(String content) throws Exception {
        String responseStr = requestRawJson(content);
        
        // 解析响应（参照 PHP 版本）
        JsonNode apiResult = objectMapper.readTree(responseStr);
        
        // 提取文本内容：choices[0].message.content
        JsonNode choicesNode = apiResult.get("choices");
        if (choicesNode != null && choicesNode.isArray() && choicesNode.size() > 0) {
            JsonNode firstChoice = choicesNode.get(0);
            JsonNode messageNode = firstChoice.get("message");
            if (messageNode != null) {
                JsonNode contentNode = messageNode.get("content");
                if (contentNode != null) {
                    return contentNode.asText();
                }
            }
        }
        
        logger.warn("Deepseek API 响应格式异常: {}", responseStr);
        return "";
    }
}

