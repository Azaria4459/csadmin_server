package com.wechat.collector.service;

import java.util.Map;

/**
 * 消息收集结果
 * 包含本次拉取的统计信息和微信接口的请求参数、返回内容
 */
public class CollectionResult {
    /**
     * 本次新增入库的消息条数
     */
    private int savedCount;

    /**
     * 本次拉取到的消息总数（包含重复的）
     */
    private int fetchedCount;

    /**
     * 微信接口请求参数
     */
    private Map<String, Object> wechatRequest;

    /**
     * 微信接口返回内容（原始 JSON）
     */
    private String wechatResponse;

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 错误信息（如果失败）
     */
    private String errorMessage;

    public CollectionResult() {
    }

    public CollectionResult(int savedCount, int fetchedCount, Map<String, Object> wechatRequest, String wechatResponse) {
        this.savedCount = savedCount;
        this.fetchedCount = fetchedCount;
        this.wechatRequest = wechatRequest;
        this.wechatResponse = wechatResponse;
        this.success = true;
    }

    public static CollectionResult error(String errorMessage) {
        CollectionResult result = new CollectionResult();
        result.success = false;
        result.errorMessage = errorMessage;
        return result;
    }

    public int getSavedCount() {
        return savedCount;
    }

    public void setSavedCount(int savedCount) {
        this.savedCount = savedCount;
    }

    public int getFetchedCount() {
        return fetchedCount;
    }

    public void setFetchedCount(int fetchedCount) {
        this.fetchedCount = fetchedCount;
    }

    public Map<String, Object> getWechatRequest() {
        return wechatRequest;
    }

    public void setWechatRequest(Map<String, Object> wechatRequest) {
        this.wechatRequest = wechatRequest;
    }

    public String getWechatResponse() {
        return wechatResponse;
    }

    public void setWechatResponse(String wechatResponse) {
        this.wechatResponse = wechatResponse;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
