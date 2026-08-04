package com.sap.abap.ai.completion.logging;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.runtime.Platform;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.preferences.AIConfiguration;

/**
 * AI 接口日志记录器。
 *
 * 特性:
 *   - 默认关闭,通过 {@link AIConfiguration#isInterfaceLoggingEnabled()} 实时控制
 *   - 输出到插件 state area: <workspace>/.metadata/.plugins/com.sap.abap.ai.completion/
 *   - 按小时滚动文件: 文件名格式 yyyyMMddHH_ai_abap.log (如 2026080409_ai_abap.log)
 *   - 自动删除一周(7 天)以前的日志文件
 *   - 串行写入(synchronized),避免异步线程竞争
 *   - 不记录 API Key/URL/模型名(敏感信息),仅记录 prompt 和 completion
 *   - 清理检查节流: 每小时最多执行一次清理扫描
 *
 * 日志格式:
 *   [2026-08-04 10:23:15.123] [REQUEST] [ZMY_PROG.abap]
 *   --- SYSTEM PROMPT ---
 *   <system prompt 全文>
 *   --- USER PROMPT ---
 *   <user prompt 全文>
 *   [2026-08-04 10:23:17.456] [RESPONSE] [ZMY_PROG.abap] (2333ms)
 *   <completion 全文>
 */
public final class AILogger {

    /** 日志文件名前缀: yyyyMMddHH */
    private static final SimpleDateFormat HOUR_FMT = new SimpleDateFormat("yyyyMMddHH");
    /** 日志文件名后缀 */
    private static final String LOG_FILE_SUFFIX = "_ai_abap.log";
    /** 日志内容时间戳格式 */
    private static final SimpleDateFormat TS = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    /** 日志保留天数 */
    private static final long RETENTION_DAYS = 7;
    /** 日志保留毫秒数 */
    private static final long RETENTION_MS = RETENTION_DAYS * 24L * 60L * 60L * 1000L;
    /** 匹配日志文件名的正则: (\d{8})_ai_abap.log (8 位日期 = yyyyMMddHH) */
    private static final Pattern LOG_NAME_PATTERN = Pattern.compile("^(\\d{8})_ai_abap\\.log$");

    private static final Object WRITE_LOCK = new Object();

    /** 上次执行清理的时间戳,节流用。AtomicLong 便于无锁读。 */
    private static final AtomicLong LAST_CLEANUP_MS = new AtomicLong(0L);
    /** 清理节流间隔: 1 小时 */
    private static final long CLEANUP_INTERVAL_MS = 60L * 60L * 1000L;

    private AILogger() {
    }

    /**
     * 记录 AI 请求(system + user prompt)。
     * 若日志未启用,立即返回,无性能开销。
     */
    public static void logRequest(String fileName, String systemPrompt, String userPrompt) {
        if (!isEnabled()) return;
        StringBuilder sb = new StringBuilder(512);
        sb.append("\n").append("************************************************************\n");
        sb.append(timestamp()).append(" [REQUEST] [").append(safe(fileName)).append("]\n");
        sb.append("--- SYSTEM PROMPT ---\n");
        sb.append(safe(systemPrompt)).append("\n");
        sb.append("--- USER PROMPT ---\n");
        sb.append(safe(userPrompt)).append("\n");
        append(sb.toString());
    }

    /**
     * 记录 AI 响应(completion + 耗时)。
     */
    public static void logResponse(String fileName, String completion, long durationMs) {
        if (!isEnabled()) return;
        StringBuilder sb = new StringBuilder(256);
        sb.append(timestamp()).append(" [RESPONSE] [")
          .append(safe(fileName)).append("] (").append(durationMs).append("ms)\n");
        sb.append(safe(completion)).append("\n");
        sb.append("************************************************************\n");
        append(sb.toString());
    }

    /**
     * 记录错误。
     */
    public static void logError(String fileName, String errorMsg) {
        if (!isEnabled()) return;
        StringBuilder sb = new StringBuilder(256);
        sb.append(timestamp()).append(" [ERROR] [").append(safe(fileName)).append("]\n");
        sb.append(safe(errorMsg)).append("\n");
        append(sb.toString());
    }

