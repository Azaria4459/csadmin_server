package com.wechat.collector;

import com.wechat.collector.config.AppConfig;
import com.wechat.collector.config.DatabaseManager;
import com.wechat.collector.repository.ConversationRepository;
import com.wechat.collector.repository.MessageRepository;
import com.wechat.collector.repository.ResourceRepository;
import com.wechat.collector.repository.WechatMemberRepository;
import com.wechat.collector.service.*;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * 应用主启动类
 * 使用 Javalin 框架提供 Web 服务和 API 接口
 */
public class Application {
    private static final Logger logger = LoggerFactory.getLogger(Application.class);
    
    private static final String DEFAULT_CONFIG_PATH = "config/app.yml";
    private static final int DEFAULT_PORT = 7070;
    
    private AppConfig config;
    private DatabaseManager databaseManager;
    private WeChatSdkService weChatSdkService;
    private MessageCollectorService collectorService;
    private MessageRepository messageRepository;
    private ResourceRepository resourceRepository;
    private OssUploadService ossUploadService;
    private WechatSyncService wechatSyncService;
    private WechatSyncScheduler wechatSyncScheduler;
    private GroupChatSyncScheduler groupChatSyncScheduler;
    private GroupChatSyncService groupChatSyncService;
    private ConversationReminderScheduler conversationReminderScheduler;
    private SentimentAnalysisScheduler sentimentAnalysisScheduler;
    private SentimentAnalysisApiService sentimentAnalysisApiService;
    private SentimentTrendService sentimentTrendService;
    private AiService aiService;
    private ResponsibleUserStatisticsService responsibleUserStatisticsService;
    private ResponsibleUserStatisticsScheduler responsibleUserStatisticsScheduler;
    private MediaRetryService mediaRetryService;
    private ConversationPullLogService conversationPullLogService;
    private AutomsgSendService automsgSendService;
    private Javalin app;
    
