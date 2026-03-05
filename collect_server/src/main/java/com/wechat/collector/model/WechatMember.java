package com.wechat.collector.model;

import java.sql.Timestamp;

/**
 * 微信成员实体类
 * 对应 wechat_member 表
 */
public class WechatMember {
    /**
     * 主键ID
     */
    private Integer id;
    
    /**
     * 类型：1-员工，2-用户
     */
    private Short type;
    
    /**
     * WeChat sender的字符串
     */
    private String accountName;
    
    /**
     * 别名
     */
    private String nickName;
    
    /**
     * 备注名称
     */
    private String remarkName;
    
    /**
     * 头像URL
     */
    private String avatar;
    
    /**
     * 性别：0-未知，1-男，2-女
     */
    private Byte gender;
    
    /**
     * 创建时间
     */
    private Timestamp createTime;
    
    /**
     * 更新时间
     */
    private Timestamp updateTime;
    
    // 构造函数
    public WechatMember() {
    }
    
    /**
     * 构造函数
     * 
     * @param accountName WeChat账号名称
     * @param type 类型：1-员工，2-用户
     */
    public WechatMember(String accountName, Short type) {
        this.accountName = accountName;
        this.type = type;
    }
    
    // Getter and Setter methods
    
    /**
     * 获取主键ID
     * 
     * @return 主键ID
     */
    public Integer getId() {
        return id;
    }
    
    /**
     * 设置主键ID
     * 
     * @param id 主键ID
     */
    public void setId(Integer id) {
        this.id = id;
    }
    
    /**
     * 获取类型
     * 
     * @return 类型：1-员工，2-用户
     */
    public Short getType() {
        return type;
    }
    
    /**
     * 设置类型
     * 
     * @param type 类型：1-员工，2-用户
     */
    public void setType(Short type) {
        this.type = type;
    }
    
    /**
     * 获取账号名称
     * 
     * @return WeChat账号名称
     */
    public String getAccountName() {
        return accountName;
    }
    
    /**
     * 设置账号名称
     * 
     * @param accountName WeChat账号名称
     */
    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }
    
    /**
     * 获取别名
     * 
     * @return 别名
     */
    public String getNickName() {
        return nickName;
    }
    
    /**
     * 设置别名
     * 
     * @param nickName 别名
     */
    public void setNickName(String nickName) {
        this.nickName = nickName;
    }
    
    /**
     * 获取备注名称
     * 
     * @return 备注名称
     */
    public String getRemarkName() {
        return remarkName;
    }
    
    /**
     * 设置备注名称
     * 
     * @param remarkName 备注名称
     */
    public void setRemarkName(String remarkName) {
        this.remarkName = remarkName;
    }
    
    /**
     * 获取头像URL
     * 
     * @return 头像URL
     */
    public String getAvatar() {
        return avatar;
    }
    
    /**
     * 设置头像URL
     * 
     * @param avatar 头像URL
     */
    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }
    
    /**
     * 获取性别
     * 
     * @return 性别：0-未知，1-男，2-女
     */
    public Byte getGender() {
        return gender;
    }
    
    /**
     * 设置性别
     * 
     * @param gender 性别：0-未知，1-男，2-女
     */
    public void setGender(Byte gender) {
        this.gender = gender;
    }
    
    /**
     * 获取创建时间
     * 
     * @return 创建时间
     */
    public Timestamp getCreateTime() {
        return createTime;
    }
    
    /**
     * 设置创建时间
     * 
     * @param createTime 创建时间
     */
    public void setCreateTime(Timestamp createTime) {
        this.createTime = createTime;
    }
    
    /**
     * 获取更新时间
     * 
     * @return 更新时间
     */
    public Timestamp getUpdateTime() {
        return updateTime;
    }
    
    /**
     * 设置更新时间
     * 
     * @param updateTime 更新时间
     */
    public void setUpdateTime(Timestamp updateTime) {
        this.updateTime = updateTime;
    }
    
    @Override
    public String toString() {
        return "WechatMember{" +
                "id=" + id +
                ", type=" + type +
                ", accountName='" + accountName + '\'' +
                ", nickName='" + nickName + '\'' +
                ", avatar='" + avatar + '\'' +
                ", gender=" + gender +
                ", createTime=" + createTime +
                ", updateTime=" + updateTime +
                '}';
    }
}

