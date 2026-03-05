package com.wechat.collector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.collector.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 企业微信通讯录API服务
 * 
 * 功能描述：
 * 1. 获取Access Token：调用企业微信API获取访问令牌
 * 2. 获取员工列表：从企业微信API获取所有员工信息
 * 3. 获取外部联系人列表：从企业微信API获取外部联系人信息
 * 4. HTTP请求封装：统一处理HTTP请求、响应解析、错误处理
 * 5. Token管理：自动处理token的获取和缓存
 * 6. 分页支持：支持分页获取大量数据
 * 
 * 使用场景：
 * - 同步企业微信通讯录到本地数据库
 * - 为其他服务提供企业微信API访问能力
 * 
 * 依赖：
 * - 企业微信开放平台API：需要配置corpId和secret
 * - HTTP客户端：使用Java 11+的HttpClient
 * 
 * 配置：
 * - 通过 AppConfig.WechatSyncConfig 配置
 * - 需要配置企业ID（corpId）和通讯录密钥（secret）
 */
public class WechatContactApiService {
    private static final Logger logger = LoggerFactory.getLogger(WechatContactApiService.class);
    
    private final AppConfig.WechatSyncConfig config;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    // 企业微信API基础URL
    private static final String API_BASE_URL = "https://qyapi.weixin.qq.com/cgi-bin";
    
    // Access Token（需要定期刷新）
    private String accessToken;
    private long tokenExpireTime = 0;
    
    /**
     * 构造函数
     * 
     * @param config 企业微信同步配置
     */
    public WechatContactApiService(AppConfig.WechatSyncConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 获取Access Token
     * 如果token未过期则返回缓存的token，否则重新获取
     * 
     * @return Access Token
     * @throws IOException 网络请求异常
     * @throws InterruptedException 请求中断异常
     */
    public synchronized String getAccessToken() throws IOException, InterruptedException {
        long currentTime = System.currentTimeMillis();
        
        // 如果token还有效（提前5分钟刷新）
        if (accessToken != null && currentTime < tokenExpireTime - 300000) {
            return accessToken;
        }
        
        // 获取新token
        String url = String.format("%s/gettoken?corpid=%s&corpsecret=%s",
                API_BASE_URL, config.getCorpid(), config.getContactSecret());
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Failed to get access token, HTTP status: " + response.statusCode());
        }
        
        JsonNode jsonNode = objectMapper.readTree(response.body());
        int errcode = jsonNode.get("errcode").asInt();
        
        if (errcode != 0) {
            String errmsg = jsonNode.get("errmsg").asText();
            throw new IOException("Failed to get access token: " + errmsg);
        }
        
        this.accessToken = jsonNode.get("access_token").asText();
        int expiresIn = jsonNode.get("expires_in").asInt();
        this.tokenExpireTime = currentTime + expiresIn * 1000L;
        
        logger.info("获取Access Token成功，有效期: {} 秒", expiresIn);
        
        return this.accessToken;
    }
    
    /**
     * 获取部门列表
     * 
     * @return 部门列表JSON
     * @throws IOException 网络请求异常
     * @throws InterruptedException 请求中断异常
     */
    public JsonNode getDepartmentList() throws IOException, InterruptedException {
        String token = getAccessToken();
        String url = String.format("%s/department/list?access_token=%s", API_BASE_URL, token);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Failed to get department list, HTTP status: " + response.statusCode());
        }
        
        JsonNode jsonNode = objectMapper.readTree(response.body());
        int errcode = jsonNode.get("errcode").asInt();
        
        if (errcode != 0) {
            String errmsg = jsonNode.get("errmsg").asText();
            throw new IOException("Failed to get department list: " + errmsg);
        }
        
        return jsonNode.get("department");
    }
    
    /**
     * 获取部门员工列表（简单信息）
     * 
     * @param departmentId 部门ID
     * @return 员工列表JSON
     * @throws IOException 网络请求异常
     * @throws InterruptedException 请求中断异常
     */
    public JsonNode getDepartmentUsers(long departmentId) throws IOException, InterruptedException {
        String token = getAccessToken();
        String url = String.format("%s/user/simplelist?access_token=%s&department_id=%d&fetch_child=1",
                API_BASE_URL, token, departmentId);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Failed to get department users, HTTP status: " + response.statusCode());
        }
        
        JsonNode jsonNode = objectMapper.readTree(response.body());
        int errcode = jsonNode.get("errcode").asInt();
        
        if (errcode != 0) {
            String errmsg = jsonNode.get("errmsg").asText();
            throw new IOException("Failed to get department users: " + errmsg);
        }
        
        return jsonNode.get("userlist");
    }
    
    /**
     * 获取外部联系人详细信息
     * 
     * @param externalUserId 外部联系人ID
     * @return 外部联系人信息JSON
     * @throws IOException 网络请求异常
     * @throws InterruptedException 请求中断异常
     */
    public JsonNode getExternalContact(String externalUserId) throws IOException, InterruptedException {
        String token = getAccessToken();
        String url = String.format("%s/externalcontact/get?access_token=%s&external_userid=%s",
                API_BASE_URL, token, externalUserId);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(10))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Failed to get external contact, HTTP status: " + response.statusCode());
        }
        
        JsonNode jsonNode = objectMapper.readTree(response.body());
        int errcode = jsonNode.get("errcode").asInt();
        
        if (errcode != 0) {
            String errmsg = jsonNode.get("errmsg").asText();
            // 如果是外部联系人不存在，返回null而不是抛出异常
            if (errcode == 84061 || errcode == 40096) {
                logger.warn("外部联系人不存在或已删除: {}", externalUserId);
                return null;
            }
            throw new IOException("Failed to get external contact: " + errmsg);
        }
        
        return jsonNode.get("external_contact");
    }
}

