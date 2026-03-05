package com.wechat.collector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.wechat.collector.model.WechatMember;
import com.wechat.collector.repository.WechatMemberRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 企业微信通讯录同步服务
 * 
 * 功能描述：
 * 1. 同步员工信息：从企业微信API获取员工列表，同步到 wechat_member 表
 * 2. 同步外部联系人：从企业微信API获取外部联系人列表，同步到 wechat_member 表
 * 3. 成员类型标记：区分员工（type=1）和客户（type=2）
 * 4. 信息更新：更新成员的昵称、头像、邮箱等信息
 * 5. 增量同步：只同步变更的成员信息
 * 6. 批量处理：支持批量同步，提高效率
 * 
 * 使用场景：
 * - 定期同步企业微信通讯录，确保成员信息最新
 * - 为情绪分析、意向识别等服务提供准确的成员类型信息
 * - 支持按成员类型进行数据分析和统计
 * 
 * 依赖：
 * - WechatContactApiService：企业微信API服务
 * - WechatMemberRepository：成员数据访问层
 * - wechat_member 表：成员表
 * 
 * 执行频率：
 * - 由 WechatSyncScheduler 定时触发（可配置）
 */
public class WechatSyncService {
    private static final Logger logger = LoggerFactory.getLogger(WechatSyncService.class);
    
    private final WechatContactApiService apiService;
    private final WechatMemberRepository memberRepository;
    
    /**
     * 构造函数
     * 
     * @param apiService 企业微信API服务
     * @param memberRepository 成员数据仓库
     */
    public WechatSyncService(WechatContactApiService apiService, WechatMemberRepository memberRepository) {
        this.apiService = apiService;
        this.memberRepository = memberRepository;
    }
    
