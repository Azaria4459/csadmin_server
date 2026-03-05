package com.wechat.collector.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

/**
 * 应用配置类
 * 从 YAML 文件读取配置信息
 */
@Data
public class AppConfig {
    private static final Logger logger = LoggerFactory.getLogger(AppConfig.class);
    
    private WeChatConfig wechat;
    private DatabaseConfig database;
    private LogConfig log;
    private OssConfig oss;
    private AudioConfig audio;
    private WechatSyncConfig wechatSync;
    private FeishuConfig feishu;
    private AiConfig ai;
    private AutomsgConfig automsg;
    
    /**
     * 从指定路径加载配置文件
     */
    public static AppConfig load(String configPath) throws IOException {
        logger.info("Loading configuration from: {}", configPath);
        
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            throw new IOException("Configuration file not found: " + configPath);
        }
        
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        AppConfig config = mapper.readValue(configFile, AppConfig.class);
        
        logger.info("Configuration loaded successfully");
        return config;
    }
    
    /**
     * 微信配置
     */
    @Data
    public static class WeChatConfig {
        private String corpid;
        private String secret;
        private String privateKeyPath;
        private int publickeyVer;
        private int pullLimit = 100;
    }
    
    /**
     * 数据库配置
     */
    @Data
    public static class DatabaseConfig {
        private String host = "127.0.0.1";
        private int port = 3306;
        private String user;
        private String password;
        private String name;
        
        /**
         * 获取 JDBC URL
         */
        public String getJdbcUrl() {
            return String.format("jdbc:mysql://%s:%d/%s?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true",
                    host, port, name);
        }
    }
    
    /**
     * 日志配置
     */
    @Data
    public static class LogConfig {
        private boolean enable = true;
        private String level = "info";
    }
    
    /**
     * 阿里云OSS配置
     */
    @Data
    public static class OssConfig {
        private boolean enable = true;
        private String accessKeyId;
        private String accessKeySecret;
        private String endpoint;
        private String bucket;
        private String domain;
        private String directory = "csapi";
    }
    
    /**
     * 音频转换配置
     */
    @Data
    public static class AudioConfig {
        /** 是否启用 AMR 转 MP3 */
        private boolean convertAmrToMp3 = true;
        /** FFmpeg 可执行文件路径（null 或空则使用系统 PATH） */
        private String ffmpegPath;
    }
    
    /**
     * 企业微信通讯录同步配置
     */
    @Data
    public static class WechatSyncConfig {
        /** 是否启用同步功能 */
        private boolean enable = true;
        /** 企业ID */
        private String corpid;
        /** 通讯录同步应用的Secret */
        private String contactSecret;
        /** 员工同步定时任务Cron表达式 */
        private String cronEmployee = "0 0 0 * * ?";
        /** 外部联系人同步定时任务Cron表达式 */
        private String cronExternalContact = "0 30 0 * * ?";
    }
    
    /**
     * 飞书配置
     */
    @Data
    public static class FeishuConfig {
        /** 是否启用飞书提醒功能 */
        private boolean enable = false;
        /** 飞书API地址 */
        private String host = "https://open.feishu.cn";
        /** 应用ID */
        private String appId;
        /** 应用密钥 */
        private String appSecret;
    }
    
    /**
     * AI 配置（支持多个AI服务提供商）
     */
    @Data
    public static class AiConfig {
        /** 
         * 是否启用情绪分析功能
         * true: 启用情绪分析（定时任务和API接口都会工作）
         * false: 禁用情绪分析（不进行情绪分析，定时任务不会启动，API接口会返回错误）
         */
        private boolean enable = false;
        /** AI服务类型：gemini 或 deepseek */
        private String type = "gemini";
        /** Gemini配置 */
        private GeminiAiConfig gemini;
        /** Deepseek配置 */
        private DeepseekAiConfig deepseek;
    }
    
    /**
     * Gemini AI 配置
     */
    @Data
    public static class GeminiAiConfig {
        /** Gemini API密钥 */
        private String apiKey;
    }
    
    /**
     * Deepseek AI 配置
     */
    @Data
    public static class DeepseekAiConfig {
        /** Deepseek API密钥 */
        private String apiKey;
    }

    /**
     * 云手机 ADB 微信发消息（automsg）配置：Java 直接调用 adb 命令，不依赖 Python。
     * 所有坐标、等待时间等运行时配置统一在 config/app.yml 的 automsg 节点下维护，
     * 此处仅作结构定义，不写死默认值；修改配置请只改 app.yml，改完后可调用 POST /config/reload 生效。
     */
    @Data
    public static class AutomsgConfig {
        /** 是否启用 automsg 接口 */
        private boolean enable = false;
        /** adb 可执行文件路径，为空则使用系统 PATH 中的 adb */
        private String adbPath;
        /** 云手机 ADB 地址，格式 host:port */
        private String remoteAdb;
        /** 微信包名 */
        private String wechatPackage;
        /** 主界面搜索按钮坐标 (x, y)，在 app.yml 中配置 */
        private int searchButtonX;
        private int searchButtonY;
        /** 搜索页输入框坐标 (x, y)，在 app.yml 中配置 */
        private int searchInputX;
        private int searchInputY;
        /** 搜索结果第一个联系人坐标 (x, y)，在 app.yml 中配置 */
        private int contactX;
        private int contactY;
        /** 聊天页输入框坐标 (x, y)，在 app.yml 中配置 */
        private int chatInputX;
        private int chatInputY;
        /** 聊天页发送按钮坐标 (x, y)，在 app.yml 中配置 */
        private int sendButtonX;
        private int sendButtonY;
        /** 各步骤等待时间（秒），在 app.yml 中配置 */
        private double waitAfterLaunch;
        private double waitAfterSearchTap;
        private double waitAfterInputSearch;
        private double waitAfterTapChat;
        private double waitAfterInputMsg;
        /** 每次发送请求生成一个日志文件的目录，为空则不写文件；如 logs/automsg */
        private String requestLogDir;
    }
}

