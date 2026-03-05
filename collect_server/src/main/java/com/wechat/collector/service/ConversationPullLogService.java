package com.wechat.collector.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 按 conversation_id 拉取群消息的请求日志服务
 *
 * 功能描述：
 * 1. 将所有根据 conversation_id 拉取群消息的请求记录写入 logs/conversation/{conversation_id}.log
 * 2. 每行一条请求记录，格式：timestamp\tip=xxx\tconversationId=xxx\tresult=xxx\trequest={...}\tresponse={...}\tmessage=xxx
 *    - timestamp: ISO 8601 格式时间戳
 *    - ip: 请求方 IP 地址
 *    - conversationId: 会话 ID
 *    - result: 结果状态（start / success / error）
 *    - request: 请求参数 JSON（仅在 start 时记录）
 *    - response: 返回结果 JSON（仅在 success/error 时记录）
 *    - message: 说明信息
 * 3. 定时清理，仅保留最近 10 天的请求记录
 *
 * 使用场景：
 * - 供 POST /conversation/pull 等按 conversation_id 触发拉取的接口统一写日志
 * - 便于排查、审计指定会话的拉取请求，包含完整的请求参数和返回结果
 */
public class ConversationPullLogService {
    private static final Logger logger = LoggerFactory.getLogger(ConversationPullLogService.class);

    private static final String LOG_BASE_DIR = "logs/conversation";
    private static final int RETENTION_DAYS = 10;
    private static final long TRIM_INTERVAL_HOURS = 24;
    private static final Pattern SANITIZE_PATTERN = Pattern.compile("[\\\\/:*?\"<>|]");

    private final Path baseDir;
    private final int retentionDays;
    private ScheduledExecutorService trimScheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConversationPullLogService() {
        this(Paths.get(LOG_BASE_DIR), RETENTION_DAYS);
    }

    /**
     * 构造函数
     *
     * @param baseDir        日志根目录，如 logs/conversation
     * @param retentionDays  保留天数，超过的请求记录将被清理
     */
    public ConversationPullLogService(Path baseDir, int retentionDays) {
        this.baseDir = baseDir;
        this.retentionDays = retentionDays;
    }

