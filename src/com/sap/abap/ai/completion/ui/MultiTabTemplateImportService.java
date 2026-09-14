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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.core.runtime.IProgressMonitor;
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

/**
 * Multi-Tab Query Handler Template 导入服务（菜单项触发）。
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
 *   <li>写 {@code .abap.json}（注册 5 个 abap-cli 扩展 + 用户输入的包名）</li>
 *   <li>调 {@code node abap-cli} 的 6 步：
 *       {@code prog-xml-create} → {@code prog-xml-update} →
 *       {@code prog-source-push} → {@code prog-xml-push} →
 *       {@code inspect} → {@code prog-xml-verify}</li>
 *   <li>汇总 6 步退出码并弹结果对话框</li>
 * </ol>
 *
 *
 * <p><b>依赖</b>：用户机器上必须已存在 abap-cli stage 工作区
 * {@link #STAGE_DIR}，其下含 {@code node_modules/abap-cli/} 与 5 个扩展
 * {@code extensions/prog-*.mjs}。{@code node} 必须在 PATH 中。HOME /
 * USERPROFILE 必须可解析（keychain 凭据在 {@code ~/.abap-cli/systems.json}）。</p>
 *
 * <p><b>为何不复用 {@link ABAPTemplateService}</b>：那条路径走 RFC
 * {@code Z_ABAPGIT_UPLOAD_FROM_XSTRING}（JCo 单次上传），与 abap-cli 扩展流
 * （按对象逐个 create/source-push/dynpro/CUA/textpool/verify）是不同的实现。
 * 用户明确"现程序应该已有，尽量不用修改"，因此独立编写本类。</p>
 */
public final class MultiTabTemplateImportService {

    /** 用户机器上的 abap-cli stage 根目录（abap-cli node_modules + 5 个扩展均在此）。 */
    public static final String STAGE_DIR =
            "D:\\Users\\96000217\\Documents\\trae_projects\\_abap-cli-stage";

    /** 模板 zip 在 bundle 内的资源路径。 */
    private static final String ZIP_RESOURCE_PATH = "references/ZTEMPLATE10.zip";

    /** 解压目录（相对 stage）；对应 .abap.json 的 sourceDir。 */
    private static final String REPO_SRC_REL = "repos/ztemplate10/src";

    /** abap-cli node 入口（相对 stage）。 */
    public static final String ABAP_CLI_INDEX_REL =
            "node_modules/abap-cli/dist/src/abap_cli/index.js";

    /** 5 个 abap-cli 扩展目录（相对 stage）。 */
    private static final String EXTENSIONS_DIR_REL = "extensions";

    /** abap-cli 扩展名（按 ZTemplate10Import 顺序）。 */
    private static final List<String> EXTENSION_NAMES = Arrays.asList(
            "prog-xml-create", "prog-xml-update",
            "prog-source-push", "prog-xml-push", "prog-xml-verify");

