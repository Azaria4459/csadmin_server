package com.wechat.collector.service;

import com.wechat.collector.model.WeChatMessage;
import java.util.List;

/**
 * 微信接口拉取结果
 * 包含解析后的消息列表和原始 JSON 响应
 */
public class FetchResult {
    private List<WeChatMessage> messages;
    private String rawJsonResponse;
    private int errorCode;
    private String errorMessage;

    public FetchResult(List<WeChatMessage> messages, String rawJsonResponse) {
        this.messages = messages;
        this.rawJsonResponse = rawJsonResponse;
        this.errorCode = 0;
    }

    public FetchResult(int errorCode, String errorMessage) {
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.messages = List.of();
        this.rawJsonResponse = null;
    }

    public List<WeChatMessage> getMessages() {
        return messages;
    }

    public String getRawJsonResponse() {
        return rawJsonResponse;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public boolean isSuccess() {
        return errorCode == 0;
    }
}
