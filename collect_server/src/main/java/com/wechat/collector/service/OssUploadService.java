package com.wechat.collector.service;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.PutObjectResult;
import com.wechat.collector.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * 阿里云OSS上传服务
 * 
 * 功能描述：
 * 1. 初始化OSS客户端：使用阿里云SDK创建OSS客户端连接
 * 2. 上传文件：将本地文件或字节流上传到阿里云OSS
 * 3. 路径管理：自动生成OSS存储路径（按日期和文件类型组织）
 * 4. 文件命名：使用UUID确保文件名唯一性
 * 5. 返回访问URL：上传成功后返回完整的OSS访问URL
 * 6. 资源清理：服务关闭时自动关闭OSS客户端连接
 * 
 * 使用场景：
 * - 上传聊天中的媒体文件（图片、语音、视频、文件）到OSS
 * - 为前端提供媒体文件的访问链接
 * - 统一管理媒体资源存储
 * 
 * 依赖：
 * - 阿里云OSS SDK：com.aliyun.oss
 * - 阿里云OSS配置：endpoint、accessKeyId、accessKeySecret、bucketName
 * 
 * 配置：
 * - 通过 AppConfig.OssConfig 配置
 * - 需要配置OSS的访问凭证和存储桶信息
 */
public class OssUploadService {
    private static final Logger logger = LoggerFactory.getLogger(OssUploadService.class);
    
    private final AppConfig.OssConfig ossConfig;
    private OSS ossClient;
    
    public OssUploadService(AppConfig.OssConfig ossConfig) {
        this.ossConfig = ossConfig;
        initOssClient();
    }
    
    /**
     * 初始化OSS客户端
     */
    private void initOssClient() {
        if (!ossConfig.isEnable()) {
            logger.info("OSS service is disabled");
            return;
        }
        
        try {
            logger.info("Initializing OSS client...");
            logger.info("Endpoint: {}", ossConfig.getEndpoint());
            logger.info("Bucket: {}", ossConfig.getBucket());
            logger.info("Domain: {}", ossConfig.getDomain());
            
            this.ossClient = new OSSClientBuilder().build(
                ossConfig.getEndpoint(),
                ossConfig.getAccessKeyId(),
                ossConfig.getAccessKeySecret()
            );
            
            // 测试连接
            boolean exists = ossClient.doesBucketExist(ossConfig.getBucket());
            if (exists) {
                logger.info("OSS client initialized successfully, bucket exists: {}", ossConfig.getBucket());
            } else {
                logger.warn("OSS bucket does not exist: {}", ossConfig.getBucket());
            }
        } catch (Exception e) {
            logger.error("Failed to initialize OSS client", e);
        }
    }
    
    /**
     * 上传字节数组到OSS
     * 
     * @param data 文件字节数据
     * @param fileExtension 文件扩展名（如：jpg, png, mp4）
     * @param msgType 消息类型（image, video, voice, file）
     * @return 上传成功返回完整URL，失败返回null
     */
    public String upload(byte[] data, String fileExtension, String msgType) {
        if (!ossConfig.isEnable() || ossClient == null) {
            logger.warn("OSS service is not enabled or not initialized");
            return null;
        }
        
        if (data == null || data.length == 0) {
            logger.warn("Upload data is empty");
            return null;
        }
        
        try {
            // 生成文件名：csapi/image/20241103/uuid.jpg
            String objectKey = generateObjectKey(msgType, fileExtension);
            
            logger.info("Uploading file to OSS: {} (size: {} bytes)", objectKey, data.length);
            
            // 创建上传请求
            ByteArrayInputStream inputStream = new ByteArrayInputStream(data);
            PutObjectRequest putObjectRequest = new PutObjectRequest(
                ossConfig.getBucket(),
                objectKey,
                inputStream
            );
            
            // 执行上传
            PutObjectResult result = ossClient.putObject(putObjectRequest);
            
            // 生成访问URL
            String url = ossConfig.getDomain() + "/" + objectKey;
            
            logger.info("File uploaded successfully: {}", url);
            logger.debug("Upload ETag: {}", result.getETag());
            
            return url;
            
        } catch (Exception e) {
            logger.error("Failed to upload file to OSS", e);
            return null;
        }
    }
    
    /**
     * 生成OSS对象键（文件路径）
     * 格式：csapi/msgType/yyyy/MM/dd/uuid.ext
     * 
     * @param msgType 消息类型
     * @param fileExtension 文件扩展名
     * @return OSS对象键
     */
    private String generateObjectKey(String msgType, String fileExtension) {
        // 使用 yyyy/MM/dd 格式的日期路径
        SimpleDateFormat yearSdf = new SimpleDateFormat("yyyy");
        SimpleDateFormat monthSdf = new SimpleDateFormat("MM");
        SimpleDateFormat daySdf = new SimpleDateFormat("dd");
        
        Date now = new Date();
        String year = yearSdf.format(now);
        String month = monthSdf.format(now);
        String day = daySdf.format(now);
        
        String uuid = UUID.randomUUID().toString().replace("-", "");
        
        // 规范化消息类型
        String typeFolder = msgType != null ? msgType.toLowerCase() : "media";
        
        // 规范化扩展名
        String ext = fileExtension;
        if (ext != null && !ext.startsWith(".")) {
            ext = "." + ext;
        }
        if (ext == null || ext.isEmpty()) {
            ext = "";
        }
        
        // 构建路径：csapi/image/2025/11/04/uuid.jpg
        return String.format("%s/%s/%s/%s/%s/%s%s",
            ossConfig.getDirectory(),
            typeFolder,
            year,
            month,
            day,
            uuid,
            ext
        );
    }
    
    /**
     * 关闭OSS客户端
     */
    public void shutdown() {
        if (ossClient != null) {
            try {
                ossClient.shutdown();
                logger.info("OSS client shutdown successfully");
            } catch (Exception e) {
                logger.error("Error shutting down OSS client", e);
            }
        }
    }
    
    /**
     * 检查OSS服务是否可用
     */
    public boolean isAvailable() {
        return ossConfig.isEnable() && ossClient != null;
    }
}

