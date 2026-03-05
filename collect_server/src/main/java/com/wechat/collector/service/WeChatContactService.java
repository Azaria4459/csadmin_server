package com.wechat.collector.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 企业微信联系人服务
 * 
 * 功能描述：
 * 1. 封装企业微信联系人API访问：提供简化的接口
 * 2. 获取Access Token：获取企业微信API访问令牌
 * 3. Token管理：自动处理token的获取和缓存
 * 
 * 使用场景：
 * - 为其他服务（如WechatSyncService）提供access_token
 * - 简化企业微信API的调用
 * 
 * 依赖：
 * - WechatContactApiService：企业微信联系人API服务（底层实现）
 */
public class WeChatContactService {
    private static final Logger logger = LoggerFactory.getLogger(WeChatContactService.class);
    
    private final WechatContactApiService apiService;
    
    /**
     * 构造函数
     * 
     * @param apiService 企业微信联系人API服务
     */
    public WeChatContactService(WechatContactApiService apiService) {
        this.apiService = apiService;
    }
    
    /**
     * 获取Access Token
     * 
     * @return Access Token，失败返回null
     */
    public String getAccessToken() {
        try {
            return apiService.getAccessToken();
        } catch (Exception e) {
            logger.error("获取Access Token失败", e);
            return null;
        }
    }
}

