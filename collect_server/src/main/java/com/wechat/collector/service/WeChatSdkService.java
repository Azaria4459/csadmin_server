package com.wechat.collector.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wework.Finance;
import com.wechat.collector.config.AppConfig;
import com.wechat.collector.model.WeChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 企业微信 SDK 服务
 * 
 * 功能描述：
 * 1. 初始化企业微信SDK：加载native库，初始化SDK实例
 * 2. 拉取聊天数据：从企业微信服务器拉取加密的聊天消息
 * 3. 消息解密：使用RSA私钥解密消息内容
 * 4. 消息解析：将解密后的JSON解析为 WeChatMessage 对象
 * 5. 支持增量拉取：基于seq顺序号，只拉取新消息
 * 6. 错误处理：处理解密失败、解析失败等异常情况
 * 
 * 使用场景：
 * - 作为消息收集的核心服务，为 MessageCollectorService 提供数据源
 * - 所有聊天消息的获取都依赖此服务
 * 
 * 依赖：
 * - 企业微信SDK：native库 libWeWorkFinanceSdk_Java.so
 * - RSA私钥：用于解密消息（PKCS1格式）
 * - 企业微信配置：corpId、secret、publickeyVer等
 * 
 * 配置：
 * - 通过 AppConfig.WeChatConfig 配置
 * - 需要配置私钥文件路径
 * - 需要配置公钥版本号
 */
public class WeChatSdkService {
    private static final Logger logger = LoggerFactory.getLogger(WeChatSdkService.class);
    
    private final AppConfig.WeChatConfig config;
    private final ObjectMapper objectMapper;
    private long sdk;
    private PrivateKey privateKey;
    private MediaProcessService mediaProcessService;
    private ExecutorService mediaProcessExecutor;
    
    public WeChatSdkService(AppConfig.WeChatConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
    }
    
    /**
     * 设置媒体处理服务
     * 必须在初始化SDK后调用
     */
    public void setMediaProcessService(MediaProcessService mediaProcessService) {
        this.mediaProcessService = mediaProcessService;
        // 创建媒体处理线程池（最多5个线程，避免阻塞消息解析）
        this.mediaProcessExecutor = Executors.newFixedThreadPool(5, r -> {
            Thread t = new Thread(r, "media-processor");
            t.setDaemon(true);
            return t;
        });
        logger.info("Media process service enabled with async executor");
    }
    
    /**
     * 获取SDK实例（用于媒体文件下载）
     */
    public long getSdk() {
        return this.sdk;
    }
    
    /**
     * 初始化 SDK
     * 初始化企业微信SDK并加载RSA私钥
     * 
     * @throws RuntimeException 如果初始化失败
     */
    public void initialize() {
        logger.info("Initializing WeChat SDK...");
        
        try {
            // 加载 RSA 私钥
            loadPrivateKey();
            
            // 创建 SDK 实例
            sdk = Finance.NewSdk();
            
            // 初始化 SDK
            int ret = Finance.Init(sdk, config.getCorpid(), config.getSecret());
            if (ret != 0) {
                throw new RuntimeException("Failed to initialize WeChat SDK, error code: " + ret);
            }
            
            logger.info("WeChat SDK initialized successfully");
            
        } catch (UnsatisfiedLinkError e) {
            logger.error("Failed to load native library. Please ensure libWeWorkFinanceSdk_Java.so is in java.library.path", e);
            throw new RuntimeException("Native library not found", e);
        } catch (Exception e) {
            logger.error("Failed to initialize WeChat SDK", e);
            throw new RuntimeException("WeChat SDK initialization failed", e);
        }
    }
    
    /**
     * 拉取聊天数据
     * 
     * @param seq 从指定的seq开始拉取消息
     * @param limit 一次拉取的消息条数
     * @return 解析后的消息列表
     */
    public List<WeChatMessage> fetchChatData(long seq, int limit) {
        FetchResult result = fetchChatDataWithResponse(seq, limit);
        return result.getMessages();
    }

