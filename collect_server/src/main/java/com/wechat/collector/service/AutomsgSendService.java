package com.wechat.collector.service;

import com.wechat.collector.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 云手机 ADB 微信发消息服务。
 * 使用 Java 直接调用系统 adb 命令，在云手机微信中向指定联系人发送消息（不调用 Python）。
 */
public class AutomsgSendService {

    private static final Logger logger = LoggerFactory.getLogger(AutomsgSendService.class);

    private static final int ADB_TIMEOUT_SECONDS = 15;

    private final AppConfig.AutomsgConfig config;
    /** 实际使用的 adb 命令（配置的 adbPath 或 "adb"） */
    private final String adbCommand;

    public AutomsgSendService(AppConfig.AutomsgConfig config) {
        this.config = config;
        String path = config.getAdbPath();
        this.adbCommand = (path != null && !path.trim().isEmpty()) ? path.trim() : "adb";
    }

    /**
     * 发送微信消息：连接 ADB，按固定坐标点击并输入联系人与消息内容。
     *
     * @param contact 联系人名称（微信显示名），不能为空
     * @param message 要发送的消息内容，不能为空
     * @return 发送结果，包含是否成功及错误信息
     */
    public SendResult send(String contact, String message) {
        if (contact == null || contact.trim().isEmpty()) {
            return SendResult.fail("联系人不能为空");
        }
        if (message == null || message.trim().isEmpty()) {
            return SendResult.fail("消息内容不能为空");
        }
        String remoteAdb = config.getRemoteAdb();
        if (remoteAdb == null || remoteAdb.trim().isEmpty()) {
            return SendResult.fail("automsg.remoteAdb 未配置");
        }

        StringBuilder log = new StringBuilder();
        StringBuilder tapLog = new StringBuilder();
        String contactTrimmed = contact.trim();
        String messageTrimmed = message.trim();
        try {
            if (!connectAdb(remoteAdb, log)) {
                SendResult r = SendResult.fail("连接 ADB 失败: " + remoteAdb + "\n" + log);
                writeRequestLog(contactTrimmed, messageTrimmed, r, log, tapLog);
                return r;
            }
            if (!closeAllAndOpenWechat(log)) {
                SendResult r = SendResult.fail("关闭应用或打开微信失败\n" + log);
                writeRequestLog(contactTrimmed, messageTrimmed, r, log, tapLog);
                return r;
            }
            if (!stepClickSearchAndInputContact(contactTrimmed, log, tapLog)) {
                SendResult r = SendResult.fail("点击搜索或输入联系人失败\n" + log);
                writeRequestLog(contactTrimmed, messageTrimmed, r, log, tapLog);
                return r;
            }
            if (!stepClickFirstSearchResult(log, tapLog)) {
                SendResult r = SendResult.fail("点击联系人失败\n" + log);
                writeRequestLog(contactTrimmed, messageTrimmed, r, log, tapLog);
                return r;
            }
            if (!stepInputMessageAndSend(messageTrimmed, log, tapLog)) {
                SendResult r = SendResult.fail("输入消息或点击发送失败\n" + log);
                writeRequestLog(contactTrimmed, messageTrimmed, r, log, tapLog);
                return r;
            }
            SendResult r = SendResult.ok("发送完成");
            writeRequestLog(contactTrimmed, messageTrimmed, r, log, tapLog);
            return r;
        } catch (Exception e) {
            logger.error("automsg 执行异常: contact={}, message={}", contact, message, e);
            SendResult r = SendResult.fail("执行异常: " + e.getMessage() + "\n" + log);
            writeRequestLog(contactTrimmed, messageTrimmed, r, log, tapLog);
            return r;
        }
    }

    /**
     * 每次发送请求生成一个日志文件到配置的 requestLogDir 目录。
     * 内容：请求参数、结果、每次点击的坐标、adb 输出。
     */
    private void writeRequestLog(String contact, String message, SendResult result,
                                 StringBuilder adbLog, StringBuilder tapLog) {
        String dir = config.getRequestLogDir();
        if (dir == null || dir.trim().isEmpty()) {
            return;
        }
        try {
            Path dirPath = Paths.get(dir.trim());
            Files.createDirectories(dirPath);
            String timePart = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String shortId = UUID.randomUUID().toString().substring(0, 8);
            String fileName = "automsg_" + timePart + "_" + shortId + ".log";
            Path filePath = dirPath.resolve(fileName);
            StringBuilder content = new StringBuilder();
            content.append("requestTime=").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
            content.append("contact=").append(contact).append("\n");
            content.append("message=").append(message).append("\n");
            content.append("success=").append(result.isSuccess()).append("\n");
            content.append("resultMessage=").append(result.getMessage()).append("\n");
            content.append("--- 点击坐标 ---\n");
            content.append(tapLog != null && tapLog.length() > 0 ? tapLog : "(none)\n");
            content.append("--- adb output ---\n");
            content.append(adbLog.length() > 0 ? adbLog : "(none)");
            Files.write(filePath, content.toString().getBytes(StandardCharsets.UTF_8));
            logger.info("automsg 请求日志已写入: {}", filePath.toAbsolutePath());
        } catch (Exception e) {
            logger.warn("automsg 写入请求日志失败: dir={}", dir, e);
        }
    }

