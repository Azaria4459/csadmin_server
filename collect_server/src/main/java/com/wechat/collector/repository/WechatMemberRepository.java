package com.wechat.collector.repository;

import com.wechat.collector.config.DatabaseManager;
import com.wechat.collector.model.WechatMember;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.HashSet;
import java.util.Set;

/**
 * 微信成员数据访问层
 * 负责管理 wechat_member 表的数据操作
 */
public class WechatMemberRepository {
    private static final Logger logger = LoggerFactory.getLogger(WechatMemberRepository.class);
    
    private final DatabaseManager databaseManager;
    
    /**
     * 构造函数
     * 
     * @param databaseManager 数据库管理器
     */
    public WechatMemberRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }
    
    /**
     * 检查指定账号名称是否已存在
     * 
     * @param accountName WeChat账号名称
     * @return 是否存在，true-存在，false-不存在
     */
    public boolean exists(String accountName) {
        if (accountName == null || accountName.trim().isEmpty()) {
            return false;
        }
        
        String sql = "SELECT COUNT(1) FROM wechat_member WHERE account_name = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, accountName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
            }
            
        } catch (SQLException e) {
            logger.error("检查成员是否存在失败: accountName={}", accountName, e);
        }
        
        return false;
    }
    
    /**
     * 插入新成员记录
     * 如果记录已存在则跳过（通过 INSERT IGNORE 实现）
     * 
     * @param accountName WeChat账号名称
     * @param type 类型：1-员工，2-用户，默认为2
     * @return 是否插入成功，true-成功插入，false-记录已存在或插入失败
     */
    public boolean insert(String accountName, Short type) {
        if (accountName == null || accountName.trim().isEmpty()) {
            logger.warn("账号名称为空，跳过插入");
            return false;
        }
        
        // 默认类型为 2（用户）
        if (type == null) {
            type = 2;
        }
        
        String sql = "INSERT IGNORE INTO wechat_member (account_name, type) VALUES (?, ?)";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, accountName);
            stmt.setShort(2, type);
            
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.debug("成功插入新成员: accountName={}, type={}", accountName, type);
                return true;
            } else {
                logger.debug("成员已存在，跳过插入: accountName={}", accountName);
                return false;
            }
            
        } catch (SQLException e) {
            logger.error("插入成员失败: accountName={}, type={}", accountName, type, e);
            return false;
        }
    }
    
    /**
     * 批量检查并插入成员
     * 对于不存在的成员自动插入，已存在的跳过
     * 
     * @param accountNames 账号名称集合
     * @param type 类型：1-员工，2-用户，默认为2
     * @return 实际插入的成员数量
     */
    public int batchInsertIfNotExists(Set<String> accountNames, Short type) {
        if (accountNames == null || accountNames.isEmpty()) {
            return 0;
        }
        
        int insertCount = 0;
        
        for (String accountName : accountNames) {
            if (accountName != null && !accountName.trim().isEmpty()) {
                if (insert(accountName, type)) {
                    insertCount++;
                }
            }
        }
        
        logger.info("批量插入成员完成: 总数={}, 新插入={}", accountNames.size(), insertCount);
        return insertCount;
    }
    
    /**
     * 检查并插入参与人员
     * 检查参与人员列表中的所有成员，不存在的自动插入
     * 
     * @param participants 参与人员集合
     * @return 新插入的成员数量
     */
    public int ensureParticipantsExist(Set<String> participants) {
        if (participants == null || participants.isEmpty()) {
            logger.debug("参与人员列表为空，跳过检查");
            return 0;
        }
        
        // 先批量检查哪些成员不存在
        Set<String> missingMembers = new HashSet<>();
        
        for (String accountName : participants) {
            if (accountName != null && !accountName.trim().isEmpty()) {
                if (!exists(accountName)) {
                    missingMembers.add(accountName);
                }
            }
        }
        
        if (missingMembers.isEmpty()) {
            logger.debug("所有参与人员都已存在，无需插入");
            return 0;
        }
        
        // 批量插入缺失的成员（默认类型为2-用户）
        int insertCount = batchInsertIfNotExists(missingMembers, (short) 2);
        
        logger.info("参与人员检查完成: 总数={}, 已存在={}, 新插入={}", 
                participants.size(), 
                participants.size() - missingMembers.size(), 
                insertCount);
        
        return insertCount;
    }
    
    /**
     * 根据账号名称查询成员信息
     * 
     * @param accountName WeChat账号名称
     * @return 成员对象，不存在返回null
     */
    public WechatMember findByAccountName(String accountName) {
        if (accountName == null || accountName.trim().isEmpty()) {
            return null;
        }
        
        String sql = "SELECT * FROM wechat_member WHERE account_name = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, accountName);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapRowToMember(rs);
                }
            }
            
        } catch (SQLException e) {
            logger.error("查询成员失败: accountName={}", accountName, e);
        }
        
        return null;
    }
    
    /**
     * 更新成员别名
     * 
     * @param accountName WeChat账号名称
     * @param nickName 新别名
     * @return 是否更新成功
     */
    public boolean updateNickName(String accountName, String nickName) {
        if (accountName == null || accountName.trim().isEmpty()) {
            return false;
        }
        
        String sql = "UPDATE wechat_member SET nick_name = ? WHERE account_name = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, nickName);
            stmt.setString(2, accountName);
            
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.debug("更新成员别名成功: accountName={}, nickName={}", accountName, nickName);
                return true;
            }
            
        } catch (SQLException e) {
            logger.error("更新成员别名失败: accountName={}, nickName={}", accountName, nickName, e);
        }
        
        return false;
    }
    
    /**
     * 获取成员总数
     * 
     * @return 成员总数
     */
    public long count() {
        String sql = "SELECT COUNT(*) FROM wechat_member";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            if (rs.next()) {
                return rs.getLong(1);
            }
            
        } catch (SQLException e) {
            logger.error("获取成员总数失败", e);
        }
        
        return 0;
    }
    
    /**
     * 插入或更新员工信息（定时任务A使用）
     * 如果记录不存在则插入，存在则更新昵称和更新时间
     * 
     * @param accountName 员工userid
     * @param nickName 员工姓名
     * @return 是否成功
     */
    public boolean upsertEmployee(String accountName, String nickName) {
        if (accountName == null || accountName.trim().isEmpty()) {
            return false;
        }
        
        String sql = "INSERT INTO wechat_member (account_name, type, nick_name, update_time) " +
                     "VALUES (?, 1, ?, NOW()) " +
                     "ON DUPLICATE KEY UPDATE nick_name = ?, update_time = NOW()";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, accountName);
            stmt.setString(2, nickName);
            stmt.setString(3, nickName);
            
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.debug("插入或更新员工成功: accountName={}, nickName={}", accountName, nickName);
                return true;
            }
            
        } catch (SQLException e) {
            logger.error("插入或更新员工失败: accountName={}, nickName={}", accountName, nickName, e);
        }
        
        return false;
    }
    
    /**
     * 更新外部联系人信息（定时任务B使用）
     * 
     * @param accountName 外部联系人ID
     * @param nickName 昵称
     * @param avatar 头像URL
     * @param gender 性别
     * @return 是否成功
     */
    public boolean updateExternalContact(String accountName, String nickName, String avatar, Byte gender) {
        if (accountName == null || accountName.trim().isEmpty()) {
            return false;
        }
        
        String sql = "UPDATE wechat_member " +
                     "SET nick_name = ?, avatar = ?, gender = ?, update_time = NOW() " +
                     "WHERE account_name = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, nickName);
            stmt.setString(2, avatar);
            if (gender != null) {
                stmt.setByte(3, gender);
            } else {
                stmt.setNull(3, Types.TINYINT);
            }
            stmt.setString(4, accountName);
            
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.debug("更新外部联系人成功: accountName={}, nickName={}", accountName, nickName);
                return true;
            }
            
        } catch (SQLException e) {
            logger.error("更新外部联系人失败: accountName={}, nickName={}", accountName, nickName, e);
        }
        
        return false;
    }
    
    /**
     * 根据类型查询所有成员
     * 
     * @param type 类型：1-员工，2-用户
     * @return 成员列表
     */
    public java.util.List<WechatMember> findAllByType(Short type) {
        java.util.List<WechatMember> members = new java.util.ArrayList<>();
        
        String sql = "SELECT * FROM wechat_member WHERE type = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setShort(1, type);
            
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    members.add(mapRowToMember(rs));
                }
            }
            
        } catch (SQLException e) {
            logger.error("根据类型查询成员失败: type={}", type, e);
        }
        
        return members;
    }
    
    /**
     * 将 ResultSet 行映射为 WechatMember 对象
     * 
     * @param rs ResultSet结果集
     * @return WechatMember对象
     * @throws SQLException SQL异常
     */
    private WechatMember mapRowToMember(ResultSet rs) throws SQLException {
        WechatMember member = new WechatMember();
        member.setId(rs.getInt("id"));
        member.setType(rs.getShort("type"));
        member.setAccountName(rs.getString("account_name"));
        member.setNickName(rs.getString("nick_name"));
        member.setRemarkName(rs.getString("remark_name"));
        
        // 新增字段（可能为空）
        String avatar = rs.getString("avatar");
        if (avatar != null) {
            member.setAvatar(avatar);
        }
        
        byte gender = rs.getByte("gender");
        if (!rs.wasNull()) {
            member.setGender(gender);
        }
        
        member.setCreateTime(rs.getTimestamp("create_time"));
        member.setUpdateTime(rs.getTimestamp("update_time"));
        
        return member;
    }
}

