package com.wechat.collector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wechat.collector.model.WeChatMessage;
import com.wechat.collector.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * 媒体处理服务
 * 
 * 功能描述：
 * 1. 识别媒体文件：从消息中识别图片、语音、视频、文件等媒体类型
 * 2. 下载媒体文件：从企业微信服务器下载媒体文件到本地
 * 3. 音频格式转换：将AMR格式语音转换为MP3格式（用于浏览器播放）
 * 4. 上传到OSS：将处理后的媒体文件上传到阿里云OSS
 * 5. 资源管理：将媒体文件信息保存到 wechat_resource 表，实现去重和统一管理
 * 6. 关联消息：将消息的 resource_id 关联到资源表
 * 
 * 使用场景：
 * - 处理聊天中的图片、语音、视频等媒体文件
 * - 统一管理媒体资源，避免重复存储
 * - 提供媒体文件的访问URL
 * 
 * 依赖：
 * - MediaDownloadService：媒体下载服务
 * - OssUploadService：OSS上传服务
 * - AudioConversionService：音频转换服务
 * - ResourceRepository：资源数据访问层
 * - wechat_resource 表：资源管理表
 */
public class MediaProcessService {
    private static final Logger logger = LoggerFactory.getLogger(MediaProcessService.class);
    
    private final MediaDownloadService downloadService;
    private final OssUploadService ossUploadService;
    private final AudioConversionService audioConversionService;
    private final com.wechat.collector.repository.ResourceRepository resourceRepository;
    private final MessageRepository messageRepository;
    private final ObjectMapper objectMapper;
    
    // 重试服务（可选，如果为null则不启用重试）
    private MediaRetryService retryService;
    
    // 媒体类型与文件扩展名映射
    private static final Map<String, String> EXTENSION_MAP = new HashMap<String, String>() {{
        put("image", "jpg");
        put("voice", "amr");
        put("video", "mp4");
        put("file", "");
    }};
    
    /**
     * 构造函数
     * 
     * @param downloadService 媒体下载服务
     * @param ossUploadService OSS上传服务
     * @param audioConversionService 音频转换服务（可为null，禁用转换功能）
     * @param resourceRepository 资源数据访问层
     * @param messageRepository 消息数据访问层（用于更新resource_id）
     */
    public MediaProcessService(MediaDownloadService downloadService, 
                               OssUploadService ossUploadService,
                               AudioConversionService audioConversionService,
                               com.wechat.collector.repository.ResourceRepository resourceRepository,
                               MessageRepository messageRepository) {
        this.downloadService = downloadService;
        this.ossUploadService = ossUploadService;
        this.audioConversionService = audioConversionService;
        this.resourceRepository = resourceRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = new ObjectMapper();
        
        if (audioConversionService != null) {
            logger.info("媒体处理服务已启用音频转换功能（AMR→MP3）");
        } else {
            logger.info("媒体处理服务未启用音频转换功能");
        }
    }
    
    /**
     * 设置重试服务
     * 
     * @param retryService 重试服务
     */
    public void setRetryService(MediaRetryService retryService) {
        this.retryService = retryService;
        if (retryService != null) {
            logger.info("媒体处理服务已启用重试功能");
        }
    }
    