    /** 执行 adb 命令（如 adb connect xxx），返回是否成功，输出追加到 out */
    private boolean runAdb(List<String> args, StringBuilder out) {
        try {
            ProcessBuilder pb = new ProcessBuilder(args);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append("\n");
                    logger.debug("[adb] {}", line);
                }
            }
            boolean finished = p.waitFor(ADB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            logger.warn("runAdb failed: {}", e.getMessage());
            out.append(e.getMessage()).append("\n");
            return false;
        }
    }

    /** 执行 adb shell 单条命令，返回是否成功 */
    private boolean runAdbShell(String shellCmd, StringBuilder out) {
        List<String> args = new ArrayList<>();
        args.add(adbCommand);
        args.add("shell");
        args.add(shellCmd);
        return runAdb(args, out);
    }

    /** 连接远程 ADB */
    private boolean connectAdb(String addr, StringBuilder out) {
        List<String> args = new ArrayList<>();
        args.add(adbCommand);
        args.add("connect");
        args.add(addr);
        boolean ok = runAdb(args, out);
        if (ok && out.toString().toLowerCase().contains("connected")) {
            logger.info("ADB 已连接: {}", addr);
            return true;
        }
        return ok;
    }

    /** 模拟点击 (x, y) */
    private boolean tap(int x, int y, StringBuilder out) {
        return runAdbShell("input tap " + x + " " + y, out);
    }

    /**
     * 对传入 adb shell 的字符串做 shell 转义，避免单引号等导致设备端 /system/bin/sh 报 no closing quote。
     * 用单引号包裹，内部单引号改为 '\''。
     */
    private static String escapeForShell(String text) {
        if (text == null) return "''";
        return "'" + text.replace("'", "'\\''") + "'";
    }

    /** 通过 ADB Keyboard 的 broadcast 输入文本（支持中文、单引号等） */
    private boolean inputTextByAdbKeyboard(String text, StringBuilder out) {
        String escaped = escapeForShell(text);
        List<String> args = new ArrayList<>();
        args.add(adbCommand);
        args.add("shell");
        args.add("am");
        args.add("broadcast");
        args.add("-a");
        args.add("ADB_INPUT_TEXT");
        args.add("--es");
        args.add("msg");
        args.add(escaped);
        return runAdb(args, out);
    }

    private void sleepSeconds(double sec) {
        if (sec <= 0) return;
        try {
            Thread.sleep((long) (sec * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("sleep interrupted", e);
        }
    }

    /** 微信包名（来自 app.yml，未配置时兜底） */
    private String getWechatPackage() {
        String pkg = config.getWechatPackage();
        return (pkg != null && !pkg.isEmpty()) ? pkg : "com.tencent.mm";
    }

    /** 关闭前台并启动微信 */
    private boolean closeAllAndOpenWechat(StringBuilder out) {
        runAdbShell("input keyevent KEYCODE_HOME", out);
        sleepSeconds(1.2);
        runAdbShell("am force-stop " + getWechatPackage(), out);
        sleepSeconds(0.8);
        boolean ok = runAdbShell(
                "monkey -p " + getWechatPackage() + " -c android.intent.category.LAUNCHER 1",
                out);
        if (!ok) {
            return false;
        }
        sleepSeconds(config.getWaitAfterLaunch());
        return true;
    }

    /** 点击搜索按钮 → 点击搜索输入框 → 输入联系人名称 */
    private boolean stepClickSearchAndInputContact(String contact, StringBuilder out, StringBuilder tapLog) {
        int x = config.getSearchButtonX(), y = config.getSearchButtonY();
        tapLog.append("搜索按钮: (").append(x).append(", ").append(y).append(")\n");
        if (!tap(x, y, out)) {
            return false;
        }
        sleepSeconds(config.getWaitAfterSearchTap());
        x = config.getSearchInputX();
        y = config.getSearchInputY();
        tapLog.append("搜索输入框: (").append(x).append(", ").append(y).append(")\n");
        if (!tap(x, y, out)) {
            return false;
        }
        sleepSeconds(0.5);
        if (!inputTextByAdbKeyboard(contact, out)) {
            return false;
        }
        sleepSeconds(config.getWaitAfterInputSearch());
        return true;
    }

    /** 点击第一个联系人 */
    private boolean stepClickFirstSearchResult(StringBuilder out, StringBuilder tapLog) {
        int x = config.getContactX(), y = config.getContactY();
        tapLog.append("联系人: (").append(x).append(", ").append(y).append(")\n");
        if (!tap(x, y, out)) {
            return false;
        }
        sleepSeconds(config.getWaitAfterTapChat());
        return true;
    }

    /** 点击输入框 → 输入消息 → 点击发送 */
    private boolean stepInputMessageAndSend(String message, StringBuilder out, StringBuilder tapLog) {
        int x = config.getChatInputX(), y = config.getChatInputY();
        tapLog.append("聊天输入框: (").append(x).append(", ").append(y).append(")\n");
        if (!tap(x, y, out)) {
            return false;
        }
        sleepSeconds(0.8);
        if (!inputTextByAdbKeyboard(message, out)) {
            return false;
        }
        sleepSeconds(config.getWaitAfterInputMsg());
        x = config.getSendButtonX();
        y = config.getSendButtonY();
        tapLog.append("发送按钮: (").append(x).append(", ").append(y).append(")\n");
        if (!tap(x, y, out)) {
            return false;
        }
        return true;
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class SendResult {
        private final boolean success;
        private final String message;

        public static SendResult ok(String message) {
            return new SendResult(true, message != null ? message : "发送成功");
        }

        public static SendResult fail(String message) {
            return new SendResult(false, message != null ? message : "发送失败");
        }
    }
}