    /**
     * 拉取聊天数据（包含原始 JSON 响应）
     *
     * @param seq 从指定的seq开始拉取消息
     * @param limit 一次拉取的消息条数
     * @return 包含消息列表和原始 JSON 响应的结果
     */
    public FetchResult fetchChatDataWithResponse(long seq, int limit) {
        logger.debug("Fetching chat data from seq: {}, limit: {}", seq, limit);
        
        List<WeChatMessage> messages = new ArrayList<>();
        long slice = 0;
        String rawJsonResponse = null;
        
        try {
            // 创建 Slice 用于接收返回数据
            slice = Finance.NewSlice();
            
            // 调用 SDK 获取聊天数据
            // proxy 和 passwd 设置为空字符串表示不使用代理
            int ret = Finance.GetChatData(sdk, seq, limit, "", "", 10, slice);
            
            if (ret != 0) {
                logger.error("Failed to fetch chat data, error code: {}", ret);
                return new FetchResult(ret, "SDK error code: " + ret);
            }
            
            // 获取返回的 JSON 数据
            rawJsonResponse = Finance.GetContentFromSlice(slice);
            
            if (rawJsonResponse == null || rawJsonResponse.isEmpty()) {
                logger.debug("No new messages");
                return new FetchResult(messages, "{}");
            }
            
            // 解析返回的 JSON
            messages = parseChatData(rawJsonResponse);
            logger.info("Fetched {} messages from WeChat", messages.size());
            
            return new FetchResult(messages, rawJsonResponse);
            
        } catch (Exception e) {
            logger.error("Error fetching chat data", e);
            return new FetchResult(-1, "Exception: " + e.getMessage());
        } finally {
            // 释放 Slice
            if (slice != 0) {
                Finance.FreeSlice(slice);
            }
        }
    }
    
    /**
     * 解析聊天数据 JSON
     */
    private List<WeChatMessage> parseChatData(String jsonData) {
        List<WeChatMessage> messages = new ArrayList<>();
        
        try {
            if (jsonData != null && !jsonData.isEmpty()) {
                logger.info("WeChat GetChatData raw response: {}", jsonData);
            }
            JsonNode root = objectMapper.readTree(jsonData);
            
            // 检查错误码
            int errcode = root.path("errcode").asInt();
            if (errcode != 0) {
                String errmsg = root.path("errmsg").asText();
                logger.error("API returned error: {} - {}", errcode, errmsg);
                return messages;
            }
            
            // 解析消息列表
            JsonNode chatdataArray = root.path("chatdata");
            if (!chatdataArray.isArray()) {
                logger.warn("chatdata is not an array, type: {}", chatdataArray.getNodeType());
                return messages;
            }
            
            int totalMessages = chatdataArray.size();
            logger.info("Parsing {} messages from chatdata array", totalMessages);
            
            int successCount = 0;
            int decryptFailedCount = 0;
            int parseFailedCount = 0;
            
            for (JsonNode chatdata : chatdataArray) {
                try {
                    WeChatMessage message = parseMessage(chatdata);
                    if (message != null) {
                        successCount++;
                        // 异步处理媒体文件（如果有），避免阻塞消息解析流程
                        if (mediaProcessService != null && mediaProcessService.isAvailable()) {
                            final WeChatMessage msg = message; // 用于lambda表达式
                            CompletableFuture.runAsync(() -> {
                                try {
                                    mediaProcessService.processMedia(msg);
                                } catch (Exception e) {
                                    logger.error("Failed to process media for message: " + msg.getMsgid(), e);
                                    // 媒体处理失败不影响消息入库
                                }
                            }, mediaProcessExecutor).exceptionally(ex -> {
                                logger.error("Media processing task failed for message: " + msg.getMsgid(), ex);
                                return null;
                            });
                        }
                        messages.add(message);
                    } else {
                        decryptFailedCount++;
                    }
                } catch (Exception e) {
                    parseFailedCount++;
                    logger.error("Failed to parse message", e);
                }
            }
            
            logger.info("Message parsing summary: total={}, success={}, decrypt_failed={}, parse_failed={}", 
                    totalMessages, successCount, decryptFailedCount, parseFailedCount);
            
        } catch (Exception e) {
            logger.error("Failed to parse chat data JSON", e);
        }
        
        return messages;
    }
    