    public static void main(String[] args) {
        Application application = new Application();
        
        try {
            application.start();
        } catch (Exception e) {
            logger.error("Failed to start application", e);
            System.exit(1);
        }
        
        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down application...");
            application.stop();
        }));
    }
    
    /**
     * 启动应用
     */
    public void start() throws Exception {
        logger.info("Starting WeChat Message Collector Application...");
        
        // 1. 加载配置文件
        loadConfig();
        
        // 2. 初始化数据库
        initDatabase();
        
        // 3. 初始化微信SDK
        initWeChatSdk();
        
        // 4. 启动消息收集服务
        startCollectorService();
        
        // 5. 初始化企业微信通讯录同步服务
        initWechatSyncService();
        
        // 6. 初始化群聊名称同步服务
        initGroupChatSyncService();
        
        // 7. 初始化会话提醒服务
        initConversationReminderService();
        
        // 8. 初始化情绪分析服务
        initSentimentAnalysisService();
        
        // 9. 初始化责任人统计服务
        initResponsibleUserStatisticsService();
        
        // 10. 启动Web服务
        startWebServer();
        
        logger.info("Application started successfully!");
        logger.info("正在监听: http://localhost:{}/health", DEFAULT_PORT);
    }
    
    /**
     * 停止应用
     */
    public void stop() {
        logger.info("Stopping application...");
        
        // 停止企业微信同步调度器
        if (wechatSyncScheduler != null) {
            wechatSyncScheduler.stop();
        }
        
        // 停止群聊同步调度器
        if (groupChatSyncScheduler != null) {
            groupChatSyncScheduler.stop();
        }
        
        // 停止会话提醒调度器
        if (conversationReminderScheduler != null) {
            conversationReminderScheduler.stop();
        }
        
        // 停止情绪分析调度器
        if (sentimentAnalysisScheduler != null) {
            sentimentAnalysisScheduler.stop();
        }
        
        // 停止责任人统计调度器
        if (responsibleUserStatisticsScheduler != null) {
            responsibleUserStatisticsScheduler.stop();
        }
        
        // 停止媒体重试服务
        if (mediaRetryService != null) {
            mediaRetryService.stop();
        }
        
        // 停止按会话拉取日志清理任务
        if (conversationPullLogService != null) {
            conversationPullLogService.stopTrimScheduler();
        }
        
        // 停止消息收集服务
        if (collectorService != null) {
            collectorService.stop();
        }
        
        // 停止Web服务
        if (app != null) {
            app.stop();
        }
        
        // 关闭OSS服务
        if (ossUploadService != null) {
            ossUploadService.shutdown();
        }
        
        // 销毁微信SDK
        if (weChatSdkService != null) {
            weChatSdkService.destroy();
        }
        
        // 关闭数据库连接池
        if (databaseManager != null) {
            databaseManager.shutdown();
        }
        
        logger.info("Application stopped");
    }
    
    /**
     * 加载配置文件
     */
    private void loadConfig() throws Exception {
        String configPath = System.getProperty("config.path", DEFAULT_CONFIG_PATH);
        
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            throw new Exception("Configuration file not found: " + configPath);
        }
        
        config = AppConfig.load(configPath);
        logger.info("Configuration loaded from: {}", configPath);
    }
    
    /**
     * 初始化数据库
     */
    private void initDatabase() {
        databaseManager = new DatabaseManager();
        databaseManager.initialize(config.getDatabase());
        
        messageRepository = new MessageRepository(databaseManager);
        resourceRepository = new ResourceRepository(databaseManager);
        logger.info("Database initialized");
    }
    
    /**
     * 初始化微信SDK和媒体处理服务
     */
    private void initWeChatSdk() {
        // 设置native库路径
        String libraryPath = System.getProperty("java.library.path");
        logger.info("java.library.path: {}", libraryPath);
        
        weChatSdkService = new WeChatSdkService(config.getWechat());
        weChatSdkService.initialize();
        logger.info("WeChat SDK initialized");
        
        // 初始化媒体处理服务
        if (config.getOss() != null && config.getOss().isEnable()) {
            logger.info("Initializing media process services...");
            
            // 创建OSS上传服务
            ossUploadService = new OssUploadService(config.getOss());
            
            // 创建媒体下载服务（需要SDK实例）
            MediaDownloadService downloadService = new MediaDownloadService(weChatSdkService.getSdk());
            
            // 创建音频转换服务（可选）
            AudioConversionService audioConversionService = null;
            if (config.getAudio() != null && config.getAudio().isConvertAmrToMp3()) {
                String ffmpegPath = config.getAudio().getFfmpegPath();
                audioConversionService = new AudioConversionService(ffmpegPath);
                
                // 检查 FFmpeg 是否可用
                if (audioConversionService.isAvailable()) {
                    logger.info("音频转换服务已启用 (FFmpeg: {})", 
                        ffmpegPath != null && !ffmpegPath.isEmpty() ? ffmpegPath : "系统PATH");
                } else {
                    logger.warn("FFmpeg 不可用，音频转换功能将被禁用");
                    logger.warn("请安装 FFmpeg: yum install -y ffmpeg (CentOS) 或 apt install -y ffmpeg (Ubuntu)");
                    audioConversionService = null;
                }
            } else {
                logger.info("音频转换功能未启用，语音消息将保持 AMR 格式");
            }
            
            // 创建媒体处理服务
            MediaProcessService mediaProcessService = new MediaProcessService(
                downloadService, 
                ossUploadService,
                audioConversionService,
                resourceRepository,  // 传入资源仓库
                messageRepository    // 传入消息仓库（用于更新resource_id）
            );
            
            // 创建媒体重试服务（用于异步重试失败的下载）
            this.mediaRetryService = new MediaRetryService(
                mediaProcessService,
                messageRepository
            );
            this.mediaRetryService.start();
            
            // 将重试服务设置到媒体处理服务中
            mediaProcessService.setRetryService(mediaRetryService);
            
            // 设置到SDK服务中
            weChatSdkService.setMediaProcessService(mediaProcessService);
            
            logger.info("Media process services initialized with retry support");
        } else {
            logger.info("OSS service is disabled, media files will not be uploaded");
        }
    }
    
    /**
     * 启动消息收集服务
     */
    private void startCollectorService() {
        collectorService = new MessageCollectorService(
            weChatSdkService,
            messageRepository,
            config
        );
        collectorService.start();
        logger.info("Message collector service started");
    }
    
    /**
     * 初始化企业微信通讯录同步服务
     */
    private void initWechatSyncService() {
        // 检查是否启用同步功能
        if (config.getWechatSync() == null || !config.getWechatSync().isEnable()) {
            logger.info("企业微信通讯录同步功能未启用");
            return;
        }
        
        logger.info("初始化企业微信通讯录同步服务...");
        
        try {
            // 创建API服务
            WechatContactApiService apiService = new WechatContactApiService(config.getWechatSync());
            
            // 创建成员仓库
            WechatMemberRepository memberRepository = new WechatMemberRepository(databaseManager);
            
            // 创建同步服务
            wechatSyncService = new WechatSyncService(apiService, memberRepository);
            
            // 创建定时任务调度器
            wechatSyncScheduler = new WechatSyncScheduler(wechatSyncService);
            
            // 启动定时任务
            wechatSyncScheduler.start();
            
            logger.info("企业微信通讯录同步服务初始化成功");
        } catch (Exception e) {
            logger.error("企业微信通讯录同步服务初始化失败", e);
        }
    }
    
    /**
     * 启动Web服务器
     */
    private void startWebServer() {
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
        }).start(DEFAULT_PORT);
        
        // 健康检查接口
        app.get("/health", this::handleHealth);
        
        // 重载配置接口
        app.post("/config/reload", this::handleConfigReload);
        
        // 手动触发收集接口
        app.post("/collect/trigger", this::handleTriggerCollection);
        
        // 统计信息接口
        app.get("/stats", this::handleStats);
        
        // 企业微信通讯录同步接口
        app.post("/wechat/sync/employees", this::handleSyncEmployees);
        app.post("/wechat/sync/contacts", this::handleSyncExternalContacts);
        
        // 群聊名称同步接口
        app.post("/wechat/sync/groupchats", this::handleSyncGroupChats);
        
        // 聊天记录媒体文件下载接口
        app.post("/chatrecord/download-media", this::handleDownloadChatRecordMedia);
        
        // 情绪分析接口（支持GET和POST）
        app.get("/sentiment/analyze", this::handleSentimentAnalysis);
        app.post("/sentiment/analyze", this::handleSentimentAnalysis);
        
        // 情绪波动数据接口
        app.get("/sentiment/trend", this::handleSentimentTrend);
        
        // 责任人统计接口（支持GET和POST）
        app.get("/responsible-statistics/trigger", this::handleTriggerResponsibleStatistics);
        app.post("/responsible-statistics/trigger", this::handleTriggerResponsibleStatistics);
        
        // 按 conversation_id 拉取会话消息（触发企业微信拉取并写日志）
        app.post("/conversation/pull", this::handleConversationPull);
        
        // 云手机 ADB 微信发消息（传入联系人和消息，调用 Python 脚本发送）
        app.post("/wechat/automsg/send", this::handleAutomsgSend);
        app.get("/wechat/automsg/send", this::handleAutomsgSend);
        if (config.getAutomsg() != null && config.getAutomsg().isEnable()) {
            automsgSendService = new AutomsgSendService(config.getAutomsg());
            logger.info("automsg 接口已启用，ADB: {}", config.getAutomsg().getRemoteAdb());
        } else {
            automsgSendService = null;
        }
        
        // 初始化按会话拉取日志服务并启动 10 天保留清理任务
        conversationPullLogService = new ConversationPullLogService();
        conversationPullLogService.startTrimScheduler();
        
        logger.info("Web server started on port {}", DEFAULT_PORT);
    }
    
    /**
     * 健康检查处理器
     */
    private void handleHealth(Context ctx) {
        // 简单请求日志：方法/路径/IP/查询参数
        try {
            logger.info("[HEALTH] method={}, path={}, ip={}, queryParams={}",
                ctx.method(),
                ctx.path(),
                ctx.ip(),
                ctx.queryParamMap()
            );
        } catch (Exception ignored) {}

        Map<String, Object> response = new HashMap<>();
        response.put("status", "UP");
        response.put("collector_running", collectorService.isRunning());
        response.put("timestamp", System.currentTimeMillis());
        
        ctx.json(response);
    }
    
    /**
     * 重载配置处理器
     */
    private void handleConfigReload(Context ctx) {
        try {
            logger.info("Reloading configuration...");
            
            // 停止收集服务
            collectorService.stop();
            
            // 重新加载配置
            loadConfig();
            
            // 重启收集服务
            collectorService = new MessageCollectorService(
                weChatSdkService,
                messageRepository,
                config
            );
            collectorService.start();
            
            // 重新创建 automsg 服务，使 automsg 配置（如 searchButtonY）重载后生效
            if (config.getAutomsg() != null && config.getAutomsg().isEnable()) {
                automsgSendService = new AutomsgSendService(config.getAutomsg());
                logger.info("automsg 配置已重载，ADB: {}", config.getAutomsg().getRemoteAdb());
            } else {
                automsgSendService = null;
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Configuration reloaded successfully");
            
            ctx.json(response);
            logger.info("Configuration reloaded successfully");
            
        } catch (Exception e) {
            logger.error("Failed to reload configuration", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "Failed to reload configuration: " + e.getMessage());
            
            ctx.status(500).json(response);
        }
    }
    
    /**
     * 手动触发收集处理器
     */
    private void handleTriggerCollection(Context ctx) {
        collectorService.triggerCollection();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Collection triggered");
        
        ctx.json(response);
    }
    
    /**
     * 统计信息处理器
     */
    private void handleStats(Context ctx) {
        Map<String, Object> response = new HashMap<>();
        response.put("total_messages", messageRepository.count());
        response.put("max_seq", messageRepository.getMaxSeq());
        response.put("collector_running", collectorService.isRunning());
        
        ConversationRepository conversationRepo = messageRepository.getConversationRepository();
        response.put("total_conversations", conversationRepo.getConversationCount());
        
        ctx.json(response);
    }
    
    /**
     * 手动同步企业员工信息处理器
     */
    private void handleSyncEmployees(Context ctx) {
        logger.info("收到手动同步员工信息请求");
        
        if (wechatSyncService == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "企业微信通讯录同步服务未启用");
            ctx.status(400).json(response);
            return;
        }
        
        try {
            // 执行同步（异步）
            new Thread(() -> {
                try {
                    WechatSyncService.SyncResult result = wechatSyncService.syncEmployees();
                    logger.info("手动同步员工信息完成: {}", result);
                } catch (Exception e) {
                    logger.error("手动同步员工信息失败", e);
                }
            }).start();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "员工信息同步已启动");
            ctx.json(response);
            
        } catch (Exception e) {
            logger.error("启动员工同步失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "同步失败: " + e.getMessage());
            ctx.status(500).json(response);
        }
    }
    
    /**
     * 手动同步外部联系人信息处理器
     */
    private void handleSyncExternalContacts(Context ctx) {
        logger.info("收到手动同步外部联系人信息请求");
        
        if (wechatSyncService == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "企业微信通讯录同步服务未启用");
            ctx.status(400).json(response);
            return;
        }
        
        try {
            // 执行同步（异步）
            new Thread(() -> {
                try {
                    WechatSyncService.SyncResult result = wechatSyncService.syncExternalContacts();
                    logger.info("手动同步外部联系人信息完成: {}", result);
                } catch (Exception e) {
                    logger.error("手动同步外部联系人信息失败", e);
                }
            }).start();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "外部联系人信息同步已启动");
            ctx.json(response);
            
        } catch (Exception e) {
            logger.error("启动外部联系人同步失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "同步失败: " + e.getMessage());
            ctx.status(500).json(response);
        }
    }
    
    /**
     * 初始化会话提醒服务
     */
    private void initConversationReminderService() {
        // 检查是否启用飞书提醒功能
        if (config.getFeishu() == null || !config.getFeishu().isEnable()) {
            logger.info("飞书提醒功能未启用");
            return;
        }
        
        logger.info("初始化会话提醒服务...");
        
        try {
            // 创建飞书服务
            com.wechat.collector.service.FeishuService feishuService = new com.wechat.collector.service.FeishuService(
                config.getFeishu().getHost(),
                config.getFeishu().getAppId(),
                config.getFeishu().getAppSecret()
            );
            
            // 创建提醒服务
            com.wechat.collector.service.ConversationReminderService reminderService = 
                new com.wechat.collector.service.ConversationReminderService(databaseManager, feishuService);
            
            // 创建定时任务调度器
            conversationReminderScheduler = new com.wechat.collector.service.ConversationReminderScheduler(reminderService);
            
            // 启动定时任务
            conversationReminderScheduler.start();
            
            logger.info("会话提醒服务初始化成功");
        } catch (Exception e) {
            logger.error("会话提醒服务初始化失败", e);
        }
    }
    
    /**
     * 初始化情绪分析服务
     * 注意：不再自动启动定时任务，改为通过API接口手动触发
     */
    /**
     * 初始化情绪分析服务
     * 根据配置选择使用 Gemini 或 Deepseek
     */
    private void initSentimentAnalysisService() {
        // 检查是否启用情绪分析功能
        if (config.getAi() == null || !config.getAi().isEnable()) {
            logger.info("情绪分析功能未启用");
            return;
        }
        
        logger.info("初始化情绪分析服务...");
        
        try {
            String aiType = config.getAi().getType();
            if (aiType == null || aiType.isEmpty()) {
                aiType = "gemini"; // 默认使用 Gemini
            }
            
            // 根据配置类型创建对应的AI服务
            if ("deepseek".equalsIgnoreCase(aiType)) {
                // 使用 Deepseek
                if (config.getAi().getDeepseek() == null || config.getAi().getDeepseek().getApiKey() == null) {
                    logger.error("Deepseek API Key 未配置");
                    return;
                }
                aiService = new DeepseekService(config.getAi().getDeepseek().getApiKey());
                logger.info("使用 Deepseek AI 服务");
            } else {
                // 默认使用 Gemini
                if (config.getAi().getGemini() == null || config.getAi().getGemini().getApiKey() == null) {
                    logger.error("Gemini API Key 未配置");
                    return;
                }
                aiService = new GeminiService(config.getAi().getGemini().getApiKey());
                logger.info("使用 Gemini AI 服务");
            }
            
            // 创建情绪分析API服务（用于按日期分析）
            sentimentAnalysisApiService = new SentimentAnalysisApiService(databaseManager, aiService);
            
            // 创建情绪波动数据服务
            sentimentTrendService = new SentimentTrendService(databaseManager);
            
            // 创建并启动新的情绪分析定时任务（V2版本）
            SentimentAnalysisSchedulerV2 sentimentAnalysisSchedulerV2 = 
                new SentimentAnalysisSchedulerV2(databaseManager, aiService, config.getAi());
            sentimentAnalysisSchedulerV2.start();
            logger.info("情绪分析定时任务V2已启动");
            
            logger.info("情绪分析服务初始化成功（使用{}，定时任务已启用）", aiService.getServiceName());
        } catch (Exception e) {
            logger.error("情绪分析服务初始化失败", e);
        }
    }
    
    /**
     * 初始化责任人统计服务
     */
    private void initResponsibleUserStatisticsService() {
        logger.info("初始化责任人统计服务...");
        
        try {
            // 创建统计服务
            responsibleUserStatisticsService = new ResponsibleUserStatisticsService(databaseManager);
            
            // 创建定时任务调度器（每天凌晨2点执行）
            responsibleUserStatisticsScheduler = new ResponsibleUserStatisticsScheduler(
                responsibleUserStatisticsService,
                2,
                0
            );
            
            // 启动定时任务
            responsibleUserStatisticsScheduler.start();
            
            logger.info("责任人统计服务初始化成功");
        } catch (Exception e) {
            logger.error("责任人统计服务初始化失败", e);
        }
    }
    
    /**
     * 初始化群聊名称同步服务
     */
    private void initGroupChatSyncService() {
        logger.info("初始化群聊名称同步服务...");
        
        try {
            // 获取企业微信联系人API服务（复用通讯录同步服务的API）
            if (config.getWechatSync() == null || !config.getWechatSync().isEnable()) {
                logger.warn("企业微信通讯录同步功能未启用，无法初始化群聊同步服务");
                return;
            }
            
            WechatContactApiService apiService = new WechatContactApiService(config.getWechatSync());
            
            // 创建会话仓库
            ConversationRepository conversationRepository = new ConversationRepository(databaseManager);
            
            // 创建企业微信联系人服务（用于获取access_token）
            WeChatContactService contactService = new WeChatContactService(apiService);
            
            // 创建群聊同步服务
            groupChatSyncService = new GroupChatSyncService(conversationRepository, contactService);
            
            // 创建定时任务调度器（每天凌晨1点执行）
            groupChatSyncScheduler = new GroupChatSyncScheduler(groupChatSyncService, 1, 0);
            
            // 启动定时任务
            groupChatSyncScheduler.start();
            
            logger.info("群聊名称同步服务初始化成功");
        } catch (Exception e) {
            logger.error("群聊名称同步服务初始化失败", e);
        }
    }
    
    /**
     * 手动同步群聊名称处理器
     */
    private void handleSyncGroupChats(Context ctx) {
        logger.info("收到手动同步群聊名称请求");
        
        if (groupChatSyncService == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "群聊名称同步服务未启用");
            ctx.status(400).json(response);
            return;
        }
        
        try {
            // 执行同步（异步）
            new Thread(() -> {
                try {
                    GroupChatSyncService.SyncResult result = groupChatSyncService.syncAllGroupChats();
                    logger.info("手动同步群聊名称完成: {}", result);
                } catch (Exception e) {
                    logger.error("手动同步群聊名称失败", e);
                }
            }).start();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "群聊名称同步已启动");
            ctx.json(response);
            
        } catch (Exception e) {
            logger.error("启动群聊名称同步失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "同步失败: " + e.getMessage());
            ctx.status(500).json(response);
        }
    }
    
    /**
     * 情绪分析接口处理器
     * 
     * 请求参数：
     * - date: 分析日期，格式：yyyy/MM/dd 或 yyyy-MM-dd（必填）
     * - test: 测试模式，传1表示测试模式（可选）
     * 
     * 测试模式：只分析少量记录，并返回Gemini的原始JSON响应
     * 正常模式：分析指定日期的所有群聊记录
     * 
     * 返回：
     * - success: 是否成功
     * - message: 提示信息
     * - date: 分析日期
     * - testMode: 是否为测试模式
     * - totalConversations: 总会话数
     * - successCount: 成功数
     * - failureCount: 失败数
     * - testResults: 测试模式下的详细结果（包含Gemini的JSON响应）
     */
    private void handleSentimentAnalysis(Context ctx) {
        // 记录请求日志：方法/路径/IP/查询参数
        try {
            logger.info("[SENTIMENT_ANALYZE] method={}, path={}, ip={}, queryParams={}",
                ctx.method(),
                ctx.path(),
                ctx.ip(),
                ctx.queryParamMap()
            );
        } catch (Exception e) {
            logger.warn("记录请求日志失败", e);
        }
        
        if (sentimentAnalysisApiService == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "情绪分析服务未启用");
            ctx.status(400).json(response);
            return;
        }
        
        try {
            // 获取参数（支持GET和POST）
            String date = null;
            boolean testMode = false;
            
            // 尝试从查询参数获取
            date = ctx.queryParam("date");
            String testParam = ctx.queryParam("test");
            if ("1".equals(testParam) || "true".equalsIgnoreCase(testParam)) {
                testMode = true;
            }
            
            // 如果查询参数没有，尝试从POST body获取
            if (date == null || date.isEmpty()) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = ctx.bodyAsClass(Map.class);
                    if (body != null) {
                        Object dateObj = body.get("date");
                        if (dateObj != null) {
                            date = dateObj.toString();
                        }
                        Object testObj = body.get("test");
                        if (testObj != null) {
                            testMode = "1".equals(testObj.toString()) || "true".equalsIgnoreCase(testObj.toString());
                        }
                    }
                } catch (Exception e) {
                    // POST body解析失败，忽略
                }
            }
            
            // 验证参数
            if (date == null || date.isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "date参数不能为空，格式：yyyy/MM/dd 或 yyyy-MM-dd");
                ctx.status(400).json(response);
                return;
            }
            
            logger.info("开始情绪分析: date={}, testMode={}", date, testMode);
            
            // 执行分析（同步执行，因为需要返回结果）
            SentimentAnalysisApiService.AnalysisResult result = sentimentAnalysisApiService.analyzeByDate(date, testMode);
            
            // 构建响应
            Map<String, Object> response = new HashMap<>();
            response.put("success", result.isSuccess());
            response.put("date", result.getDate());
            response.put("testMode", result.isTestMode());
            response.put("totalConversations", result.getTotalConversations());
            response.put("successCount", result.getSuccessCount());
            response.put("failureCount", result.getFailureCount());
            
            if (result.getErrorMessage() != null) {
                response.put("message", result.getErrorMessage());
            } else {
                response.put("message", String.format("分析完成：总数=%d, 成功=%d, 失败=%d", 
                    result.getTotalConversations(), result.getSuccessCount(), result.getFailureCount()));
            }
            
            // 测试模式下返回详细结果
            if (testMode && !result.getTestResults().isEmpty()) {
                response.put("testResults", result.getTestResults());
            }
            
            ctx.json(response);
            
        } catch (Exception e) {
            logger.error("情绪分析请求处理失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "分析失败: " + e.getMessage());
            ctx.status(500).json(response);
        }
    }
    
    /**
     * 获取情绪波动数据接口处理器
     * 
     * 查询参数：
     * - conversationId: 会话ID（必填）
     * - startDate: 开始日期，格式：yyyy-MM-dd（可选，默认最近7天）
     * - endDate: 结束日期，格式：yyyy-MM-dd（可选，默认今天）
     * 
     * 返回：
     * - success: 是否成功
     * - data: 情绪波动数据列表
     */
    private void handleSentimentTrend(Context ctx) {
        try {
            String conversationId = ctx.queryParam("conversationId");
            if (conversationId == null || conversationId.isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "conversationId参数必填");
                ctx.status(400).json(response);
                return;
            }
            
            if (sentimentTrendService == null) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "情绪波动服务未初始化");
                ctx.status(500).json(response);
                return;
            }
            
            // 解析日期参数
            java.time.LocalDate endDate = java.time.LocalDate.now();
            java.time.LocalDate startDate = endDate.minusDays(7); // 默认最近7天
            
            String startDateStr = ctx.queryParam("startDate");
            String endDateStr = ctx.queryParam("endDate");
            
            if (startDateStr != null && !startDateStr.isEmpty()) {
                try {
                    startDate = java.time.LocalDate.parse(startDateStr);
                } catch (Exception e) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", false);
                    response.put("message", "startDate格式错误，请使用yyyy-MM-dd格式");
                    ctx.status(400).json(response);
                    return;
                }
            }
            
            if (endDateStr != null && !endDateStr.isEmpty()) {
                try {
                    endDate = java.time.LocalDate.parse(endDateStr);
                } catch (Exception e) {
                    Map<String, Object> response = new HashMap<>();
                    response.put("success", false);
                    response.put("message", "endDate格式错误，请使用yyyy-MM-dd格式");
                    ctx.status(400).json(response);
                    return;
                }
            }
            
            // 获取情绪波动数据
            Map<String, Object> result = sentimentTrendService.getSentimentTrendMap(conversationId, startDate, endDate);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", result);
            
            ctx.json(response);
            
        } catch (Exception e) {
            logger.error("获取情绪波动数据失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "获取数据失败: " + e.getMessage());
            ctx.status(500).json(response);
        }
    }
    
    /**
     * 手动触发责任人统计接口处理器
     * 
     * 返回：
     * - success: 是否成功
     * - message: 提示信息
     */
    private void handleTriggerResponsibleStatistics(Context ctx) {
        logger.info("收到手动触发责任人统计请求");
        
        if (responsibleUserStatisticsScheduler == null || !responsibleUserStatisticsScheduler.isRunning()) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "责任人统计调度器未运行");
            ctx.status(400).json(response);
            return;
        }
        
        try {
            // 手动触发统计（异步执行）
            responsibleUserStatisticsScheduler.triggerStatistics();
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "责任人统计任务已启动");
            ctx.json(response);
            
        } catch (Exception e) {
            logger.error("手动触发责任人统计失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "触发失败: " + e.getMessage());
            ctx.status(500).json(response);
        }
    }
    
    /**
     * 按 conversation_id 拉取会话消息接口处理器
     *
     * 根据 conversation_id 同步执行一次从企业微信拉取消息（全局增量拉取，该会话的新消息在范围内会一并入库），
     * 等待拉取完成后再返回，并将请求记录写入 logs/conversation/{conversation_id}.log，保留 10 天。
     *
     * 请求参数：
     * - conversationId: 会话 ID（必填，可通过查询参数或 JSON body 传递）
     * - seq: 起始 seq（可选，可通过查询参数或 JSON body 传递，不传则使用全局最大 seq）
     *
     * 示例：
     * - GET: /conversation/pull?conversationId=xxx&seq=9744
     * - POST: { "conversationId": "xxx", "seq": 9744 }
     *
     * 返回：
     * - success: 是否成功
     * - message: 提示信息
     * - conversationId: 会话 ID
     * - savedCount: 本次新增入库消息条数
     * - seq: 实际使用的 seq
     */
    private void handleConversationPull(Context ctx) {
        String conversationId = null;
        Long customSeq = null;
        try {
            // 从查询参数获取
            conversationId = ctx.queryParam("conversationId");
            String seqParam = ctx.queryParam("seq");
            
            // 如果查询参数中没有，尝试从 JSON body 获取
            if (conversationId == null || conversationId.trim().isEmpty() || seqParam == null) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = ctx.bodyAsClass(Map.class);
                    if (body != null) {
                        if (conversationId == null || conversationId.trim().isEmpty()) {
                            Object convIdObj = body.get("conversationId");
                            if (convIdObj != null) {
                                conversationId = String.valueOf(convIdObj).trim();
                            }
                        }
                        if (seqParam == null) {
                            Object seqObj = body.get("seq");
                            if (seqObj != null) {
                                try {
                                    customSeq = Long.parseLong(String.valueOf(seqObj));
                                } catch (NumberFormatException e) {
                                    logger.warn("Invalid seq parameter: {}", seqObj);
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            } else {
                conversationId = conversationId.trim();
            }
            
            // 解析查询参数中的 seq
            if (seqParam != null && customSeq == null) {
                try {
                    customSeq = Long.parseLong(seqParam.trim());
                } catch (NumberFormatException e) {
                    logger.warn("Invalid seq query parameter: {}", seqParam);
                }
            }
        } catch (Exception e) {
            logger.warn("解析 conversation/pull 请求参数失败", e);
        }

        if (conversationId == null || conversationId.isEmpty()) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "conversationId 不能为空，请通过查询参数或 JSON body 传递");
            ctx.status(400).json(response);
            return;
        }

        String clientIp = ctx.ip();
        Map<String, Object> requestParams = new HashMap<>();
        requestParams.put("conversationId", conversationId);
        if (customSeq != null) {
            requestParams.put("seq", customSeq);
        }
        if (conversationPullLogService != null) {
            String startMsg = customSeq != null 
                    ? ("Pull triggered with custom seq: " + customSeq)
                    : "Pull triggered";
            conversationPullLogService.log(conversationId, clientIp, "start", startMsg, requestParams, null);
        }

        try {
            CollectionResult collectionResult = collectorService.triggerCollectionAndWaitWithResult(90, customSeq);
            int savedCount = collectionResult.getSavedCount();
            String resultMsg = savedCount > 0
                    ? ("Collection completed, " + savedCount + " new messages saved")
                    : "Collection completed, no new messages";
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", savedCount > 0
                    ? ("拉取完成，本次新增 " + savedCount + " 条消息")
                    : "拉取完成，本次无新消息");
            response.put("conversationId", conversationId);
            response.put("savedCount", savedCount);
            
            // 在日志中记录微信接口的请求参数和返回内容
            Map<String, Object> wechatRequest = collectionResult.getWechatRequest();
            String wechatResponse = collectionResult.getWechatResponse();
            if (wechatRequest != null && wechatRequest.containsKey("seq")) {
                response.put("seq", wechatRequest.get("seq"));
            }
            
            if (conversationPullLogService != null) {
                // 将微信接口的请求参数和返回内容也加入到日志中
                Map<String, Object> logRequestParams = new HashMap<>(requestParams);
                if (wechatRequest != null) {
                    logRequestParams.put("wechatRequest", wechatRequest);
                }
                Map<String, Object> logResponse = new HashMap<>(response);
                if (wechatResponse != null) {
                    logResponse.put("wechatResponse", wechatResponse);
                }
                conversationPullLogService.log(conversationId, clientIp, "success", resultMsg, logRequestParams, logResponse);
            }
            ctx.json(response);
        } catch (java.util.concurrent.TimeoutException e) {
            logger.error("按 conversation_id 拉取超时: conversationId={}", conversationId, e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "拉取超时（90秒），请稍后重试");
            response.put("conversationId", conversationId);
            if (conversationPullLogService != null) {
                conversationPullLogService.log(conversationId, clientIp, "error", "Collection timeout (90s)", requestParams, response);
            }
            ctx.status(504).json(response);
        } catch (Exception e) {
            logger.error("按 conversation_id 触发拉取失败: conversationId={}", conversationId, e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "触发拉取失败: " + e.getMessage());
            response.put("conversationId", conversationId);
            if (conversationPullLogService != null) {
                conversationPullLogService.log(conversationId, clientIp, "error", "Trigger failed: " + e.getMessage(), requestParams, response);
            }
            ctx.status(500).json(response);
        }
    }
    
    /**
     * 云手机 ADB 微信发消息接口处理器。
     * 传入联系人和消息内容，调用 automsg Python 脚本通过 ADB 在云手机微信中发送消息。
     *
     * 请求参数（支持 GET 查询参数或 POST JSON body）：
     * - contact: 联系人名称（微信显示名），必填
     * - message: 要发送的消息内容，必填
     *
     * 返回：success、message
     */
    private void handleAutomsgSend(Context ctx) {
        try {
            logger.info("[AUTOMSG_SEND] method={}, path={}, ip={}", ctx.method(), ctx.path(), ctx.ip());
        } catch (Exception ignored) {}
        
        if (automsgSendService == null) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "automsg 功能未启用，请在 config/app.yml 中配置 automsg.enable=true 及 automsg.remoteAdb");
            ctx.status(400).json(response);
            return;
        }
        
        String contact = ctx.queryParam("contact");
        String message = ctx.queryParam("message");
        
        if ((contact == null || contact.isEmpty()) || (message == null || message.isEmpty())) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                if (body != null) {
                    if (contact == null || contact.isEmpty()) {
                        Object c = body.get("contact");
                        if (c != null) contact = c.toString();
                    }
                    if (message == null || message.isEmpty()) {
                        Object m = body.get("message");
                        if (m != null) message = m.toString();
                    }
                }
            } catch (Exception ignored) {}
        }
        
        if (contact == null || contact.trim().isEmpty()) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "参数 contact（联系人）不能为空");
            ctx.status(400).json(response);
            return;
        }
        if (message == null || message.trim().isEmpty()) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "参数 message（消息内容）不能为空");
            ctx.status(400).json(response);
            return;
        }
        
        AutomsgSendService.SendResult result = automsgSendService.send(contact.trim(), message.trim());
        Map<String, Object> response = new HashMap<>();
        response.put("success", result.isSuccess());
        response.put("message", result.getMessage());
        if (result.isSuccess()) {
            ctx.json(response);
        } else {
            ctx.status(500).json(response);
        }
    }
    
    /**
     * 下载聊天记录媒体文件处理器
     * 先检查资源表是否已存在，如果存在则直接返回，避免重复下载
     * 
     * 请求参数（JSON）：
     * - sdkfileid: 企业微信SDK文件ID（必填）
     * - md5sum: 文件MD5值（可选，用于去重）
     * - fileType: 文件类型（image/voice/video/file）
     * - fileExtension: 文件扩展名（jpg/png/amr/mp4等，可选）
     * 
     * 返回：
     * - success: 是否成功
     * - url: OSS访问URL
     * - message: 提示信息
     * - fromCache: 是否来自缓存
     * - resourceId: 资源ID
     */
    private void handleDownloadChatRecordMedia(Context ctx) {
        logger.info("收到聊天记录媒体文件下载请求");
        
        try {
            // 解析请求参数
            @SuppressWarnings("unchecked")
            Map<String, Object> params = ctx.bodyAsClass(Map.class);
            String sdkFileId = (String) params.get("sdkfileid");
            String md5sum = (String) params.get("md5sum");
            String fileType = (String) params.getOrDefault("fileType", "image");
            String fileExtension = (String) params.get("fileExtension");
            
            if (sdkFileId == null || sdkFileId.isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "sdkfileid 参数不能为空");
                ctx.status(400).json(response);
                return;
            }
            
            // 1. 先检查资源表是否已存在该资源
            logger.info("检查资源是否已存在: md5sum={}, sdkfileid length={}", 
                md5sum, sdkFileId.length());
            
            ResourceRepository.Resource existingResource = resourceRepository.findByIdentifier(md5sum, sdkFileId);
            
            if (existingResource != null) {
                // 资源已存在，直接返回
                logger.info("资源已存在，直接复用: resource_id={}, url={}", 
                    existingResource.getId(), existingResource.getOssUrl());
                
                // 增加引用计数
                resourceRepository.incrementDownloadCount(existingResource.getId());
                
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("url", existingResource.getOssUrl());
                response.put("message", "资源已存在，直接复用");
                response.put("fromCache", true);
                response.put("resourceId", existingResource.getId());
                response.put("fileSize", existingResource.getFileSize());
                
                ctx.json(response);
                return;
            }
            
            // 2. 资源不存在，继续下载流程
            logger.info("资源不存在，开始下载...");
            
            
            // 检查OSS服务是否可用
            if (ossUploadService == null || !ossUploadService.isAvailable()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "OSS服务未启用或不可用");
                ctx.status(503).json(response);
                return;
            }
            
            // 创建媒体下载服务
            MediaDownloadService downloadService = new MediaDownloadService(weChatSdkService.getSdk());
            
            // 下载媒体文件
            logger.info("开始下载媒体文件: sdkFileId={}, fileType={}", sdkFileId, fileType);
            byte[] mediaData = downloadService.downloadMedia(sdkFileId);
            
            if (mediaData == null || mediaData.length == 0) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "下载媒体文件失败");
                ctx.status(500).json(response);
                return;
            }
            
            logger.info("媒体文件下载成功，大小: {} bytes", mediaData.length);
            
            // 确定文件扩展名
            String extension = fileExtension;
            if (extension == null || extension.isEmpty()) {
                // 根据文件类型设置默认扩展名
                extension = getDefaultExtension(fileType);
            }
            
            // 上传到OSS
            logger.info("开始上传到OSS: fileType={}, extension={}", fileType, extension);
            String ossUrl = ossUploadService.upload(mediaData, extension, fileType);
            
            if (ossUrl == null || ossUrl.isEmpty()) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "上传到OSS失败");
                ctx.status(500).json(response);
                return;
            }
            
            logger.info("媒体文件上传成功: {}", ossUrl);
            
            // 返回成功响应
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("url", ossUrl);
            response.put("message", "媒体文件下载并上传成功");
            response.put("fromCache", false);
            response.put("resourceId", null);  // 新资源，PHP端会创建resource记录
            response.put("fileSize", mediaData.length);
            
            ctx.json(response);
            
        } catch (Exception e) {
            logger.error("处理聊天记录媒体文件下载失败", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "处理失败: " + e.getMessage());
            ctx.status(500).json(response);
        }
    }
    
    /**
     * 根据文件类型获取默认扩展名
     * 
     * @param fileType 文件类型
     * @return 默认扩展名
     */
    private String getDefaultExtension(String fileType) {
        switch (fileType.toLowerCase()) {
            case "image":
                return "jpg";
            case "voice":
                return "amr";
            case "video":
                return "mp4";
            case "file":
                return "bin";
            default:
                return "dat";
        }
    }
    
    // 会话相关处理器已移除
}