    /**
     * 同步企业员工信息（定时任务A）
     * 1. 获取所有部门
     * 2. 遍历部门获取员工列表
     * 3. 插入或更新员工信息到数据库
     * 
     * @return 同步结果信息
     */
    public SyncResult syncEmployees() {
        logger.info("开始同步企业员工信息...");
        long startTime = System.currentTimeMillis();
        
        int totalEmployees = 0;
        int successCount = 0;
        int failCount = 0;
        
        try {
            // 1. 获取部门列表
            JsonNode departments = apiService.getDepartmentList();
            
            if (departments == null || !departments.isArray()) {
                logger.error("获取部门列表失败");
                return new SyncResult(false, "获取部门列表失败", 0, 0, 0);
            }
            
            logger.info("获取到 {} 个部门", departments.size());
            
            // 2. 遍历每个部门
            for (JsonNode dept : departments) {
                long departmentId = dept.get("id").asLong();
                String departmentName = dept.get("name").asText();
                
                logger.info("正在同步部门: {} (ID: {})", departmentName, departmentId);
                
                try {
                    // 3. 获取部门员工列表
                    JsonNode userlist = apiService.getDepartmentUsers(departmentId);
                    
                    if (userlist == null || !userlist.isArray()) {
                        logger.warn("部门 {} 没有员工", departmentName);
                        continue;
                    }
                    
                    // 4. 插入或更新每个员工
                    for (JsonNode user : userlist) {
                        totalEmployees++;
                        
                        String userid = user.get("userid").asText();
                        String name = user.get("name").asText();
                        
                        try {
                            boolean success = memberRepository.upsertEmployee(userid, name);
                            if (success) {
                                successCount++;
                            } else {
                                failCount++;
                            }
                        } catch (Exception e) {
                            logger.error("插入或更新员工失败: userid={}, name={}", userid, name, e);
                            failCount++;
                        }
                    }
                    
                    logger.info("部门 {} 同步完成，员工数: {}", departmentName, userlist.size());
                    
                } catch (Exception e) {
                    logger.error("同步部门 {} 失败", departmentName, e);
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            logger.info("员工同步完成: 总数={}, 成功={}, 失败={}, 耗时={}ms", 
                    totalEmployees, successCount, failCount, duration);
            
            return new SyncResult(true, "同步完成", totalEmployees, successCount, failCount);
            
        } catch (Exception e) {
            logger.error("同步员工信息异常", e);
            return new SyncResult(false, "同步异常: " + e.getMessage(), totalEmployees, successCount, failCount);
        }
    }
    
    /**
     * 同步外部联系人信息（定时任务B）
     * 1. 查询 type=2 的所有成员
     * 2. 遍历每个成员，调用API获取详细信息
     * 3. 更新成员的昵称、头像、性别
     * 
     * @return 同步结果信息
     */
    public SyncResult syncExternalContacts() {
        logger.info("开始同步外部联系人信息...");
        long startTime = System.currentTimeMillis();
        
        int totalContacts = 0;
        int successCount = 0;
        int failCount = 0;
        int notFoundCount = 0;
        
        try {
            // 1. 查询所有 type=2 的成员
            List<WechatMember> externalMembers = memberRepository.findAllByType((short) 2);
            
            if (externalMembers.isEmpty()) {
                logger.info("没有需要同步的外部联系人");
                return new SyncResult(true, "没有需要同步的外部联系人", 0, 0, 0);
            }
            
            totalContacts = externalMembers.size();
            logger.info("找到 {} 个外部联系人需要同步", totalContacts);
            
            // 2. 遍历每个外部联系人
            for (WechatMember member : externalMembers) {
                String externalUserId = member.getAccountName();
                
                try {
                    // 3. 调用API获取详细信息
                    JsonNode contact = apiService.getExternalContact(externalUserId);
                    
                    if (contact == null) {
                        // 联系人不存在或已删除
                        logger.warn("外部联系人不存在: {}", externalUserId);
                        notFoundCount++;
                        failCount++;
                        continue;
                    }
                    
                    // 4. 提取信息
                    String name = contact.has("name") ? contact.get("name").asText() : null;
                    String avatar = contact.has("avatar") ? contact.get("avatar").asText() : null;
                    Byte gender = null;
                    if (contact.has("gender")) {
                        gender = (byte) contact.get("gender").asInt();
                    }
                    
                    // 5. 更新数据库
                    boolean success = memberRepository.updateExternalContact(externalUserId, name, avatar, gender);
                    
                    if (success) {
                        successCount++;
                        logger.debug("更新外部联系人成功: {} - {}", externalUserId, name);
                    } else {
                        failCount++;
                    }
                    
                    // 6. 添加延迟避免API限流（每秒最多20次）
                    Thread.sleep(50);
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("同步被中断", e);
                    break;
                } catch (Exception e) {
                    logger.error("同步外部联系人失败: {}", externalUserId, e);
                    failCount++;
                }
            }
            
            long duration = System.currentTimeMillis() - startTime;
            logger.info("外部联系人同步完成: 总数={}, 成功={}, 失败={}, 不存在={}, 耗时={}ms", 
                    totalContacts, successCount, failCount, notFoundCount, duration);
            
            return new SyncResult(true, "同步完成", totalContacts, successCount, failCount);
            
        } catch (Exception e) {
            logger.error("同步外部联系人异常", e);
            return new SyncResult(false, "同步异常: " + e.getMessage(), totalContacts, successCount, failCount);
        }
    }
    
    /**
     * 同步结果类
     */
    public static class SyncResult {
        private final boolean success;
        private final String message;
        private final int total;
        private final int successCount;
        private final int failCount;
        
        /**
         * 构造函数
         * 
         * @param success 是否成功
         * @param message 结果消息
         * @param total 总数
         * @param successCount 成功数
         * @param failCount 失败数
         */
        public SyncResult(boolean success, String message, int total, int successCount, int failCount) {
            this.success = success;
            this.message = message;
            this.total = total;
            this.successCount = successCount;
            this.failCount = failCount;
        }
        
        public boolean isSuccess() {
            return success;
        }
        
        public String getMessage() {
            return message;
        }
        
        public int getTotal() {
            return total;
        }
        
        public int getSuccessCount() {
            return successCount;
        }
        
        public int getFailCount() {
            return failCount;
        }
        
        @Override
        public String toString() {
            return String.format("SyncResult{success=%s, message='%s', total=%d, success=%d, fail=%d}",
                    success, message, total, successCount, failCount);
        }
    }
}

