package com.wechat.collector.service;

import com.tencent.wework.Finance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 媒体文件下载服务
 * 
 * 功能描述：
 * 1. 从企业微信服务器下载媒体文件：使用企业微信Finance SDK
 * 2. 分块下载：支持大文件分块下载（每块512KB），提高下载效率
 * 3. 超时控制：设置10秒超时，避免长时间等待
 * 4. 错误处理：处理下载失败、网络异常等情况
 * 5. 返回字节流：下载完成后返回文件的字节数组
 * 
 * 使用场景：
 * - 下载聊天中的图片、语音、视频、文件等媒体
 * - 为 MediaProcessService 提供下载功能
 * 
 * 依赖：
 * - 企业微信Finance SDK：需要已初始化的SDK实例
 * - sdkfileid：企业微信媒体文件ID
 * 
 * 限制：
 * - 单次下载最大块大小：512KB
 * - 超时时间：10秒
 */
public class MediaDownloadService {
    private static final Logger logger = LoggerFactory.getLogger(MediaDownloadService.class);
    
    private static final int MAX_CHUNK_SIZE = 512 * 1024; // 512KB per chunk
    private static final int TIMEOUT = 10; // 10 seconds timeout
    
    private final long sdk;
    
    public MediaDownloadService(long sdk) {
        this.sdk = sdk;
    }
    
    /**
     * 下载媒体文件
     * 
     * @param sdkFileId 媒体文件ID（从消息中获取）
     * @return 文件字节数据，失败返回null
     */
    public byte[] downloadMedia(String sdkFileId) {
        if (sdkFileId == null || sdkFileId.isEmpty()) {
            logger.warn("sdkFileId is empty");
            return null;
        }
        
        logger.info("Downloading media file: {}", sdkFileId);
        
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            String indexBuf = "";
            int chunkIndex = 0;
            
            while (true) {
                // 创建media_data
                long mediaData = Finance.NewMediaData();
                
                try {
                    // 下载分片
                    long ret = Finance.GetMediaData(
                        sdk,
                        indexBuf,
                        sdkFileId,
                        "", // proxy
                        "", // passwd
                        TIMEOUT,
                        mediaData
                    );
                    
                    if (ret != 0) {
                        logger.error("GetMediaData failed, ret: {}, sdkFileId: {}, chunk: {}",
                            ret, sdkFileId, chunkIndex);
                        return null;
                    }
                    
                    // 获取数据
                    byte[] chunkData = Finance.GetData(mediaData);
                    int dataLen = Finance.GetDataLen(mediaData);
                    int isFinish = Finance.IsMediaDataFinish(mediaData);
                    
                    logger.debug("Downloaded chunk {}: {} bytes, isFinish: {}",
                        chunkIndex, dataLen, isFinish);
                    
                    // 写入数据
                    if (chunkData != null && dataLen > 0) {
                        outputStream.write(chunkData, 0, dataLen);
                    }
                    
                    // 检查是否完成
                    if (isFinish == 1) {
                        logger.info("Media download completed: {} (total: {} bytes, chunks: {})",
                            sdkFileId, outputStream.size(), chunkIndex + 1);
                        break;
                    }
                    
                    // 获取下一个indexBuf
                    indexBuf = Finance.GetOutIndexBuf(mediaData);
                    chunkIndex++;
                    
                } finally {
                    // 释放资源
                    Finance.FreeMediaData(mediaData);
                }
            }
            
            return outputStream.toByteArray();
            
        } catch (Exception e) {
            logger.error("Failed to download media file: " + sdkFileId, e);
            return null;
        }
    }
    
    /**
     * 批量下载媒体文件
     * 
     * @param sdkFileIds 媒体文件ID列表
     * @return 下载成功的文件数据列表
     */
    public List<MediaFile> batchDownload(List<String> sdkFileIds) {
        List<MediaFile> results = new ArrayList<>();
        
        if (sdkFileIds == null || sdkFileIds.isEmpty()) {
            return results;
        }
        
        logger.info("Batch downloading {} media files", sdkFileIds.size());
        
        int successCount = 0;
        int failCount = 0;
        
        for (String sdkFileId : sdkFileIds) {
            byte[] data = downloadMedia(sdkFileId);
            if (data != null) {
                results.add(new MediaFile(sdkFileId, data));
                successCount++;
            } else {
                failCount++;
            }
        }
        
        logger.info("Batch download completed: {} success, {} failed",
            successCount, failCount);
        
        return results;
    }
    
    /**
     * 媒体文件数据类
     */
    public static class MediaFile {
        private final String sdkFileId;
        private final byte[] data;
        
        public MediaFile(String sdkFileId, byte[] data) {
            this.sdkFileId = sdkFileId;
            this.data = data;
        }
        
        public String getSdkFileId() {
            return sdkFileId;
        }
        
        public byte[] getData() {
            return data;
        }
        
        public int getSize() {
            return data != null ? data.length : 0;
        }
    }
}

