package com.wechat.collector.repository;

import com.wechat.collector.config.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 资源数据访问层
 * 负责查询 wechat_resource 表
 */
public class ResourceRepository {
    private static final Logger logger = LoggerFactory.getLogger(ResourceRepository.class);
    
    private final DatabaseManager databaseManager;
    
    public ResourceRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }
    
    /**
     * 根据 MD5 或 sdkfileid 查找资源
     * 优先使用 md5sum（有唯一索引，查询快）
     * 
     * @param md5sum 文件MD5值
     * @param sdkfileid 企业微信SDK文件ID
     * @return 资源对象，如果不存在返回null
     */
    public Resource findByIdentifier(String md5sum, String sdkfileid) {
        // 优先使用 md5sum 查找（有唯一索引，更快）
        if (md5sum != null && !md5sum.isEmpty()) {
            Resource resource = findByMd5sum(md5sum);
            if (resource != null) {
                logger.info("通过 md5sum 找到已存在的资源: resource_id={}, md5sum={}", 
                    resource.getId(), md5sum);
                return resource;
            }
        }
        
        // md5sum 未找到，尝试使用 sdkfileid 查找
        if (sdkfileid != null && !sdkfileid.isEmpty()) {
            Resource resource = findBySdkfileid(sdkfileid);
            if (resource != null) {
                logger.info("通过 sdkfileid 找到已存在的资源: resource_id={}", resource.getId());
                return resource;
            }
        }
        
        logger.debug("资源不存在: md5sum={}, sdkfileid length={}", 
            md5sum, sdkfileid != null ? sdkfileid.length() : 0);
        return null;
    }
    
    /**
     * 根据 MD5 查找资源
     * 
     * @param md5sum 文件MD5值
     * @return 资源对象或null
     */
    private Resource findByMd5sum(String md5sum) {
        String sql = "SELECT id, md5sum, sdkfileid, file_type, file_size, file_extension, " +
                    "oss_url, oss_path, oss_bucket, download_count, status " +
                    "FROM wechat_resource " +
                    "WHERE md5sum = ? AND status = 1 " +
                    "LIMIT 1";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, md5sum);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToResource(rs);
                }
            }
            
        } catch (SQLException e) {
            logger.error("查询资源失败 (md5sum): {}", md5sum, e);
        }
        
        return null;
    }
    
    /**
     * 根据 sdkfileid 查找资源
     * 
     * @param sdkfileid SDK文件ID
     * @return 资源对象或null
     */
    private Resource findBySdkfileid(String sdkfileid) {
        String sql = "SELECT id, md5sum, sdkfileid, file_type, file_size, file_extension, " +
                    "oss_url, oss_path, oss_bucket, download_count, status " +
                    "FROM wechat_resource " +
                    "WHERE sdkfileid = ? AND status = 1 " +
                    "LIMIT 1";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, sdkfileid);
            
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return mapResultSetToResource(rs);
                }
            }
            
        } catch (SQLException e) {
            logger.error("查询资源失败 (sdkfileid)", e);
        }
        
        return null;
    }
    
    /**
     * 创建或获取资源
     * 如果资源已存在（根据md5sum或sdkfileid），则返回已有资源并增加引用计数
     * 如果不存在，则创建新资源
     * 
     * @param md5sum 文件MD5值
     * @param sdkfileid 企业微信SDK文件ID
     * @param fileType 文件类型（image/voice/video/file）
     * @param fileSize 文件大小（字节）
     * @param fileExtension 文件扩展名
     * @param ossUrl OSS完整URL
     * @param ossPath OSS路径
     * @param ossBucket OSS存储桶
     * @return 资源ID，失败返回null
     */
    public Integer createOrGet(String md5sum, String sdkfileid, String fileType, 
                               long fileSize, String fileExtension, 
                               String ossUrl, String ossPath, String ossBucket) {
        // 1. 先检查资源是否已存在
        Resource existingResource = findByIdentifier(md5sum, sdkfileid);
        if (existingResource != null) {
            // 资源已存在，增加引用计数
            incrementDownloadCount(existingResource.getId());
            logger.info("资源已存在，复用: resource_id={}, md5sum={}", 
                existingResource.getId(), md5sum);
            return existingResource.getId();
        }
        
        // 2. 资源不存在，创建新资源
        String sql = "INSERT INTO wechat_resource " +
                    "(md5sum, sdkfileid, file_type, file_size, file_extension, " +
                    "oss_url, oss_path, oss_bucket, download_count, status) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, 1)";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)) {
            
            stmt.setString(1, md5sum);
            stmt.setString(2, sdkfileid);
            stmt.setString(3, fileType);
            stmt.setLong(4, fileSize);
            stmt.setString(5, fileExtension);
            stmt.setString(6, ossUrl);
            stmt.setString(7, ossPath);
            stmt.setString(8, ossBucket);
            
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                try (ResultSet generatedKeys = stmt.getGeneratedKeys()) {
                    if (generatedKeys.next()) {
                        int resourceId = generatedKeys.getInt(1);
                        logger.info("创建新资源成功: resource_id={}, md5sum={}, ossUrl={}", 
                            resourceId, md5sum, ossUrl);
                        return resourceId;
                    }
                }
            }
            
        } catch (SQLException e) {
            logger.error("创建资源失败: md5sum={}, ossUrl={}", md5sum, ossUrl, e);
        }
        
        return null;
    }
    
    /**
     * 增加资源的引用计数
     * 
     * @param resourceId 资源ID
     * @return 是否成功
     */
    public boolean incrementDownloadCount(int resourceId) {
        String sql = "UPDATE wechat_resource SET download_count = download_count + 1 WHERE id = ?";
        
        try (Connection conn = databaseManager.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setInt(1, resourceId);
            int affected = stmt.executeUpdate();
            
            if (affected > 0) {
                logger.info("资源引用计数+1: resource_id={}", resourceId);
            }
            
            return affected > 0;
            
        } catch (SQLException e) {
            logger.error("更新资源引用计数失败: resource_id={}", resourceId, e);
            return false;
        }
    }
    
    /**
     * 将 ResultSet 映射为 Resource 对象
     * 
     * @param rs ResultSet
     * @return Resource 对象
     * @throws SQLException SQL异常
     */
    private Resource mapResultSetToResource(ResultSet rs) throws SQLException {
        Resource resource = new Resource();
        resource.setId(rs.getInt("id"));
        resource.setMd5sum(rs.getString("md5sum"));
        resource.setSdkfileid(rs.getString("sdkfileid"));
        resource.setFileType(rs.getString("file_type"));
        resource.setFileSize(rs.getLong("file_size"));
        resource.setFileExtension(rs.getString("file_extension"));
        resource.setOssUrl(rs.getString("oss_url"));
        resource.setOssPath(rs.getString("oss_path"));
        resource.setOssBucket(rs.getString("oss_bucket"));
        resource.setDownloadCount(rs.getInt("download_count"));
        resource.setStatus(rs.getInt("status"));
        return resource;
    }
    
    /**
     * 资源实体类
     */
    public static class Resource {
        private int id;
        private String md5sum;
        private String sdkfileid;
        private String fileType;
        private long fileSize;
        private String fileExtension;
        private String ossUrl;
        private String ossPath;
        private String ossBucket;
        private int downloadCount;
        private int status;
        
        // Getters and Setters
        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        
        public String getMd5sum() { return md5sum; }
        public void setMd5sum(String md5sum) { this.md5sum = md5sum; }
        
        public String getSdkfileid() { return sdkfileid; }
        public void setSdkfileid(String sdkfileid) { this.sdkfileid = sdkfileid; }
        
        public String getFileType() { return fileType; }
        public void setFileType(String fileType) { this.fileType = fileType; }
        
        public long getFileSize() { return fileSize; }
        public void setFileSize(long fileSize) { this.fileSize = fileSize; }
        
        public String getFileExtension() { return fileExtension; }
        public void setFileExtension(String fileExtension) { this.fileExtension = fileExtension; }
        
        public String getOssUrl() { return ossUrl; }
        public void setOssUrl(String ossUrl) { this.ossUrl = ossUrl; }
        
        public String getOssPath() { return ossPath; }
        public void setOssPath(String ossPath) { this.ossPath = ossPath; }
        
        public String getOssBucket() { return ossBucket; }
        public void setOssBucket(String ossBucket) { this.ossBucket = ossBucket; }
        
        public int getDownloadCount() { return downloadCount; }
        public void setDownloadCount(int downloadCount) { this.downloadCount = downloadCount; }
        
        public int getStatus() { return status; }
        public void setStatus(int status) { this.status = status; }
    }
}

