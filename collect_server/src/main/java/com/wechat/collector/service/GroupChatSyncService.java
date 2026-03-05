package com.wechat.collector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.collector.config.AppConfig;
import com.wechat.collector.model.Conversation;
import com.wechat.collector.repository.ConversationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * 群聊信息同步服务
 * 
 * 功能描述：
 * 1. 从企业微信API获取群聊详情：包括群名称、成员列表等信息
 * 2. 更新会话表：将群聊名称等信息更新到 wechat_conversation 表
 * 3. 同步成员信息：确保群聊成员信息在 wechat_member 表中存在
 * 4. 批量处理：支持批量同步多个群聊信息
 * 5. 错误处理：处理API调用失败、数据解析失败等异常
 * 
 * 使用场景：
 * - 定期同步群聊名称变化
 * - 确保群聊成员信息完整
 * - 为前端展示提供准确的群聊信息
 * 
 * 依赖：
 * - 企业微信API：需要access_token
 * - ConversationRepository：会话数据访问
 * - WechatMemberRepository：成员数据访问
 * - wechat_conversation 表：会话表
 * - wechat_member 表：成员表
 * 
 * 执行频率：
 * - 由 GroupChatSyncScheduler 定时触发（可配置）
 */
public class GroupChatSyncService {
    private static final Logger logger = LoggerFactory.getLogger(GroupChatSyncService.class);
    
    private final ConversationRepository conversationRepository;
    private final WeChatContactService weChatContactService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    /**
     * 构造函数
     * 
     * @param conversationRepository 会话数据访问层
     * @param weChatContactService 企业微信联系人服务（用于获取token）
     */
    public GroupChatSyncService(ConversationRepository conversationRepository, 
                                WeChatContactService weChatContactService) {
        this.conversationRepository = conversationRepository;
        this.weChatContactService = weChatContactService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 同步所有群聊名称
     * 查询数据库中所有有roomid的会话，调用企业微信API获取群聊详情并更新名称
     * 
     * @return 同步结果统计信息
     */
    public SyncResult syncAllGroupChats() {
        logger.info("开始同步群聊名称...");
        
        SyncResult result = new SyncResult();
        
        try {
            // 1. 获取access_token
            String accessToken = weChatContactService.getAccessToken();
            if (accessToken == null || accessToken.isEmpty()) {
                logger.error("无法获取access_token，同步终止");
                result.failureCount++;
                result.errorMessage = "无法获取access_token";
                return result;
            }
            
            // 2. 查询所有有roomid的会话
            List<Conversation> groupChats = conversationRepository.getGroupConversations();
            logger.info("找到 {} 个群聊会话需要同步", groupChats.size());
            result.totalCount = groupChats.size();
            
            // 3. 遍历每个群聊，调用API获取详情
            for (Conversation conversation : groupChats) {
                try {
                    syncSingleGroupChat(conversation, accessToken);
                    result.successCount++;
                    
                    // 避免API频率限制，每次请求后休眠100ms
                    Thread.sleep(100);
                    
                } catch (Exception e) {
                    result.failureCount++;
                    logger.error("同步群聊失败: roomid={}, conversationId={}", 
                        conversation.getRoomid(), conversation.getConversationId(), e);
                }
            }
            
            logger.info("群聊名称同步完成: 总数={}, 成功={}, 失败={}", 
                result.totalCount, result.successCount, result.failureCount);
            
        } catch (Exception e) {
            logger.error("同步群聊名称失败", e);
            result.errorMessage = e.getMessage();
        }
        
        return result;
    }
    
    /**
     * 同步单个群聊的名称
     * 
     * @param conversation 会话对象
     * @param accessToken 企业微信API访问令牌
     * @throws Exception 同步失败时抛出异常
     */
    private void syncSingleGroupChat(Conversation conversation, String accessToken) throws Exception {
        String roomid = conversation.getRoomid();
        if (roomid == null || roomid.isEmpty()) {
            logger.warn("会话roomid为空，跳过: conversationId={}", conversation.getConversationId());
            return;
        }
        
        // 1. 调用企业微信API获取群聊详情
        String apiUrl = "https://qyapi.weixin.qq.com/cgi-bin/externalcontact/groupchat/get?access_token=" + accessToken;
        
        // 构建请求体
        String requestBody = String.format("{\"chat_id\":\"%s\",\"need_name\":1}", roomid);
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(10))
                .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        // 2. 解析响应
        JsonNode root = objectMapper.readTree(response.body());
        int errcode = root.path("errcode").asInt();
        
        if (errcode != 0) {
            String errmsg = root.path("errmsg").asText();
            logger.warn("获取群聊详情失败: roomid={}, errcode={}, errmsg={}", 
                roomid, errcode, errmsg);
            throw new RuntimeException("API error: " + errcode + " - " + errmsg);
        }
        
        // 3. 提取群聊名称
        JsonNode groupChat = root.path("group_chat");
        String groupName = groupChat.path("name").asText();
        
        if (groupName == null || groupName.isEmpty()) {
            logger.warn("群聊名称为空: roomid={}", roomid);
            return;
        }
        
        // 4. 更新数据库
        boolean updated = conversationRepository.updateConversationName(
            conversation.getConversationId(), 
            groupName
        );
        
        if (updated) {
            logger.info("更新群聊名称成功: roomid={}, name={}", roomid, groupName);
        } else {
            logger.warn("更新群聊名称失败: roomid={}", roomid);
        }
    }
    
    /**
     * 同步结果统计类
     */
    public static class SyncResult {
        /**
         * 总数
         */
        public int totalCount = 0;
        
        /**
         * 成功数
         */
        public int successCount = 0;
        
        /**
         * 失败数
         */
        public int failureCount = 0;
        
        /**
         * 错误信息
         */
        public String errorMessage = null;
        
        @Override
        public String toString() {
            return String.format("SyncResult{total=%d, success=%d, failure=%d, error=%s}", 
                totalCount, successCount, failureCount, errorMessage);
        }
    }
}