    // ==================== 内部方法 ====================

    private static boolean isEnabled() {
        try {
            return AIConfiguration.isInterfaceLoggingEnabled();
        } catch (Exception e) {
            // 偏好未就绪时默认不记录
            return false;
        }
    }

    private static String timestamp() {
        synchronized (TS) {
            return "[" + TS.format(new Date()) + "]";
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /**
     * 构造当前小时对应的日志文件路径。
     * 文件名格式: yyyyMMddHH_ai_abap.log (如 2026080409_ai_abap.log)
     */
    private static Path getCurrentLogPath() {
        try {
            String hourPart;
            synchronized (HOUR_FMT) {
                hourPart = HOUR_FMT.format(new Date());
            }
            String logFileName = hourPart + LOG_FILE_SUFFIX;
            return Platform.getStateLocation(Activator.getDefault().getBundle())
                    .append(logFileName)
                    .toFile()
                    .toPath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取日志目录(插件 state area)。
     */
    private static Path getLogDirectory() {
        try {
            return Platform.getStateLocation(Activator.getDefault().getBundle())
                    .toFile()
                    .toPath();
        } catch (Exception e) {
            return null;
        }
    }

    private static void append(String content) {
        Path path = getCurrentLogPath();
        if (path == null) return;
        synchronized (WRITE_LOCK) {
            try {
                Files.createDirectories(path.getParent());
                Files.write(path,
                        content.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (Exception e) {
                // 日志失败不影响主流程
            }
            // 节流执行清理: 每小时最多一次
            cleanupOldLogsIfNeeded();
        }
    }

    /**
     * 按节流间隔触发清理。实际清理逻辑在 {@link #doCleanupOldLogs()}。
     */
    private static void cleanupOldLogsIfNeeded() {
        long now = System.currentTimeMillis();
        long last = LAST_CLEANUP_MS.get();
        if (now - last < CLEANUP_INTERVAL_MS) {
            return;   // 距上次清理不足 1 小时,跳过
        }
        // CAS 更新,只有一个线程能进入清理
        if (LAST_CLEANUP_MS.compareAndSet(last, now)) {
            try {
                doCleanupOldLogs();
            } catch (Exception e) {
                // 清理失败不影响主流程
            }
        }
    }

    /**
     * 删除一周以前的日志文件。
     *
     * 判定策略(任一即删除):
     *   1. 文件名匹配 yyyyMMddHH_ai_abap.log,且文件名中的时间戳早于保留阈值
     *   2. 文件名不匹配但最后修改时间早于保留阈值(兜底,处理异常命名)
     */
    private static void doCleanupOldLogs() {
        Path dir = getLogDirectory();
        if (dir == null || !Files.isDirectory(dir)) return;

        long cutoffMs = System.currentTimeMillis() - RETENTION_MS;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*_ai_abap.log")) {
            for (Path file : stream) {
                if (!Files.isRegularFile(file)) continue;
                boolean shouldDelete = false;

                // 策略 1: 按文件名中的时间戳判定
                String name = file.getFileName().toString();
                Matcher m = LOG_NAME_PATTERN.matcher(name);
                if (m.matches()) {
                    try {
                        long fileHourMs = new SimpleDateFormat("yyyyMMddHH").parse(m.group(1)).getTime();
                        if (fileHourMs < cutoffMs) {
                            shouldDelete = true;
                        }
                    } catch (Exception parseEx) {
                        // 解析失败,退化到策略 2
                    }
                }

                // 策略 2: 按最后修改时间兜底
                if (!shouldDelete) {
                    try {
                        FileTime t = Files.getLastModifiedTime(file);
                        if (t.toMillis() < cutoffMs) {
                            shouldDelete = true;
                        }
                    } catch (IOException ioEx) {
                        // 读取属性失败,不删除
                    }
                }

                if (shouldDelete) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException delEx) {
                        // 删除失败,下次再试
                    }
                }
            }
        } catch (IOException listEx) {
            // 列目录失败,忽略
        }
    }
}
