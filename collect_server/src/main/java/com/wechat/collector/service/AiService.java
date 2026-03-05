package com.wechat.collector.service;

/**
 * AI服务接口
 * 
 * 功能描述：
 * 统一的AI服务接口，支持不同的AI服务提供商（Gemini、Deepseek等）
 * 
 * 使用场景：
 * - 情绪分析：分析客户消息的情感倾向
 * - 文本分析：其他文本分析任务
 */
public interface AiService {
    
    /**
     * 发送请求到AI API（返回原始JSON响应）
     * 
     * @param content 请求内容（提示词）
     * @return AI返回的原始JSON响应字符串
     * @throws Exception 请求失败时抛出异常
     */
    String requestRawJson(String content) throws Exception;
    
    /**
     * 估算文本的token数量
     * 
     * @param text 文本内容
     * @return 估算的token数量
     */
    long estimateTokenCount(String text);
    
    /**
     * 获取最大输入token限制
     * 
     * @return 最大token数量
     */
    long getMaxInputTokens();
    
    /**
     * 获取AI服务名称（用于日志记录）
     * 
     * @return 服务名称（如 "Gemini"、"Deepseek"）
     */
    String getServiceName();
}

