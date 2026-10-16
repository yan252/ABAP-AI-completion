package com.sap.abap.ai.completion.ui;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.preferences.AIConfiguration;

/**
 * abapGit（abap-cli）导入的<b>公共支撑</b>：供各"模板导入"服务复用的静态工具集。
 *
 * <p>本类是 {@link MultiTabTemplateImportService}（Simple Query Handler Template）
 * 与 {@link BatchTemplateImportService}（Batch Import Handler Templates）等导入流程
 * 抽取出来的共用实现，覆盖：</p>
 *
 * <ul>
 *   <li><b>环境检查</b>：stage 目录 / abap-cli 入口 / 扩展文件 / node；</li>
 *   <li><b>连接注入</b>：把 SAP 配置页连接临时写入 {@code ~/.abap-cli/systems.json}
 *       + keychain（snapshot / apply / restore）；</li>
 *   <li><b>传输请求</b>：复用用户输入或 {@code transport create} 自动创建；</li>
 *   <li><b>步骤执行</b>：调一次 {@code node abap-cli ...} 并落盘 stdout/stderr；</li>
 *   <li><b>ZIP 解压 / 文件改名 / 写 {@code .abap.json}</b>；</li>
 *   <li><b>对象锁（ENQUEUE）检测</b>：从步骤输出中识别被其它用户 / 传输请求锁定的对象；</li>
 *   <li><b>UI / 日志</b>：输入对话框、结果对话框辅助、Error Log 与插件日志文件写入。</li>
 * </ul>
 *
 * <p>各服务只需负责"编排"（哪一步、传哪些文件）与"结果文案"，与 abap-cli 交互的
 * 细节统一由本类承担。</p>
 */
public final class AbapGitImportSupport {

    /**
     * Resolve the on-disk abap-cli stage root. The runtime (node_modules +
     * extensions + helper scripts) ships inside the plugin and is extracted to
     * the workspace state area on first use; no separate abap-cli installation
     * is required on the customer machine. See {@link AbapCliRuntime}.
     */
    public static Path stageRoot() {
        try {
            return AbapCliRuntime.ensureStage();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** abap-cli node 入口（相对 stage）。 */
    public static final String ABAP_CLI_INDEX_REL =
            "node_modules/abap-cli/dist/src/abap_cli/index.js";

    /** abap-cli 扩展目录（相对 stage）。 */
    static final String EXTENSIONS_DIR_REL = "extensions";

    /**
     * 标准 abap-cli 扩展名（prog / DDIC / OO 三类模板导入所需的全部扩展）。
     *
     * <ul>
     *   <li><b>ddic-xml-create</b>：把 {@code *.tabl.xml}（结构 / 透明表）与
     *       {@code *.ttyp.xml}（表类型）建成并激活；</li>
     *   <li><b>oo-xml-create</b>：把 {@code *.clas.xml}/{@code *.clas.abap}（类）与
     *       {@code *.intf.xml}/{@code *.intf.abap}（接口）建成并推送源码。</li>
     * </ul>
     */
    static final List<String> STANDARD_EXTENSIONS = Arrays.asList(
            "prog-xml-create", "ddic-xml-create", "oo-xml-create",
            "prog-xml-update", "prog-source-push", "prog-xml-push",
            "prog-xml-verify", "prog-batch-activate");

    /** 文本类文件扩展名（用于 zip 内占位符重命名）。 */
    static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "abap", "xml", "asddls", "asbdef", "ddls", "ddlx", "asddlxs",
            "bdef", "clas", "intf", "prog", "tabl", "doma", "dtel",
            "txt", "md", "json", "properties"));

    /** 子进程默认超时（秒）。 */
    static final int DEFAULT_TIMEOUT_SEC = 600;

    /** 导入日志文件名里的时间戳格式（插件 state area 下的 import_<名称>_<时间戳>.log）。 */
    static final SimpleDateFormat LOG_TS_FMT = new SimpleDateFormat("yyyyMMddHHmmss");

    /** {@code _prefs_snapshot.mjs} 相对 stage 的路径（与 abap-cli index 同在 stage 根）。 */
    private static final String PREFS_SNAPSHOT_REL = "_prefs_snapshot.mjs";

    /**
     * abap-cli 输出中"对象锁冲突（ENQUEUE）"的标记短语。
     * 命中即判定对象被其它用户 / 传输请求锁定（如"使用者 GWDEV45 当前编辑
     * ZTEST10"、"already locked in request S4DK906927"），需要用户在
     * SE03 / SE10 释放锁后再重试。
     */
    static final String[] LOCK_MARKERS = {
        "already locked",
        "locked in request",
        "currently editing",
        "current editing",
        "locked by another user/request",
        "当前编辑",
        "ENQUEUE",
        "Lock exists",
        "object is locked",
        "could not be locked",
        "locks could not be set",
        "cannot be locked",
        "加锁失败",
        "无法加锁",
        "不能加锁"
    };

