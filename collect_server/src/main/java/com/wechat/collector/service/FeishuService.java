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
 * 飞书服务类
 * 
 * 功能描述：
 * 1. 获取飞书访问令牌（Tenant Access Token）：自动管理token的获取和刷新
 * 2. 发送文本消息：向指定用户（通过open_id）发送文本消息
 * 3. Token缓存：缓存token，避免频繁请求API
 * 4. 错误处理：API调用失败时记录日志并返回错误信息
 * 
 * 使用场景：
 * - 情绪预警通知：检测到负面情绪时通知管理员
 * - 超时提醒通知：客户消息超时未回复时通知管理员
 * - 其他业务通知需求
 * 
 * 依赖：
 * - 飞书开放平台API：需要配置 App ID 和 App Secret
 * - 飞书服务器地址：通过构造函数传入
 * 
 * 配置：
 * - feishuHost：飞书API服务器地址
 * - appId：飞书应用ID
 * - appSecret：飞书应用密钥
 */
public class FeishuService {
    private static final Logger logger = LoggerFactory.getLogger(FeishuService.class);
    
    private final String feishuHost;
    private final String appId;
    private final String appSecret;
    private final ObjectMapper objectMapper;
    
    // Token缓存（简单实现，生产环境建议使用Redis）
    private String cachedTenantAccessToken;
    private long tokenExpireTime;
    private static final long TOKEN_EXPIRE_DURATION = 7000 * 1000; // 7000秒转换为毫秒
    
    /**
     * 构造函数
     * 
     * @param feishuHost 飞书API地址
     * @param appId 应用ID
     * @param appSecret 应用密钥
     */
    public FeishuService(String feishuHost, String appId, String appSecret) {
        this.feishuHost = feishuHost != null && !feishuHost.isEmpty() 
            ? feishuHost : "https://open.feishu.cn";
        this.appId = appId;
        this.appSecret = appSecret;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 获取租户访问令牌
     * 带缓存机制，避免频繁请求
     * 
     * @return 租户访问令牌
     * @throws Exception 获取令牌失败时抛出异常
     */
    private String getTenantAccessToken() throws Exception {
        // 检查缓存是否有效
        if (cachedTenantAccessToken != null && System.currentTimeMillis() < tokenExpireTime) {
            return cachedTenantAccessToken;
        }
        
        String url = feishuHost + "/open-apis/auth/v3/tenant_access_token/internal";
        
        Map<String, String> requestData = new HashMap<>();
        requestData.put("app_id", appId);
        requestData.put("app_secret", appSecret);
        
        String jsonData = objectMapper.writeValueAsString(requestData);
        
        Map<String, Object> response = sendRequest(url, jsonData, true);
        
        String token = null;
        if (response != null && response.containsKey("tenant_access_token")) {
            token = (String) response.get("tenant_access_token");
        }
        
        if (token == null || token.isEmpty()) {
            throw new Exception("获取飞书访问令牌失败: " + response);
        }
        
        // 缓存token
        cachedTenantAccessToken = token;
        tokenExpireTime = System.currentTimeMillis() + TOKEN_EXPIRE_DURATION;
        
        logger.debug("获取飞书访问令牌成功，已缓存");
        return token;
    }
    
    /**
     * 发送HTTP请求
     * 
     * @param url 请求URL
     * @param data 请求数据（JSON字符串）
     * @param isPost 是否为POST请求
     * @return 响应数据（Map格式）
     */
    private Map<String, Object> sendRequest(String url, String data, boolean isPost) {
        return sendRequest(url, data, null, isPost);
    }
    
    /**
     * 发送HTTP请求
     * 
     * @param url 请求URL
     * @param data 请求数据（JSON字符串）
     * @param extraHeaders 额外的请求头
     * @param isPost 是否为POST请求
     * @return 响应数据（Map格式）
     */
    private Map<String, Object> sendRequest(String url, String data, Map<String, String> extraHeaders, boolean isPost) {
        HttpURLConnection connection = null;
        try {
            URL requestUrl = new URL(url);
            connection = (HttpURLConnection) requestUrl.openConnection();
            connection.setRequestMethod(isPost ? "POST" : "GET");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(isPost);
            connection.setDoInput(true);
            connection.setConnectTimeout(10000); // 10秒连接超时
            connection.setReadTimeout(30000); // 30秒读取超时
            
            // 添加额外的请求头
            if (extraHeaders != null) {
                for (Map.Entry<String, String> header : extraHeaders.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            
            // 发送POST数据
            if (isPost && data != null && !data.isEmpty()) {
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = data.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
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
                // 解析JSON响应
                if (responseStr != null && !responseStr.isEmpty()) {
                    return objectMapper.readValue(responseStr, Map.class);
                }
                return new HashMap<>();
            } else {
                logger.error("飞书API请求失败: url={}, code={}, response={}", url, responseCode, responseStr);
                return null;
            }
            
        } catch (Exception e) {
            logger.error("发送飞书API请求异常: url={}", url, e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
    
    /**
     * 发送消息
     * 
     * @param receiveId 接收者ID（open_id或user_id）
     * @param content 消息内容
     * @param receiveIdType 接收者ID类型：open_id/user_id
     * @param msgType 消息类型：text
     * @return 响应数据
     * @throws Exception 发送失败时抛出异常
     */
    public Map<String, Object> sendMessage(String receiveId, String content, String receiveIdType, String msgType) throws Exception {
        String accessToken = getTenantAccessToken();
        String url = feishuHost + "/open-apis/im/v1/messages?receive_id_type=" + receiveIdType;
        
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("receive_id", receiveId);
        requestData.put("msg_type", msgType);
        
        Map<String, String> contentMap = new HashMap<>();
        contentMap.put("text", content);
        requestData.put("content", objectMapper.writeValueAsString(contentMap));
        
        String jsonData = objectMapper.writeValueAsString(requestData);
        
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        
        Map<String, Object> response = sendRequest(url, jsonData, headers, true);
        
        if (response == null) {
            throw new Exception("发送飞书消息失败");
        }
        
        logger.info("发送飞书消息成功: receiveId={}, content={}", receiveId, content);
        return response;
    }
    
    /**
     * 发送文本消息（简化方法）
     * 
     * @param receiveId 接收者ID（open_id）
     * @param content 消息内容
     * @return 是否发送成功
     */
    public boolean sendTextMessage(String receiveId, String content) {
        try {
            sendMessage(receiveId, content, "open_id", "text");
            return true;
        } catch (Exception e) {
            logger.error("发送飞书文本消息失败: receiveId={}", receiveId, e);
            return false;
        }
    }
}

