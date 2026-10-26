package com.sap.abap.ai.completion.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;

import com.sap.abap.ai.completion.ui.AbapGitImportSupport.EnvironmentException;
import com.sap.abap.ai.completion.ui.AbapGitImportSupport.ImportResult;
import com.sap.abap.ai.completion.ui.AbapGitImportSupport.StepOutcome;

/**
 * Batch Import Handler Templates 导入服务（菜单项触发）。
 *
 * <p>把 {@code references/ZTEMPLATE20.zip} 里的<b>全部程序（PROG）与函数组（FUGR / 函数模块）</b>
 * 批量导入 SAP。整体编排与 {@link MultiTabTemplateImportService}（Simple Query Handler Template）
 * <b>完全一致</b>：环境检查 → 输入 PACKAGE / 程序名 / 传输 → 解压 → 占位符改名 →
 * 写 {@code .abap.json} → 调 {@code node abap-cli} 各步 → 汇总结果；与 abap-cli 交互的
 * 所有细节统一由 {@link AbapGitImportSupport} 提供。</p>
 *
 * <p><b>占位符改名</b>：ZIP 里的主程序名为 {@code ZTEMPLATE20}（含 includes
 * {@code ZTEMPLATE20_F01/F02/F03}），导入时用户输入新程序名，随后在<b>文件名与文本内容</b>
 * 中把 {@code ztemplate20} / {@code ZTEMPLATE20} 整体替换为新名字——与 Simple Query Handler
 * 的 {@code ZTEMPLATE10} 处理方式相同。ZIP 里其余程序（如 {@code ZTR_SHOW_JD}）不含占位符，
 * 按原名导入。</p>
 *
 * <p><b>与 Simple Query Handler 的唯一差异：函数组</b>。abapGit 的 FUGR 文件布局与
 * abap-cli 内置命令期望的布局不同，解压后先由 {@link AbapGitImportSupport#renameFugrLayout}
 * 重命名（{@code .fugr.sapl<g>.abap → .reps.abap}、{@code .fugr.l<g>top.abap → .reps.abap}、
 * {@code .fugr.<fm>.abap → .func.abap}），随后走 abap-cli <b>内置命令</b>：
 * {@code create FUGR <group>} → {@code create FUGR <group> --func <fm>}（每个函数模块）
 * → {@code push <reps/func 源码>}。函数组步骤先于程序步骤（程序会调用函数模块）。</p>
 *
 * <p><b>整体激活</b>：程序部分由最后一步 {@code prog-batch-activate} 合并成一次
 * {@code POST /sap/bc/adt/activation}（避免互相引用时逐个激活报伪错误）；函数模块由
 * {@code push} 自行激活（{@code create FUGR} 先建出骨架，随后 push 覆盖源码并激活）。</p>
 */
public final class BatchTemplateImportService {

    /** 模板 zip 在 bundle 内的资源路径。 */
    private static final String ZIP_RESOURCE_PATH = "references/ZTEMPLATE20.zip";

    /** 解压目录（相对 stage）；对应 .abap.json 的 sourceDir。 */
    private static final String REPO_SRC_REL = "repos/ztemplate20/src";

    /** 主程序占位符（与 ZIP 中现存模板主程序名一致）。 */
    private static final String PLACEHOLDER_LOWER = "ztemplate20";
    private static final String PLACEHOLDER_UPPER = "ZTEMPLATE20";

    /** 对象描述前缀（用于 create FUGR / create FUGR --func）。 */
    private static final String OBJ_DESC_PREFIX = "Batch import ";

    /**
     * abapGit {@code <object>.<type>.<ext>} 里本流程已能导入的类型：
     * prog 与 fugr。其余（若 ZIP 里有）只报告不阻断。
     */
    private static final Set<String> HANDLED_OBJECT_TYPES = new HashSet<>(Arrays.asList(
            "prog", "fugr"));

    private BatchTemplateImportService() {
    }