    /**
     * 处理消息中的媒体文件
     * 如果消息包含媒体文件，则下载并上传到OSS
     * 
     * @param message 消息对象
     * @return 是否成功处理（没有媒体文件或处理成功都返回true）
     */
    public boolean processMedia(WeChatMessage message) {
        if (message == null) {
            return true;
        }
        
        String msgType = message.getMsgtype();
        
        // 只处理媒体类型消息
        if (!isMediaMessage(msgType)) {
            return true;
        }
        
        logger.info("Processing media message: msgid={}, msgtype={}", 
            message.getMsgid(), msgType);
        
        try {
            // 从消息内容中提取sdkFileId
            String sdkFileId = extractSdkFileId(message.getContent(), msgType);
            
            if (sdkFileId == null || sdkFileId.isEmpty()) {
                logger.warn("No sdkFileId found in message: msgid={}", message.getMsgid());
                return true; // 没有文件ID也算成功，避免阻塞流程
            }
            
            logger.info("Found sdkFileId: {} in message: {}", sdkFileId, message.getMsgid());
            
            // 下载媒体文件
            byte[] mediaData = downloadService.downloadMedia(sdkFileId);
            
            if (mediaData == null || mediaData.length == 0) {
                logger.warn("Failed to download media: sdkFileId={}, msgid={}", sdkFileId, message.getMsgid());
                
                // 如果启用了重试服务，将任务加入重试队列
                if (retryService != null) {
                    retryService.addRetryTask(message, 0); // 第一次重试，retryCount=0
                    logger.info("已将下载失败的消息加入重试队列: msgid={}", message.getMsgid());
                }
                
                return false;
            }
            
            logger.info("Downloaded media file: {} bytes", mediaData.length);
            
            // ✅ 优先检查资源表，避免重复上传到 OSS
            try {
                JsonNode content = objectMapper.readTree(message.getContent());
                JsonNode mediaNode = content.path(msgType);
                
                // 提取 MD5 和文件信息
                String md5sum = mediaNode.path("md5sum").asText(null);
                long filesize = mediaNode.path("filesize").asLong(mediaData.length);
                
                // 检查资源是否已存在
                if (md5sum != null && !md5sum.isEmpty()) {
                    com.wechat.collector.repository.ResourceRepository.Resource existingResource = 
                        resourceRepository.findByIdentifier(md5sum, sdkFileId);
                    
                    if (existingResource != null) {
                        // 资源已存在，直接使用现有资源，不再上传
                        logger.info("资源已存在，跳过上传: resource_id={}, md5sum={}, ossUrl={}", 
                            existingResource.getId(), md5sum, existingResource.getOssUrl());
                        
                        // 只设置 resource_id，不再设置 media_url（通过关联查询获取）
                        message.setResourceId(existingResource.getId());
                        
                        // 更新数据库中的 resource_id（异步处理时消息可能已经入库）
                        if (messageRepository != null) {
                            messageRepository.updateResourceId(message.getMsgid(), existingResource.getId());
                        } else {
                            logger.warn("messageRepository is null, cannot update resource_id: msgid={}", message.getMsgid());
                        }
                        
                        // 增加引用计数
                        resourceRepository.incrementDownloadCount(existingResource.getId());
                        
                        return true;  // 直接返回成功，跳过上传流程
                    }
                }
                
                logger.info("资源不存在，需要下载并上传到 OSS: md5sum={}", md5sum);
                
            } catch (Exception e) {
                logger.warn("检查资源是否存在时发生异常，继续上传流程", e);
                // 继续执行上传流程
            }
            
            // 获取文件扩展名
            String extension = getFileExtension(message.getContent(), msgType);
            
            // 处理 AMR 转 MP3（仅对语音消息）
            byte[] dataToUpload = mediaData;
            String finalExtension = extension;
            
            if ("voice".equals(msgType) && audioConversionService != null) {
                logger.info("检测到语音消息，尝试转换 AMR → MP3");
                
                File tempAmrFile = null;
                File tempMp3File = null;
                
                try {
                    // 创建临时 AMR 文件
                    tempAmrFile = File.createTempFile("wechat_voice_", ".amr");
                    try (FileOutputStream fos = new FileOutputStream(tempAmrFile)) {
                        fos.write(mediaData);
                    }
                    
                    // 创建临时 MP3 文件
                    tempMp3File = File.createTempFile("wechat_voice_", ".mp3");
                    
                    // 执行转换
                    boolean converted = audioConversionService.convertAmrToMp3(tempAmrFile, tempMp3File);
                    
                    if (converted && tempMp3File.exists() && tempMp3File.length() > 0) {
                        // 转换成功，读取 MP3 数据
                        dataToUpload = java.nio.file.Files.readAllBytes(tempMp3File.toPath());
                        finalExtension = "mp3";
                        logger.info("语音转换成功: AMR ({} bytes) → MP3 ({} bytes)", 
                            mediaData.length, dataToUpload.length);
                    } else {
                        logger.warn("语音转换失败，使用原始 AMR 格式上传");
                    }
                    
                } catch (Exception e) {
                    logger.error("语音转换过程发生异常，使用原始 AMR 格式上传", e);
                } finally {
                    // 清理临时文件
                    if (tempAmrFile != null && tempAmrFile.exists()) {
                        tempAmrFile.delete();
                    }
                    if (tempMp3File != null && tempMp3File.exists()) {
                        tempMp3File.delete();
                    }
                }
            }
            
            // 上传到OSS
            String ossUrl = ossUploadService.upload(dataToUpload, finalExtension, msgType);
            
            if (ossUrl == null || ossUrl.isEmpty()) {
                logger.warn("Failed to upload media to OSS: sdkFileId={}", sdkFileId);
                return false;
            }
            
            logger.info("Uploaded media to OSS: {}", ossUrl);
            
            // 写入 wechat_resource 表（不再设置 media_url 到消息对象）
            try {
                JsonNode content = objectMapper.readTree(message.getContent());
                JsonNode mediaNode = content.path(msgType);
                
                // 提取资源信息
                String md5sum = mediaNode.path("md5sum").asText(null);
                long filesize = mediaNode.path("filesize").asLong(dataToUpload.length);
                String fileext = finalExtension;
                
                // 从 OSS URL 中提取路径和存储桶
                // 格式: https://img.flwvip.com/csapi/file/2025/11/06/xxx.xlsx
                String ossPath = null;
                String ossBucket = null;
                if (ossUrl != null && ossUrl.contains("/csapi/")) {
                    int csapiIndex = ossUrl.indexOf("/csapi/");
                    ossPath = ossUrl.substring(csapiIndex);
                    ossBucket = "csapi"; // 根据实际配置调整
                }
                
                // 调用 ResourceRepository 创建或获取资源
                Integer resourceId = resourceRepository.createOrGet(
                    md5sum,
                    sdkFileId,
                    msgType,
                    filesize,
                    fileext,
                    ossUrl,
                    ossPath,
                    ossBucket
                );
                
                if (resourceId != null) {
                    // 只设置 resource_id，不设置 media_url（通过关联查询获取）
                    message.setResourceId(resourceId);
                    
                    // 更新数据库中的 resource_id（异步处理时消息可能已经入库）
                    if (messageRepository != null) {
                        boolean updated = messageRepository.updateResourceId(message.getMsgid(), resourceId);
                        if (updated) {
                            logger.info("资源已关联到消息: resource_id={}, msgid={}", resourceId, message.getMsgid());
                        } else {
                            logger.warn("更新消息 resource_id 失败: resource_id={}, msgid={}", resourceId, message.getMsgid());
                        }
                    } else {
                        logger.error("messageRepository is null, cannot update resource_id: msgid={}, resource_id={}", 
                            message.getMsgid(), resourceId);
                    }
                } else {
                    logger.warn("写入资源表失败: msgid={}, ossUrl={}", message.getMsgid(), ossUrl);
                }
                
            } catch (Exception e) {
                logger.error("写入资源表时发生异常: msgid={}, ossUrl={}", message.getMsgid(), ossUrl, e);
                // 不影响主流程，继续返回成功
            }
            
            return true;
            
        } catch (Exception e) {
            logger.error("Error processing media for message: " + message.getMsgid(), e);
            return false;
        }
    }
    