    /** 文本类文件扩展名（与 AbapGITUploadService.TEXT_EXTENSIONS 同源）。 */
    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
                "abap", "xml", "asddls", "asbdef", "ddls", "ddlx", "asddlxs",
                "bdef", "clas", "intf", "prog", "tabl", "doma", "dtel",
                "txt", "md", "json", "properties"));

    /** 主程序占位符（与 ZIP 中现存模板主程序名一致）。 */
    private static final String PLACEHOLDER_LOWER = "ztemplate10";
    private static final String PLACEHOLDER_UPPER = "ZTEMPLATE10";

    /** 子进程默认超时（秒）。 */
    private static final int DEFAULT_TIMEOUT_SEC = 600;

    private MultiTabTemplateImportService() {
    }

    /**
     * 公共入口：从菜单点击触发。
     * 流程：环境检查 → 输入 → 进度对话框跑 6 步 → 弹结果。
     */
    public static void runImport() {
        // ===== [1] 环境检查 =====
        Path stageRoot;
        try {
            stageRoot = checkEnvironment();
        } catch (EnvironmentException ee) {
            showError("Multi-Tab Query Handler — 环境检查失败", ee.getMessage());
            return;
        }

        // ===== [2] 输入 PACKAGE / 程序名 =====
        String ivPackage = promptZy("Multi-Tab Query Handler Import",
                "Please enter the target SAP package (e.g., ZDEV_PACKAGE or $TMP).\n"
                        + "Must start with Z or Y; '/' and '$' allowed; auto-uppercase.",
                true);
        if (ivPackage == null) return;

        String ivProgName = promptZy("Multi-Tab Query Handler Import",
                "Please enter the new program name (e.g., ZMY_MT_QUERY).\n"
                        + "Must start with Z or Y; English letters / digits / '_' only; "
                        + "max 30 chars; auto-uppercase.\n"
                        + "The placeholder " + PLACEHOLDER_UPPER
                        + " will be renamed to this name in the uploaded objects.",
                false);
        if (ivProgName == null) return;

        // ===== 进度对话框跑解压+改名+6 步 abap-cli =====
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
                        holder[0] = executeAll(stageFinal, pkgFinal, progFinal, monitor);
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
            showError("Multi-Tab Query Handler — Failed",
                    "Unexpected error during import:\n\n" + stackTrace(errorHolder[0]));
            return;
        }

        // ===== 弹结果 =====
        ImportResult r = holder[0];
        String title = r.overallRc == 0 ? "Import Succeeded" : "Import Failed";
        StringBuilder sb = new StringBuilder();
        sb.append("Multi-Tab Query Handler Template → SAP\n\n");
        sb.append("  Package : ").append(pkgFinal).append('\n');
        sb.append("  Program : ").append(progFinal).append("  (placeholder ")
                .append(r.placeholderUpper).append(" → ").append(progFinal).append(")\n");
        sb.append("  Stage   : ").append(stageFinal).append('\n');
        sb.append("  Renamed : files=").append(r.renamedFiles)
                .append(", text contents=").append(r.renamedContent).append("\n\n");
        sb.append("  Steps:\n");
        for (StepOutcome o : r.steps) {
            sb.append("    [").append(o.idx).append("] ")
                    .append(padRight(o.name, 22))
                    .append(" exit=").append(o.exitCode);
            if (o.resultFile != null) {
                sb.append("   → ").append(o.resultFile.getFileName());
            }
            sb.append('\n');
        }
        sb.append("\n  Overall: ").append(r.overallRc == 0 ? "OK" : "FAILED (exit=" + r.overallRc + ")");
        if (r.overallRc != 0) {
            sb.append("\n\n  Check:\n");
            sb.append("    • ").append(Paths.get(stageFinal.toString(), "_multi-tab_*.txt").toString()).append('\n');
            sb.append("    • Eclipse Error Log (Window → Show View → Error Log)\n");
            sb.append("    • abap-cli systems.json profile: ~/.abap-cli/systems.json");
        }

        if (r.overallRc == 0) {
            MessageDialog.openInformation(getShell(), title, sb.toString());
        } else {
            MessageDialog.openError(getShell(), title, sb.toString());
        }
    }

    // ====================================================================
    // 主流程：解压 → 改名 → 写 .abap.json → 跑 6 步
    // ====================================================================

    /**
     * 解压 + 改名 + 写 .abap.json + 6 步 abap-cli。
     * 全部完成后返回 ImportResult。
     */
    private static ImportResult executeAll(Path stageRoot, String ivPackage, String ivProgName,
                                          IProgressMonitor monitor) throws Exception {
        monitor.beginTask("Importing Multi-Tab Query Handler Template\n"
                        + "Package: " + ivPackage + "   Program: " + ivProgName
                        + "   Stage: " + stageRoot,
                IProgressMonitor.UNKNOWN);

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

        // 6 步 abap-cli
        List<StepOutcome> steps = new ArrayList<>();
        int overall = 0;

        // [6.1] prog-xml-create
        steps.add(runAbapCliStep(1, "prog-xml-create",
                stageRoot,
                new ArrayList<>(Arrays.asList("prog-xml-create")),
                relToStage(stageRoot, xmlFiles),
                stageRoot.resolve("_multi-tab_create_result.txt"),
                monitor));
        if (steps.get(steps.size() - 1).exitCode != 0) overall = steps.get(steps.size() - 1).exitCode;

        // [6.2] prog-xml-update
        if (overall == 0) {
            steps.add(runAbapCliStep(2, "prog-xml-update",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-xml-update")),
                    relToStage(stageRoot, xmlFiles),
                    stageRoot.resolve("_multi-tab_update_result.txt"),
                    monitor));
            // update 非零不阻断
        }

        // [6.3] prog-source-push
        steps.add(runAbapCliStep(3, "prog-source-push",
                stageRoot,
                new ArrayList<>(Arrays.asList("prog-source-push")),
                relToStage(stageRoot, abapFiles),
                stageRoot.resolve("_multi-tab_source_push_result.txt"),
                monitor));
        if (steps.get(steps.size() - 1).exitCode != 0 && overall == 0) {
            overall = steps.get(steps.size() - 1).exitCode;
        }

        // [6.4] prog-xml-push (DYNPROS / CUA / I18N_TPOOL)
        if (Files.isRegularFile(mainXml)) {
            steps.add(runAbapCliStep(4, "prog-xml-push",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-xml-push")),
                    Arrays.asList(relToStage(stageRoot, mainXml)),
                    stageRoot.resolve("_multi-tab_xml_push_result.txt"),
                    monitor));
            // xml-push 非零不阻断
        } else {
            steps.add(new StepOutcome(4, "prog-xml-push", -1, null,
                    "main prog.xml not found: " + mainXml));
        }

        // [6.5] inspect
        steps.add(runAbapCliStep(5, "inspect",
                stageRoot,
                new ArrayList<>(Arrays.asList("inspect", ivProgName, "--includes", "--pretty-json")),
                new ArrayList<>(),
                stageRoot.resolve("_multi-tab_inspect_result.txt"),
                monitor));

        // [6.6] prog-xml-verify
        if (Files.isRegularFile(mainXml)) {
            steps.add(runAbapCliStep(6, "prog-xml-verify",
                    stageRoot,
                    new ArrayList<>(Arrays.asList("prog-xml-verify")),
                    Arrays.asList(relToStage(stageRoot, mainXml)),
                    stageRoot.resolve("_multi-tab_verify_result.txt"),
                    monitor));
        } else {
            steps.add(new StepOutcome(6, "prog-xml-verify", -1, null,
                    "main prog.xml not found: " + mainXml));
        }

        ImportResult r = new ImportResult();
        r.placeholderUpper = PLACEHOLDER_UPPER;
        r.renamedFiles = stats.renamedFiles;
        r.renamedContent = stats.renamedContent;
        r.steps = steps;
        r.overallRc = overall;
        r.extractedCount = extractedCount;
        return r;
    }

    // ====================================================================
    // 步骤执行：调一次 node abap-cli
    // ====================================================================

    /**
     * 调一次 node abap-cli，并把 stdout/stderr 写到 resultFile。
     */
    private static StepOutcome runAbapCliStep(int idx, String label, Path stageRoot,
                                              List<String> headArgs, List<String> fileArgs,
                                              Path resultFile, IProgressMonitor monitor) {
        monitor.subTask("[" + idx + "/6] " + label + " ...");
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

    /**
     * 读取子进程 stdout/stderr 合流；超时则强制 destroy。
     */
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
     * 检查 stage / abap-cli / 5 个扩展 / node 是否就位。
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
                            + "\n\nPlease copy the 5 prog-*.mjs extensions from your abapgit project"
                            + "\ninto the stage's extensions/ directory.");
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
     * 文件名规则：base_lower 与 base_lower + "_xxx" 整体改名为 newLower / newLower_xxx。
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
            String nameLower = name.toLowerCase(Locale.ROOT);

            String newName = null;
            if (nameLower.equals(baseLower)) {
                // baseLower → newLower（保持后缀）
                int dot = name.lastIndexOf('.');
                String suffix = dot >= 0 ? name.substring(dot) : "";
                newName = newLower + suffix;
            } else if (nameLower.startsWith(baseLower + "_")) {
                // baseLower_xxx → newLower_xxx（保持后缀）
                String tail = nameLower.substring(baseLower.length()); // "_xxx..."
                int dot = name.lastIndexOf('.');
                String suffix = dot >= 0 ? name.substring(dot) : "";
                newName = newLower + tail + suffix;
            } else if (nameLower.startsWith(baseLower + ".")) {
                // baseLower.<ext> → newLower.<ext>
                int dot = name.lastIndexOf('.');
                String suffix = dot >= 0 ? name.substring(dot) : "";
                newName = newLower + suffix;
            }

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

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        return name.substring(dot + 1);
    }

    /**
     * 写 .abap.json（覆盖现有），注册 5 个 abap-cli 扩展 + 用户输入的包名。
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
    // UI 辅助（仿 ABAPTemplateService 风格，独立实现）
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
    // Bundle 资源读取（与 AbapGITUploadService 同风格，但本类自包含）
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