    /**
     * 公共入口：从菜单点击触发。
     * 流程：环境检查 → 输入 PACKAGE / 程序名 / 传输 → 进度对话框跑各步 → 弹结果。
     */
    public static void runImport() {
        // ===== [1] 环境检查 =====
        // ABAP CLI runtime 未安装时先弹询问框：确定→直接下载安装，取消→退出导入
        if (!AbapCliInstallPrompt.ensureInstalledWithPrompt(null)) {
            return;
        }
        Path stageRoot;
        try {
            stageRoot = AbapGitImportSupport.checkEnvironment(
                    AbapGitImportSupport.STANDARD_EXTENSIONS);
        } catch (EnvironmentException ee) {
            AbapGitImportSupport.showError(ImportMessages.get("batchImport.envFailedTitle"),
                    ee.getMessage());
            return;
        }

        // ===== [1.5] SAP 连接预检：连不上 SAP 直接报错退出并打开配置页 =====
        if (!AbapGitImportSupport.checkSapConnectionBeforeImport(
                ImportMessages.get("batchImport.dialog.title"))) {
            return;
        }

        // ===== [2] 输入 PACKAGE / 新程序名 =====
        String ivPackage = AbapGitImportSupport.promptZy(
                ImportMessages.get("batchImport.dialog.title"),
                ImportMessages.get("batchImport.prompt.package"),
                true);
        if (ivPackage == null) return;

        String ivProgName = AbapGitImportSupport.promptZy(
                ImportMessages.get("batchImport.dialog.title"),
                ImportMessages.format("batchImport.prompt.program", PLACEHOLDER_UPPER),
                false);
        if (ivProgName == null) return;

        // [2.5] 传输请求（可选）。留空 = 自动创建新请求。
        String ivTransport = AbapGitImportSupport.prompt(
                ImportMessages.get("batchImport.dialog.title"),
                ImportMessages.get("batchImport.prompt.transport"));
        if (ivTransport == null) return; // 用户取消
        final String transportInputFinal = ivTransport.trim().isEmpty() ? null : ivTransport.trim();

        // ===== 进度对话框跑解压 + 改名 + abap-cli 各步 =====
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
                        holder[0] = executeAll(stageFinal, pkgFinal, progFinal,
                                transportInputFinal, monitor);
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
            AbapGitImportSupport.showError(ImportMessages.get("batchImport.failedTitle"),
                    ImportMessages.format("importSupport.error.unexpected",
                            AbapGitImportSupport.stackTrace(errorHolder[0])));
            return;
        }

        // ===== 弹结果：对话框只报 成功 / 失败，明细走 Eclipse Error Log =====
        ImportResult r = holder[0];
        final List<String> lockedNames = AbapGitImportSupport.lockedObjectNames(r);

        final String status;
        final String title;
        final int overallSev;
        if (r.overallRc != 0) {
            status = ImportMessages.get("importSupport.result.statusFailed");
            title = ImportMessages.get("importSupport.result.titleFailed");
            overallSev = IStatus.ERROR;
        } else {
            status = ImportMessages.get("importSupport.result.statusSuccess");
            title = ImportMessages.get("importSupport.result.titleSucceeded");
            overallSev = IStatus.INFO;
        }

        Path importLogFile = logImportDetails(r, stageFinal, pkgFinal, progFinal, overallSev);