    /**
     * 解析单条消息
     * 
     * @param chatdata 从企业微信API返回的单条聊天数据JSON节点
     * @return 解析后的消息对象，解密失败返回null
     * @throws Exception 解析过程中的异常
     */
    private WeChatMessage parseMessage(JsonNode chatdata) throws Exception {
        // 获取加密信息
        long seq = chatdata.path("seq").asLong();
        int publickeyVer = chatdata.path("publickey_ver").asInt();
        String encryptRandomKey = chatdata.path("encrypt_random_key").asText();
        String encryptChatMsg = chatdata.path("encrypt_chat_msg").asText();
        
        // 检查必要字段
        if (encryptRandomKey == null || encryptRandomKey.isEmpty()) {
            logger.warn("Missing encrypt_random_key at seq: {}", seq);
            return null;
        }
        if (encryptChatMsg == null || encryptChatMsg.isEmpty()) {
            logger.warn("Missing encrypt_chat_msg at seq: {}", seq);
            return null;
        }
        
        // 解密消息
        String decryptedMsg = decryptMessage(encryptRandomKey, encryptChatMsg, publickeyVer);
        if (decryptedMsg == null || decryptedMsg.isEmpty()) {
            logger.warn("Failed to decrypt message at seq: {}, publickey_ver: {}", seq, publickeyVer);
            return null;
        }
        
        // 解析解密后的消息
        JsonNode msgNode = objectMapper.readTree(decryptedMsg);
        
        WeChatMessage message = new WeChatMessage();
        message.setSeq(seq);
        message.setMsgid(msgNode.path("msgid").asText());
        message.setMsgtime(msgNode.path("msgtime").asLong());
        message.setAction(msgNode.path("action").asText("send"));
        message.setFromUser(msgNode.path("from").asText());
        message.setMsgtype(msgNode.path("msgtype").asText());
        
        // 处理群聊信息
        if (msgNode.has("roomid")) {
            message.setRoomid(msgNode.path("roomid").asText());
        }
        
        // 处理接收人列表
        if (msgNode.has("tolist")) {
            message.setToList(msgNode.path("tolist").toString());
        }
        
        // 保存消息内容
        message.setContent(decryptedMsg);
        message.setRawJson(chatdata.toString());
        
        return message;
    }
    
    /**
     * 解密消息
     * 先用RSA私钥解密encrypt_random_key，然后用解密后的key解密消息内容
     * 
     * @param encryptRandomKey 加密的随机密钥（需要用RSA私钥解密）
     * @param encryptMsg 加密的消息内容
     * @param publickeyVer 公钥版本号
     * @return 解密后的消息明文，失败返回null
     */
    private String decryptMessage(String encryptRandomKey, String encryptMsg, int publickeyVer) {
        long slice = 0;
        
        try {
            // 检查公钥版本是否匹配
            if (publickeyVer != config.getPublickeyVer()) {
                logger.warn("Publickey version mismatch: message={}, config={}", publickeyVer, config.getPublickeyVer());
                // 即使版本不匹配，也尝试解密，因为可能有多个版本的消息
            }
            
            // 步骤1: 使用RSA私钥解密encrypt_random_key，得到真正的encrypt_key
            String encryptKey = decryptRandomKey(encryptRandomKey);
            if (encryptKey == null) {
                logger.error("Failed to decrypt random key with RSA private key");
                return null;
            }
            
            // 步骤2: 使用解密后的encrypt_key调用SDK解密消息内容
            slice = Finance.NewSlice();
            
            int ret = Finance.DecryptData(sdk, encryptKey, encryptMsg, slice);
            if (ret != 0) {
                logger.error("Failed to decrypt message, error code: {}", ret);
                return null;
            }
            
            return Finance.GetContentFromSlice(slice);
            
        } catch (Exception e) {
            logger.error("Error decrypting message", e);
            return null;
        } finally {
            if (slice != 0) {
                Finance.FreeSlice(slice);
            }
        }
    }
    