    /**
     * 判断是否为媒体消息类型
     */
    private boolean isMediaMessage(String msgType) {
        if (msgType == null) {
            return false;
        }
        return msgType.equals("image") || 
               msgType.equals("voice") || 
               msgType.equals("video") || 
               msgType.equals("file");
    }
    
    /**
     * 从消息内容中提取sdkFileId
     * 
     * @param contentJson 消息内容JSON字符串
     * @param msgType 消息类型
     * @return sdkFileId，失败返回null
     */
    private String extractSdkFileId(String contentJson, String msgType) {
        if (contentJson == null || contentJson.isEmpty()) {
            return null;
        }
        
        try {
            JsonNode content = objectMapper.readTree(contentJson);
            
            // 不同类型的消息，sdkfileid的位置不同
            // image: content.image.sdkfileid
            // voice: content.voice.sdkfileid
            // video: content.video.sdkfileid
            // file: content.file.sdkfileid
            
            JsonNode mediaNode = content.path(msgType);
            if (mediaNode.isMissingNode()) {
                return null;
            }
            
            String sdkFileId = mediaNode.path("sdkfileid").asText(null);
            return sdkFileId;
            
        } catch (Exception e) {
            logger.error("Failed to extract sdkFileId from content", e);
            return null;
        }
    }
    
    /**
     * 获取文件扩展名
     * 优先从content中获取filename，否则使用默认扩展名
     * 
     * @param contentJson 消息内容JSON字符串
     * @param msgType 消息类型
     * @return 文件扩展名（不带点），如 "jpg", "mp4"
     */
    private String getFileExtension(String contentJson, String msgType) {
        try {
            JsonNode content = objectMapper.readTree(contentJson);
            JsonNode mediaNode = content.path(msgType);
            
            // 尝试从filename中提取扩展名
            if (mediaNode.has("filename")) {
                String filename = mediaNode.path("filename").asText();
                if (filename != null && filename.contains(".")) {
                    String ext = filename.substring(filename.lastIndexOf(".") + 1);
                    return ext.toLowerCase();
                }
            }
            
            // 对于image类型，可能有play_length字段表示是否是动图
            if (msgType.equals("image")) {
                int playLength = mediaNode.path("play_length").asInt(0);
                if (playLength > 0) {
                    return "gif"; // 动图
                }
            }
            
        } catch (Exception e) {
            logger.debug("Failed to extract extension from content, using default");
        }
        
        // 使用默认扩展名
        return EXTENSION_MAP.getOrDefault(msgType, "");
    }
    
    /**
     * 检查服务是否可用
     */
    public boolean isAvailable() {
        return downloadService != null && ossUploadService != null && ossUploadService.isAvailable();
    }
}

