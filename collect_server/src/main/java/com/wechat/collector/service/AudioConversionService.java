package com.wechat.collector.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 音频格式转换服务
 * 
 * 功能描述：
 * 1. 音频格式转换：将企业微信的AMR格式语音转换为MP3格式
 * 2. 使用FFmpeg：调用系统FFmpeg工具进行格式转换
 * 3. 浏览器兼容：转换为浏览器可直接播放的MP3格式
 * 4. 错误处理：处理转换失败、文件不存在等异常
 * 5. 文件管理：转换完成后删除临时文件
 * 
 * 使用场景：
 * - 处理企业微信语音消息，转换为Web可播放格式
 * - 为前端提供统一的音频播放支持
 * 
 * 依赖：
 * - FFmpeg：系统需要安装FFmpeg工具
 * - FFmpeg路径：通过构造函数配置
 * 
 * 配置：
 * - ffmpegPath：FFmpeg可执行文件路径
 * - 输出格式：MP3（固定）
 */
public class AudioConversionService {
    private static final Logger logger = LoggerFactory.getLogger(AudioConversionService.class);
    
    private final String ffmpegPath;
    
    /**
     * 构造函数
     * 
     * @param ffmpegPath FFmpeg 可执行文件路径（null 则使用系统 PATH）
     */
    public AudioConversionService(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath != null && !ffmpegPath.isEmpty() ? ffmpegPath : "ffmpeg";
        logger.info("音频转换服务初始化完成，FFmpeg路径: {}", this.ffmpegPath);
    }
    
    /**
     * 将 AMR 文件转换为 MP3
     * 
     * @param inputFile 输入的 AMR 文件
     * @param outputFile 输出的 MP3 文件
     * @return 是否转换成功
     */
    public boolean convertAmrToMp3(File inputFile, File outputFile) {
        if (inputFile == null || !inputFile.exists()) {
            logger.error("输入文件不存在: {}", inputFile != null ? inputFile.getAbsolutePath() : "null");
            return false;
        }
        
        if (outputFile == null) {
            logger.error("输出文件路径为空");
            return false;
        }
        
        long startTime = System.currentTimeMillis();
        logger.info("开始转换音频: {} -> {}", inputFile.getName(), outputFile.getName());
        
        try {
            // 构建 FFmpeg 命令
            // -i: 输入文件
            // -ar 44100: 采样率 44.1kHz
            // -ac 2: 双声道（stereo）
            // -ab 128k: 比特率 128kbps
            // -f mp3: 输出格式为 MP3
            // -y: 覆盖已存在的文件
            ProcessBuilder processBuilder = new ProcessBuilder(
                ffmpegPath,
                "-i", inputFile.getAbsolutePath(),
                "-ar", "44100",
                "-ac", "2",
                "-ab", "128k",
                "-f", "mp3",
                "-y",
                outputFile.getAbsolutePath()
            );
            
            // 合并标准错误流到标准输出
            processBuilder.redirectErrorStream(true);
            
            // 启动进程
            Process process = processBuilder.start();
            
            // 读取输出日志（FFmpeg 的日志信息在 stderr）
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                    logger.debug("FFmpeg: {}", line);
                }
            }
            
            // 等待进程结束
            int exitCode = process.waitFor();
            
            long duration = System.currentTimeMillis() - startTime;
            
            if (exitCode == 0 && outputFile.exists() && outputFile.length() > 0) {
                logger.info("音频转换成功: {} -> {} (耗时: {}ms, 大小: {} -> {} bytes)", 
                    inputFile.getName(), 
                    outputFile.getName(),
                    duration,
                    inputFile.length(),
                    outputFile.length());
                return true;
            } else {
                logger.error("音频转换失败，退出码: {}, 输出文件存在: {}, 输出文件大小: {}", 
                    exitCode, 
                    outputFile.exists(),
                    outputFile.exists() ? outputFile.length() : 0);
                logger.error("FFmpeg 输出:\n{}", output.toString());
                return false;
            }
            
        } catch (IOException e) {
            logger.error("执行 FFmpeg 时发生IO异常，请确认 FFmpeg 已正确安装", e);
            return false;
        } catch (InterruptedException e) {
            logger.error("FFmpeg 进程被中断", e);
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            logger.error("音频转换过程中发生未知异常", e);
            return false;
        }
    }
    
    /**
     * 检测是否为 AMR 格式
     * 
     * @param filename 文件名
     * @return 是否为 AMR 格式
     */
    public boolean isAmrFormat(String filename) {
        return filename != null && filename.toLowerCase().endsWith(".amr");
    }
    
    /**
     * 将文件扩展名替换为 MP3
     * 
     * @param filename 原文件名
     * @return 替换后的文件名
     */
    public String replaceExtensionToMp3(String filename) {
        if (filename == null || filename.isEmpty()) {
            return filename;
        }
        if (filename.toLowerCase().endsWith(".amr")) {
            return filename.substring(0, filename.length() - 4) + ".mp3";
        }
        return filename + ".mp3";
    }
    
    /**
     * 检查 FFmpeg 是否可用
     * 
     * @return FFmpeg 是否可用
     */
    public boolean isAvailable() {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(ffmpegPath, "-version");
            Process process = processBuilder.start();
            int exitCode = process.waitFor();
            
            if (exitCode == 0) {
                logger.info("FFmpeg 检查通过，版本信息可正常获取");
                return true;
            } else {
                logger.warn("FFmpeg 检查失败，退出码: {}", exitCode);
                return false;
            }
        } catch (Exception e) {
            logger.warn("无法执行 FFmpeg，请确认已安装: {}", e.getMessage());
            return false;
        }
    }
}

