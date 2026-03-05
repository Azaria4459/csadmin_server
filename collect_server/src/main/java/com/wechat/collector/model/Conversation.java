package com.wechat.collector.model;

import lombok.Data;

import java.sql.Timestamp;

/**
 * 会话汇总实体类
 * 对应数据库表 wechat_conversation
 * 用于优化会话列表查询性能，避免每次都对消息表进行 GROUP BY 统计
 */
@Data
public class Conversation {
    /**
     * 自增主键
     */
    private Long id;
    
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
     * 会话名称（群聊名称）
     */
    private String name;
    
    /**
     * 备注名称
     */
    private String remarkName;
    
    /**
     * 群聊ID（仅当会话类型为group时有值）
     */
    private String roomid;
    
    /**
     * 消息总数
     */
    private Integer messageCount;
    
    /**
     * 第一条消息时间戳（毫秒）
     */
    private Long firstMessageTime;
    
    /**
     * 最后一条消息时间戳（毫秒）
     */
    private Long lastMessageTime;
    
    /**
     * 最后一条消息ID
     */
    private String lastMessageId;
    
    /**
     * 最后一条消息类型
     */
    private String lastMessageType;
    
    /**
     * 最后一条消息内容摘要
     */
    private String lastMessageContent;
    
    /**
     * 最后一条消息发送者
     */
    private String lastMessageSender;
    
    /**
     * 参与人员列表（JSON格式）
     * 格式：["userid1", "userid2", ...]
     */
    private String participants;
    
    /**
     * 参与人数
     */
    private Integer participantCount;
    
    /**
     * 记录创建时间
     */
    private Timestamp createTime;
    
    /**
     * 记录更新时间
     */
    private Timestamp updateTime;
    
    /**
     * 是否删除：0-未删除，1-已删除
     */
    private Short isDelete;
    
    /**
     * 责任人ID（关联wechat_member.id）
     */
    private Integer responsibleUserId;
}

