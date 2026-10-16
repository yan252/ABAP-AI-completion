package com.sap.abap.ai.completion.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;

import com.sap.abap.ai.completion.ui.AbapGitImportSupport.EnvironmentException;
import com.sap.abap.ai.completion.ui.AbapGitImportSupport.ImportResult;
import com.sap.abap.ai.completion.ui.AbapGitImportSupport.StepOutcome;

/**
 * Simple Query Handler Template 导入服务（菜单项触发）。
 *
 * <p>参照 {@code abapgit} 项目 {@code ZTemplate10Import.java} 的 10 步编排，但适配
 * Eclipse 插件环境：</p>
 *
 * <ol>
 *   <li>环境检查（stage / extensions / node）</li>
 *   <li>弹窗输入 PACKAGE / 程序名</li>
 *   <li>从 bundle 解压 {@code references/ZTEMPLATE10.zip} 到 stage 工作区</li>
 *   <li>把解压目录里所有 {@code ztemplate10}（文件名 + 文本文件内容）替换为新名字</li>
 *   <li>写 {@code .abap.json}（注册 8 个 abap-cli 扩展 + 用户输入的包名）</li>
 *   <li>调 {@code node abap-cli} 的 9 步：
 *       {@code prog-xml-create} → {@code ddic-xml-create} →
 *       {@code oo-xml-create} → {@code prog-xml-update} →
 *       {@code prog-source-push} → {@code prog-xml-push} → {@code inspect} →
 *       {@code prog-xml-verify} → {@code prog-batch-activate}</li>
 *   <li>汇总 9 步退出码并弹结果对话框</li>
 * </ol>
 *
 * <p><b>为何最后才整体激活</b>：程序 / INCLUDE / 结构 / 表 / 表类型 / 类互相引用，
 * 逐个对象边写边激活会拿着尚未更新的依赖去编译（报
 * {@code Field "X" is unknown.} 之类伪错误）而留在 inactive。
 * 因此 {@code prog-source-push} 只写源码不激活，等所有对象写入完成后，
 * 由最后一步 {@code prog-batch-activate} 合并成<b>一次</b>
 * {@code POST /sap/bc/adt/activation}，让 SAP 自行解析依赖顺序。</p>
 *
 * <p><b>复用</b>：与 abap-cli 交互的所有细节（环境检查 / 连接注入 / 传输 / 步骤执行 /
 * 解压 / 改名 / 写 {@code .abap.json} / 锁检测 / 日志）统一由
 * {@link AbapGitImportSupport} 提供，本类只负责"编排 + 结果文案"。</p>
 */
public final class MultiTabTemplateImportService {

    /** 模板 zip 在 bundle 内的资源路径。 */
    private static final String ZIP_RESOURCE_PATH = "references/ZTEMPLATE10.zip";

    /** 解压目录（相对 stage）；对应 .abap.json 的 sourceDir。 */
    private static final String REPO_SRC_REL = "repos/ztemplate10/src";

    /** 进度对话框中的步骤总数。 */
    private static final int TOTAL_STEPS = 9;

    /** 主程序占位符（与 ZIP 中现存模板主程序名一致）。 */
    private static final String PLACEHOLDER_LOWER = "ztemplate10";
    private static final String PLACEHOLDER_UPPER = "ZTEMPLATE10";

    /**
     * abapGit {@code <object>.<type>.<xml|abap>} 里本流程已能导入的类型：
     * prog / tabl / ttyp（prog-xml-create + ddic-xml-create）与
     * clas / intf（oo-xml-create）。
     */
    private static final Set<String> HANDLED_OBJECT_TYPES = new HashSet<>(Arrays.asList(
                "prog", "tabl", "ttyp", "clas", "intf"));

    private MultiTabTemplateImportService() {
    }

