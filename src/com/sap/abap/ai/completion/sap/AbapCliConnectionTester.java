package com.sap.abap.ai.completion.sap;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * abapGit（abap-cli）方式的 SAP 连接测试。
 *
 * <p>模板导入已从 RFC {@code Z_ABAPGIT_UPLOAD_FROM_XSTRING}（JCo 单次上传）改为
 * abapGit / abap-cli 方式（{@code node abap-cli} 的 prog-* 扩展流，见
 * {@code MultiTabTemplateImportService}）。因此"SAP 配置"页的测试连接也改为
 * 调用 abap-cli 的 {@code profile test <system>} 命令，逐层探测
 * tls / auth / adt / icf / capabilities 等，不再校验原 RFC 函数是否存在。
 * （tls + auth + adt 三层通过即视为连接成功；icf 等属于附加信息，
 * 部分内网系统 ICF 探测返回 404 不影响 ADT 导入。）</p>
 *
 * <p>运行方式与导入流程完全一致：在 abap-cli stage 目录下执行
 * {@code node <stage>/node_modules/abap-cli/dist/src/abap_cli/index.js profile test <system>}，
 * 并设置 HOME / USERPROFILE 为当前用户主目录（abap-cli 从
 * {@code ~/.abap-cli/systems.json} 读取 profile，凭据存于系统钥匙串），
 * 以及 NODE_TLS_REJECT_UNAUTHORIZED=0（与导入步骤相同，容忍自签名证书）。</p>
 *
 * <p>注意：abap-cli 所有命令启动时都会校验 stage 目录下 {@code .abap.json}
 * 的 {@code system} 字段，缺失时报 {@code CONFIG_ERROR: Missing "system" in
 * .abap.json}。本类在测试前会自动补齐该字段（见
 * {@link #resolveSystemName(Path)}），同时修复导入流程的同一问题。</p>
 */
public final class AbapCliConnectionTester {

    /** 单层探测超时（秒）：profile test 共 5-6 层网络探测，留足余量。 */
    private static final int TIMEOUT_SEC = 180;

    /** 结果行详情中单行的最大长度（icf 失败时 SAP 返回整页 HTML，需要截断）。 */
    private static final int MAX_LINE_LEN = 200;

    /** 连接成功所必需的核心探测层。 */
    private static final List<String> REQUIRED_LAYERS = List.of("tls", "auth", "adt");

    /** {@code .abap.json} 中 "system" 字段的提取正则。 */
    private static final Pattern SYSTEM_FIELD =
            Pattern.compile("\"system\"\\s*:\\s*\"([^\"]+)\"");

    /** {@code systems.json} 中 profile 键的提取正则（"systems" 对象下的键）。 */
    private static final Pattern PROFILE_KEY =
            Pattern.compile("\"([A-Za-z0-9_\\-]+)\"\\s*:\\s*\\{");

    private AbapCliConnectionTester() {
    }

    /**
     * 连接测试结果。
     */
    public static final class Result {
        /** 是否连接成功（tls / auth / adt 全部 ok）。 */
        public final boolean success;
        /** 测试使用的 system profile 名（如 S4DEV）。 */
        public final String systemName;
        /** 各层探测摘要，如 {@code tls=ok, auth=ok, adt=ok, icf=error}。 */
        public final String layersSummary;
        /** 详细信息（环境检查失败原因 / 命令输出末尾若干行），用于弹窗展示。 */
        public final String detail;

        Result(boolean success, String systemName, String layersSummary, String detail) {
            this.success = success;
            this.systemName = systemName;
            this.layersSummary = layersSummary == null ? "" : layersSummary;
            this.detail = detail == null ? "" : detail;
        }
    }

    /**
     * 执行 abapGit（abap-cli）方式的连接测试。
     *
     * <p>流程：环境检查（stage 目录 / abap-cli 入口 / node）→
     * 确保 {@code .abap.json} 含 {@code system} → 运行
     * {@code node abap-cli profile test <system>} → 解析各层结果。</p>
     *
     * @return 测试结果（不会抛出异常，所有失败都通过 {@link Result#success}
     *         与 {@link Result#detail} 表达）
     */
    public static Result testConnection() {
        // ===== [1] 环境检查（与 MultiTabTemplateImportService.checkEnvironment 同源） =====
        Path stageRoot;
        String envError = checkEnvironment();
        if (envError != null) {
            return new Result(false, "", "", envError);
        }
        stageRoot = Paths.get(com.sap.abap.ai.completion.ui.MultiTabTemplateImportService.STAGE_DIR)
                .toAbsolutePath().normalize();

        // ===== [2] 确保 .abap.json 有 system 字段 =====
        final String system;
        try {
            system = ensureSystemInAbapJson(stageRoot);
        } catch (Exception e) {
            return new Result(false, "", "", "Cannot resolve abap-cli system profile:\n"
                    + e.getMessage());
        }

        // ===== [3] 运行 node abap-cli profile test <system> =====
        List<String> cmd = new ArrayList<>();
        cmd.add("node");
        cmd.add(stageRoot.resolve(
                com.sap.abap.ai.completion.ui.MultiTabTemplateImportService.ABAP_CLI_INDEX_REL)
                .toString());
        cmd.add("profile");
        cmd.add("test");
        cmd.add(system);

        List<String> output;
        int exitCode;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(stageRoot.toRealPath().toFile());
            pb.redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            String canonicalHome = Paths.get(System.getProperty("user.home")).toRealPath().toString();
            env.put("HOME", canonicalHome);
            env.put("USERPROFILE", canonicalHome);
            env.put("NODE_TLS_REJECT_UNAUTHORIZED", "0");

            Process p = pb.start();
            output = readProcessOutput(p, TIMEOUT_SEC);
            exitCode = p.exitValue();
        } catch (Exception e) {
            return new Result(false, system, "",
                    "Failed to run abap-cli profile test:\n"
                            + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // ===== [4] 解析各层结果 =====
        Map<String, String> layers = parseLayers(output);
        if (layers.isEmpty()) {
            // 未出现 "xxx: ok/error" 格式的层结果 —— 命令本身失败（配置错误等）
            return new Result(false, system, "",
                    "abap-cli profile test '" + system + "' did not run (exit=" + exitCode
                            + ").\n\nOutput:\n" + tailLines(output, 15));
        }

        StringBuilder summary = new StringBuilder();
        boolean ok = true;
        for (String layer : REQUIRED_LAYERS) {
            String st = layers.get(layer);
            if (!"ok".equals(st)) {
                ok = false;
            }
        }
        for (Map.Entry<String, String> e : new TreeMap<>(layers).entrySet()) {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(e.getKey()).append('=').append(e.getValue());
        }

        return new Result(ok, system, summary.toString(),
                "abap-cli profile test '" + system + "' (exit=" + exitCode + ")\n\n"
                        + tailLines(output, 20));
    }

    // ====================================================================
    // system profile 解析 / .abap.json 修复
    // ====================================================================

    /**
     * 解析要使用的 abap-cli system profile 名。
     *
     * <p>解析顺序：</p>
     * <ol>
     *   <li>stage {@code .abap.json} 现有 {@code system}（且存在于
     *       {@code ~/.abap-cli/systems.json}）→ 直接沿用；</li>
     *   <li>{@code systems.json} 中只有一个 profile → 使用它；</li>
     *   <li>现有 {@code system} 非空（即使 systems.json 中没有，保留原名让
     *       abap-cli 如实报错，便于发现问题）；</li>
     *   <li>其余情况（多 profile 且 .abap.json 无 system / 无任何 profile）→
     *       抛出异常并给出修复指引。</li>
     * </ol>
     *
     * @param stageRoot abap-cli stage 根目录
     * @return system profile 名
     * @throws IllegalStateException 无法唯一确定 profile
     */
    public static String resolveSystemName(Path stageRoot) {
        Map<String, Boolean> profiles = readProfileNames();
        String existing = readAbapJsonSystem(stageRoot);

        if (existing != null && !existing.isEmpty()
                && profiles.containsKey(existing)) {
            return existing;
        }
        if (profiles.size() == 1) {
            return profiles.keySet().iterator().next();
        }
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        if (profiles.isEmpty()) {
            throw new IllegalStateException(
                    "No abap-cli connection profile found in\n  "
                            + abapCliSystemsJsonPath()
                            + "\n\nPlease run 'node <abap-cli> init --profile <name>' "
                            + "(or 'profile add') to create one.");
        }
        throw new IllegalStateException(
                "Multiple abap-cli profiles configured ("
                        + String.join(", ", profiles.keySet())
                        + ") and .abap.json has no \"system\" field.\n\n"
                        + "Please set \"system\" in\n  "
                        + stageRoot.resolve(".abap.json")
                        + "\nto one of them.");
    }

    /**
     * 确保 stage {@code .abap.json} 含非空 {@code system} 字段：缺失时自动
     * 补写（在文件头部插入 {@code "system": "<name>"}，保留其余内容）。
     *
     * <p>abap-cli 所有命令启动时都会读取该字段，缺失即报
     * {@code CONFIG_ERROR: Missing "system" in .abap.json}。
     * 导入流程 {@code MultiTabTemplateImportService.writeAbapJson} 重写
     * {@code .abap.json} 时可能把该字段丢掉，此方法用于兜底修复。</p>
     *
     * @param stageRoot abap-cli stage 根目录
     * @return system profile 名
     * @throws IllegalStateException 无法解析 profile 或文件读写失败
     */
    public static String ensureSystemInAbapJson(Path stageRoot) {
        Path cfg = stageRoot.resolve(".abap.json");
        String system = resolveSystemName(stageRoot);

        String content = null;
        if (Files.isRegularFile(cfg)) {
            try {
                content = new String(Files.readAllBytes(cfg), StandardCharsets.UTF_8);
            } catch (Exception e) {
                content = null;
            }
        }

        boolean hasSystem = false;
        if (content != null) {
            Matcher m = SYSTEM_FIELD.matcher(content);
            hasSystem = m.find() && !m.group(1).trim().isEmpty();
        }

        if (!hasSystem) {
            String newContent = content == null || content.trim().isEmpty()
                    ? "{\n  \"system\": \"" + system + "\"\n}\n"
                    : content.replaceFirst("\\{",
                            "{\n  \"system\": \"" + system + "\",");
            try {
                Files.createDirectories(cfg.getParent());
                Files.write(cfg, newContent.getBytes(StandardCharsets.UTF_8));
                logInfo("Auto-added missing \"system\": \"" + system + "\" to " + cfg);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Cannot write \"system\" into " + cfg + ": " + e.getMessage(), e);
            }
        }
        return system;
    }

    /** 读取 {@code ~/.abap-cli/systems.json} 中的 profile 名集合（键→是否存在）。 */
    private static Map<String, Boolean> readProfileNames() {
        Map<String, Boolean> names = new LinkedHashMap<>();
        Path systems = abapCliSystemsJsonPath();
        if (!Files.isRegularFile(systems)) {
            return names;
        }
        try {
            String content = new String(Files.readAllBytes(systems), StandardCharsets.UTF_8);
            // 只取 "systems": { ... } 对象内的键；先定位 "systems" 再截取其后
            // 到匹配的第一个闭合大括号（简化解析，足够覆盖 systems.json 结构）。
            int idx = content.indexOf("\"systems\"");
            if (idx < 0) {
                return names;
            }
            int depth = 0;
            int start = -1;
            int end = -1;
            for (int i = idx; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') {
                    if (depth == 0) {
                        start = i;
                    }
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
            if (start >= 0 && end > start) {
                Matcher m = PROFILE_KEY.matcher(content.substring(start + 1, end));
                while (m.find()) {
                    names.put(m.group(1), Boolean.TRUE);
                }
            }
        } catch (Exception e) {
            logInfo("readProfileNames failed: " + e);
        }
        return names;
    }

    /** 读取 stage {@code .abap.json} 的 "system" 字段值（缺失返回 null）。 */
    private static String readAbapJsonSystem(Path stageRoot) {
        Path cfg = stageRoot.resolve(".abap.json");
        if (!Files.isRegularFile(cfg)) {
            return null;
        }
        try {
            Matcher m = SYSTEM_FIELD.matcher(
                    new String(Files.readAllBytes(cfg), StandardCharsets.UTF_8));
            return m.find() ? m.group(1).trim() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code ~/.abap-cli/systems.json} 路径（与 abap-cli 运行时 HOME 一致）。 */
    private static Path abapCliSystemsJsonPath() {
        try {
            return Paths.get(System.getProperty("user.home")).toRealPath()
                    .resolve(".abap-cli").resolve("systems.json");
        } catch (Exception e) {
            return Paths.get(System.getProperty("user.home"))
                    .resolve(".abap-cli").resolve("systems.json");
        }
    }

    // ====================================================================
    // 环境检查 / 进程执行 / 输出解析
    // ====================================================================

    /**
     * 环境检查：stage 目录、abap-cli 入口、node 是否就位。
     *
     * @return {@code null}=环境就绪；否则为失败说明
     */
    private static String checkEnvironment() {
        File stageFile = new File(
                com.sap.abap.ai.completion.ui.MultiTabTemplateImportService.STAGE_DIR);
        if (!stageFile.isDirectory()) {
            return "Abap-cli stage directory not found:\n  " + stageFile.getAbsolutePath()
                    + "\n\nThe abapGit-based import runs 'node abap-cli' from this directory.\n"
                    + "Please verify it exists (see MultiTabTemplateImportService.STAGE_DIR).";
        }
        Path stageRoot = stageFile.toPath().toAbsolutePath().normalize();

        File indexJs = stageRoot.resolve(
                com.sap.abap.ai.completion.ui.MultiTabTemplateImportService.ABAP_CLI_INDEX_REL)
                .toFile();
        if (!indexJs.isFile()) {
            return "abap-cli entry not found:\n  " + indexJs.getAbsolutePath()
                    + "\n\nPlease run 'npm install abap-cli' in:\n  " + stageRoot;
        }
        return null;
    }

    /**
     * 读取子进程 stdout/stderr 合流输出；超时强制结束进程并抛出异常。
     */
    private static List<String> readProcessOutput(Process p, int timeoutSec) throws Exception {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (lines) {
                            lines.add(line);
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "AbapCliConnTest-reader");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            while (reader.isAlive()) {
                if (System.currentTimeMillis() > deadline) {
                    p.destroyForcibly();
                    reader.interrupt();
                    throw new IllegalStateException(
                            "abap-cli profile test timed out after " + timeoutSec + "s");
                }
                Thread.sleep(100);
            }
            p.waitFor();
        }
        return lines;
    }

    /**
     * 从输出中解析各探测层状态。
     *
     * <p>目标行格式（profile test 的人读输出）：</p>
     * <pre>
     * Connection probe 'S4DEV':
     *   tls: ok
     *   auth: ok
     *   adt: ok
     *   icf: error — &lt;html&gt;...
     * </pre>
     */
    private static Map<String, String> parseLayers(List<String> lines) {
        Pattern layerLine = Pattern.compile("^\\s*([A-Za-z0-9_.]+):\\s*(\\S+)");
        Map<String, String> layers = new LinkedHashMap<>();
        synchronized (lines) {
            for (String raw : lines) {
                if (raw == null || !raw.contains(":")) {
                    continue;
                }
                Matcher m = layerLine.matcher(raw);
                if (m.find()) {
                    layers.put(m.group(1), m.group(2));
                }
            }
        }
        return layers;
    }

    /** 取输出末尾若干行（跳过 NODE_TLS 警告行，单行截断到 {@link #MAX_LINE_LEN}）。 */
    private static String tailLines(List<String> lines, int count) {
        List<String> useful = new ArrayList<>();
        synchronized (lines) {
            for (String l : lines) {
                if (l == null) {
                    continue;
                }
                if (l.contains("NODE_TLS_REJECT_UNAUTHORIZED")
                        && l.contains("Warning")) {
                    continue;
                }
                useful.add(l.length() > MAX_LINE_LEN
                        ? l.substring(0, MAX_LINE_LEN) + " ...[truncated]"
                        : l);
            }
        }
        int from = Math.max(0, useful.size() - count);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < useful.size(); i++) {
            sb.append(useful.get(i)).append('\n');
        }
        return sb.toString();
    }

    private static void logInfo(String message) {
        System.out.println("[AbapCliConnectionTester] " + message);
        try {
            com.sap.abap.ai.completion.Activator activator =
                    com.sap.abap.ai.completion.Activator.getDefault();
            if (activator != null) {
                activator.getLog().log(new org.eclipse.core.runtime.Status(
                        org.eclipse.core.runtime.IStatus.INFO,
                        com.sap.abap.ai.completion.Activator.PLUGIN_ID,
                        "[AbapCliConnectionTester] " + message, null));
            }
        } catch (Throwable ignored) {
            // 日志失败不影响主流程
        }
    }
}
