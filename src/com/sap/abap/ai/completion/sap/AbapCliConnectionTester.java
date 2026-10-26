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

    /**
     * 直连探测脚本名（位于 abap-cli stage 根目录）。
     *
     * <p>abap-cli CLI 的 {@code profile test} 只认
     * {@code ~/.abap-cli/systems.json} + 系统钥匙串里的 profile，不允许用命令行
     * 覆盖 url / 用户名 / 密码。而"SAP 配置"页的 URL / Client / User / Password
     * 是页面上填写的信息，因此用这个独立脚本在进程内复刻同一套探测逻辑
     * （tls → auth → adt → icf），输出同样的人读格式供 {@link #parseLayers} 解析。</p>
     */
    private static final String PROBE_SCRIPT_NAME = "_probe_direct.mjs";

    /** {@code .abap.json} 中 "system" 字段的提取正则。 */
    private static final Pattern SYSTEM_FIELD =
            Pattern.compile("\"system\"\\s*:\\s*\"([^\"]+)\"");

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
        // ===== [1] 环境检查（bundled runtime 释放 + node） =====
        Path stageRoot;
        String envError = checkEnvironment();
        if (envError != null) {
            return new Result(false, "", "", envError);
        }
        stageRoot = com.sap.abap.ai.completion.ui.AbapGitImportSupport.stageRoot();

        // ===== [2] 确保 .abap.json 有 system 字段 =====
        final String system;
        try {
            system = ensureSystemInAbapJson(stageRoot);
        } catch (Exception e) {
            return new Result(false, "", "", Messages.format(
                    "sapTester.error.resolveSystem", e.getMessage()));
        }

        // ===== [3] 运行 node abap-cli profile test <system> =====
        List<String> cmd = new ArrayList<>();
        cmd.add(com.sap.abap.ai.completion.ui.AbapCliRuntime.nodeCommand());
        cmd.add(stageRoot.resolve(
                com.sap.abap.ai.completion.ui.AbapGitImportSupport.ABAP_CLI_INDEX_REL)
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
                    Messages.format("sapTester.error.runFailed",
                            e.getClass().getSimpleName(), e.getMessage()));
        }

        // ===== [4] 解析各层结果 =====
        Map<String, String> layers = parseLayers(output);
        if (layers.isEmpty()) {
            // 未出现 "xxx: ok/error" 格式的层结果 —— 命令本身失败（配置错误等）
            return new Result(false, system, "",
                    Messages.format("sapTester.error.notRun", system, exitCode,
                            tailLines(output, 15)));
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
                Messages.format("sapTester.detail.profileTest", system, exitCode,
                        tailLines(output, 20)));
    }

    /**
     * 用"SAP 配置"页上填写的连接信息直接做 abapGit（abap-cli 同款）连接测试。
     *
     * <p>不读 {@code ~/.abap-cli/systems.json}、不读系统钥匙串、不走 JCo：
     * 在 abap-cli stage 目录下运行 {@code node _probe_direct.mjs --url=... --client=...
     * --username=... --password=... --language=... --insecure=1}，该脚本用
     * abap-adt-api 依次探测 tls / auth / adt / icf，输出与人读格式一致的行。</p>
     *
     * @param url      abap-cli URL，如 {@code https://s4devapp.sap.com.cn:1443}
     *                 （缺 scheme 时自动补 {@code https://}）
     * @param client   SAP Client，如 {@code 150}
     * @param user     SAP 用户名
     * @param password SAP 密码
     * @param language 登录语言，如 {@code EN}
     * @return 测试结果（不会抛出异常，所有失败都通过 {@link Result#success}
     *         与 {@link Result#detail} 表达）
     */
    public static Result testConnection(String url, String client, String user,
                                        String password, String language) {
        // ===== [1] URL 必填校验 / 规范化 =====
        final String target;
        try {
            target = normalizeRequiredUrl(url);
        } catch (IllegalArgumentException iae) {
            return new Result(false, "", "", iae.getMessage());
        }

        // ===== [2] 环境检查（bundled runtime 释放 + 探测脚本 + node） =====
        String envError = checkEnvironment();
        if (envError != null) {
            return new Result(false, target, "", envError);
        }
        Path stageRoot = com.sap.abap.ai.completion.ui.AbapGitImportSupport.stageRoot();
        Path probeScript = stageRoot.resolve(PROBE_SCRIPT_NAME);
        if (!Files.isRegularFile(probeScript)) {
            return new Result(false, target, "",
                    Messages.format("sapTester.error.probeMissing", probeScript));
        }

        // ===== [3] 运行 node _probe_direct.mjs =====
        List<String> cmd = new ArrayList<>();
        cmd.add(com.sap.abap.ai.completion.ui.AbapCliRuntime.nodeCommand());
        cmd.add(probeScript.toString());
        cmd.add("--url=" + target);
        cmd.add("--client=" + nullToEmpty(client));
        cmd.add("--username=" + nullToEmpty(user));
        cmd.add("--password=" + nullToEmpty(password));
        cmd.add("--language=" + nullToEmpty(language));
        cmd.add("--insecure=1");

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
            return new Result(false, target, "",
                    Messages.format("sapTester.error.probeRunFailed", PROBE_SCRIPT_NAME,
                            e.getClass().getSimpleName(), e.getMessage()));
        }

        // ===== [4] 解析各层结果 =====
        Map<String, String> layers = parseLayers(output);
        if (layers.isEmpty()) {
            return new Result(false, target, "",
                    Messages.format("sapTester.error.probeNotRun", PROBE_SCRIPT_NAME,
                            exitCode, tailLines(output, 15)));
        }

        boolean ok = true;
        for (String layer : REQUIRED_LAYERS) {
            if (!"ok".equals(layers.get(layer))) {
                ok = false;
            }
        }
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(layers).entrySet()) {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(e.getKey()).append('=').append(e.getValue());
        }

        return new Result(ok, target, summary.toString(),
                Messages.format("sapTester.detail.probe", target, exitCode,
                        tailLines(output, 20)));
    }

    /**
     * 校验并规范化 abap-cli URL：空白即报错；缺 {@code http(s)://} 前缀时补
     * {@code https://}。
     */
    private static String normalizeRequiredUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException(Messages.get("sapTester.error.noUrl"));
        }
        String u = url.trim();
        if (!u.matches("(?i)^https?://.*")) {
            u = "https://" + u;
        }
        return u;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
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
                    Messages.format("sapTester.error.noProfile", abapCliSystemsJsonPath()));
        }
        throw new IllegalStateException(
                Messages.format("sapTester.error.multipleProfiles",
                        String.join(", ", profiles.keySet()),
                        stageRoot.resolve(".abap.json")));
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
                        Messages.format("sapTester.error.writeSystem", cfg, e.getMessage()), e);
            }
        }
        return system;
    }

    /** 读取 {@code ~/.abap-cli/systems.json} 中的 profile 名集合（键→是否存在）。
     *
     *  <p>只枚举 {@code "systems"} 对象的<b>直接子键</b>（且值为对象的键）。
     *  profile 内部由 abap-cli 写回的嵌套缓存对象（{@code auth} / {@code runtime} /
     *  {@code apiCapabilities} / {@code icf} / {@code httpService} /
     *  {@code adtTextpool} 等）绝不能算作 profile——此前用扁平正则扫描整个
     *  {@code "systems"} 子串会把它们误判为多 profile，导致全新 stage
     *  （{@code .abap.json} 尚无 {@code system} 字段）下解析失败。
     *  扫描器跟踪大括号深度与字符串字面量（含转义），避免值内
     *  {@code "{...}"} 文本干扰深度计数。</p>
     */
    private static Map<String, Boolean> readProfileNames() {
        Map<String, Boolean> names = new LinkedHashMap<>();
        Path systems = abapCliSystemsJsonPath();
        if (!Files.isRegularFile(systems)) {
            return names;
        }
        try {
            String content = new String(Files.readAllBytes(systems), StandardCharsets.UTF_8);
            int idx = content.indexOf("\"systems\"");
            if (idx < 0) {
                return names;
            }
            // 定位 "systems" 值对象的开括号
            int start = -1;
            for (int i = idx + "\"systems\"".length(); i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == '{') {
                    start = i;
                    break;
                }
                if (!Character.isWhitespace(c) && c != ':') {
                    return names; // 结构不符
                }
            }
            if (start < 0) {
                return names;
            }

            int depth = 0;
            boolean inStr = false;
            boolean esc = false;
            StringBuilder key = null;      // 正在读取的 depth-1 键名
            String pendingKey = null;      // 已读完、等待判定值类型的键名
            for (int i = start; i < content.length(); i++) {
                char c = content.charAt(i);
                if (inStr) {
                    if (esc) {
                        esc = false;
                    } else if (c == '\\') {
                        esc = true;
                    } else if (c == '"') {
                        inStr = false;
                        if (depth == 1 && key != null) {
                            pendingKey = key.toString();
                            key = null;
                        }
                    } else if (depth == 1 && key != null) {
                        key.append(c);
                    }
                    continue;
                }
                if (c == '"') {
                    inStr = true;
                    // 判定该字符串是否为 depth-1 的键：前一个非空白字符须为 '{' 或 ','
                    int p = i - 1;
                    while (p > start && Character.isWhitespace(content.charAt(p))) {
                        p--;
                    }
                    char pc = content.charAt(p);
                    if (depth == 1 && (pc == '{' || pc == ',')) {
                        key = new StringBuilder();
                    } else if (depth == 1 && pc == ':' && pendingKey != null) {
                        pendingKey = null; // 值是标量字符串，不是 profile
                    }
                    continue;
                }
                if (c == '{') {
                    if (depth == 1 && pendingKey != null) {
                        names.put(pendingKey, Boolean.TRUE);
                        pendingKey = null;
                    }
                    depth++;
                    continue;
                }
                if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        break; // "systems" 对象结束
                    }
                    continue;
                }
                if (depth == 1 && pendingKey != null && c != ':'
                        && !Character.isWhitespace(c)) {
                    pendingKey = null; // 值是数字/布尔/null 等标量，不是 profile
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
     * 环境检查：bundled abap-cli 运行时能否释放/就位、abap-cli 入口、node 是否可用。
     *
     * @return {@code null}=环境就绪；否则为失败说明
     */
    private static String checkEnvironment() {
        Path stageRoot;
        try {
            stageRoot = com.sap.abap.ai.completion.ui.AbapCliRuntime.ensureStage();
        } catch (com.sap.abap.ai.completion.ui.AbapCliRuntime.NotInstalledException e) {
            // Runtime not installed and not bundled: message carries the
            // one-click install / GitHub download guidance.
            return e.getMessage();
        } catch (Exception e) {
            return Messages.format("sapTester.error.runtimePrep", e.getMessage());
        }

        File indexJs = stageRoot.resolve(
                com.sap.abap.ai.completion.ui.AbapGitImportSupport.ABAP_CLI_INDEX_REL)
                .toFile();
        if (!indexJs.isFile()) {
            return Messages.format("sapTester.error.cliEntryMissing",
                    indexJs.getAbsolutePath());
        }
        if (!com.sap.abap.ai.completion.ui.AbapGitImportSupport.isNodeAvailable()) {
            return Messages.get("sapTester.error.nodeMissing");
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
                            Messages.format("sapTester.error.timeout", timeoutSec));
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