    /**
     * 公共入口：从菜单点击触发。
     * 流程：环境检查 → 输入 → 进度对话框跑 9 步 → 弹结果。
     */
    public static void runImport() {
        // ===== [1] 环境检查 =====
        Path stageRoot;
        try {
            stageRoot = AbapGitImportSupport.checkEnvironment(
                    AbapGitImportSupport.STANDARD_EXTENSIONS);
        } catch (EnvironmentException ee) {
            AbapGitImportSupport.showError("Simple Query Handler — 环境检查失败", ee.getMessage());
            return;
        }

        // ===== [1.5] SAP 连接预检：连不上 SAP 直接报错退出并打开配置页 =====
        if (!AbapGitImportSupport.checkSapConnectionBeforeImport(
                "Simple Query Handler Template")) {
            return;
        }

        // ===== [2] 输入 PACKAGE / 程序名 =====
        String ivPackage = AbapGitImportSupport.promptZy("Simple Query Handler Template",
                "Please enter the target SAP package (e.g., ZDEV_PACKAGE or $TMP).\n"
                        + "Must start with Z or Y; '/' and '$' allowed; auto-uppercase.",
                true);
        if (ivPackage == null) return;

        String ivProgName = AbapGitImportSupport.promptZy("Simple Query Handler Template",
                "Please enter the new program name (e.g., ZMY_MT_QUERY).\n"
                        + "Must start with Z or Y; English letters / digits / '_' only; "
                        + "max 30 chars; auto-uppercase.\n"
                        + "The placeholder " + PLACEHOLDER_UPPER
                        + " will be renamed to this name in the uploaded objects.",
                false);
        if (ivProgName == null) return;

        // [2.5] 传输请求（可选）。留空 = 自动创建新请求。
        String ivTransport = AbapGitImportSupport.prompt("Simple Query Handler Template",
                "SAP transport request number (optional).\n"
                        + "Leave EMPTY to let the plugin AUTO-CREATE a new workbench request\n"
                        + "inside the target package (recommended; S4DEV requires corrNr).\n"
                        + "Or enter an existing open request, e.g. S4DK910369.");
        if (ivTransport == null) return; // 用户取消
        final String transportInputFinal = ivTransport.trim().isEmpty() ? null : ivTransport.trim();

        // ===== 进度对话框跑解压+改名+9 步 abap-cli =====
        final Path stageFinal = stageRoot;
        final String pkgFinal = ivPackage;
        final String progFinal = ivProgName;
        final ImportResult[] holder = { null };
        final Exception[] errorHolder = { null };

        ProgressMonitorDialog pmd = new ProgressMonitorDialog(AbapGitImportSupport.getShell());
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
            AbapGitImportSupport.showError("Simple Query Handler — Failed",
                    "Unexpected error during import:\n\n"
                            + AbapGitImportSupport.stackTrace(errorHolder[0]));
            return;
        }

        // ===== 弹结果：对话框只报 成功 / 失败，明细走 Eclipse Error Log =====
        ImportResult r = holder[0];
        final List<String> lockedNames = AbapGitImportSupport.lockedObjectNames(r);

        final String status;
        final String title;
        final int overallSev;
        if (r.overallRc != 0) {
            status = "FAILED";
            title = "Import Failed";
            overallSev = IStatus.ERROR;
        } else {
            status = "SUCCESS";
            title = "Import Succeeded";
            overallSev = IStatus.INFO;
        }

        Path importLogFile = logImportDetails(r, stageFinal, pkgFinal, progFinal, overallSev);

        StringBuilder sb = new StringBuilder();
        sb.append("Simple Query Handler Template → SAP\n\n");
        sb.append("  Result    : ").append(status).append('\n');
        sb.append("  Package   : ").append(pkgFinal).append('\n');
        sb.append("  Program   : ").append(progFinal).append('\n');
        sb.append("  Transport : ").append(r.transportRequest != null
                ? r.transportRequest : "(none / skipped)").append('\n');
        if (!lockedNames.isEmpty()) {
            sb.append("\n  Failed — object(s) locked in another transport request,\n");
            sb.append("  so they could NOT be imported:\n");
            for (String n : lockedNames) {
                sb.append("    • ").append(n).append('\n');
            }
            sb.append("  Release the lock (SE03 → 传输组织器 → 请求/任务 → 释放对象锁)\n");
            sb.append("  and re-run the import.\n");
        }
        sb.append("\n  Details were written to:\n");
        sb.append("    • Eclipse Error Log : Window → Show View → Error Log\n");
        if (importLogFile != null) {
            sb.append("    • Import log file   : ").append(importLogFile);
        } else {
            sb.append("    • Import log file   : (unavailable)");
        }