        StringBuilder sb = new StringBuilder();
        sb.append(ImportMessages.get("batchImport.result.header"));
        sb.append("  ").append(ImportMessages.get("importSupport.result.labelResult"))
                .append(' ').append(status).append('\n');
        sb.append("  ").append(ImportMessages.get("importSupport.result.labelPackage"))
                .append(' ').append(pkgFinal).append('\n');
        sb.append("  ").append(ImportMessages.get("importSupport.result.labelProgram"))
                .append(' ').append(progFinal).append('\n');
        sb.append("  ").append(ImportMessages.get("importSupport.result.labelTransport"))
                .append(' ').append(r.transportRequest != null
                ? r.transportRequest : ImportMessages.get("importSupport.result.transportNone"))
                .append('\n');
        if (!lockedNames.isEmpty()) {
            sb.append(ImportMessages.get("importSupport.result.lockedIntro"));
            for (String n : lockedNames) {
                sb.append("    • ").append(n).append('\n');
            }
            sb.append(ImportMessages.get("importSupport.result.lockedRelease"));
        }
        sb.append(ImportMessages.get("importSupport.result.detailsWrittenTo"));
        sb.append(ImportMessages.get("importSupport.result.errorLogLine"));
        if (importLogFile != null) {
            sb.append("    • ").append(ImportMessages.get("importSupport.result.logFileLabel"))
                    .append(' ').append(importLogFile);
        } else {
            sb.append("    • ").append(ImportMessages.get("importSupport.result.logFileLabel"))
                    .append(' ')
                    .append(ImportMessages.get("importSupport.result.logFileUnavailable"));
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
    // 主流程：解压 → 占位符改名 → FUGR 布局改名 → 写 .abap.json → FUGR 步 → PROG 步
    // ====================================================================

    /**
     * 解压 + 占位符改名 + FUGR 布局重命名 + 写 .abap.json + abap-cli 各步。
     *
     * <p>{@code transportInput} 为 null 或空 = 自动创建新传输请求；否则复用该请求号。
     * 扩展命令经 {@code ABAP_TRANSPORT} 环境变量注入传输号；内置命令
     * （create FUGR / push）在 headArgs 里显式带 {@code --tr}。</p>
     */
    private static ImportResult executeAll(Path stageRoot, String ivPackage, String ivProgName,
                                          String transportInput, IProgressMonitor monitor) throws Exception {
        monitor.beginTask(ImportMessages.format("batchImport.progress.begin",
                        ivPackage, ivProgName, stageRoot),
                IProgressMonitor.UNKNOWN);

        // 应用 SAP 配置页连接信息（临时改写 ~/.abap-cli/systems.json + keychain）。
        String prefsSnapshotFile = AbapGitImportSupport.applyPrefsConnection(stageRoot, monitor);
        System.out.println("[Batch]   prefsSnapshot="
                + (prefsSnapshotFile == null ? "(skip)" : prefsSnapshotFile));
        try {

        // [1] 解析传输请求（用户输入 or 自动创建）
        monitor.subTask(ImportMessages.get("batchImport.progress.resolvingTransport"));
        String transport = AbapGitImportSupport.resolveTransport(stageRoot, ivPackage,
                "Batch Handler import " + ivProgName + " " + ivPackage + " (auto)",
                transportInput, monitor);
        monitor.worked(1);
        System.out.println("[Batch]   → transport=" + transport);

        // [2] 解压 ZIP
        monitor.subTask(ImportMessages.format("batchImport.progress.extracting", REPO_SRC_REL));
        Path dstDir = stageRoot.resolve(REPO_SRC_REL);
        byte[] zipBytes = AbapGitImportSupport.readBundleResource(ZIP_RESOURCE_PATH);
        AbapGitImportSupport.deleteRecursively(dstDir);
        Files.createDirectories(dstDir);
        int extractedCount = AbapGitImportSupport.extractZip(zipBytes, dstDir);
        monitor.worked(1);

        // [3] 占位符改名（文件名 + 文件内容）：ztemplate20 → 新程序名
        monitor.subTask(ImportMessages.format("batchImport.progress.renaming",
                PLACEHOLDER_UPPER, ivProgName));
        AbapGitImportSupport.RenameStats stats = AbapGitImportSupport.renameInDir(dstDir,
                PLACEHOLDER_LOWER, PLACEHOLDER_UPPER,
                ivProgName.toLowerCase(Locale.ROOT), ivProgName.toUpperCase(Locale.ROOT));
        monitor.worked(1);

        // [4] FUGR 布局重命名：abapGit → abap-cli 内置命令期望的 .reps / .func
        monitor.subTask(ImportMessages.get("batchImport.progress.renamingFugr"));
        List<String> groups = AbapGitImportSupport.discoverFugrGroups(dstDir);
        int fugrRenamed = 0;
        for (String g : groups) {
            fugrRenamed += AbapGitImportSupport.renameFugrLayout(dstDir, g);
        }
        monitor.worked(1);
        System.out.println("[Batch]   → FUGR groups=" + groups + ", layoutRenamed=" + fugrRenamed);

        // [5] 写 .abap.json
        monitor.subTask(ImportMessages.format("batchImport.progress.writingAbapJson",
                ivPackage));
        AbapGitImportSupport.writeAbapJson(stageRoot.resolve(".abap.json"), ivPackage,
                REPO_SRC_REL, AbapGitImportSupport.STANDARD_EXTENSIONS);
        monitor.worked(1);

        // 收集文件
        List<Path> progXmlFiles = AbapGitImportSupport.collectByExt(dstDir, ".prog.xml");
        List<Path> progAbapFiles = AbapGitImportSupport.collectByExt(dstDir, ".prog.abap");
        Path mainXml = dstDir.resolve(ivProgName.toLowerCase(Locale.ROOT) + ".prog.xml");

        List<Path> fugrRepsFiles = AbapGitImportSupport.collectByExt(dstDir, ".reps.abap");
        List<Path> fugrFuncFiles = AbapGitImportSupport.collectByExt(dstDir, ".func.abap");

        List<Path> fugrSourceFiles = new ArrayList<>(fugrRepsFiles);
        fugrSourceFiles.addAll(fugrFuncFiles);
        List<Path> fugrXmlFiles = AbapGitImportSupport.collectByExt(dstDir, ".fugr.xml");

        // 最后一步「统一激活」的输入：程序源码 + FUGR 源码 + FUGR 组元数据，
        // 使函数组 / 函数模块与程序在同一次 activation 请求里一并激活。
        List<Path> batchActivateFiles = new ArrayList<>(progAbapFiles);
        batchActivateFiles.addAll(fugrSourceFiles);
        batchActivateFiles.addAll(fugrXmlFiles);

        // 函数模块：从 <group>.fugr.<fm>.func.abap 文件名解析 group → FM。
        Map<String, List<String>> fmsByGroup = collectFunctionModules(fugrFuncFiles);
        int totalFms = 0;
        for (List<String> fms : fmsByGroup.values()) totalFms += fms.size();

        // 未被本流程处理的 abapGit 对象类型：只报告不阻断。
        List<String> unhandledTypes = AbapGitImportSupport.findUnhandledObjectTypes(
                dstDir, HANDLED_OBJECT_TYPES);

        // 进度总步数（与实际执行步数一致；create 失败时会少跑 prog-xml-update）。
        int total = groups.size()
                + totalFms
                + (fugrSourceFiles.isEmpty() ? 0 : 1)          // push FUGR sources
                + (progXmlFiles.isEmpty() ? 0 : 1)              // prog-xml-create
                + (progXmlFiles.isEmpty() ? 0 : 1)              // prog-xml-update
                + (progAbapFiles.isEmpty() ? 0 : 1)             // prog-source-push
                + (Files.isRegularFile(mainXml) ? 1 : 0)        // prog-xml-push
                + 1                                             // inspect
                + (Files.isRegularFile(mainXml) ? 1 : 0)        // prog-xml-verify
                + (batchActivateFiles.isEmpty() ? 0 : 1);       // prog-batch-activate
        if (total <= 0) total = 1;

        List<StepOutcome> steps = new ArrayList<>();
        int idx = 0;

        // ---- FUGR（先于 PROG：程序会调用函数模块）----

        // create FUGR <group>（建函数组，骨架主程序由后续 push 覆盖）
        for (String group : groups) {
            String groupUp = group.toUpperCase(Locale.ROOT);
            List<String> head = new ArrayList<>(Arrays.asList(
                    "create", "FUGR", groupUp,
                    "--package", ivPackage,
                    "--description", OBJ_DESC_PREFIX + groupUp,
                    "--yes", "--no-pull"));
            addTransport(head, transport);
            idx++;
            steps.add(AbapGitImportSupport.runAbapCliStep(idx, "create FUGR " + groupUp,
                    stageRoot, head, new ArrayList<>(),
                    stageRoot.resolve("_batch_fugr_create_" + group + "_result.txt"),
                    transport, total, monitor));
        }

        // create FUGR <group> --func <fm>（每个函数模块）
        for (Map.Entry<String, List<String>> e : fmsByGroup.entrySet()) {
            String groupUp = e.getKey().toUpperCase(Locale.ROOT);
            for (String fm : e.getValue()) {
                List<String> head = new ArrayList<>(Arrays.asList(
                        "create", "FUGR", groupUp,
                        "--func", fm,
                        "--package", ivPackage,
                        "--description", OBJ_DESC_PREFIX + fm,
                        "--yes", "--no-pull"));
                addTransport(head, transport);
                idx++;
                steps.add(AbapGitImportSupport.runAbapCliStep(idx, "create FUGR func " + fm,
                        stageRoot, head, new ArrayList<>(),
                        stageRoot.resolve("_batch_fugr_func_" + fm.toLowerCase(Locale.ROOT)
                                + "_result.txt"),
                        transport, total, monitor));
            }
        }

        // push FUGR 源码（主程序 .reps + include .reps + 函数模块 .func）
        // --no-activate：只写源码、跳过逐对象激活与 latest===active 校验；三个
        // 子对象（主程序 / include / FM）互相依赖，逐个激活会报 "active source
        // is stale" 或参数错误。统一激活放到流程最后一步 prog-batch-activate。
        if (!fugrSourceFiles.isEmpty()) {
            List<String> head = new ArrayList<>(Arrays.asList("push", "--yes", "--no-activate"));
            addTransport(head, transport);
            idx++;
            steps.add(AbapGitImportSupport.runAbapCliStep(idx, "push FUGR sources",
                    stageRoot, head,
                    AbapGitImportSupport.relToStage(stageRoot, fugrSourceFiles),
                    stageRoot.resolve("_batch_fugr_push_result.txt"),
                    transport, total, monitor));
        }

        // ---- PROG（与 Simple Query Handler Template 完全一致的步骤序列）----

        // prog-xml-create
        if (!progXmlFiles.isEmpty()) {
            idx++;
            steps.add(AbapGitImportSupport.runAbapCliStep(idx, "prog-xml-create",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-xml-create")),
                    AbapGitImportSupport.relToStage(stageRoot, progXmlFiles),
                    stageRoot.resolve("_batch_prog_create_result.txt"),
                    transport, total, monitor));
        }
        // create 退出码只用于决定后续是否还要跑 update；整体成败由流程末尾统一判定。
        final boolean createOk = stepExitCode(steps, "prog-xml-create") == 0;

        // prog-xml-update
        if (createOk && !progXmlFiles.isEmpty()) {
            idx++;
            steps.add(AbapGitImportSupport.runAbapCliStep(idx, "prog-xml-update",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-xml-update")),
                    AbapGitImportSupport.relToStage(stageRoot, progXmlFiles),
                    stageRoot.resolve("_batch_prog_update_result.txt"),
                    transport, total, monitor));
        }

        // prog-source-push（只写源码，不激活）
        if (!progAbapFiles.isEmpty()) {
            idx++;
            steps.add(AbapGitImportSupport.runAbapCliStep(idx, "prog-source-push",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-source-push")),
                    AbapGitImportSupport.relToStage(stageRoot, progAbapFiles),
                    stageRoot.resolve("_batch_prog_source_push_result.txt"),
                    transport, total, monitor));
        }

        // prog-xml-push（主程序 xml：DYNPROS / CUA / I18N_TPOOL / PROGDIR 属性）
        if (Files.isRegularFile(mainXml)) {
            idx++;
            steps.add(AbapGitImportSupport.runAbapCliStep(idx, "prog-xml-push",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-xml-push")),
                    Arrays.asList(AbapGitImportSupport.relToStage(stageRoot, mainXml)),
                    stageRoot.resolve("_batch_prog_xml_push_result.txt"),
                    transport, total, monitor));
        } else {
            idx++;
            steps.add(new StepOutcome(idx, "prog-xml-push", -1, null,
                    ImportMessages.format("batchImport.step.mainXmlNotFound", mainXml)));
        }

        // inspect（核对主程序及其 includes；信息性步骤，失败不阻断整体）
        idx++;
        steps.add(AbapGitImportSupport.runAbapCliStep(idx, "inspect",
                stageRoot,
                new ArrayList<>(Arrays.asList("inspect", ivProgName, "--includes", "--pretty-json")),
                new ArrayList<>(),
                stageRoot.resolve("_batch_prog_inspect_result.txt"),
                transport, total, monitor));

        // prog-xml-verify
        if (Files.isRegularFile(mainXml)) {
            idx++;
            steps.add(AbapGitImportSupport.runAbapCliStep(idx, "prog-xml-verify",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-xml-verify")),
                    Arrays.asList(AbapGitImportSupport.relToStage(stageRoot, mainXml)),
                    stageRoot.resolve("_batch_prog_verify_result.txt"),
                    transport, total, monitor));
        } else {
            idx++;
            steps.add(new StepOutcome(idx, "prog-xml-verify", -1, null,
                    ImportMessages.format("batchImport.step.mainXmlNotFound", mainXml)));
        }

        // prog-batch-activate —— 全部对象（程序 + 函数组 + 函数模块）统一激活
        // （流程最后一步；这一步成功即整体成功，见下方整体判定）
        boolean batchActivateRan = false;
        if (!batchActivateFiles.isEmpty()) {
            batchActivateRan = true;
            idx++;
            steps.add(AbapGitImportSupport.runAbapCliStep(idx, "prog-batch-activate",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-batch-activate")),
                    AbapGitImportSupport.relToStage(stageRoot, batchActivateFiles),
                    stageRoot.resolve("_batch_prog_batch_activate_result.txt"),
                    transport, total, monitor));
        } else {
            idx++;
            steps.add(new StepOutcome(idx, "prog-batch-activate", -1, null,
                    ImportMessages.get("batchImport.step.noActivatableSource")));
        }

        // 整体成败判定：以流程最后一步「统一激活」(prog-batch-activate) 为准。
        // 该步把所有对象（程序 + 函数组 + 函数模块）放入同一次 activation 请求，
        // 等价于 SE38 中「全选未激活对象 → 激活」；只要它成功，说明所有对象都已
        // 落到 active 状态，中间步骤的个别告警（如逐对象激活时序、xml-update 的
        // corrNr 提示）不再影响整体结论。
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
                if (o.message != null
                        && o.message.contains(ImportMessages.get("importSupport.marker.notFound"))) {
                    overall = -1;
                    continue;
                }
                if (o.locked) {
                    overall = -1;
                    continue;
                }
                if (o.exitCode != 0 && isCriticalStep(o.name)) {
                    overall = -1;
                }
            }
        }

        ImportResult r = new ImportResult();
        r.placeholderUpper = PLACEHOLDER_UPPER;
        r.transportRequest = transport;
        r.renamedFiles = stats.renamedFiles + fugrRenamed;
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
                    System.err.println("[Batch] prefs restore failed: " + e.getMessage());
                }
            }
        }
    }

    // ====================================================================
    // 辅助
    // ====================================================================

    /**
     * 关键步骤（失败即整体 FAILED）。仅在「未执行统一激活」的回退判定中使用；
     * 统一激活本身由 {@code prog-batch-activate} 单独判定，故不列入此处。
     */
    private static boolean isCriticalStep(String name) {
        return name.startsWith("create FUGR")
                || name.startsWith("push FUGR")
                || "prog-xml-create".equals(name)
                || "prog-source-push".equals(name);
    }

    /** 传输号非空时，给内置命令（create / push）追加 {@code --tr <transport>}。 */
    private static void addTransport(List<String> head, String transport) {
        if (transport != null && !transport.trim().isEmpty()) {
            head.add("--tr");
            head.add(transport.trim());
        }
    }

    /** 已执行步骤里 name 匹配者的退出码；未执行返回 0（视为无需 update / 成功）。 */
    private static int stepExitCode(List<StepOutcome> steps, String name) {
        for (StepOutcome o : steps) {
            if (name.equals(o.name)) {
                return o.exitCode;
            }
        }
        return 0;
    }

    /**
     * 从 {@code <group>.fugr.<fm>.func.abap} 文件名解析函数模块，按 group 分组
     * （保持插入顺序，FM 去重）。
     */
    private static Map<String, List<String>> collectFunctionModules(List<Path> funcFiles) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Path p : funcFiles) {
            String base = p.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!base.endsWith(".func.abap")) continue;
            String stem = base.substring(0, base.length() - ".func.abap".length());
            String[] parts = stem.split("\\.");
            if (parts.length != 3 || !"fugr".equals(parts[1])) continue;
            String group = parts[0];
            String fm = parts[2].toUpperCase(Locale.ROOT);
            List<String> fms = out.computeIfAbsent(group, k -> new ArrayList<>());
            if (!fms.contains(fm)) fms.add(fm);
        }
        return out;
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

        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "Batch Import Handler Templates → SAP");
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
        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "  Extracted : " + r.extractedCount + " files");

        AbapGitImportSupport.emit(fileLines, IStatus.INFO, "  Steps:");
        for (StepOutcome o : r.steps) {
            int sev = o.exitCode != 0 ? IStatus.ERROR : IStatus.INFO;
            StringBuilder sb = new StringBuilder();
            sb.append("    [").append(o.idx).append("] ")
                    .append(AbapGitImportSupport.padRight(o.name, 28))
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
                "===== Batch Import Handler Templates import =====");
        return logFile;
    }
}
