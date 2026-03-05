package com.wechat.collector.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;

import java.sql.Timestamp;
import java.util.Arrays;

/**
 * 企业微信消息实体类
 * 对应数据库表 wechat_message
 */
@Data
public class WeChatMessage {
    /**
     * 自增主键
     */
    private Long id;
    
    /**
     * 企业微信消息ID（唯一）
     */
    private String msgid;
    
    /**
     * 顺序号，拉取时排序用
     */
    private Long seq;
    
    /**
     * 消息时间戳（毫秒）
     */
    private Long msgtime;
    
    /**
     * 消息动作：send/recall
     */
    private String action;
    
    /**
     * 群聊ID（如果是群消息）
     */
    private String roomid;
    
    /**
     * 会话ID
     * 群聊：等于roomid
     * 单聊：两个userid按字典序排列，用下划线连接，如 "user1_user2"
     */
    private String conversationId;
    
    /**
     * 会话类型：single单聊 / group群聊
     */
    private String conversationType;
    
    /**
     * 发送者userid
     */
    private String fromUser;
    
    /**
     * 接收人列表（JSON格式）
     */
    private String toList;
    
    /**
     * 消息类型：text/image/voice/file/video/emotion/mixed等
     */
    private String msgtype;
    
    /**
     * 消息主要内容（JSON格式）
     */
    private String content;
    
    /**
     * 媒体文件OSS链接（image/video/voice/file类型消息）
     */
    private String mediaUrl;
    
    /**
     * 资源文件ID（关联wechat_resource表）
     */
    private Integer resourceId;
    
    /**
     * 原始微信返回JSON（可选）
     */
    private String rawJson;
    
    /**
     * 记录创建时间
     */
    private Timestamp createTime;
    
    /**
     * 生成会话ID和会话类型
     * 群聊：conversationId = roomid, conversationType = "group"
     * 单聊：conversationId = 两个userid按字典序排列（user1_user2）, conversationType = "single"
     */
    public void generateConversationInfo() {
        if (roomid != null && !roomid.isEmpty()) {
            // 群聊消息
            this.conversationId = roomid;
            this.conversationType = "group";
        } else {
            // 单聊消息：从toList中提取接收人，与发送人组合
            String toUser = extractFirstRecipient(this.toList);
            if (toUser != null) {
                this.conversationId = generateSingleConversationId(this.fromUser, toUser);
                this.conversationType = "single";
            }
        }
    }
    
    /**
     * 从toList JSON中提取第一个接收人
     * 
     * @param toListJson toList的JSON字符串，如 "[\"userid1\"]" 或 "[\"userid1\",\"userid2\"]"
     * @return 第一个接收人的userid，解析失败返回null
     */
    private String extractFirstRecipient(String toListJson) {
        if (toListJson == null || toListJson.isEmpty()) {
            return null;
        }
        
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode arrayNode = mapper.readTree(toListJson);
            
            if (arrayNode.isArray() && arrayNode.size() > 0) {
                return arrayNode.get(0).asText();
            }
        } catch (Exception e) {
            // 解析失败，返回null
        }
        
        return null;
    }
    
    /**
     * 生成单聊会话ID
     * 将两个userid按字典序排列，用下划线连接
     * 
     * @param user1 第一个用户ID
     * @param user2 第二个用户ID
     * @return 会话ID，如 "user1_user2"
     */
    private String generateSingleConversationId(String user1, String user2) {
        if (user1 == null || user2 == null) {
            return null;
        }
        
        // 按字典序排列，确保A与B聊天、B与A聊天的conversationId一致
        String[] users = {user1, user2};
        Arrays.sort(users);
        
        return users[0] + "_" + users[1];
    }
}