        if (overallSev == IStatus.ERROR) {
            MessageDialog.openError(AbapGitImportSupport.getShell(), title, sb.toString());
        } else if (overallSev == IStatus.WARNING) {
            MessageDialog.openWarning(AbapGitImportSupport.getShell(), title, sb.toString());
        } else {
            MessageDialog.openInformation(AbapGitImportSupport.getShell(), title, sb.toString());
        }
    }

    // ====================================================================
    // 主流程：解压 → 改名 → 写 .abap.json → 跑 9 步
    // ====================================================================

    /**
     * 解压 + 改名 + 写 .abap.json + 9 步 abap-cli。
     *
     * <p>{@code transportInput} 为 null 或空 = 自动创建新传输请求；否则复用该请求号。
     * 解析出的传输号会在各步骤中以 {@code ABAP_TRANSPORT} 环境变量注入。</p>
     */
    private static ImportResult executeAll(Path stageRoot, String ivPackage, String ivProgName,
                                          String transportInput, IProgressMonitor monitor) throws Exception {
        monitor.beginTask("Importing Simple Query Handler Template\n"
                        + "Package: " + ivPackage + "   Program: " + ivProgName
                        + "   Stage: " + stageRoot,
                IProgressMonitor.UNKNOWN);

        // [2.4] 应用 SAP 配置页连接信息（临时改写 ~/.abap-cli/systems.json + keychain）。
        String prefsSnapshotFile = AbapGitImportSupport.applyPrefsConnection(stageRoot, monitor);
        System.out.println("[MultiTab]   prefsSnapshot="
                + (prefsSnapshotFile == null ? "(skip)" : prefsSnapshotFile));
        try {

        // [2.5] 解析传输请求（用户输入 or 自动创建）
        monitor.subTask("[2.5] Resolving transport request...");
        String transport = AbapGitImportSupport.resolveTransport(stageRoot, ivPackage,
                "Simple Query Handler import " + ivProgName + " " + ivPackage + " (auto)",
                transportInput, monitor);
        monitor.worked(1);
        System.out.println("[MultiTab]   → transport=" + transport);

        // [3] 解压 ZIP
        monitor.subTask("[3] Extracting ZTEMPLATE10.zip to " + REPO_SRC_REL);
        Path dstDir = stageRoot.resolve(REPO_SRC_REL);
        byte[] zipBytes = AbapGitImportSupport.readBundleResource(ZIP_RESOURCE_PATH);
        AbapGitImportSupport.deleteRecursively(dstDir);
        Files.createDirectories(dstDir);
        int extractedCount = AbapGitImportSupport.extractZip(zipBytes, dstDir);
        monitor.worked(1);

        // [4] 改名（文件名 + 文件内容）
        monitor.subTask("[4] Renaming placeholder " + PLACEHOLDER_UPPER
                + " → " + ivProgName);
        AbapGitImportSupport.RenameStats stats = AbapGitImportSupport.renameInDir(dstDir,
                PLACEHOLDER_LOWER, PLACEHOLDER_UPPER,
                ivProgName.toLowerCase(Locale.ROOT), ivProgName.toUpperCase(Locale.ROOT));
        monitor.worked(1);

        // [5] 写 .abap.json
        monitor.subTask("[5] Writing .abap.json (package=" + ivPackage + ")");
        AbapGitImportSupport.writeAbapJson(stageRoot.resolve(".abap.json"), ivPackage,
                REPO_SRC_REL, AbapGitImportSupport.STANDARD_EXTENSIONS);
        monitor.worked(1);

        // 收集文件
        List<Path> xmlFiles = AbapGitImportSupport.collectByExt(dstDir, ".prog.xml");
        List<Path> abapFiles = AbapGitImportSupport.collectByExt(dstDir, ".prog.abap");
        Path mainXml = dstDir.resolve(ivProgName.toLowerCase(Locale.ROOT) + ".prog.xml");

        // DDIC 文件：*.tabl.xml（结构 / 透明表）+ *.ttyp.xml（表类型）。顺序敏感：
        // 表类型的行类型必须先是一个已激活的表/结构，因此 .tabl.xml 排在 .ttyp.xml 之前。
        List<Path> ddicXmlFiles = new ArrayList<>(
                AbapGitImportSupport.collectByExt(dstDir, ".tabl.xml"));
        ddicXmlFiles.addAll(AbapGitImportSupport.collectByExt(dstDir, ".ttyp.xml"));

        // OO 文件：类 / 接口元数据 + 源码（扩展内部按对象 URL 去重）。
        List<Path> ooFiles = new ArrayList<>(
                AbapGitImportSupport.collectByExt(dstDir, ".clas.xml"));
        ooFiles.addAll(AbapGitImportSupport.collectByExt(dstDir, ".intf.xml"));
        ooFiles.addAll(AbapGitImportSupport.collectByExt(dstDir, ".clas.abap"));
        ooFiles.addAll(AbapGitImportSupport.collectByExt(dstDir, ".intf.abap"));

        // 未被本流程处理的 abapGit 对象类型：只报告不阻断。
        List<String> unhandledTypes = findUnhandledObjectTypes(dstDir);

        // 9 步 abap-cli
        List<StepOutcome> steps = new ArrayList<>();

        // [6.1] prog-xml-create
        steps.add(AbapGitImportSupport.runAbapCliStep(1, "prog-xml-create",
                stageRoot,
                new ArrayList<>(Arrays.asList("prog-xml-create")),
                AbapGitImportSupport.relToStage(stageRoot, xmlFiles),
                stageRoot.resolve("_simple_query_handler_create_result.txt"),
                transport, TOTAL_STEPS,
                monitor));
        // create 退出码只用于决定后续是否还要跑 update；整体成败由流程末尾统一判定。
        final boolean createOk = steps.get(steps.size() - 1).exitCode == 0;

        // [6.2] ddic-xml-create（结构 / 透明表 / 表类型）
        if (ddicXmlFiles.isEmpty()) {
            steps.add(new StepOutcome(2, "ddic-xml-create", 0, null,
                    "no *.tabl.xml / *.ttyp.xml in src — DDIC step skipped"));
        } else {
            steps.add(AbapGitImportSupport.runAbapCliStep(2, "ddic-xml-create",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("ddic-xml-create")),
                    AbapGitImportSupport.relToStage(stageRoot, ddicXmlFiles),
                    stageRoot.resolve("_simple_query_handler_ddic_result.txt"),
                    transport, TOTAL_STEPS,
                    monitor));
        }

        // [6.3] oo-xml-create（类 CLAS/OC / 接口 INTF/OI）
        if (ooFiles.isEmpty()) {
            steps.add(new StepOutcome(3, "oo-xml-create", 0, null,
                    "no *.clas.* / *.intf.* in src — OO step skipped"));
        } else {
            steps.add(AbapGitImportSupport.runAbapCliStep(3, "oo-xml-create",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("oo-xml-create")),
                    AbapGitImportSupport.relToStage(stageRoot, ooFiles),
                    stageRoot.resolve("_simple_query_handler_oo_result.txt"),
                    transport, TOTAL_STEPS,
                    monitor));
        }

        // [6.4] prog-xml-update
        if (createOk) {
            steps.add(AbapGitImportSupport.runAbapCliStep(4, "prog-xml-update",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-xml-update")),
                    AbapGitImportSupport.relToStage(stageRoot, xmlFiles),
                    stageRoot.resolve("_simple_query_handler_update_result.txt"),
                    transport, TOTAL_STEPS,
                    monitor));
        }

        // [6.5] prog-source-push
        steps.add(AbapGitImportSupport.runAbapCliStep(5, "prog-source-push",
                stageRoot,
                new ArrayList<>(Arrays.asList("prog-source-push")),
                AbapGitImportSupport.relToStage(stageRoot, abapFiles),
                stageRoot.resolve("_simple_query_handler_source_push_result.txt"),
                transport, TOTAL_STEPS,
                monitor));

        // [6.6] prog-xml-push (DYNPROS / CUA / I18N_TPOOL)
        if (Files.isRegularFile(mainXml)) {
            steps.add(AbapGitImportSupport.runAbapCliStep(6, "prog-xml-push",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-xml-push")),
                    Arrays.asList(AbapGitImportSupport.relToStage(stageRoot, mainXml)),
                    stageRoot.resolve("_simple_query_handler_xml_push_result.txt"),
                    transport, TOTAL_STEPS,
                    monitor));
        } else {
            steps.add(new StepOutcome(6, "prog-xml-push", -1, null,
                    "main prog.xml not found: " + mainXml));
        }

        // [6.7] inspect
        steps.add(AbapGitImportSupport.runAbapCliStep(7, "inspect",
                stageRoot,
                new ArrayList<>(Arrays.asList("inspect", ivProgName, "--includes", "--pretty-json")),
                new ArrayList<>(),
                stageRoot.resolve("_simple_query_handler_inspect_result.txt"),
                transport, TOTAL_STEPS,
                monitor));

        // [6.8] prog-xml-verify
        if (Files.isRegularFile(mainXml)) {
            steps.add(AbapGitImportSupport.runAbapCliStep(8, "prog-xml-verify",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-xml-verify")),
                    Arrays.asList(AbapGitImportSupport.relToStage(stageRoot, mainXml)),
                    stageRoot.resolve("_simple_query_handler_verify_result.txt"),
                    transport, TOTAL_STEPS,
                    monitor));
        } else {
            steps.add(new StepOutcome(8, "prog-xml-verify", -1, null,
                    "main prog.xml not found: " + mainXml));
        }

        // [6.9] prog-batch-activate —— 整体激活（流程最后一步）
        List<Path> activateFiles = new ArrayList<>(abapFiles);
        activateFiles.addAll(ddicXmlFiles);
        activateFiles.addAll(ooFiles);
        boolean batchActivateRan = false;
        if (activateFiles.isEmpty()) {
            steps.add(new StepOutcome(9, "prog-batch-activate", -1, null,
                    "no source/metadata files collected — batch activation skipped"));
        } else {
            batchActivateRan = true;
            steps.add(AbapGitImportSupport.runAbapCliStep(9, "prog-batch-activate",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-batch-activate")),
                    AbapGitImportSupport.relToStage(stageRoot, activateFiles),
                    stageRoot.resolve("_simple_query_handler_batch_activate_result.txt"),
                    transport, TOTAL_STEPS,
                    monitor));
        }

        // 整体成败判定：以流程最后一步「统一激活」(prog-batch-activate) 为准。
        // 该步把所有对象放入同一次 activation 请求，等价于 SE38 中「全选未激活
        // 对象 → 激活」；只要它成功，说明所有对象都已 active，中间步骤的个别告警
        // 不再影响整体结论。
        StepOutcome finalActivation = null;
        for (StepOutcome o : steps) {
            o.locked = AbapGitImportSupport.detectLock(o);
            if ("prog-batch-activate".equals(o.name)) {
                finalActivation = o;
            }
        }

        int overall = 0;
        if (batchActivateRan && finalActivation != null) {
            if (finalActivation.locked || finalActivation.exitCode != 0) {
                overall = -1;
            }
        } else {
            // 未执行统一激活（无对象可激活）：退回关键步骤逐个判定。
            for (StepOutcome o : steps) {
                if (o.message != null && o.message.contains("not found")) {
                    overall = -1;
                    continue;
                }
                if (o.locked) {
                    overall = -1;
                    continue;
                }
                if (o.exitCode != 0
                        && ("prog-xml-create".equals(o.name)
                            || "ddic-xml-create".equals(o.name)
                            || "oo-xml-create".equals(o.name)
                            || "prog-source-push".equals(o.name))) {
                    overall = -1;
                }
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
        r.unhandledObjectTypes = unhandledTypes;
        return r;
        } finally {
            // 无论成功失败，都还原 ~/.abap-cli/systems.json + keychain。
            if (prefsSnapshotFile != null) {
                try {
                    AbapGitImportSupport.restorePrefsConnection(stageRoot, prefsSnapshotFile);
                } catch (Exception e) {
                    System.err.println("[MultiTab] prefs restore failed: " + e.getMessage());
                }
            }
        }
    }

    // ====================================================================
    // SUBC（程序类型）解析：用于结果日志里逐个核对「对象 → 程序类型」
    // ====================================================================

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
     * 从 prog-xml-create 的 pretty-json 结果文件解析每个对象的 SUBC 值。
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
                        current = null;
                    }
                }
            }
        } catch (IOException e) {
            return new java.util.LinkedHashMap<>();
        }
        return out;
    }

    /**
     * 生成「对象 → SUBC 类型」对照块，追加到结果日志文案。
     */
    private static void appendSubcTypeBlock(StringBuilder sb, Path createResultFile, String indent) {
        Map<String, String> subcMap = parseCreateSubcMap(createResultFile);
        if (subcMap.isEmpty()) {
            sb.append(indent).append("Object types (SUBC) : (create result not readable)\n");
            return;
        }
        sb.append(indent).append("Object types (SUBC) : 依据 *.prog.xml 的 <SUBC> 导入\n");
        for (Map.Entry<String, String> e : subcMap.entrySet()) {
            sb.append(indent).append("    • ").append(AbapGitImportSupport.padRight(e.getKey(), 14))
                    .append(subcLabel(e.getValue())).append('\n');
        }
    }

    // ====================================================================
    // 未处理对象类型
    // ====================================================================

    /**
     * 扫描解压目录，返回 ZIP 里存在、但本流程不会导入的 abapGit 对象类型（去重升序）。
     * {@code package.devc.xml} 是包自身，不计入。
     */
    private static List<String> findUnhandledObjectTypes(Path dir) throws IOException {
        Set<String> out = new TreeSet<>();
        if (!Files.isDirectory(dir)) return new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                // 仅识别严格的 3 段名 `<object>.<type>.<ext>`。
                String[] parts = p.getFileName().toString().split("\\.");
                if (parts.length != 3) continue;
                String type = parts[1].toLowerCase(Locale.ROOT);
                if (HANDLED_OBJECT_TYPES.contains(type) || "devc".equals(type)) continue;
                out.add(type);
            }
        }
        return new ArrayList<>(out);
    }

    // ====================================================================
    // 结果日志
    // ====================================================================

    /**
     * 把导入明细逐行写出：Eclipse Error Log + 插件 state area 下的
     * {@code import_<程序名>_<时间戳>.log}。
     *
     * @param overallSev 整体结果对应的日志级别（INFO / WARNING / ERROR）
     * @return 本次导入日志文件的路径；无法取得插件 state area 时为 {@code null}
     */
    private static Path logImportDetails(ImportResult r, Path stageFinal, String pkgFinal,
                                         String progFinal, int overallSev) {
        List<String> fileLines = new ArrayList<>();
        Path logFile = AbapGitImportSupport.resolveImportLogFile(progFinal);

        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "Simple Query Handler Template → SAP");
        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "  Plugin log file : "
                + (logFile != null ? logFile : "(unavailable)"));
        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "  Package : " + pkgFinal);
        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "  Program : " + progFinal
                + "  (placeholder " + r.placeholderUpper + " → " + progFinal + ")");
        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "  Transport : " + (r.transportRequest != null
                ? r.transportRequest : "(none / skipped)"));
        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "  Stage   : " + stageFinal);
        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "  Renamed : files=" + r.renamedFiles
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
                AbapGitImportSupport.emit(fileLines, IStatus.INFO, line);
            }
        }

        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "  Steps:");
        for (StepOutcome o : r.steps) {
            int sev = o.exitCode != 0 ? IStatus.ERROR : IStatus.INFO;
            StringBuilder sb = new StringBuilder();
            sb.append("    [").append(o.idx).append("] ")
                    .append(AbapGitImportSupport.padRight(o.name, 22))
                    .append(" exit=").append(o.exitCode);
            if (o.resultFile != null) {
                sb.append("   → ").append(o.resultFile.getFileName());
            }
            if (o.locked) {
                sb.append("   [FAILED: object lock]");
            }
            AbapGitImportSupport.emit(fileLines, sev, sb.toString());
        }
        AbapGitImportSupport.emit(fileLines, overallSev, r.overallRc == 0
                ? "  Overall: OK"
                : "  Overall: FAILED (exit=" + r.overallRc + ")");

        if (r.unhandledObjectTypes != null && !r.unhandledObjectTypes.isEmpty()) {
            AbapGitImportSupport.emit(fileLines, IStatus.WARNING,
                    "  Not imported — no importer for object type(s): "
                    + String.join(", ", r.unhandledObjectTypes)
                    + "   (files present in the ZIP but skipped by this flow)");
        }

        // 对象锁（ENQUEUE）导致导入失败：点名被锁对象，红色（ERROR）标记。
        boolean anyLock = false;
        for (StepOutcome o : r.steps) {
            if (o.locked && o.message != null && !o.message.isEmpty()) {
                anyLock = true;
                break;
            }
        }
        if (anyLock) {
            AbapGitImportSupport.emit(fileLines, IStatus.ERROR,
                    "  Failures (object lock conflict — these objects were NOT imported):");
            for (StepOutcome o : r.steps) {
                if (o.locked && o.message != null && !o.message.isEmpty()) {
                    AbapGitImportSupport.emit(fileLines, IStatus.ERROR,
                            "    • [" + o.idx + "] " + o.name + ":");
                    for (String line : o.message.split("\\R")) {
                        if (!line.trim().isEmpty()) {
                            AbapGitImportSupport.emit(fileLines, IStatus.ERROR, "      " + line);
                        }
                    }
                }
            }
        }

        if (r.overallRc != 0) {
            AbapGitImportSupport.emit(fileLines, IStatus.ERROR, "  Step details:");
            for (StepOutcome o : r.steps) {
                if (!o.locked && o.exitCode != 0 && o.message != null && !o.message.isEmpty()) {
                    AbapGitImportSupport.emit(fileLines, IStatus.ERROR,
                            "    • [" + o.idx + "] " + o.name + ":");
                    for (String line : o.message.split("\\R")) {
                        if (!line.trim().isEmpty()) {
                            AbapGitImportSupport.emit(fileLines, IStatus.ERROR, "      " + line);
                        }
                    }
                }
            }
        }

        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "  Where things are:");
        AbapGitImportSupport.emit(fileLines, IStatus.INFO,
                "    • Raw step outputs (the files named after → above) are written by abap-cli");
        AbapGitImportSupport.emit(fileLines, IStatus.INFO,
                "      itself, one file per step, for troubleshooting only —");
        AbapGitImportSupport.emit(fileLines, IStatus.INFO,
                "      they are not this plugin's log. Directory:");
        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "        " + stageFinal);
        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "    • abap-cli SAP connection profile: "
                + System.getProperty("user.home") + "\\.abap-cli\\systems.json");

        AbapGitImportSupport.writeImportLogFile(logFile, fileLines,
                "===== Simple Query Handler Template import =====");
        return logFile;
    }
}