    /**
     * 启动定时清理任务，每 24 小时执行一次，删除超过保留天数的记录
     */
    public void startTrimScheduler() {
        if (running.getAndSet(true)) {
            logger.warn("ConversationPullLogService trim scheduler already running");
            return;
        }
        trimScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "conversation-pull-log-trim");
            t.setDaemon(true);
            return t;
        });
        trimScheduler.scheduleWithFixedDelay(
                this::trimRetention,
                TRIM_INTERVAL_HOURS,
                TRIM_INTERVAL_HOURS,
                TimeUnit.HOURS
        );
        trimRetention();
        logger.info("ConversationPullLogService trim scheduler started, retention={} days, interval={}h",
                retentionDays, TRIM_INTERVAL_HOURS);
    }

    /**
     * 停止定时清理任务
     */
    public void stopTrimScheduler() {
        if (!running.getAndSet(false)) {
            return;
        }
        if (trimScheduler != null) {
            trimScheduler.shutdown();
            try {
                if (!trimScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    trimScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                trimScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            trimScheduler = null;
        }
        logger.info("ConversationPullLogService trim scheduler stopped");
    }

    /**
     * 写入一条按 conversation_id 拉取群消息的请求记录
     *
     * @param conversationId 会话 ID
     * @param clientIp       请求方 IP
     * @param result         结果：start / success / error
     * @param message        说明信息
     */
    public void log(String conversationId, String clientIp, String result, String message) {
        log(conversationId, clientIp, result, message, null, null);
    }

    /**
     * 写入一条按 conversation_id 拉取群消息的请求记录（包含请求参数和返回结果）
     *
     * @param conversationId 会话 ID
     * @param clientIp       请求方 IP
     * @param result         结果：start / success / error
     * @param message        说明信息
     * @param requestParams  请求参数（Map，可选）
     * @param response       返回结果（Map，可选）
     */
    public void log(String conversationId, String clientIp, String result, String message, Map<String, Object> requestParams, Map<String, Object> response) {
        if (conversationId == null || conversationId.trim().isEmpty()) {
            logger.warn("ConversationPullLogService.log: conversationId is null or empty, skip");
            return;
        }
        String ts = Instant.now().toString();
        StringBuilder lineBuilder = new StringBuilder();
        lineBuilder.append(ts);
        lineBuilder.append("\tip=").append(clientIp != null ? clientIp : "");
        lineBuilder.append("\tconversationId=").append(conversationId);
        lineBuilder.append("\tresult=").append(result != null ? result : "");
        if (requestParams != null && !requestParams.isEmpty()) {
            try {
                String requestJson = objectMapper.writeValueAsString(requestParams);
                lineBuilder.append("\trequest=").append(escapeTabAndNewline(requestJson));
            } catch (Exception e) {
                logger.warn("Failed to serialize request params to JSON", e);
            }
        }
        if (response != null && !response.isEmpty()) {
            try {
                String responseJson = objectMapper.writeValueAsString(response);
                lineBuilder.append("\tresponse=").append(escapeTabAndNewline(responseJson));
            } catch (Exception e) {
                logger.warn("Failed to serialize response to JSON", e);
            }
        }
        lineBuilder.append("\tmessage=").append(message != null ? escapeTabAndNewline(message) : "");
        lineBuilder.append("\n");
        Path file = resolveLogFile(conversationId);
        try {
            ensureDir(file.getParent());
            Files.write(file, lineBuilder.toString().getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.error("Failed to write conversation pull log: conversationId={}, file={}", conversationId, file, e);
        }
    }

    /**
     * 转义字符串中的制表符和换行符，避免破坏日志格式
     *
     * @param str 原始字符串
     * @return 转义后的字符串
     */
    private String escapeTabAndNewline(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    /**
     * 清理所有 conversation 日志文件，仅保留最近 retentionDays 天的请求记录
     */
    public void trimRetention() {
        if (!Files.isDirectory(baseDir)) {
            return;
        }
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        try {
            List<Path> files = Files.list(baseDir)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".log"))
                    .collect(Collectors.toList());
            for (Path f : files) {
                trimFile(f, cutoff);
            }
        } catch (IOException e) {
            logger.error("Failed to list conversation log dir: {}", baseDir, e);
        }
    }

    /**
     * 根据 conversation_id 解析对应的日志文件路径
     * conversation_id 中的非法文件名字符将替换为下划线
     *
     * @param conversationId 会话 ID
     * @return 日志文件 Path
     */
    private Path resolveLogFile(String conversationId) {
        String safe = SANITIZE_PATTERN.matcher(conversationId).replaceAll("_");
        if (safe.isEmpty()) {
            safe = "unknown";
        }
        return baseDir.resolve(safe + ".log");
    }

    private void ensureDir(Path dir) throws IOException {
        if (dir != null && !Files.isDirectory(dir)) {
            Files.createDirectories(dir);
        }
    }

    /**
     * 清理单个日志文件，删除 timestamp 早于 cutoff 的行
     *
     * @param file   日志文件
     * @param cutoff 截止时间，早于此时间的记录删除
     */
    private void trimFile(Path file, Instant cutoff) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            List<String> kept = lines.stream()
                    .filter(line -> {
                        int tab = line.indexOf('\t');
                        if (tab <= 0) return true;
                        String tsStr = line.substring(0, tab).trim();
                        try {
                            Instant ts = Instant.parse(tsStr);
                            return !ts.isBefore(cutoff);
                        } catch (Exception e) {
                            return true;
                        }
                    })
                    .collect(Collectors.toList());
            if (kept.size() < lines.size()) {
                Files.write(file, kept, StandardCharsets.UTF_8);
                logger.debug("Trimmed conversation log: {} removed {} lines", file.getFileName(), lines.size() - kept.size());
            }
        } catch (IOException e) {
            logger.error("Failed to trim conversation log file: {}", file, e);
        }
    }
}
