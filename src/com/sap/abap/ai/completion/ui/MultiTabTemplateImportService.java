package com.sap.abap.ai.completion.ui;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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
 * Simple Query Handler Template 导入服务（菜单项触发）。
 *
 *
 * <p>参照 {@code D:\Users\96000217\Documents\trae_projects\abapgit} 项目
 * {@code ZTemplate10Import.java} 的 10 步编排，但适配 Eclipse 插件环境：</p>
 *
 * <ol>
 *   <li>环境检查（stage / extensions / node）</li>
 *   <li>弹窗输入 PACKAGE / 程序名</li>
 *   <li>从 bundle 解压 {@code references/ZTEMPLATE10.zip} 到 stage 工作区</li>
 *   <li>把解压目录里所有 {@code ztemplate10}（文件名 + 文本文件内容）替换为用户输入的新名字</li>
 *   <li>写 {@code .abap.json}（注册 6 个 abap-cli 扩展 + 用户输入的包名）</li>
 *   <li>调 {@code node abap-cli} 的 7 步：
 *       {@code prog-xml-create} → {@code ddic-xml-create} →
 *       {@code prog-xml-update} → {@code prog-source-push} →
 *       {@code prog-xml-push} → {@code inspect} → {@code prog-xml-verify}</li>
 *   <li>汇总 7 步退出码并弹结果对话框</li>
 * </ol>
 *
 *
 * <p><b>依赖</b>：用户机器上必须已存在 abap-cli stage 工作区
 * {@link #STAGE_DIR}，其下含 {@code node_modules/abap-cli/} 与 6 个扩展
 * {@code extensions/prog-*.mjs} + {@code extensions/ddic-xml-create.mjs}。
 * {@code node} 必须在 PATH 中。HOME /
 * USERPROFILE 必须可解析（keychain 凭据在 {@code ~/.abap-cli/systems.json}）。</p>
 *
 * <p><b>为何独立实现</b>：旧的“模板导入”走 RFC
 * {@code Z_ABAPGIT_UPLOAD_FROM_XSTRING}（JCo 单次上传），与 abap-cli 扩展流
 * （按对象逐个 create/source-push/dynpro/CUA/textpool/verify）是不同的实现。
 * 用户明确"现程序应该已有，尽量不用修改"，因此独立编写本类。</p>
 */
public final class MultiTabTemplateImportService {

    /** 用户机器上的 abap-cli stage 根目录（abap-cli node_modules + 6 个扩展均在此）。 */
    public static final String STAGE_DIR =
            "D:\\Users\\96000217\\Documents\\trae_projects\\_abap-cli-stage";

    /** 模板 zip 在 bundle 内的资源路径。 */
    private static final String ZIP_RESOURCE_PATH = "references/ZTEMPLATE10.zip";

    /** 解压目录（相对 stage）；对应 .abap.json 的 sourceDir。 */
    private static final String REPO_SRC_REL = "repos/ztemplate10/src";

    /** abap-cli node 入口（相对 stage）。 */
    public static final String ABAP_CLI_INDEX_REL =
            "node_modules/abap-cli/dist/src/abap_cli/index.js";

    /** abap-cli 扩展目录（相对 stage）。 */
    private static final String EXTENSIONS_DIR_REL = "extensions";

    /**
     * abap-cli 扩展名（按 ZTemplate10Import 顺序 + DDIC 导入扩展）。
     *
     * <p><b>ddic-xml-create</b>：把 {@code *.tabl.xml}（TABCLASS=INTTAB → 结构、
     * TRANSP → 透明表）与 {@code *.ttyp.xml}（表类型）真正建成并激活。
     * 缺了它，ZIP 里的 zsztest10.tabl.xml / ztztest10.tabl.xml /
     * zttztest10.ttyp.xml 永远不会被推送，SE11 里也就看不到对应的
     * ZSZTEST10 / ZTZTEST10 / ZTTZTEST10。</p>
     */
    private static final List<String> EXTENSION_NAMES = Arrays.asList(
            "prog-xml-create", "ddic-xml-create", "prog-xml-update",
            "prog-source-push", "prog-xml-push", "prog-xml-verify");

    /**
     * 进度对话框中的步骤总数：
     * prog-xml-create / ddic-xml-create / prog-xml-update / prog-source-push /
     * prog-xml-push / inspect / prog-xml-verify。
     */
    private static final int TOTAL_STEPS = 7;

    /** 文本类文件扩展名（用于 zip 内占位符重命名）。 */
    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
                "abap", "xml", "asddls", "asbdef", "ddls", "ddlx", "asddlxs",
                "bdef", "clas", "intf", "prog", "tabl", "doma", "dtel",
                "txt", "md", "json", "properties"));

    /** 主程序占位符（与 ZIP 中现存模板主程序名一致）。 */
    private static final String PLACEHOLDER_LOWER = "ztemplate10";
    private static final String PLACEHOLDER_UPPER = "ZTEMPLATE10";

    /** 子进程默认超时（秒）。 */
    private static final int DEFAULT_TIMEOUT_SEC = 600;

    /** 导入日志文件名里的时间戳格式（插件 state area 下的 import_<程序名>_<时间戳>.log）。 */
    private static final SimpleDateFormat LOG_TS_FMT = new SimpleDateFormat("yyyyMMddHHmmss");

    /**
     * abap-cli 输出中"对象锁冲突（ENQUEUE）"的标记短语。
     * 命中即判定对象被其它用户/传输请求锁定（如"使用者 GWDEV45 当前编辑
     * ZTEST10"、"already locked in request S4DK906927"），需要用户在
     * SE03 / SE10 释放锁后再重试。
     */
    private static final String[] LOCK_MARKERS = {
        "already locked",
        "locked in request",
        "current editing",
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

    private MultiTabTemplateImportService() {
    }

    /**
     * 公共入口：从菜单点击触发。
     * 流程：环境检查 → 输入 → 进度对话框跑 7 步 → 弹结果。
     */
    public static void runImport() {
        // ===== [1] 环境检查 =====
        Path stageRoot;
        try {
            stageRoot = checkEnvironment();
        } catch (EnvironmentException ee) {
            showError("Simple Query Handler — 环境检查失败", ee.getMessage());
            return;
        }

        // ===== [2] 输入 PACKAGE / 程序名 =====
        String ivPackage = promptZy("Simple Query Handler Template",
                "Please enter the target SAP package (e.g., ZDEV_PACKAGE or $TMP).\n"
                        + "Must start with Z or Y; '/' and '$' allowed; auto-uppercase.",
                true);
        if (ivPackage == null) return;

        String ivProgName = promptZy("Simple Query Handler Template",
                "Please enter the new program name (e.g., ZMY_MT_QUERY).\n"
                        + "Must start with Z or Y; English letters / digits / '_' only; "
                        + "max 30 chars; auto-uppercase.\n"
                        + "The placeholder " + PLACEHOLDER_UPPER
                        + " will be renamed to this name in the uploaded objects.",
                false);
        if (ivProgName == null) return;

        // [2.5] 传输请求（可选）。留空 = 自动创建新请求。
        // 根因修复：S4DEV 的 source/textpool 推送要求 corrNr（缺省报
        // "Parameter corrNr could not be found"），而旧实现从不设 ABAP_TRANSPORT。
        // 这里让用户既可复用已有请求号，也可留空由插件自动 transport create。
        String ivTransport = prompt("Simple Query Handler Template",
                "SAP transport request number (optional).\n"
                        + "Leave EMPTY to let the plugin AUTO-CREATE a new workbench request\n"
                        + "inside the target package (recommended; S4DEV requires corrNr).\n"
                        + "Or enter an existing open request, e.g. S4DK910369.");
        if (ivTransport == null) return; // 用户取消
        final String transportInputFinal = ivTransport.trim().isEmpty() ? null : ivTransport.trim();

        // ===== 进度对话框跑解压+改名+7 步 abap-cli =====
        final Path stageFinal = stageRoot;
        final String pkgFinal = ivPackage;
        final String progFinal = ivProgName;
        final ImportResult[] holder = { null };
        final Exception[] errorHolder = { null };

        ProgressMonitorDialog pmd = new ProgressMonitorDialog(getShell());
        try {
            pmd.run(true, false, new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor) {
                    try {
                        holder[0] = executeAll(stageFinal, pkgFinal, progFinal, transportInputFinal, monitor);
                    } catch (Exception e) {
                        errorHolder[0] = e;
                    } finally {
                        monitor.done();
                    }
                }
            });
        } catch (Exception e) {
            errorHolder[0] = e;
        }

        if (errorHolder[0] != null) {
            showError("Simple Query Handler — Failed",
                    "Unexpected error during import:\n\n" + stackTrace(errorHolder[0]));
            return;
        }

        // ===== 弹结果：对话框只报 成功 / 失败 / 部分失败，明细走 Eclipse Error Log =====
        ImportResult r = holder[0];
        final boolean anyLock = hasLock(r);

        final String status;
        final String title;
        final int overallSev;
        if (r.overallRc != 0) {
            status = "FAILED";
            title = "Import Failed";
            overallSev = IStatus.ERROR;
        } else if (anyLock) {
            status = "PARTIALLY FAILED";
            title = "Import Partially Failed";
            overallSev = IStatus.WARNING;
        } else {
            status = "SUCCESS";
            title = "Import Succeeded";
            overallSev = IStatus.INFO;
        }

        // 明细逐行写入 Error Log（每行一条记录）+ 插件 state area 日志文件，对话框不再展开。
        Path importLogFile = logImportDetails(r, stageFinal, pkgFinal, progFinal, overallSev);

        StringBuilder sb = new StringBuilder();
        sb.append("Simple Query Handler Template → SAP\n\n");
        sb.append("  Result    : ").append(status).append('\n');
        sb.append("  Package   : ").append(pkgFinal).append('\n');
        sb.append("  Program   : ").append(progFinal).append('\n');
        sb.append("  Transport : ").append(r.transportRequest != null
                ? r.transportRequest : "(none / skipped)").append('\n');
        if (anyLock) {
            sb.append("\n  Some objects were skipped because they are locked by another user.\n");
        }
        sb.append("\n  Details were written to:\n");
        sb.append("    • Eclipse Error Log : Window → Show View → Error Log\n");
        if (importLogFile != null) {
            sb.append("    • Import log file   : ").append(importLogFile);
        } else {
            sb.append("    • Import log file   : (unavailable)");
        }

        if (overallSev == IStatus.ERROR) {
            MessageDialog.openError(getShell(), title, sb.toString());
        } else if (overallSev == IStatus.WARNING) {
            MessageDialog.openWarning(getShell(), title, sb.toString());
        } else {
            MessageDialog.openInformation(getShell(), title, sb.toString());
        }
    }

    // ====================================================================
    // 主流程：解压 → 改名 → 写 .abap.json → 跑 7 步
    // ====================================================================

    /**
     * 解压 + 改名 + 写 .abap.json + 7 步 abap-cli。
     *
     * <p>{@code transportInput} 为 null 或空 = 自动创建新传输请求；否则复用该请求号。
     * 解析出的传输号会在 7 个步骤中以 {@code ABAP_TRANSPORT} 环境变量注入
     * （S4DEV 的 source/textpool 推送缺 corrNr 会报
     * <i>Parameter corrNr could not be found</i>，这是本类历史故障根因）。</p>
     *
     * 全部完成后返回 ImportResult。
     */
    private static ImportResult executeAll(Path stageRoot, String ivPackage, String ivProgName,
                                          String transportInput, IProgressMonitor monitor) throws Exception {
        monitor.beginTask("Importing Simple Query Handler Template\n"
                        + "Package: " + ivPackage + "   Program: " + ivProgName
                        + "   Stage: " + stageRoot,
                IProgressMonitor.UNKNOWN);

        // [2.4] 应用 SAP 配置页连接信息：临时改写 ~/.abap-cli/systems.json 的
        //       profile + keychain 密码，使内置命令（inspect / transport create）
        //       与扩展命令都走配置页连接；finally 中还原。
        String prefsSnapshotFile = applyPrefsConnection(stageRoot, monitor);
        System.out.println("[MultiTab]   prefsSnapshot="
                + (prefsSnapshotFile == null ? "(skip)" : prefsSnapshotFile));
        try {

        // [2.5] 解析传输请求（用户输入 or 自动创建）
        monitor.subTask("[2.5] Resolving transport request...");
        String transport = resolveTransport(stageRoot, ivPackage, ivProgName, transportInput, monitor);
        monitor.worked(1);
        System.out.println("[MultiTab]   → transport=" + transport);

        // [3] 解压 ZIP
        monitor.subTask("[3] Extracting ZTEMPLATE10.zip to " + REPO_SRC_REL);
        Path dstDir = stageRoot.resolve(REPO_SRC_REL);
        byte[] zipBytes = readBundleResource(ZIP_RESOURCE_PATH);
        deleteRecursively(dstDir);
        Files.createDirectories(dstDir);
        int extractedCount = extractZip(zipBytes, dstDir);
        monitor.worked(1);

        // [4] 改名（文件名 + 文件内容）
        monitor.subTask("[4] Renaming placeholder " + PLACEHOLDER_UPPER
                + " → " + ivProgName);
        RenameStats stats = renameInDir(dstDir, PLACEHOLDER_LOWER, PLACEHOLDER_UPPER,
                ivProgName.toLowerCase(Locale.ROOT), ivProgName.toUpperCase(Locale.ROOT));
        monitor.worked(1);

        // [5] 写 .abap.json
        monitor.subTask("[5] Writing .abap.json (package=" + ivPackage + ")");
        writeAbapJson(stageRoot.resolve(".abap.json"), ivPackage, REPO_SRC_REL);
        monitor.worked(1);

        // 收集文件
        List<Path> xmlFiles = collectByExt(dstDir, ".prog.xml");
        List<Path> abapFiles = collectByExt(dstDir, ".prog.abap");
        Path mainXml = dstDir.resolve(ivProgName.toLowerCase(Locale.ROOT) + ".prog.xml");

        // DDIC 文件：*.tabl.xml（结构 / 透明表）+ *.ttyp.xml（表类型）。
        // 顺序敏感——表类型（zttztest10.ttyp.xml）的行类型（ROWTYPE=ZSZTEST10）
        // 必须先是一个已激活的表/结构，因此 .tabl.xml 必须排在 .ttyp.xml 之前。
        List<Path> ddicXmlFiles = new ArrayList<>(collectByExt(dstDir, ".tabl.xml"));
        ddicXmlFiles.addAll(collectByExt(dstDir, ".ttyp.xml"));

        // 7 步 abap-cli
        List<StepOutcome> steps = new ArrayList<>();

        // [6.1] prog-xml-create
        steps.add(runAbapCliStep(1, "prog-xml-create",
                stageRoot,
                new ArrayList<>(Arrays.asList("prog-xml-create")),
                relToStage(stageRoot, xmlFiles),
                stageRoot.resolve("_simple_query_handler_create_result.txt"),
                transport,
                monitor));
        // create 退出码只用于决定后续是否还要跑 update（对象已存在时 update 才有意义）；
        // 整体成败由流程末尾统一判定。
        final boolean createOk = steps.get(steps.size() - 1).exitCode == 0;

        // [6.2] ddic-xml-create（结构 / 透明表 / 表类型）
        // 必须早于 prog-source-push：程序源码里引用了 ZTTZTEST10 等类型，
        // 类型不存在时源码推上去也无法激活。
        if (ddicXmlFiles.isEmpty()) {
            steps.add(new StepOutcome(2, "ddic-xml-create", 0, null,
                    "no *.tabl.xml / *.ttyp.xml in src — DDIC step skipped"));
        } else {
            steps.add(runAbapCliStep(2, "ddic-xml-create",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("ddic-xml-create")),
                    relToStage(stageRoot, ddicXmlFiles),
                    stageRoot.resolve("_simple_query_handler_ddic_result.txt"),
                    transport,
                    monitor));
            // DDIC 非零不即刻阻断（仍继续推送源码，尽量多建对象），
            // 但整体判失败由下方 steps 校验统一处理。
        }

        // [6.3] prog-xml-update
        if (createOk) {
            steps.add(runAbapCliStep(3, "prog-xml-update",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-xml-update")),
                    relToStage(stageRoot, xmlFiles),
                    stageRoot.resolve("_simple_query_handler_update_result.txt"),
                    transport,
                    monitor));
            // update 非零不阻断（对象已由 create 建好，源码由 source-push 推送）
        }

        // [6.4] prog-source-push
        steps.add(runAbapCliStep(4, "prog-source-push",
                stageRoot,
                new ArrayList<>(Arrays.asList("prog-source-push")),
                relToStage(stageRoot, abapFiles),
                stageRoot.resolve("_simple_query_handler_source_push_result.txt"),
                transport,
                monitor));

        // [6.5] prog-xml-push (DYNPROS / CUA / I18N_TPOOL)
        if (Files.isRegularFile(mainXml)) {
            steps.add(runAbapCliStep(5, "prog-xml-push",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-xml-push")),
                    Arrays.asList(relToStage(stageRoot, mainXml)),
                    stageRoot.resolve("_simple_query_handler_xml_push_result.txt"),
                    transport,
                    monitor));
            // xml-push 非零不阻断
        } else {
            steps.add(new StepOutcome(5, "prog-xml-push", -1, null,
                    "main prog.xml not found: " + mainXml));
        }

        // [6.6] inspect
        steps.add(runAbapCliStep(6, "inspect",
                stageRoot,
                new ArrayList<>(Arrays.asList("inspect", ivProgName, "--includes", "--pretty-json")),
                new ArrayList<>(),
                stageRoot.resolve("_simple_query_handler_inspect_result.txt"),
                transport,
                monitor));

        // [6.7] prog-xml-verify
        if (Files.isRegularFile(mainXml)) {
            steps.add(runAbapCliStep(7, "prog-xml-verify",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-xml-verify")),
                    Arrays.asList(relToStage(stageRoot, mainXml)),
                    stageRoot.resolve("_simple_query_handler_verify_result.txt"),
                    transport,
                    monitor));
        } else {
            steps.add(new StepOutcome(7, "prog-xml-verify", -1, null,
                    "main prog.xml not found: " + mainXml));
        }

        // 整体成败判定：
        //   • 关键步骤（prog-xml-create / ddic-xml-create / prog-source-push）非零退出
        //     = 对象没建出来或源码没推上去 → 失败，避免返回 "Overall: OK" 却在
        //     SE38 / SE11 里找不到对象的假象。
        //   • 对象被其它用户或传输请求锁定（ENQUEUE）属环境副作用而非导入失败：
        //     其余对象通常已成功，只作告警提示，不影响成败判定。
        int overall = 0;
        for (StepOutcome o : steps) {
            boolean locked = detectLock(o);
            o.locked = locked;
            if (o.message != null && o.message.contains("not found")) {
                overall = -1;
                continue;
            }
            if (locked) {
                continue;
            }
            if (o.exitCode != 0
                    && ("prog-xml-create".equals(o.name)
                        || "ddic-xml-create".equals(o.name)
                        || "prog-source-push".equals(o.name))) {
                overall = -1;
            }
        }

        ImportResult r = new ImportResult();
        r.placeholderUpper = PLACEHOLDER_UPPER;
        r.transportRequest = transport;
        r.renamedFiles = stats.renamedFiles;
        r.renamedContent = stats.renamedContent;
        r.steps = steps;
        r.overallRc = overall;
        r.extractedCount = extractedCount;
        return r;
        } finally {
            // 无论成功失败，都还原 ~/.abap-cli/systems.json + keychain，
            // 避免污染用户其它 abap-cli 使用。
            if (prefsSnapshotFile != null) {
                try {
                    restorePrefsConnection(stageRoot, prefsSnapshotFile);
                } catch (Exception e) {
                    System.err.println("[MultiTab] prefs restore failed: " + e.getMessage());
                }
            }
        }
    }

    // ====================================================================
    // 步骤执行：调一次 node abap-cli
    // ====================================================================

    /**
     * 调一次 node abap-cli，并把 stdout/stderr 写到 resultFile。
     *
     * <p>扩展命令（prog-xml-* / prog-source-push）拒绝命令行 {@code --transport}
     * （commander 阶段就拒），因此通过 {@code ABAP_TRANSPORT} 环境变量注入——
     * 与参考实现 ZTemplate10Import.java 一致；extensions/*.mjs 都会从 env 读取。</p>
     */
    private static StepOutcome runAbapCliStep(int idx, String label, Path stageRoot,
                                              List<String> headArgs, List<String> fileArgs,
                                              Path resultFile, String transport,
                                              IProgressMonitor monitor) {
        monitor.subTask("[" + idx + "/" + TOTAL_STEPS + "] " + label + " ...");
        List<String> cmd = new ArrayList<>();
        cmd.add("node");
        cmd.add(stageRoot.resolve(ABAP_CLI_INDEX_REL).toString());
        cmd.addAll(headArgs);
        cmd.addAll(fileArgs);
        cmd.add("--pretty-json");

        StepOutcome o = new StepOutcome(idx, label, -1, resultFile, null);
        System.out.println("[MultiTab] $ " + String.join(" ", cmd));

        // 检查 node 在 PATH
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
            // SAP 配置页连接：扩展命令（prog-xml-* / prog-source-push）在
            // loadProfile 时应用 $ABAP_URL/$ABAP_CLIENT/$ABAP_USERNAME/
            // $ABAP_LANGUAGE/$ABAP_PASSWORD 覆盖；配置页 URL 未填时跳过。
            injectPrefsEnv(procEnv);
            // 传输请求号：source/textpool 推送等写操作需要 corrNr；缺省会报
            // "Parameter corrNr could not be found"。null 则扩展内部不设 corrNr。
            if (transport != null && !transport.isEmpty()) {
                procEnv.put("ABAP_TRANSPORT", transport);
            }

            Process p = pb.start();
            List<String> lines = readProcessOutput(p, DEFAULT_TIMEOUT_SEC);
            int exit = p.exitValue();

            // 写结果文件
            Files.createDirectories(resultFile.getParent());
            Files.write(resultFile,
                    String.join(System.lineSeparator(), lines).getBytes(StandardCharsets.UTF_8));
            System.out.println("[MultiTab]   → " + resultFile + " (" + lines.size() + " lines)");

            // 末尾 10 行打印到 console（避免进度对话框被刷屏）
            int from = Math.max(0, lines.size() - 10);
            for (int i = from; i < lines.size(); i++) {
                System.out.println("[MultiTab]   | " + lines.get(i));
            }
            o.exitCode = exit;
        } catch (IOException | InterruptedException e) {
            o.message = e.getClass().getSimpleName() + ": " + e.getMessage();
            System.err.println("[MultiTab] step [" + idx + "] " + label + " failed: " + o.message);
        }
        monitor.worked(1);
        return o;
    }

    // ====================================================================
    // SAP 配置页连接：临时改写 ~/.abap-cli/systems.json + keychain
    // ====================================================================

    /** _prefs_snapshot.mjs 相对 stage 的路径（与 abap-cli index 同在 stage 根）。 */
    private static final String PREFS_SNAPSHOT_REL = "_prefs_snapshot.mjs";

    /**
     * 把 SAP 配置页（Preference 页面）的连接信息临时应用到 abap-cli 的
     * ~/.abap-cli/systems.json 对应 profile（url/client/username/language）
     * 与 keychain 密码，使所有 abap-cli 步骤（含 <b>内置命令</b> inspect /
     * transport create，它们只读 systems.json + keychain、不接受命令行或
     * 环境变量覆盖）都走配置页连接。
     *
     * <p>流程：先 snapshot 当前 systems.json 全文 + keychain 密码到临时文件，
     * 再 apply 配置页值；执行完成后由 {@link #restorePrefsConnection} 在
     * executeAll 的 finally 中还原。</p>
     *
     * @return 快照文件绝对路径（供 restore 使用）；配置页未填 ABAP CLI URL
     *         时返回 null（跳过本次 apply/restore）。
     * @throws Exception snapshot / apply 失败时抛出，由调用方 finally 兜底。
     */
    private static String applyPrefsConnection(Path stageRoot, IProgressMonitor monitor) throws Exception {
        String url = trimToNull(AIConfiguration.getSapAbapCliUrl());
        if (url == null) {
            System.out.println("[MultiTab]   prefs: ABAP CLI URL not set in SAP Preferences — "
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
        System.out.println("[MultiTab]   prefs: patching profile '" + systemName + "'"
                + "  url=" + url
                + "  client=" + (client == null ? "" : client)
                + "  user=" + (user == null ? "" : user)
                + "  language=" + (language == null ? "" : language));

        monitor.subTask("[2.4] Applying SAP Preferences connection...");

        // 1) snapshot：脚本把 systems.json 全文 + keychain 密码打成一个 JSON 行
        String snapJson = runPrefsScript(stageRoot, "snapshot", Arrays.asList(systemName));
        if (snapJson == null || snapJson.isEmpty()) {
            throw new IOException("prefs snapshot returned nothing for profile '" + systemName + "'");
        }
        if (!snapJson.startsWith("{")) {
            throw new IOException("prefs snapshot output not a JSON object for profile '"
                    + systemName + "': " + snapJson);
        }
        Path snap = Files.createTempFile("multi-tab-prefs-", ".json");
        Files.write(snap, snapJson.getBytes(StandardCharsets.UTF_8));
        String snapshotFile = snap.toAbsolutePath().toString();
        System.out.println("[MultiTab]   prefs snapshot -> " + snapshotFile);

        // 2) apply：改写 systems.json（空字段保留原值）+ 写 keychain 密码
        List<String> applyArgs = new ArrayList<>();
        applyArgs.add(systemName);
        applyArgs.add(nonNull(url));
        applyArgs.add(nonNull(client));
        applyArgs.add(nonNull(user));
        applyArgs.add(nonNull(language));
        applyArgs.add(password == null ? "" : password);
        String applied = runPrefsScript(stageRoot, "apply", applyArgs);
        if (applied == null || !applied.contains("applied:ok")) {
            // apply 失败：回滚以免留下半应用状态
            try {
                restorePrefsConnection(stageRoot, snapshotFile);
            } catch (Exception e2) {
                System.err.println("[MultiTab]   prefs rollback failed: " + e2.getMessage());
            }
            throw new IOException("prefs apply failed for profile '" + systemName
                    + "' (output: " + (applied == null ? "<null>" : applied) + ")");
        }
        System.out.println("[MultiTab]   prefs applied: " + applied.trim());
        return snapshotFile;
    }

    /**
     * 还原 systems.json 与 keychain 到 apply 之前的状态，然后删除临时快照文件。
     */
    private static void restorePrefsConnection(Path stageRoot, String snapshotFile) throws IOException {
        if (snapshotFile == null || snapshotFile.isEmpty()) {
            return;
        }
        String s = runPrefsScript(stageRoot, "restore", Arrays.asList(snapshotFile));
        if (s == null || !s.contains("restored:ok")) {
            System.err.println("[MultiTab]   prefs restore output: " + s);
            // 即使还原失败也不抛出（避免掩盖主流程的结果），仅记录。
            return;
        }
        System.out.println("[MultiTab]   prefs restored: " + s.trim());
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
        cmd.add("node");
        cmd.add(stageRoot.resolve(PREFS_SNAPSHOT_REL).toString());
        if (mode != null) {
            cmd.add(mode);
        }
        cmd.addAll(args);
        System.out.println("[MultiTab] $ " + String.join(" ", cmd));

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
                System.err.println("[MultiTab]   prefs script exit=" + p.exitValue() + ":\n"
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
     * <p>扩展命令（prog-xml-* / prog-source-push）在 loadProfile 时应用
     * $ABAP_URL / $ABAP_CLIENT / $ABAP_USERNAME / $ABAP_LANGUAGE /
     * $ABAP_PASSWORD 覆盖；这样即使 systems.json 未成功 patch，扩展命令
     * 也走配置页连接。内置命令忽略这些变量（靠已 patch 的 systems.json）。</p>
     */
    private static void injectPrefsEnv(Map<String, String> env) {
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

    private static void putIfPresent(Map<String, String> env, String key, String val) {
        if (val != null && !val.isEmpty()) {
            env.put(key, val);
        }
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    /**
     * 读取步骤结果文件，识别 ENQUEUE 对象锁冲突。
     *
     * <p>当对象被其它用户/传输请求锁定（如"使用者 GWDEV45 当前编辑 ZTEST10"、
     * "already locked in request S4DK906927"）时，abap-cli 扩展旧实现对
     * pretty-json 分支不设非零退出码，导致 Java 误判"Overall: OK"而 SE38 里
     * 找不到程序。这里主动扫描结果文件，命中即把 SE03 / SE10 释放指引写入
     * 步骤 message，并把该步骤标记为 locked。</p>
     *
     * <p>锁冲突属环境副作用（被锁的通常只是个别对象，其余对象已成功导入），
     * 因此只作告警，不计入整体成败判定。</p>
     *
     * @return true 表示检测到锁冲突（调用方据此把该步骤降级为告警，不判整体失败）。
     */
    private static boolean detectLock(StepOutcome o) {
        if (o.resultFile == null || !Files.isRegularFile(o.resultFile)) {
            return false;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(o.resultFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return false;
        }
        for (String line : lines) {
            String trimmed = trimToNull(line.replaceAll("\\s+", " "));
            if (trimmed != null && containsAny(trimmed, LOCK_MARKERS)) {
                o.message = "Object is locked by another user / transport request:\n"
                        + "    \"" + trimmed + "\"\n"
                        + "\n"
                        + "Please release the object lock and re-run the import:\n"
                        + "  • SE03 → 传输组织器 → 请求/任务 → 释放对象锁\n"
                        + "  • or SE10 → 删除/撤销该锁定条目\n"
                        + "  • or ask the editing user (e.g. GWDEV45) to save & release.";
                if (o.exitCode == 0) {
                    o.exitCode = 1;
                }
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String s, String[] markers) {
        for (String m : markers) {
            if (s != null && s.contains(m)) {
                return true;
            }
        }
        return false;
    }

    /** SUBC 值 → 可读的 ADT 程序类型标签（对应 SAP PROGDIR-SUBC 域）。 */
    private static String subcLabel(String subc) {
        if (subc == null) return "?";
        switch (subc.trim().toUpperCase(Locale.ROOT)) {
            case "1": return "1 → 可执行程序 (Executable / REPT)";
            case "I": return "I → INCLUDE 程序 (INCLUDE)";
            case "M": return "M → 模块池 (Module Pool)";
            case "S": return "S → 子程序池 (Subroutine Pool)";
            case "T": return "T → 类型组 (Type Pool)";
            case "X": return "X → Dynpro 流逻辑 (Flow Logic)";
            default:  return subc.trim() + " → (未知 SUBC)";
        }
    }

    /**
     * 从 prog-xml-create 的 pretty-json 结果文件解析每个对象的 SUBC 值，
     * 用于在结果对话框里逐个核对「对象 → 程序类型」。
     *
     * 文件形如：
     *   [
     *     { "object": "ZTEST10", "action": "created",
     *       "stages": [ "parsed: NAME=ZTEST10 SUBC=1 typeId=PROG/P (abapProgram)", ... ] },
     *     ...
     *   ]
     *
     * @param resultFile _simple_query_handler_create_result.txt
     * @return 按出现顺序：对象名 → SUBC 值（如 ZTEST10 → "1"）。解析失败返回空 Map。
     */
    private static Map<String, String> parseCreateSubcMap(Path resultFile) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (resultFile == null || !Files.isRegularFile(resultFile)) {
            return out;
        }
        String current = null;
        try {
            for (String line : Files.readAllLines(resultFile, StandardCharsets.UTF_8)) {
                String t = line.trim();
                if (current == null) {
                    java.util.regex.Matcher mObj = java.util.regex.Pattern
                            .compile("\"object\"\\s*:\\s*\"([A-Za-z0-9_]+)\"").matcher(t);
                    if (mObj.find()) {
                        current = mObj.group(1);
                        continue;
                    }
                }
                if (current != null) {
                    java.util.regex.Matcher mSubc = java.util.regex.Pattern
                            .compile("SUBC=([A-Za-z0-9]+)\\s+typeId=PROG/").matcher(t);
                    if (mSubc.find()) {
                        out.putIfAbsent(current, mSubc.group(1));
                        current = null;  // 每个对象只取首个 parsed 行
                    }
                }
            }
        } catch (IOException e) {
            return new java.util.LinkedHashMap<>();
        }
        return out;
    }

    /**
     * 生成「对象 → SUBC 类型」对照块，追加到结果对话框文案。
     *
     * @param sb      目标 StringBuilder
     * @param createResultFile  prog-xml-create 步骤的结果文件
     * @param indent  每行前缀缩进（如 "  "）
     */
    private static void appendSubcTypeBlock(StringBuilder sb, Path createResultFile, String indent) {
        Map<String, String> subcMap = parseCreateSubcMap(createResultFile);
        if (subcMap.isEmpty()) {
            sb.append(indent).append("Object types (SUBC) : (create result not readable)\n");
            return;
        }
        sb.append(indent).append("Object types (SUBC) : 依据 *.prog.xml 的 <SUBC> 导入\n");
        for (Map.Entry<String, String> e : subcMap.entrySet()) {
            sb.append(indent).append("    • ").append(padRight(e.getKey(), 14))
                    .append(subcLabel(e.getValue())).append('\n');
        }
    }

    /**
     * 解析传输请求号：用户已输入则直接复用；否则自动创建。
     *
     * <p>自动创建执行 {@code node abap-cli transport create "<desc>" --package <pkg> --yes
     * --pretty-json}，从返回 JSON 的 {@code data.transport} 字段取新请求号。
     * 参考 ZTemplate10Import 已验证 S4DEV（gwdev45）当前没有打开的 workbench 请求，
     * 自动创建是唯一可行路径（返回如 {@code S4DK910369}）。</p>
     *
     * @return 传输请求号；用户输入为空且自动创建失败时返回 null（调用方据此跳过）。
     */
    private static String resolveTransport(Path stageRoot, String ivPackage, String ivProgName,
                                           String transportInput, IProgressMonitor monitor) {
        if (transportInput != null && !transportInput.trim().isEmpty()) {
            System.out.println("[MultiTab]   transport: reuse " + transportInput);
            return transportInput.trim();
        }

        // 自动创建：描述含程序名/包名便于在 SE03 / SE10 里识别
        String desc = "Simple Query Handler import " + ivProgName + " " + ivPackage + " (auto)";
        List<String> cmd = new ArrayList<>();
        cmd.add("node");
        cmd.add(stageRoot.resolve(ABAP_CLI_INDEX_REL).toString());
        cmd.addAll(Arrays.asList("transport", "create", desc,
                "--package", ivPackage, "--yes", "--pretty-json"));

        System.out.println("[MultiTab] $ " + String.join(" ", cmd));
        monitor.subTask("[2.5] transport create: auto-creating workbench request...");
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
            System.out.println("[MultiTab]   transport create -> exit=" + p.exitValue());

            // 解析 data.transport（简单正则，避免引入 JSON 库）
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"transport\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(json);
            if (m.find()) {
                String tr = m.group(1);
                System.out.println("[MultiTab]   → created transport " + tr);
                return tr;
            }
            System.err.println("[MultiTab] transport create: could not parse transport from output:\n"
                    + (lines.size() > 20 ? String.join("\n", lines.subList(lines.size() - 20, lines.size()))
                                         : json));
        } catch (Exception e) {
            System.err.println("[MultiTab] transport create failed: " + e.getMessage());
        }
        // 解析失败则返回 null：各扩展不设 corrNr，可能报 "Parameter corrNr could not be found"。
        // 整体会因 source-push 退出码非 0 而判失败，用户在结果对话框可看到原因。
        return null;
    }
    private static List<String> readProcessOutput(Process p, int timeoutSec)
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
            }, "MultiTab-abapCli-reader");
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
            // 等子进程退出
            p.waitFor();
        }
        return lines;
    }

    private static boolean isNodeInPath() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            List<String> lines = readProcessOutput(p, 10);
            return p.exitValue() == 0 && !lines.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    // ====================================================================
    // 环境检查
    // ====================================================================

    /**
     * 检查 stage / abap-cli / 所有扩展 / node 是否就位。
     */
    private static Path checkEnvironment() throws EnvironmentException {
        File stageFile = new File(STAGE_DIR);
        if (!stageFile.isDirectory()) {
            throw new EnvironmentException(
                    "Abap-cli stage directory not found:\n  " + stageFile.getAbsolutePath()
                            + "\n\nPlease verify STAGE_DIR in MultiTabTemplateImportService.java,"
                            + "\nor run 'npm install abap-cli' in your chosen stage directory.");
        }
        Path stageRoot = stageFile.toPath().toAbsolutePath().normalize();

        File indexJs = stageRoot.resolve(ABAP_CLI_INDEX_REL).toFile();
        if (!indexJs.isFile()) {
            throw new EnvironmentException(
                    "abap-cli entry not found:\n  " + indexJs.getAbsolutePath()
                            + "\n\nPlease run 'npm install abap-cli' in:\n  " + stageRoot);
        }

        Path extDir = stageRoot.resolve(EXTENSIONS_DIR_REL);
        List<String> missing = new ArrayList<>();
        for (String name : EXTENSION_NAMES) {
            File f = extDir.resolve(name + ".mjs").toFile();
            if (!f.isFile()) missing.add("extensions/" + name + ".mjs");
        }
        if (!missing.isEmpty()) {
            throw new EnvironmentException(
                    "Missing abap-cli extension files in stage:\n  "
                            + extDir.toString()
                            + "\n  Missing:\n    - " + String.join("\n    - ", missing)
                            + "\n\nPlease copy the 6 prog-*.mjs / ddic-*.mjs extensions from your abapgit"
                            + "\nproject into the stage's extensions/ directory.");
        }

        if (!isNodeInPath()) {
            throw new EnvironmentException(
                    "'node' not found in PATH. Please install Node.js (>= 14) and ensure"
                            + "\n'node --version' works in this command shell.");
        }
        return stageRoot;
    }

    // ====================================================================
    // 解压 / 改名 / 写 .abap.json / 列文件 / 路径工具
    // ====================================================================

    /**
     * 把 zipBytes 解压到 dstDir；条目名若以 "src/" 开头则去掉前缀。
     */
    private static int extractZip(byte[] zipBytes, Path dstDir) throws IOException {
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
     * 文件名规则：在文件名中全量替换占位符（大小写两形式），保留 abapGit 的
     * .prog.xml / .prog.abap 双层后缀（见 {@link #renameObjectBaseInFileName}）。
     * 内容规则：base_lower → newLower，baseUpper → newUpper。
     */
    private static RenameStats renameInDir(Path dstDir, String baseLower, String baseUpper,
                                           String newLower, String newUpper) throws IOException {
        RenameStats stats = new RenameStats();
        if (!Files.isDirectory(dstDir)) return stats;

        // 1) 文件名：先收集后改名（避免遍历过程中路径变化）
        List<Path> allFiles;
        try (Stream<Path> walk = Files.walk(dstDir)) {
            allFiles = new ArrayList<>();
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(p)) allFiles.add(p);
            }
        }
        // 按路径深度倒序，确保先改深层文件
        allFiles.sort(Comparator.comparingInt((Path p) -> p.toString().length()).reversed());

        for (Path p : allFiles) {
            String name = p.getFileName().toString();

            // abapGit 序列化文件名形如 <obj>.prog.xml / <obj>_<inc>.prog.xml，占位对象名
            // 可能出现在前缀也可能出现在 include 段。这里在文件名中全量替换占位符，
            // 天然保留 .prog.xml / .prog.abap 双层后缀——旧实现按“前缀重拼最后一段后缀”
            // 会丢掉 .prog 段（ztemplate10.prog.xml → ztest10.xml）或造成双重后缀
            // （ztemplate10_cls.prog.xml → ztest10_cls.prog.xml.xml），
            // 导致 collectByExt(".prog.xml") 找不到对象、SE38 也看不到主程序。
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

            // 2) 文件内容
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
     * 在文件名中全量替换对象占位符（占位符大小写两种形式都替换）。
     *
     * <p>abapGit 序列化文件名形如 {@code <obj>.prog.xml}、{@code <obj>_<inc>.prog.abap}，
     * 占位符可能出现于前缀（{@code ztemplate10.prog.xml}）或 include 段
     * （{@code ztemplate10_cls.prog.xml}），且带双层后缀 {@code .prog.xml} / {@code .prog.abap}。
     * 只在“前缀 + 最后一段后缀”上重拼的旧实现会破坏后缀，这里改为整名替换占位符出现，
     * 后缀整体保留。文件名不含占位符时原样返回（如 {@code ztr_show_jd.prog.xml}）。
     */
    private static String renameObjectBaseInFileName(String name, String baseUpper,
                                                     String baseLower, String newUpper,
                                                     String newLower) {
        String out = name;
        if (name.contains(baseUpper)) out = out.replace(baseUpper, newUpper);
        if (out.contains(baseLower)) out = out.replace(baseLower, newLower);
        return out;
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1);
    }

    /**
     * 写 .abap.json（覆盖现有），注册 6 个 abap-cli 扩展 + 用户输入的包名。
     *
     * <p><b>system 字段</b>：abap-cli 所有命令启动时都要求 .abap.json 含
     * {@code "system"}（否则 {@code CONFIG_ERROR: Missing "system" in .abap.json}）。
     * system 名通过 {@link com.sap.abap.ai.completion.sap.AbapCliConnectionTester#resolveSystemName}
     * 解析：沿用现有 .abap.json 的 system → {@code ~/.abap-cli/systems.json}
     * 中唯一 profile。</p>
     */
    private static void writeAbapJson(Path target, String ivPackage, String sourceDir) throws IOException {
        // 解析 system profile（缺失时无法运行任何 abap-cli 命令）
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
        for (int i = 0; i < EXTENSION_NAMES.size(); i++) {
            String name = EXTENSION_NAMES.get(i);
            sb.append("    { \"name\": \"").append(name).append("\", \"type\": \"command\",\n")
              .append("      \"source\": { \"sourceType\": \"path\", \"path\": \"")
              .append(EXTENSIONS_DIR_REL).append("/").append(name).append(".mjs\" } }");
            if (i < EXTENSION_NAMES.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ]\n");
        sb.append("}\n");
        Files.createDirectories(target.getParent());
        Files.write(target, sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 列出 dir 下 ext 后缀的常规文件，按文件名排序。 */
    private static List<Path> collectByExt(Path dir, String ext) throws IOException {
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
    private static String relToStage(Path stageRoot, Path file) {
        try {
            Path stageAbs = stageRoot.toAbsolutePath().normalize();
            Path fileAbs = file.toAbsolutePath().normalize();
            return stageAbs.relativize(fileAbs).toString().replace('\\', '/');
        } catch (Exception e) {
            return file.toString().replace('\\', '/');
        }
    }

    private static List<String> relToStage(Path stageRoot, List<Path> files) {
        List<String> out = new ArrayList<>();
        for (Path p : files) out.add(relToStage(stageRoot, p));
        return out;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk.sorted(Comparator.reverseOrder())::iterator) {
                Files.deleteIfExists(p);
            }
        }
    }

    // ====================================================================
    // UI 辅助（输入/结果对话框辅助）
    // ====================================================================

    /**
     * 弹出必填输入框。{@code allowSlashAndDollar} 为 true 时允许字符 '/' 与 '$'（用于包名）。
     */
    private static String promptZy(String title, String message, boolean allowSlashAndDollar) {
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
     * 与 {@link #promptZy} 不同：传输号不以 Z/Y 开头（如 S4DK910369），也不强制大写。
     */
    private static String prompt(String title, String message) {
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

    private static Shell getShell() {
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

    /** 是否存在被其它用户 / 传输请求锁定的步骤。 */
    private static boolean hasLock(ImportResult r) {
        for (StepOutcome o : r.steps) {
            if (o.locked) return true;
        }
        return false;
    }

    /**
     * 把导入明细逐行写出：Eclipse Error Log（Window → Show View → Error Log）
     * + 插件 state area 下的 {@code import_<程序名>_<时间戳>.log}。
     *
     * <p>每行一条日志记录，便于在日志视图里逐条查看、排序与筛选；
     * 结果对话框只保留成功 / 失败 / 部分失败的汇总。</p>
     *
     * @param overallSev 整体结果对应的日志级别（INFO / WARNING / ERROR）
     * @return 本次导入日志文件的路径；无法取得插件 state area 时为 {@code null}
     */
    private static Path logImportDetails(ImportResult r, Path stageFinal, String pkgFinal,
                                         String progFinal, int overallSev) {
        // 明细同时写入两处：Eclipse Error Log（逐行）+ 本插件 state area 下的日志文件。
        List<String> fileLines = new ArrayList<>();
        Path logFile = resolveImportLogFile(progFinal);

        emit(fileLines, IStatus.INFO, "Simple Query Handler Template → SAP");
        emit(fileLines, IStatus.INFO, "  Plugin log file : "
                + (logFile != null ? logFile : "(unavailable)"));
        emit(fileLines, IStatus.INFO, "  Package : " + pkgFinal);
        emit(fileLines, IStatus.INFO, "  Program : " + progFinal + "  (placeholder "
                + r.placeholderUpper + " → " + progFinal + ")");
        emit(fileLines, IStatus.INFO, "  Transport : " + (r.transportRequest != null
                ? r.transportRequest : "(none / skipped)"));
        emit(fileLines, IStatus.INFO, "  Stage   : " + stageFinal);
        emit(fileLines, IStatus.INFO, "  Renamed : files=" + r.renamedFiles
                + ", text contents=" + r.renamedContent);

        // 对象类型核对：从 prog-xml-create 的结果解析每个对象的 SUBC → 类型。
        Path createResultFile = null;
        for (StepOutcome o : r.steps) {
            if ("prog-xml-create".equals(o.name) && o.resultFile != null) {
                createResultFile = o.resultFile;
                break;
            }
        }
        StringBuilder subc = new StringBuilder();
        appendSubcTypeBlock(subc, createResultFile, "  ");
        for (String line : subc.toString().split("\\R")) {
            if (!line.trim().isEmpty()) {
                emit(fileLines, IStatus.INFO, line);
            }
        }

        emit(fileLines, IStatus.INFO, "  Steps:");
        for (StepOutcome o : r.steps) {
            int sev = o.locked ? IStatus.WARNING
                    : (o.exitCode != 0 ? IStatus.ERROR : IStatus.INFO);
            StringBuilder sb = new StringBuilder();
            sb.append("    [").append(o.idx).append("] ")
                    .append(padRight(o.name, 22))
                    .append(" exit=").append(o.exitCode);
            if (o.resultFile != null) {
                sb.append("   → ").append(o.resultFile.getFileName());
            }
            if (o.locked) {
                sb.append("   [WARNING: object lock]");
            }
            emit(fileLines, sev, sb.toString());
        }
        emit(fileLines, overallSev, r.overallRc == 0
                ? "  Overall: OK"
                : "  Overall: FAILED (exit=" + r.overallRc + ")");

        // 对象锁（ENQUEUE）只作告警：被锁的通常只是个别对象，其余对象已成功导入。
        boolean anyLock = false;
        for (StepOutcome o : r.steps) {
            if (o.locked && o.message != null && !o.message.isEmpty()) {
                anyLock = true;
                break;
            }
        }
        if (anyLock) {
            emit(fileLines, IStatus.WARNING, "  Warnings (object lock — NOT counted as import failure):");
            for (StepOutcome o : r.steps) {
                if (o.locked && o.message != null && !o.message.isEmpty()) {
                    emit(fileLines, IStatus.WARNING, "    • [" + o.idx + "] " + o.name + ":");
                    for (String line : o.message.split("\\R")) {
                        if (!line.trim().isEmpty()) {
                            emit(fileLines, IStatus.WARNING, "      " + line);
                        }
                    }
                }
            }
            emit(fileLines, IStatus.WARNING, "  The other objects were imported; only the locked object was skipped.");
            emit(fileLines, IStatus.WARNING, "  Release the lock, then re-run the import to update that object.");
        }

        if (r.overallRc != 0) {
            emit(fileLines, IStatus.ERROR, "  Step details:");
            for (StepOutcome o : r.steps) {
                if (!o.locked && o.exitCode != 0 && o.message != null && !o.message.isEmpty()) {
                    emit(fileLines, IStatus.ERROR, "    • [" + o.idx + "] " + o.name + ":");
                    for (String line : o.message.split("\\R")) {
                        if (!line.trim().isEmpty()) {
                            emit(fileLines, IStatus.ERROR, "      " + line);
                        }
                    }
                }
            }
        }

        emit(fileLines, IStatus.INFO, "  Where things are:");
        emit(fileLines, IStatus.INFO,
                "    • Raw step outputs (the files named after → above) are written by abap-cli");
        emit(fileLines, IStatus.INFO, "      itself, one file per step, for troubleshooting only —");
        emit(fileLines, IStatus.INFO, "      they are not this plugin's log. Directory:");
        emit(fileLines, IStatus.INFO, "        " + stageFinal);
        emit(fileLines, IStatus.INFO, "    • abap-cli SAP connection profile: "
                + System.getProperty("user.home") + "\\.abap-cli\\systems.json");

        writeImportLogFile(logFile, fileLines);
        return logFile;
    }

    /**
     * 写入一条导入明细：同时进 Eclipse Error Log（逐行）与插件日志文件。
     *
     * @param fileLines 插件日志文件的行缓冲
     */
    private static void emit(List<String> fileLines, int severity, String text) {
        logLine(severity, text);
        fileLines.add("[" + levelTag(severity) + "] " + (text == null ? "" : text));
    }

    /** 日志级别标签，用作日志文件的行前缀。 */
    private static String levelTag(int severity) {
        if (severity == IStatus.ERROR) return "ERROR  ";
        if (severity == IStatus.WARNING) return "WARNING";
        return "INFO   ";
    }

    /**
     * 本次导入的日志文件路径，位于插件 state area
     * {@code <workspace>/.metadata/.plugins/com.sap.abap.ai.completion/}。
     *
     * @return 日志文件路径；插件未初始化或取不到 state area 时返回 {@code null}
     */
    private static Path resolveImportLogFile(String progFinal) {
        try {
            if (Activator.getDefault() == null) return null;
            IPath state = Platform.getStateLocation(Activator.getDefault().getBundle());
            if (state == null) return null;
            String ts;
            synchronized (LOG_TS_FMT) {
                ts = LOG_TS_FMT.format(new Date());
            }
            String name = (progFinal == null || progFinal.isEmpty()) ? "UNKNOWN" : progFinal;
            return state.append("import_" + name + "_" + ts + ".log")
                    .toFile()
                    .toPath();
        } catch (Exception e) {
            return null;
        }
    }

    /** 把本次导入明细追加写入插件日志文件。 */
    private static void writeImportLogFile(Path logFile, List<String> fileLines) {
        if (logFile == null) return;
        try {
            Files.createDirectories(logFile.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append("===== Simple Query Handler Template import =====")
                    .append(System.lineSeparator());
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
    private static void logLine(int severity, String text) {
        try {
            Activator activator = Activator.getDefault();
            if (activator == null || activator.getLog() == null) return;
            activator.getLog().log(new Status(severity, Activator.PLUGIN_ID,
                    text == null ? "" : text));
        } catch (Exception ignored) {
            // 日志失败不影响主流程
        }
    }

    private static void showError(String title, String message) {
        try {
            Display.getDefault().asyncExec(() ->
                    MessageDialog.openError(getShell(), title, message));
        } catch (Exception ignored) {
        }
    }

    private static String stackTrace(Throwable t) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            return sw.toString();
        } catch (Exception e) {
            return t.getClass().getName() + ": " + t.getMessage();
        }
    }

    private static String padRight(String s, int n) {
        if (s.length() >= n) return s;
        StringBuilder sb = new StringBuilder(s);
        while (sb.length() < n) sb.append(' ');
        return sb.toString();
    }

    // ====================================================================
    // Bundle 资源读取（从 Activator 读取插件内资源）
    // ====================================================================

    private static byte[] readBundleResource(String resourcePath) throws IOException {
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
    private static final class StepOutcome {
        final int idx;
        final String name;
        int exitCode;
        final Path resultFile;
        String message;

        /** 该步骤失败的唯一原因是对象被其它用户 / 传输请求锁定（ENQUEUE）。 */
        boolean locked;

        StepOutcome(int idx, String name, int exitCode, Path resultFile, String message) {
            this.idx = idx;
            this.name = name;
            this.exitCode = exitCode;
            this.resultFile = resultFile;
            this.message = message;
        }
    }

    /** 改名统计。 */
    private static final class RenameStats {
        int renamedFiles = 0;
        int renamedContent = 0;
    }

    /** 总结果。 */
    private static final class ImportResult {
        String placeholderUpper;
        String transportRequest;
        int renamedFiles;
        int renamedContent;
        int extractedCount;
        List<StepOutcome> steps;
        int overallRc;
    }

    /** 环境检查失败异常（不计入整体异常流）。 */
    private static final class EnvironmentException extends Exception {
        EnvironmentException(String msg) { super(msg); }
    }
}