    /**
     * 加载RSA私钥
     * 支持PKCS#1和PKCS#8两种格式的RSA私钥
     * 
     * @throws Exception 加载私钥失败
     */
    private void loadPrivateKey() throws Exception {
        String privateKeyPath = config.getPrivateKeyPath();
        logger.info("Loading RSA private key from: {}", privateKeyPath);
        
        // 读取私钥文件内容
        String privateKeyContent = new String(Files.readAllBytes(Paths.get(privateKeyPath)));
        
        // 判断私钥格式
        boolean isPKCS1 = privateKeyContent.contains("-----BEGIN RSA PRIVATE KEY-----");
        
        // 去除PEM文件的头尾标记和换行符
        privateKeyContent = privateKeyContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        
        // Base64解码
        byte[] keyBytes = Base64.getDecoder().decode(privateKeyContent);
        
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        
        if (isPKCS1) {
            // PKCS#1 格式需要转换为 PKCS#8 格式
            logger.info("Detected PKCS#1 format, converting to PKCS#8...");
            keyBytes = convertPKCS1toPKCS8(keyBytes);
        }
        
        // 生成私钥对象
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
        this.privateKey = keyFactory.generatePrivate(keySpec);
        
        logger.info("RSA private key loaded successfully");
    }
    
    /**
     * 将PKCS#1格式的RSA私钥转换为PKCS#8格式
     * 
     * @param pkcs1Bytes PKCS#1格式的私钥字节
     * @return PKCS#8格式的私钥字节
     */
    private byte[] convertPKCS1toPKCS8(byte[] pkcs1Bytes) {
        // PKCS#8 格式头部
        // 包含算法标识符 (RSA) 和版本号
        int pkcs1Length = pkcs1Bytes.length;
        int totalLength = pkcs1Length + 22; // 22是PKCS#8包装的额外字节
        
        byte[] pkcs8Header = new byte[]{
                0x30, (byte) 0x82, (byte) ((totalLength >> 8) & 0xff), (byte) (totalLength & 0xff), // SEQUENCE
                0x02, 0x01, 0x00, // version
                0x30, 0x0D, // SEQUENCE
                0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01, // RSA OID
                0x05, 0x00, // NULL
                0x04, (byte) 0x82, (byte) ((pkcs1Length >> 8) & 0xff), (byte) (pkcs1Length & 0xff) // OCTET STRING
        };
        
        byte[] pkcs8Bytes = new byte[pkcs8Header.length + pkcs1Length];
        System.arraycopy(pkcs8Header, 0, pkcs8Bytes, 0, pkcs8Header.length);
        System.arraycopy(pkcs1Bytes, 0, pkcs8Bytes, pkcs8Header.length, pkcs1Length);
        
        return pkcs8Bytes;
    }
    
    /**
     * 使用RSA私钥解密encrypt_random_key
     * 企业微信的encrypt_random_key是用RSA公钥加密的AES密钥
     * 解密后得到的是Base64格式的AES密钥字符串，直接转换即可
     * 
     * @param encryptRandomKey Base64编码的加密随机密钥
     * @return 解密后的AES密钥（Base64字符串），失败返回null
     */
    private String decryptRandomKey(String encryptRandomKey) {
        try {
            if (privateKey == null) {
                logger.error("Private key not loaded");
                return null;
            }
            
            // Base64解码加密的随机密钥
            byte[] encryptedBytes = Base64.getDecoder().decode(encryptRandomKey);
            
            // 使用RSA私钥解密
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] decryptedBytes = cipher.doFinal(encryptedBytes);
            
            // RSA解密后的字节直接转为字符串（它本身就是Base64格式的AES密钥）
            // 不要再次进行Base64编码
            String decryptedKey = new String(decryptedBytes, java.nio.charset.StandardCharsets.UTF_8);
            
            logger.debug("RSA decrypted key length: {} bytes", decryptedBytes.length);
            
            return decryptedKey;
            
        } catch (Exception e) {
            logger.error("Failed to decrypt random key with RSA", e);
            return null;
        }
    }
    
    /**
     * 销毁 SDK
     */
    /**
     * 销毁SDK实例和资源
     */
    public void destroy() {
        // 关闭媒体处理线程池
        if (mediaProcessExecutor != null) {
            logger.info("Shutting down media process executor...");
            mediaProcessExecutor.shutdown();
            try {
                if (!mediaProcessExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    logger.warn("Media process executor did not terminate, forcing shutdown...");
                    mediaProcessExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for media process executor to terminate");
                mediaProcessExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (sdk != 0) {
            logger.info("Destroying WeChat SDK...");
            Finance.DestroySdk(sdk);
            sdk = 0;
        }
    }
}