    /** 锁冲突行里"被锁对象名"的正则（英文句式："X is already locked / is currently being edited"）。 */
    private static final java.util.regex.Pattern LOCK_NAME_BEFORE = java.util.regex.Pattern.compile(
            "\\b([A-Za-z_][A-Za-z0-9_]{1,39})\\s+(?:is|was|were)\\s+(?:already\\s+|currently\\s+)?"
            + "(?:locked|being\\s+edited)",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 锁冲突行里"被锁对象名"的正则（中文句式："使用者 GWDEV45 当前编辑 ZTEST10"）。 */
    private static final java.util.regex.Pattern LOCK_NAME_AFTER_EDIT = java.util.regex.Pattern.compile(
            "(?:当前编辑|正在编辑)\\s*([A-Za-z0-9_]{2,40})");

    /** 锁冲突行里的传输请求号。 */
    private static final String LOCK_REQUEST_PATTERN =
            "(?:in request|request|请求)\\s*([A-Za-z0-9_]{4,20})";

    /** 锁冲突行里的锁持有者用户。 */
    private static final String LOCK_USER_PATTERN =
            "(?:of user|by user|使用者|用户)\\s*([A-Za-z0-9_]{2,20})";

    /** 结果文件里的对象名字段（pretty-json: "object": "ZTEST40"）。 */
    private static final java.util.regex.Pattern JSON_OBJECT_FIELD = java.util.regex.Pattern.compile(
            "\"(?:object|name)\"\\s*:\\s*\"([A-Za-z0-9_]+)\"");

    private AbapGitImportSupport() {
    }

    // ====================================================================
    // 环境检查
    // ====================================================================

    /**
     * 检查 stage / abap-cli / 指定扩展 / node 是否就位。
     *
     * @param extensionNames 本次流程所需的扩展名（不含 {@code .mjs}）
     * @return 归一化后的 stage 根路径
     * @throws EnvironmentException 任一前提不满足
     */
    static Path checkEnvironment(List<String> extensionNames) throws EnvironmentException {
        Path stageRoot;
        try {
            stageRoot = AbapCliRuntime.ensureStage();
        } catch (IOException e) {
            throw new EnvironmentException(
                    "The bundled abap-cli runtime could not be prepared:\n  "
                            + e.getMessage()
                            + "\n\nPlease restart Eclipse and retry. If the problem persists,"
                            + "\nreinstall the plugin from the update site.");
        }

        File indexJs = stageRoot.resolve(ABAP_CLI_INDEX_REL).toFile();
        if (!indexJs.isFile()) {
            throw new EnvironmentException(
                    "abap-cli entry not found in the plugin runtime:\n  " + indexJs.getAbsolutePath()
                            + "\n\nThe plugin installation appears incomplete — please reinstall"
                            + "\nthe plugin from the update site.");
        }

        Path extDir = stageRoot.resolve(EXTENSIONS_DIR_REL);
        List<String> missing = new ArrayList<>();
        for (String name : extensionNames) {
            File f = extDir.resolve(name + ".mjs").toFile();
            if (!f.isFile()) missing.add("extensions/" + name + ".mjs");
        }
        if (!missing.isEmpty()) {
            throw new EnvironmentException(
                    "Missing abap-cli extension files in the plugin runtime:\n  "
                            + extDir.toString()
                            + "\n  Missing:\n    - " + String.join("\n    - ", missing)
                            + "\n\nThe plugin installation appears incomplete — please reinstall"
                            + "\nthe plugin from the update site.");
        }

        if (!isNodeInPath()) {
            throw new EnvironmentException(
                    "Node.js was not found on PATH ('node --version' failed). The abap-cli"
                            + "\nengine is bundled with this plugin, but Node.js 18 or newer is"
                            + "\nrequired to run it."
                            + "\n\nPlease install the LTS build for Windows from:"
                            + "\n  https://nodejs.org/"
                            + "\nand make sure 'node' is on PATH, then restart Eclipse.");
        }
        return stageRoot;
    }

    // ====================================================================
    // 步骤执行：调一次 node abap-cli
    // ====================================================================

    /**
     * 调一次 {@code node abap-cli}，把 stdout/stderr 写到 resultFile。
     *
     * <p>扩展命令（prog-xml-* / prog-source-push）拒绝命令行 {@code --transport}
     * （commander 阶段就拒），因此通过 {@code ABAP_TRANSPORT} 环境变量注入；
     * 内置命令（create FUGR / push / transport create）应自行在 headArgs 里带
     * {@code --tr}。</p>
     *
     * @param totalSteps 进度显示的总步数（用于 {@code [idx/total]} 文案）
     */
    static StepOutcome runAbapCliStep(int idx, String label, Path stageRoot,
                                      List<String> headArgs, List<String> fileArgs,
                                      Path resultFile, String transport, int totalSteps,
                                      IProgressMonitor monitor) {
        monitor.subTask("[" + idx + "/" + totalSteps + "] " + label + " ...");
        List<String> cmd = new ArrayList<>();
        cmd.add(AbapCliRuntime.nodeCommand());
        cmd.add(stageRoot.resolve(ABAP_CLI_INDEX_REL).toString());
        cmd.addAll(headArgs);
        cmd.addAll(fileArgs);
        cmd.add("--pretty-json");

        StepOutcome o = new StepOutcome(idx, label, -1, resultFile, null);
        System.out.println("[AbapCli] $ " + String.join(" ", cmd));

        if (!isNodeInPath()) {
            o.message = "node not found in PATH. Please install Node.js and ensure 'node' is on PATH.";
            return o;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(stageRoot.toRealPath().toFile());
            pb.redirectErrorStream(true);
            Map<String, String> procEnv = pb.environment();
            String canonicalHome = Paths.get(System.getProperty("user.home")).toRealPath().toString();
            procEnv.put("HOME", canonicalHome);
            procEnv.put("USERPROFILE", canonicalHome);
            procEnv.put("NODE_TLS_REJECT_UNAUTHORIZED", "0");
            // SAP 配置页连接：扩展命令在 loadProfile 时应用
            // $ABAP_URL/$ABAP_CLIENT/$ABAP_USERNAME/$ABAP_LANGUAGE/$ABAP_PASSWORD 覆盖。
            injectPrefsEnv(procEnv);
            // 传输请求号：source/textpool 推送等写操作需要 corrNr；缺省会报
            // "Parameter corrNr could not be found"。
            if (transport != null && !transport.isEmpty()) {
                procEnv.put("ABAP_TRANSPORT", transport);
            }

            Process p = pb.start();
            List<String> lines = readProcessOutput(p, DEFAULT_TIMEOUT_SEC);
            int exit = p.exitValue();

            Files.createDirectories(resultFile.getParent());
            Files.write(resultFile,
                    String.join(System.lineSeparator(), lines).getBytes(StandardCharsets.UTF_8));
            System.out.println("[AbapCli]   → " + resultFile + " (" + lines.size() + " lines)");

            int from = Math.max(0, lines.size() - 10);
            for (int i = from; i < lines.size(); i++) {
                System.out.println("[AbapCli]   | " + lines.get(i));
            }
            o.exitCode = exit;
        } catch (IOException | InterruptedException e) {
            o.message = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("[AbapCli] step [" + idx + "] " + label + " failed: " + o.message);
        }
        monitor.worked(1);
        return o;
    }

    // ====================================================================
    // 导入前 SAP 连接预检（Templates 子菜单导入共用）
    // ====================================================================

    /**
     * Import-time SAP connection pre-check, shared by all "Templates" menu
     * imports.
     *
     * <p>Runs the same direct probe as the "Test Connection" button on the
     * "SAP Connection Config" page, using the connection values stored there
     * (abap-cli URL / client / user / password / language). This happens
     * before any import work so an unreachable SAP system aborts the import
     * immediately.</p>
     *
     * <p>On failure (not configured or probe fails) an error dialog is shown,
     * the "SAP Connection Config" preference page is opened, and the caller
     * must abort the import.</p>
     *
     * @param title dialog title prefix, e.g. "Simple Query Handler Template"
     * @return {@code true} if the SAP connection is usable and the import may proceed
     */
    static boolean checkSapConnectionBeforeImport(final String title) {
        // ===== [1] SAP 配置页必须已填 abap-cli URL =====
        final String url = trimToNull(AIConfiguration.getSapAbapCliUrl());
        if (url == null) {
            showErrorBlocking(title + " — SAP Connection Failed",
                    "The SAP connection is not configured. The import was aborted.\n\n"
                            + "Please fill in the 'abap-cli URL' (and credentials) on the\n"
                            + "\"SAP Connection Config\" page first.\n\n"
                            + "The \"SAP Connection Config\" page will be opened now.");
            // 用户已确认错误框，再打开 SAP 配置页
            AICompletionMenuBuilder.openSapPreferences();
            return false;
        }
        final String client = trimToNull(AIConfiguration.getSapClient());
        final String language = trimToNull(AIConfiguration.getSapLanguage());
        final String user = trimToNull(AIConfiguration.getSapUser());
        final String password = AIConfiguration.getSapPassword(); // 密码不 trim（可能含特殊字符）

        // ===== [2] 运行与 "Test Connection" 同款的直接探测 =====
        final com.sap.abap.ai.completion.sap.AbapCliConnectionTester.Result[] holder =
                new com.sap.abap.ai.completion.sap.AbapCliConnectionTester.Result[1];
        final String[] probeError = { null };
        ProgressMonitorDialog pmd = new ProgressMonitorDialog(getShell());
        try {
            pmd.run(true, false, new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor) {
                    try {
                        monitor.beginTask("Validating SAP connection...",
                                IProgressMonitor.UNKNOWN);
                        holder[0] = com.sap.abap.ai.completion.sap.AbapCliConnectionTester
                                .testConnection(url, client, user, password, language);
                    } finally {
                        monitor.done();
                    }
                }
            });
        } catch (Exception e) {
            probeError[0] = "Failed to run connection probe: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        // ===== [3] 失败：先弹错误框，用户确认后再打开 SAP Connection Config 页 =====
        com.sap.abap.ai.completion.sap.AbapCliConnectionTester.Result r = holder[0];
        String detail;
        if (probeError[0] != null) {
            detail = probeError[0];
        } else if (r == null) {
            detail = "connection probe returned no result";
        } else {
            detail = r.detail;
        }
        if (probeError[0] != null || r == null || !r.success) {
            showErrorBlocking(title + " — SAP Connection Failed",
                    "Cannot connect to SAP (target '" + url + "'). The import was aborted.\n\n"
                            + detail + "\n\n"
                            + "The \"SAP Connection Config\" page will be opened now —\n"
                            + "please verify the connection settings and try again.");
            Activator.getDefault().getLog().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                    "Import aborted: SAP connection pre-check failed for '" + url
                            + "': " + detail, null));
            // 用户已确认错误框，再打开 SAP 配置页
            AICompletionMenuBuilder.openSapPreferences();
            return false;
        }
        System.out.println("[AbapCli] pre-import connection check OK (" + url + ")");
        return true;
    }

    /**
     * 同步弹出错误框：阻塞直至用户点击确认后返回（区别于 {@link #showError} 的
     * asyncExec 异步弹窗）。用于"先确认错误、再做下一步动作"的顺序场景，
     * 如导入预检失败：确认错误框之后才打开 SAP Connection Config 页。
     */
    private static void showErrorBlocking(String title, String message) {
        try {
            Runnable open = () -> MessageDialog.openError(getShell(), title, message);
            if (Display.getDefault().getThread() == Thread.currentThread()) {
                open.run(); // 已在 UI 线程：直接阻塞等待用户确认
            } else {
                Display.getDefault().syncExec(open); // 非 UI 线程：同步派发
            }
        } catch (Exception ignored) {
        }
    }

    // ====================================================================
    // SAP 配置页连接：临时改写 ~/.abap-cli/systems.json + keychain
    // ====================================================================

    /**
     * 把 SAP 配置页的连接信息临时应用到 abap-cli 的
     * {@code ~/.abap-cli/systems.json} 对应 profile 与 keychain 密码，使所有
     * abap-cli 步骤（含内置命令，它们只读 systems.json + keychain）都走配置页连接。
     *
     * <p>流程：先 snapshot，再 apply；执行完成后由 {@link #restorePrefsConnection}
     * 在调用方的 finally 中还原。</p>
     *
     * @return 快照文件绝对路径（供 restore 使用）；配置页未填 ABAP CLI URL 时返回 null
     */
    static String applyPrefsConnection(Path stageRoot, IProgressMonitor monitor) throws Exception {
        String url = trimToNull(AIConfiguration.getSapAbapCliUrl());
        if (url == null) {
            System.out.println("[AbapCli]   prefs: ABAP CLI URL not set in SAP Preferences — "
                    + "skipping prefs apply (falling back to ~/.abap-cli/systems.json).");
            return null;
        }
        String client = trimToNull(AIConfiguration.getSapClient());
        String language = trimToNull(AIConfiguration.getSapLanguage());
        String user = trimToNull(AIConfiguration.getSapUser());
        String password = AIConfiguration.getSapPassword(); // 密码不 trim（可能含特殊字符）

        String systemName;
        try {
            systemName = com.sap.abap.ai.completion.sap.AbapCliConnectionTester
                    .resolveSystemName(stageRoot);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "Cannot apply SAP Preferences connection: no abap-cli profile to patch.\n\n"
                            + e.getMessage(), e);
        }
        System.out.println("[AbapCli]   prefs: patching profile '" + systemName + "'"
                + "  url=" + url
                + "  client=" + (client == null ? "" : client)
                + "  user=" + (user == null ? "" : user)
                + "  language=" + (language == null ? "" : language));

        monitor.subTask("Applying SAP Preferences connection...");

        String snapJson = runPrefsScript(stageRoot, "snapshot", Arrays.asList(systemName));
        if (snapJson == null || snapJson.isEmpty()) {
            throw new IOException("prefs snapshot returned nothing for profile '" + systemName + "'");
        }
        if (!snapJson.startsWith("{")) {
            throw new IOException("prefs snapshot output not a JSON object for profile '"
                    + systemName + "': " + snapJson);
        }
        Path snap = Files.createTempFile("abapgit-prefs-", ".json");
        Files.write(snap, snapJson.getBytes(StandardCharsets.UTF_8));
        String snapshotFile = snap.toAbsolutePath().toString();
        System.out.println("[AbapCli]   prefs snapshot -> " + snapshotFile);

        List<String> applyArgs = new ArrayList<>();
        applyArgs.add(systemName);
        applyArgs.add(nonNull(url));
        applyArgs.add(nonNull(client));
        applyArgs.add(nonNull(user));
        applyArgs.add(nonNull(language));
        applyArgs.add(password == null ? "" : password);
        String applied = runPrefsScript(stageRoot, "apply", applyArgs);
        if (applied == null || !applied.contains("applied:ok")) {
            try {
                restorePrefsConnection(stageRoot, snapshotFile);
            } catch (Exception e2) {
                System.err.println("[AbapCli]   prefs rollback failed: " + e2.getMessage());
            }
            throw new IOException("prefs apply failed for profile '" + systemName
                    + "' (output: " + (applied == null ? "<null>" : applied) + ")");
        }
        System.out.println("[AbapCli]   prefs applied: " + applied.trim());
        return snapshotFile;
    }

    /** 还原 systems.json 与 keychain 到 apply 之前的状态，然后删除临时快照文件。 */
    static void restorePrefsConnection(Path stageRoot, String snapshotFile) throws IOException {
        if (snapshotFile == null || snapshotFile.isEmpty()) {
            return;
        }
        String s = runPrefsScript(stageRoot, "restore", Arrays.asList(snapshotFile));
        if (s == null || !s.contains("restored:ok")) {
            System.err.println("[AbapCli]   prefs restore output: " + s);
            return;
        }
        System.out.println("[AbapCli]   prefs restored: " + s.trim());
        try {
            Files.deleteIfExists(Paths.get(snapshotFile));
        } catch (IOException ignored) {
            // 临时文件删除失败可忽略
        }
    }

    /**
     * 运行一次 {@code node _prefs_snapshot.mjs <mode> [args...]}，返回 stdout 的
     * trim 结果；退出码非 0 时打印 stderr 并返回 null。
     */
    private static String runPrefsScript(Path stageRoot, String mode, List<String> args) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(AbapCliRuntime.nodeCommand());
        cmd.add(stageRoot.resolve(PREFS_SNAPSHOT_REL).toString());
        if (mode != null) {
            cmd.add(mode);
        }
        cmd.addAll(args);
        System.out.println("[AbapCli] $ " + String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(stageRoot.toRealPath().toFile());
        pb.redirectErrorStream(true);
        Map<String, String> procEnv = pb.environment();
        String canonicalHome = Paths.get(System.getProperty("user.home")).toRealPath().toString();
        procEnv.put("HOME", canonicalHome);
        procEnv.put("USERPROFILE", canonicalHome);
        procEnv.put("NODE_TLS_REJECT_UNAUTHORIZED", "0");

        try {
            Process p = pb.start();
            List<String> lines = readProcessOutput(p, 60);
            String out = String.join(System.lineSeparator(), lines);
            if (p.exitValue() != 0) {
                int from = Math.max(0, lines.size() - 20);
                System.err.println("[AbapCli]   prefs script exit=" + p.exitValue() + ":\n"
                        + String.join("\n", lines.subList(from, lines.size())));
                return null;
            }
            return out.trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 把 SAP 配置页连接值注入子进程环境（仅当 ABAP CLI URL 已配置）。
     *
     * <p>扩展命令在 loadProfile 时应用这些变量；内置命令忽略它们
     * （靠已 patch 的 systems.json）。</p>
     */
    static void injectPrefsEnv(Map<String, String> env) {
        String url = trimToNull(AIConfiguration.getSapAbapCliUrl());
        if (url == null) {
            return;
        }
        putIfPresent(env, "ABAP_URL", url);
        putIfPresent(env, "ABAP_CLIENT", trimToNull(AIConfiguration.getSapClient()));
        putIfPresent(env, "ABAP_USERNAME", trimToNull(AIConfiguration.getSapUser()));
        putIfPresent(env, "ABAP_LANGUAGE", trimToNull(AIConfiguration.getSapLanguage()));
        String pwd = AIConfiguration.getSapPassword();
        if (pwd != null && !pwd.isEmpty()) {
            env.put("ABAP_PASSWORD", pwd);
        }
    }

    static void putIfPresent(Map<String, String> env, String key, String val) {
        if (val != null && !val.isEmpty()) {
            env.put(key, val);
        }
    }

    static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    static String nonNull(String s) {
        return s == null ? "" : s;
    }

    // ====================================================================
    // 传输请求
    // ====================================================================

    /**
     * 解析传输请求号：用户已输入则直接复用；否则自动创建。
     *
     * <p>自动创建执行 {@code node abap-cli transport create "<desc>" --package <pkg>
     * --yes --pretty-json}，从返回 JSON 的 {@code data.transport} 字段取新请求号。</p>
     *
     * @param desc 自动创建时的请求描述（便于在 SE03 / SE10 里识别）
     * @return 传输请求号；用户输入为空且自动创建失败时返回 null
     */
    static String resolveTransport(Path stageRoot, String ivPackage, String desc,
                                   String transportInput, IProgressMonitor monitor) {
        if (transportInput != null && !transportInput.trim().isEmpty()) {
            System.out.println("[AbapCli]   transport: reuse " + transportInput);
            return transportInput.trim();
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(AbapCliRuntime.nodeCommand());
        cmd.add(stageRoot.resolve(ABAP_CLI_INDEX_REL).toString());
        cmd.addAll(Arrays.asList("transport", "create", desc,
                "--package", ivPackage, "--yes", "--pretty-json"));

        System.out.println("[AbapCli] $ " + String.join(" ", cmd));
        monitor.subTask("transport create: auto-creating workbench request...");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(stageRoot.toRealPath().toFile());
            pb.redirectErrorStream(true);
            Map<String, String> procEnv = pb.environment();
            String canonicalHome = Paths.get(System.getProperty("user.home")).toRealPath().toString();
            procEnv.put("HOME", canonicalHome);
            procEnv.put("USERPROFILE", canonicalHome);
            procEnv.put("NODE_TLS_REJECT_UNAUTHORIZED", "0");

            Process p = pb.start();
            List<String> lines = readProcessOutput(p, DEFAULT_TIMEOUT_SEC);
            String json = String.join(System.lineSeparator(), lines);
            System.out.println("[AbapCli]   transport create -> exit=" + p.exitValue());

            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"transport\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(json);
            if (m.find()) {
                String tr = m.group(1);
                System.out.println("[AbapCli]   → created transport " + tr);
                return tr;
            }
            System.err.println("[AbapCli] transport create: could not parse transport from output:\n"
                    + (lines.size() > 20 ? String.join("\n", lines.subList(lines.size() - 20, lines.size()))
                                         : json));
        } catch (Exception e) {
            System.err.println("[AbapCli] transport create failed: " + e.getMessage());
        }
        return null;
    }

    static List<String> readProcessOutput(Process p, int timeoutSec)
            throws IOException, InterruptedException {
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
                } catch (IOException ignored) {
                }
            }, "abapCli-reader");
            reader.setDaemon(true);
            reader.start();

            long deadline = System.currentTimeMillis() + timeoutSec * 1000L;
            while (reader.isAlive()) {
                if (System.currentTimeMillis() > deadline) {
                    p.destroyForcibly();
                    reader.interrupt();
                    throw new IOException("abap-cli step timed out after " + timeoutSec + "s");
                }
                Thread.sleep(100);
            }
            p.waitFor();
        }
        return lines;
    }

    static boolean isNodeInPath() {
        try {
            Process p = new ProcessBuilder(AbapCliRuntime.nodeCommand(), "--version")
                    .redirectErrorStream(true).start();
            List<String> lines = readProcessOutput(p, 10);
            return p.exitValue() == 0 && !lines.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** Public visibility bridge for the connection tester (different package). */
    public static boolean isNodeAvailable() {
        return isNodeInPath();
    }

    // ====================================================================
    // 解压 / 改名 / 写 .abap.json / 列文件 / 路径工具
    // ====================================================================

    /**
     * 把 zipBytes 解压到 dstDir；条目名若以 "src/" 开头则去掉前缀，
     * 并跳过 abapGit 元数据 {@code .abapgit.xml} 与 {@code src} 目录条目。
     *
     * @return 实际写出的文件数
     */
    static int extractZip(byte[] zipBytes, Path dstDir) throws IOException {
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(
                new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                String rel;
                if (name.startsWith("src/")) {
                    rel = name.substring(4);
                } else if (name.equals(".abapgit.xml") || name.equals("src")) {
                    zis.closeEntry();
                    continue;
                } else {
                    rel = name;
                }
                if (rel.isEmpty()) {
                    zis.closeEntry();
                    continue;
                }
                Path outFile = dstDir.resolve(rel);
                if (entry.isDirectory()) {
                    Files.createDirectories(outFile);
                } else {
                    if (outFile.getParent() != null) Files.createDirectories(outFile.getParent());
                    try (FileOutputStream fos = new FileOutputStream(outFile.toFile())) {
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    count++;
                }
                zis.closeEntry();
            }
        }
        return count;
    }

    /**
     * 在 dstDir 里把 base_lower 替换为 newLower（文件名 + 文本内容）。
     * 文件名规则：整名替换占位符（大小写两形式），保留 abapGit 的双层后缀
     * （见 {@link #renameObjectBaseInFileName}）。
     */
    static RenameStats renameInDir(Path dstDir, String baseLower, String baseUpper,
                                   String newLower, String newUpper) throws IOException {
        RenameStats stats = new RenameStats();
        if (!Files.isDirectory(dstDir)) return stats;

        List<Path> allFiles;
        try (Stream<Path> walk = Files.walk(dstDir)) {
            allFiles = new ArrayList<>();
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(p)) allFiles.add(p);
            }
        }
        allFiles.sort(Comparator.comparingInt((Path p) -> p.toString().length()).reversed());

        for (Path p : allFiles) {
            String name = p.getFileName().toString();

            String newName = renameObjectBaseInFileName(
                    name, baseUpper, baseLower, newUpper, newLower);

            if (newName != null && !newName.equals(name)) {
                Path target = p.resolveSibling(newName);
                if (!Files.exists(target)) {
                    Files.move(p, target);
                    stats.renamedFiles++;
                    p = target;
                    name = newName;
                }
            }

            String ext = extensionOf(name);
            if (ext != null && TEXT_EXTENSIONS.contains(ext.toLowerCase(Locale.ROOT))) {
                byte[] data = Files.readAllBytes(p);
                String text = new String(data, StandardCharsets.UTF_8);
                String orig = text;
                if (text.contains(baseUpper)) {
                    text = text.replace(baseUpper, newUpper);
                }
                if (text.contains(baseLower)) {
                    text = text.replace(baseLower, newLower);
                }
                if (!text.equals(orig)) {
                    Files.write(p, text.getBytes(StandardCharsets.UTF_8));
                    stats.renamedContent++;
                }
            }
        }
        return stats;
    }

    /**
     * 在文件名中整名替换对象占位符（大小写两种形式都替换），保留后缀。
     *
     * <p>abapGit 序列化文件名形如 {@code <obj>.prog.xml}、{@code <obj>_<inc>.prog.abap}，
     * 占位符可能出现于前缀或 include 段，且带双层后缀 {@code .prog.xml} / {@code .prog.abap}。
     * 文件名不含占位符时原样返回。</p>
     */
    static String renameObjectBaseInFileName(String name, String baseUpper,
                                             String baseLower, String newUpper,
                                             String newLower) {
        String out = name;
        if (name.contains(baseUpper)) out = out.replace(baseUpper, newUpper);
        if (out.contains(baseLower)) out = out.replace(baseLower, newLower);
        return out;
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1);
    }

    /**
     * 发现解压目录里由 abapGit 序列化的函数组（{@code <group>.fugr.xml}），
     * 返回小写 group 名（去重、升序）。
     *
     * <p>abapGit 一定会为每个函数组写出 {@code <group>.fugr.xml}，因此可据此
     * 在 {@link #renameFugrLayout} 之前确定 group 名。</p>
     */
    static List<String> discoverFugrGroups(Path dir) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.endsWith(".fugr.xml")) continue;
                String g = name.substring(0, name.length() - ".fugr.xml".length());
                if (!g.isEmpty() && !out.contains(g)) out.add(g);
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    /**
     * 把 abapGit 的 FUGR 文件布局重命名为 abap-cli 内置命令
     * （{@code create FUGR} / {@code push}）期望的布局：
     *
     * <pre>
     *   &lt;group&gt;.fugr.sapl&lt;group&gt;.abap  →  &lt;group&gt;.fugr.sapl&lt;group&gt;.reps.abap
     *   &lt;group&gt;.fugr.l&lt;group&gt;*.abap    →  &lt;group&gt;.fugr.l&lt;group&gt;*.reps.abap
     *   &lt;group&gt;.fugr.&lt;fm&gt;.abap         →  &lt;group&gt;.fugr.&lt;fm&gt;.func.abap
     * </pre>
     *
     * <p>判定规则：中间段以 {@code sapl} 开头（主程序 SAPL&lt;group&gt;），或以
     * {@code l&lt;group&gt;} 开头（include）→ {@code .reps}（FUGR/I）；其余 → {@code .func}
     * （函数模块 FUGR/FF）。{@code *.fugr.xml} 等元数据不改名（FUGR 元数据不能 push），
     * 已带子类型的文件原样跳过（幂等）。</p>
     *
     * @param group 函数组名（大小写不敏感）
     * @return 改名后的文件数
     */
    static int renameFugrLayout(Path dir, String group) throws IOException {
        if (!Files.isDirectory(dir) || group == null || group.isEmpty()) return 0;
        String g = group.toLowerCase(Locale.ROOT);
        String prefix = g + ".fugr.";
        int renamed = 0;

        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = new ArrayList<>();
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(p)) files.add(p);
            }
        }
        for (Path p : files) {
            String lower = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!lower.startsWith(prefix) || !lower.endsWith(".abap")) continue;
            String seg = lower.substring(prefix.length(), lower.length() - ".abap".length());
            if (seg.indexOf('.') >= 0) continue; // 已带子类型（.reps / .func），幂等
            boolean reps = seg.startsWith("sapl") || seg.startsWith("l" + g);
            String newName = lower.substring(0, lower.length() - ".abap".length())
                    + (reps ? ".reps.abap" : ".func.abap");
            Path target = p.resolveSibling(newName);
            if (!target.equals(p) && !Files.exists(target)) {
                Files.move(p, target);
                renamed++;
            }
        }
        return renamed;
    }

    /**
     * 扫描解压目录，返回 ZIP 里存在、但本流程不会导入的 abapGit 对象类型（去重升序）。
     * 仅识别严格的三段名 {@code <object>.<type>.<ext>}；{@code devc}（包自身）不计入。
     *
     * @param handled 本流程已能导入的对象类型（小写），如 prog / tabl / ttyp / fugr
     */
    static List<String> findUnhandledObjectTypes(Path dir, Set<String> handled) throws IOException {
        Set<String> out = new java.util.TreeSet<>();
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                String[] parts = p.getFileName().toString().split("\\.");
                if (parts.length != 3) continue;
                String type = parts[1].toLowerCase(Locale.ROOT);
                if (handled.contains(type) || "devc".equals(type)) continue;
                out.add(type);
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 写 {@code .abap.json}（覆盖现有），注册给定扩展 + 用户输入的包名。
     *
     * <p>{@code system} 字段由
     * {@link com.sap.abap.ai.completion.sap.AbapCliConnectionTester#resolveSystemName}
     * 解析（缺失时任何 abap-cli 命令都会报
     * {@code CONFIG_ERROR: Missing "system" in .abap.json}）。</p>
     */
    static void writeAbapJson(Path target, String ivPackage, String sourceDir,
                              List<String> extensionNames) throws IOException {
        String systemName;
        try {
            systemName = com.sap.abap.ai.completion.sap.AbapCliConnectionTester
                    .resolveSystemName(target.getParent());
        } catch (Exception e) {
            throw new IOException("Cannot resolve abap-cli system profile: " + e.getMessage(), e);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"system\": \"").append(jsonEscape(systemName)).append("\",\n");
        sb.append("  \"package\": \"").append(jsonEscape(ivPackage)).append("\",\n");
        sb.append("  \"sourceDir\": \"").append(jsonEscape(sourceDir)).append("\",\n");
        sb.append("  \"extensions\": [\n");
        for (int i = 0; i < extensionNames.size(); i++) {
            String name = extensionNames.get(i);
            sb.append("    { \"name\": \"").append(name).append("\", \"type\": \"command\",\n")
              .append("      \"source\": { \"sourceType\": \"path\", \"path\": \"")
              .append(EXTENSIONS_DIR_REL).append("/").append(name).append(".mjs\" } }");
            if (i < extensionNames.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        Files.createDirectories(target.getParent());
        Files.write(target, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 列出 dir 下 ext 后缀的常规文件，按文件名排序。 */
    static List<Path> collectByExt(Path dir, String ext) throws IOException {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(ext)) {
                    out.add(p);
                }
            }
        }
        out.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return out;
    }

    /** 把 file 的绝对路径转成 相对 stage 的 POSIX 路径。 */
    static String relToStage(Path stageRoot, Path file) {
        try {
            Path stageAbs = stageRoot.toAbsolutePath().normalize();
            Path fileAbs = file.toAbsolutePath().normalize();
            return stageAbs.relativize(fileAbs).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.toString().replace('\\', '/');
        }
    }

    static List<String> relToStage(Path stageRoot, List<Path> files) {
        List<String> out = new ArrayList<>();
        for (Path p : files) out.add(relToStage(stageRoot, p));
        return out;
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ====================================================================
    // 对象锁（ENQUEUE）检测
    // ====================================================================

    /**
     * 读取步骤结果文件，识别 ENQUEUE 对象锁冲突。
     *
     * <p>abap-cli 扩展旧实现对 pretty-json 分支不设非零退出码，可能误判成功；
     * 这里主动扫描结果文件，命中即把被锁对象名 + 锁持有者 + SE03/SE10 释放指引
     * 写入步骤 message，并把该步骤标记为 locked。</p>
     *
     * @return true 表示检测到锁冲突
     */
    static boolean detectLock(StepOutcome o) {
        if (o.resultFile == null || !Files.isRegularFile(o.resultFile)) {
            return false;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(o.resultFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return false;
        }

        List<String> names = new ArrayList<>();
        List<String> details = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = trimToNull(lines.get(i).replaceAll("\\s+", " "));
            if (trimmed == null || !containsAny(trimmed, LOCK_MARKERS)) {
                continue;
            }
            String raw = objectNameFromLockLine(trimmed);
            if (raw == null) {
                raw = objectNameFromBlock(lines, i);
            }
            String display = objectDisplayName(lines, raw);

            if (!names.contains(display)) {
                names.add(display);
            }
            String request = firstGroup(trimmed, LOCK_REQUEST_PATTERN);
            String user = firstGroup(trimmed, LOCK_USER_PATTERN);
            StringBuilder d = new StringBuilder();
            d.append("      • ").append(display);
            if (request != null) {
                d.append(" — locked in request ").append(request);
            }
            if (user != null) {
                d.append(request != null ? " by user " : " — locked by user ").append(user);
            }
            d.append('\n');
            d.append("        \"").append(trimmed).append('"');
            details.add(d.toString());
        }
        if (details.isEmpty()) {
            return false;
        }

        o.lockedObjects.addAll(names);
        o.message = "Object lock conflict — the following object(s) were NOT imported:\n"
                + String.join("\n", details) + "\n"
                + "\n"
                + "The import FAILED. Release the object lock and re-run the import:\n"
                + "  • SE03 → 传输组织器 → 请求/任务 → 释放对象锁\n"
                + "  • or SE10 → 删除/撤销该锁定条目\n"
                + "  • or ask the editing user to save & release.";
        if (o.exitCode == 0) {
            o.exitCode = 1;
        }
        return true;
    }

    /** 从锁冲突行本身提取被锁对象名；提取不到返回 null。 */
    private static String objectNameFromLockLine(String line) {
        java.util.regex.Matcher m = LOCK_NAME_BEFORE.matcher(line);
        if (m.find()) {
            return m.group(1).toUpperCase(Locale.ROOT);
        }
        m = LOCK_NAME_AFTER_EDIT.matcher(line);
        if (m.find()) {
            return m.group(1).toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /** 锁冲突行所在 JSON 块的对象名（向上找最近的 "object": "..." 字段）；找不到返回 null。 */
    private static String objectNameFromBlock(List<String> lines, int lockLineIdx) {
        for (int i = lockLineIdx; i >= 0; i--) {
            java.util.regex.Matcher m = JSON_OBJECT_FIELD.matcher(lines.get(i));
            if (m.find()) {
                return m.group(1).toUpperCase(Locale.ROOT);
            }
        }
        return null;
    }

    /**
     * 被锁对象的可读名，如 {@code INCLUDE ZTR_SHOW_JD} / {@code PROGRAM ZTEST40}。
     * 类型取自同一结果文件里的 {@code parsed: NAME=<对象> SUBC=<值>} 行。
     */
    private static String objectDisplayName(List<String> lines, String objectName) {
        if (objectName == null) {
            return "(unknown object)";
        }
        String subc = null;
        String prefix = "NAME=" + objectName;
        for (String line : lines) {
            String t = line.trim();
            int p = t.indexOf(prefix + " ");
            if (p < 0) {
                continue;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("SUBC=([A-Za-z0-9]+)").matcher(t.substring(p));
            if (m.find()) {
                subc = m.group(1);
                break;
            }
        }
        String type = objectTypeLabel(subc);
        return type == null ? objectName : type + " " + objectName;
    }

    /** SUBC 值 → 简短对象类型标签（用于锁冲突点名）；未知返回 null。 */
    private static String objectTypeLabel(String subc) {
        if (subc == null) {
            return null;
        }
        switch (subc.trim().toUpperCase(Locale.ROOT)) {
            case "1": return "PROGRAM";
            case "I": return "INCLUDE";
            case "M": return "MODULE POOL";
            case "S": return "SUBROUTINE POOL";
            case "T": return "TYPE POOL";
            case "X": return "FLOW LOGIC";
            default:  return null;
        }
    }

    /** 取正则的第一个捕获组；未命中返回 null。 */
    private static String firstGroup(String s, String regex) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static boolean containsAny(String s, String[] markers) {
        for (String m : markers) {
            if (s != null && s.contains(m)) {
                return true;
            }
        }
        return false;
    }

    // ====================================================================
    // UI 辅助（输入/结果对话框辅助）
    // ====================================================================

    /**
     * 弹出必填输入框。{@code allowSlashAndDollar} 为 true 时允许字符 '/' 与 '$'（用于包名）。
     * 名称必须以 Z / Y 开头，自动大写，最长 30 字符。
     *
     * @return 规范化后的输入；用户取消返回 null
     */
    static String promptZy(String title, String message, boolean allowSlashAndDollar) {
        final String[] holder = { null };
        try {
            Display.getDefault().syncExec(() -> {
                Shell shell = getShell();
                while (true) {
                    InputDialog dlg = new InputDialog(shell, title, message, "", null) {
                        @Override
                        protected Control createDialogArea(Composite parent) {
                            Control area = super.createDialogArea(parent);
                            Text txt = getText();
                            if (txt != null) {
                                txt.addListener(SWT.Verify, new Listener() {
                                    @Override
                                    public void handleEvent(Event e) {
                                        String in = e.text;
                                        if (in == null || in.isEmpty()) return;
                                        String up = in.toUpperCase(Locale.ROOT);
                                        for (int i = 0; i < up.length(); i++) {
                                            char c = up.charAt(i);
                                            boolean allowed =
                                                    (c >= 'A' && c <= 'Z')
                                                    || (c >= '0' && c <= '9')
                                                    || c == '_'
                                                    || (allowSlashAndDollar && (c == '/' || c == '$'));
                                            if (!allowed) {
                                                e.doit = false;
                                                return;
                                            }
                                        }
                                        e.text = up;
                                    }
                                });
                            }
                            return area;
                        }
                    };
                    if (dlg.open() != Window.OK) {
                        holder[0] = null;
                        return;
                    }
                    String v = dlg.getValue();
                    if (v == null || v.trim().isEmpty()) {
                        MessageDialog.openWarning(shell, title,
                                "Input cannot be empty. Please enter a value.");
                        continue;
                    }
                    String norm = v.trim().toUpperCase(Locale.ROOT);
                    char first = norm.charAt(0);
                    if (first != 'Z' && first != 'Y') {
                        MessageDialog.openWarning(shell, title,
                                "The name must start with Z or Y (customer namespace).");
                        continue;
                    }
                    if (norm.length() > 30) {
                        MessageDialog.openWarning(shell, title,
                                "The name is too long (max 30 chars). Please re-enter.");
                        continue;
                    }
                    holder[0] = norm;
                    return;
                }
            });
        } catch (Exception e) {
            return null;
        }
        return holder[0];
    }

    /**
     * 弹出可空输入框（用于传输请求号）。返回规范化文本，取消返回 null，留空返回空串。
     * 与 {@link #promptZy} 不同：不以 Z/Y 开头（如 S4DK910369），也不强制大写。
     */
    static String prompt(String title, String message) {
        final String[] holder = { null };
        try {
            Display.getDefault().syncExec(() -> {
                Shell shell = getShell();
                InputDialog dlg = new InputDialog(shell, title, message, "", null);
                if (dlg.open() != Window.OK) {
                    holder[0] = null;
                    return;
                }
                String v = dlg.getValue();
                holder[0] = (v == null) ? "" : v.trim();
            });
        } catch (Exception e) {
            return null;
        }
        return holder[0];
    }

    static Shell getShell() {
        try {
            if (PlatformUI.getWorkbench() == null) return null;
            Shell shell = PlatformUI.getWorkbench().getDisplay().getActiveShell();
            if (shell == null || shell.isDisposed()) {
                IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window != null) shell = window.getShell();
            }
            return (shell != null && !shell.isDisposed()) ? shell : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 本次导入中因对象锁冲突而未能导入的对象（可读名），按出现顺序去重。
     */
    static List<String> lockedObjectNames(ImportResult r) {
        List<String> out = new ArrayList<>();
        for (StepOutcome o : r.steps) {
            for (String n : o.lockedObjects) {
                if (!out.contains(n)) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    // ====================================================================
    // 日志（Eclipse Error Log + 插件 state area 日志文件）
    // ====================================================================

    /**
     * 写入一条导入明细：同时进 Eclipse Error Log（逐行）与插件日志文件。
     *
     * @param fileLines 插件日志文件的行缓冲
     */
    static void emit(List<String> fileLines, int severity, String text) {
        logLine(severity, text);
        fileLines.add("[" + levelTag(severity) + "] " + (text == null ? "" : text));
    }

    /** 日志级别标签，用作日志文件的行前缀。 */
    static String levelTag(int severity) {
        if (severity == IStatus.ERROR) return "ERROR  ";
        if (severity == IStatus.WARNING) return "WARNING";
        return "INFO   ";
    }

    /**
     * 本次导入的日志文件路径，位于插件 state area
     * {@code <workspace>/.metadata/.plugins/com.sap.abap.ai.completion/}。
     *
     * @param name 文件名中的标识（如程序名 / 模板名）
     * @return 日志文件路径；插件未初始化或取不到 state area 时返回 {@code null}
     */
    static Path resolveImportLogFile(String name) {
        try {
            if (Activator.getDefault() == null) return null;
            IPath state = Platform.getStateLocation(Activator.getDefault().getBundle());
            if (state == null) return null;
            String ts;
            synchronized (LOG_TS_FMT) {
                ts = LOG_TS_FMT.format(new Date());
            }
            String safe = (name == null || name.isEmpty()) ? "UNKNOWN" : name;
            return state.append("import_" + safe + "_" + ts + ".log")
                    .toFile()
                    .toPath();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把本次导入明细追加写入插件日志文件。
     *
     * @param header 日志文件头（如 "===== Simple Query Handler Template import ====="）
     */
    static void writeImportLogFile(Path logFile, List<String> fileLines, String header) {
        if (logFile == null) return;
        try {
            Files.createDirectories(logFile.getParent());
            StringBuilder sb = new StringBuilder();
            if (header != null) {
                sb.append(header).append(System.lineSeparator());
            }
            for (String line : fileLines) {
                sb.append(line).append(System.lineSeparator());
            }
            sb.append(System.lineSeparator());
            Files.write(logFile, sb.toString().getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // 写文件失败不影响主流程
        }
    }

    /** 写入一条 Eclipse Error Log 记录（Window → Show View → Error Log）。 */
    static void logLine(int severity, String text) {
        try {
            Activator activator = Activator.getDefault();
            if (activator == null || activator.getLog() == null) return;
            activator.getLog().log(new Status(severity, Activator.PLUGIN_ID,
                    text == null ? "" : text));
        } catch (Exception ignored) {
            // 日志失败不影响主流程
        }
    }

    static void showError(String title, String message) {
        try {
            Display.getDefault().asyncExec(() ->
                    MessageDialog.openError(getShell(), title, message));
        } catch (Exception ignored) {
        }
    }

    static String stackTrace(Throwable t) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            return sw.toString();
        } catch (Exception e) {
            return t.getClass().getName() + ": " + t.getMessage();
        }
    }

    static String padRight(String s, int n) {
        if (s.length() >= n) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    // ====================================================================
    // Bundle 资源读取（从 Activator 读取插件内资源）
    // ====================================================================

    static byte[] readBundleResource(String resourcePath) throws IOException {
        if (Activator.getDefault() == null) {
            throw new IOException("Plugin is not initialized; cannot read resource: " + resourcePath);
        }
        java.net.URL url = Activator.getDefault().getBundle().getEntry(resourcePath);
        if (url == null) throw new IOException("Resource not found in bundle: " + resourcePath);
        try (InputStream in = url.openStream()) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    // ====================================================================
    // 数据结构
    // ====================================================================

    /** 单步结果。 */
    static final class StepOutcome {
        final int idx;
        final String name;
        int exitCode;
        final Path resultFile;
        String message;

        /** 该步骤失败的原因是对象被其它用户 / 传输请求锁定（ENQUEUE）。 */
        boolean locked;

        /** 被锁定的对象（可读名，如 "INCLUDE ZTR_SHOW_JD"），按出现顺序，供对话框/日志点名。 */
        final List<String> lockedObjects = new ArrayList<>();

        StepOutcome(int idx, String name, int exitCode, Path resultFile, String message) {
            this.idx = idx;
            this.name = name;
            this.exitCode = exitCode;
            this.resultFile = resultFile;
            this.message = message;
        }
    }

    /** 改名统计。 */
    static final class RenameStats {
        int renamedFiles = 0;
        int renamedContent = 0;
    }

    /** 总结果。 */
    static final class ImportResult {
        /** 被替换的占位符（大写）；无占位符流程可为 null。 */
        String placeholderUpper;
        String transportRequest;
        int renamedFiles;
        int renamedContent;
        int extractedCount;
        List<StepOutcome> steps;
        int overallRc;
        /** ZIP 里存在但本流程未导入的 abapGit 对象类型（如 doma / dtel / fugr）。 */
        List<String> unhandledObjectTypes;
    }

    /** 环境检查失败异常（不计入整体异常流）。 */
    static final class EnvironmentException extends Exception {
        private static final long serialVersionUID = 1L;
        EnvironmentException(String msg) { super(msg); }
    }
}
