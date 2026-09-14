package com.sap.abap.ai.completion.ui;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

import com.sap.abap.ai.completion.sap.AbapGITUploadService;

/**
 * “模板”菜单服务：将插件 <code>references/</code> 下的 zip(离线 abapGIT 仓库)
 * 通过 JCo 调用 RFC <code>Z_ABAPGIT_UPLOAD_FROM_XSTRING</code> 上传到 SAP 系统。
 *
 * <p>“模板”子菜单中的每个子项及其对应 zip 由插件根目录的配置文件
 * <code>templates.properties</code> 决定(见 {@link #getTemplateDefs()})。
 * 新增模板菜单时，只需把 zip 放入 <code>references/</code> 并在
 * <code>templates.properties</code> 中增加一行 “菜单名=zip文件名” 即可，
 * 无需修改代码。</p>
 */
public final class ABAPTemplateService {

    /** “模板”zip 所在根目录(bundle 资源路径)。 */
    private static final String REFERENCES_BASE = "references/";

    /** 模板菜单配置文件(bundle 根目录资源路径)。格式: 菜单名=references 下的 zip 文件名。 */
    private static final String TEMPLATES_CONFIG = "templates.properties";

    /** 模板定义缓存(首次调用 {@link #getTemplateDefs()} 时从配置文件加载)。 */
    private static volatile List<TemplateZipDef> cachedDefs;

    private ABAPTemplateService() {
    }

    /**
     * 返回当前可用的模板定义列表(菜单项据此生成)。
     *
     * <p>从 bundle 根目录的 <code>templates.properties</code> 读取
     * “菜单名=zip文件名” 映射；配置缺失或解析失败时回退到内置默认项。</p>
     */
    public static List<TemplateZipDef> getTemplateDefs() {
        List<TemplateZipDef> defs = cachedDefs;
        if (defs == null) {
            synchronized (ABAPTemplateService.class) {
                defs = cachedDefs;
                if (defs == null) {
                    defs = Collections.unmodifiableList(loadTemplateDefs());
                    cachedDefs = defs;
                }
            }
        }
        return defs;
    }

    /**
     * 从 <code>templates.properties</code> 加载模板定义。
     * 每行格式为 “菜单名=zip文件名”(zip 位于 references/ 下)；'#' 开头与空行忽略。
     * 读取失败时回退到内置默认项，保证菜单始终可用。
     */
    private static List<TemplateZipDef> loadTemplateDefs() {
        List<TemplateZipDef> defs = new ArrayList<>();
        try {
            byte[] cfg = AbapGITUploadService.readResourceBytes(TEMPLATES_CONFIG);
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new ByteArrayInputStream(cfg), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq <= 0) {
                        continue; // 缺少 '=' 或菜单名为空
                    }
                    String label = trimmed.substring(0, eq).trim();
                    String zipFile = trimmed.substring(eq + 1).trim();
                    if (label.isEmpty() || zipFile.isEmpty()) {
                        continue;
                    }
                    // 配置中只写 references/ 下的文件名; 拼上基础目录
                    String resourcePath = zipFile.contains("/")
                            ? zipFile
                            : REFERENCES_BASE + zipFile;
                    defs.add(new TemplateZipDef(label, resourcePath, "OFFLINE_REPO"));
                }
            }
        } catch (Exception e) {
            // 配置文件缺失/不可读: 记录并回退到内置默认项
            System.out.println("[ABAPTemplateService] Cannot read " + TEMPLATES_CONFIG
                    + " (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + "), falling back to built-in default.");
        }

        if (defs.isEmpty()) {
            defs.add(new TemplateZipDef(
                    "Simple Query Handler Template",
                    REFERENCES_BASE + "ZTEMPLATE10.zip",
                    "OFFLINE_REPO"));
        }
        return defs;
    }

    /**
     * 执行模板 zip 上传主流程：
     * 输入必填的 IV_PACKAGE / IV_REPO_NAME → 后台线程调用 RFC 上传 → 弹出结果。
     *
     * @param label 模板菜单显示名(须存在于 {@link #getTemplateDefs()})
     */
    public static void runTemplateUpload(final String label) {
        TemplateZipDef def = findTemplateDef(label);
        if (def == null) {
            showError("Template Upload",
                    "Template definition not found: " + label
                            + "\nPlease configure it in ABAPTemplateService.getTemplateDefs().");
            return;
        }

        // 1. IV_PACKAGE —— target package (required, must start with Z or Y)
        String ivPackage = promptRequired("Template Upload",
                "Please enter the target package (e.g., ZDEV_PACKAGE).\n"
                        + "The package name must start with Z or Y (customer namespace).\n"
                        + "Only English letters, digits, '_', '/' and '$' are accepted; "
                        + "letters are converted to uppercase automatically:");
        if (ivPackage == null) {
            return; // user cancelled
        }

        // 2. New program/object name (required, must start with Z or Y).
        //    Used to rename the template placeholder ZTEMPLATE10 inside the zip
        //    (both file names and text-file contents) before uploading.
        String ivProgName = promptRequiredWithDefault("Template Upload",
                "Please enter the new program name to create (e.g., ZMY_QUERY).\n"
                        + "It must start with Z or Y, use English letters/digits/'_' only "
                        + "(max 30 chars), and letters are converted to uppercase automatically.\n"
                        + "The template placeholder "
                        + AbapGITUploadService.TEMPLATE_PLACEHOLDER_UPPER
                        + " will be renamed to this name in the uploaded objects:",
                null, true, false);
        if (ivProgName == null) {
            return; // user cancelled
        }

        // 3. IV_REPO_NAME —— auto-generated, no prompt: AI_OFFLINE_HHmmss
        //    (HHmmss = current time hour/minute/second, 24-hour format)
        String ivRepoName = "AI_OFFLINE_" + java.time.LocalTime.now().format(
                java.time.format.DateTimeFormatter.ofPattern("HHmmss"));

        // 4. 进度对话框中执行后台处理 + 上传:
        //    ProgressMonitorDialog 在任务运行期间显示进度条, run() 返回后
        //    对话框【自动关闭】。处理链路: 读取模板 zip -> 实例化(重命名占位符)
        //    -> 重新打包 -> JCo/RFC 上传。
        final String fPackage = ivPackage;
        final String fRepo = ivRepoName;
        final String fProgName = ivProgName;
        final AbapGITUploadService.UploadResult[] resultHolder =
                new AbapGITUploadService.UploadResult[1];
        final AbapGITUploadService.TemplateInstantiationResult[] instHolder =
                new AbapGITUploadService.TemplateInstantiationResult[1];
        final Exception[] errorHolder = new Exception[1];

        ProgressMonitorDialog pmd = new ProgressMonitorDialog(getShell());
        try {
            pmd.run(true, false, new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor) {
                    monitor.beginTask("Uploading \"" + def.getLabel() + "\" to SAP ..."
                            + "\nPackage: " + fPackage + "   Program: " + fProgName
                            + "   Repository: " + fRepo,
                            IProgressMonitor.UNKNOWN);
                    try {
                        byte[] templateZip = AbapGITUploadService.readResourceBytes(
                                def.getZipResourcePath());
                        AbapGITUploadService.TemplateInstantiationResult inst =
                                AbapGITUploadService.instantiateTemplate(templateZip, fProgName);
                        instHolder[0] = inst;
                        resultHolder[0] = AbapGITUploadService.upload(
                                inst.getZipData(), fPackage, fRepo);
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

        // pmd.run() 返回时进度对话框已自动关闭, 以下代码回到 UI 线程, 直接弹结果
        if (errorHolder[0] != null) {
            MessageDialog.openError(getShell(), "Template Upload Failed",
                    "Error calling " + AbapGITUploadService.RFC_FUNCTION
                            + ":\n\n" + buildErrorHint(errorHolder[0]));
            return;
        }

        AbapGITUploadService.UploadResult result = resultHolder[0];
        AbapGITUploadService.TemplateInstantiationResult inst = instHolder[0];

        // Rename summary + warning if the template contained no replaceable program
        String renameInfo;
        boolean renameWarning = false;
        if (inst != null && inst.isRenamed()) {
            renameInfo = "\n  Renamed   : " + inst.getPlaceholder() + " -> "
                    + inst.getNewProgramName()
                    + "  (" + inst.getRenamedFiles() + " files, "
                    + inst.getRenamedContent() + " text contents)\n";
        } else {
            renameWarning = true;
            renameInfo = "\n  Renamed   : WARNING - no replaceable program ("
                    + (inst != null ? inst.getPlaceholder() : "n/a")
                    + ") found in this template;\n"
                    + "              the zip was uploaded with its ORIGINAL object names.\n"
                    + "              (The template zip may not contain a main program to rename.)\n";
        }

        String title = result.isSuccess() ? "Upload Succeeded" : "Upload Failed";
        String msg = (result.isSuccess()
                ? "RFC call succeeded.\n\n"
                : "RFC call finished, but SAP returned a failure.\n\n")
                + "  Function  : " + AbapGITUploadService.RFC_FUNCTION + "\n"
                + "  Package   : " + fPackage + "\n"
                + "  Program   : " + fProgName + "\n"
                + "  Repository: " + fRepo + "\n"
                + renameInfo + "\n"
                + "SAP message:\n" + result.getMessage()
                + packageOccupiedHint(result.getMessage());
        // A successful RFC but with no rename is misleading; surface it as a warning.
        if (result.isSuccess() && !renameWarning) {
            MessageDialog.openInformation(getShell(), title, msg);
        } else {
            MessageDialog.openWarning(getShell(), title, msg);
        }
    }

    /**
     * 当 SAP 返回消息表明"目标包已被其它 abapGit 仓库占用"时，
     * 追加处理指引(用 ZABAPGIT 程序删除已占用该包的仓库)。
     *
     * <p>abapGit 在包已被其它仓库使用时返回的消息(中/英文)通常包含：
     * "包 ... 已被其它仓库占用" / "package ... already used by another
     * repository"。这里按关键词宽松匹配。</p>
     */
    private static String packageOccupiedHint(String sapMessage) {
        if (sapMessage == null) {
            return "";
        }
        String lower = sapMessage.toLowerCase();
        boolean cnHit = sapMessage.contains("包")
                && (sapMessage.contains("占用") || sapMessage.contains("其它仓库")
                        || sapMessage.contains("其他仓库"));
        boolean enHit = lower.contains("package")
                && (lower.contains("already") || lower.contains("used by")
                        || lower.contains("another repository")
                        || lower.contains("other repository"));
        if (!cnHit && !enHit) {
            return "";
        }
        return "\n\nSuggestion: This package is already used by another abapGit "
                + "repository. Please first remove the repository that occupies this "
                + "package using the ZABAPGIT program, then retry:\n"
                + "  1. Run transaction SE38 (or SA38) and execute report ZABAPGIT;\n"
                + "  2. In the abapGit repository list, find the repository using this package;\n"
                + "  3. Choose [Uninstall] for that repository (deletes the repo and its objects);\n"
                + "  4. Re-run the template upload afterwards.";
    }

    private static TemplateZipDef findTemplateDef(String label) {
        for (TemplateZipDef def : getTemplateDefs()) {
            if (def.getLabel().equals(label)) {
                return def;
            }
        }
        return null;
    }

    /**
     * 将异常转为对用户友好的提示。
     * 针对常见问题给出精准指引：函数未远程启用、函数不存在、连接未配置、
     * JCo native 库缺失等。
     */
    private static String buildErrorHint(Throwable t) {
        StringBuilder sb = new StringBuilder();
        if (t != null && t.getMessage() != null) {
            sb.append(t.getMessage()).append('\n');
        }

        // 1) 函数模块存在但未标记为"远程启用" —— 最常见的 RFC 调用失败
        if (AbapGITUploadService.isNotRemoteEnabledError(t)) {
            sb.append('\n').append(AbapGITUploadService.remoteEnabledFixHint());
            return sb.toString();
        }

        // 2) Function module does not exist
        if (t != null && t.getMessage() != null
                && t.getMessage().toUpperCase().contains("NOT_FOUND")
                && t.getMessage().contains(AbapGITUploadService.RFC_FUNCTION)) {
            sb.append("\nFunction module ").append(AbapGITUploadService.RFC_FUNCTION)
              .append(" does not exist in the SAP system. Please create it in SE37 "
                      + "(and mark it as Remote-Enabled).");
            return sb.toString();
        }

        // 3) Generic checklist
        sb.append("\nPlease check:\n")
          .append("  1. Connection info is filled in \"ABAP AI Completion -> SAP Connection Config\";\n")
          .append("  2. Function module ").append(AbapGITUploadService.RFC_FUNCTION)
          .append(" exists and is marked as [Remote-Enabled Module] in SE37 attributes;\n")
          .append("  3. The JCo libraries (sapjco3.jar + native sapjco3.dll) are correctly installed.");
        return sb.toString();
    }

    // ==================== Dialogs ====================

    /**
     * 弹出必填输入框(空值或取消会一直循环直到输入合法值或取消)。
     * 用于包名: 必须 Z/Y 开头, 允许 '/'(命名空间) 与 '$'。
     */
    private static String promptRequired(String title, String message) {
        return promptRequiredWithDefault(title, message, null, true, true);
    }

    /**
     * 弹出必填输入框(带默认值); 用户必须输入非空内容, 取消返回 {@code null}。
     *
     * @param requireZyPrefix     为 true 时, 输入值必须以 Z 或 Y 开头(ABAP 客户命名空间)。
     * @param allowSlashAndDollar 为 true 时允许字符 '/' 与 '$'(包名/命名空间);
     *                            为 false 时仅允许字母/数字/'_'(程序名等简单对象名)。
     */
    private static String promptRequiredWithDefault(final String title,
                                                    final String message,
                                                    final String defaultValue,
                                                    final boolean requireZyPrefix,
                                                    final boolean allowSlashAndDollar) {
        final String[] resultHolder = { null };
        try {
            Display.getDefault().syncExec(new Runnable() {
                @Override
                public void run() {
                    Shell shell = getShell();
                    while (true) {
                        InputDialog dlg = new InputDialog(shell, title, message,
                                defaultValue == null ? "" : defaultValue, null) {
                            @Override
                            protected Control createDialogArea(Composite parent) {
                                Control area = super.createDialogArea(parent);
                                Text txt = getText();
                                if (txt != null) {
                                    // Restrict input to English/ASCII characters used in
                                    // ABAP object names and auto-convert letters to uppercase
                                    // as the user types (or pastes). Any non-allowed character
                                    // (e.g. Chinese) makes the whole insertion be rejected.
                                    txt.addListener(SWT.Verify, new Listener() {
                                        @Override
                                        public void handleEvent(Event e) {
                                            String in = e.text;
                                            if (in == null || in.isEmpty()) {
                                                return; // deletion / no new text
                                            }
                                            String up = in.toUpperCase(java.util.Locale.ROOT);
                                            for (int i = 0; i < up.length(); i++) {
                                                char c = up.charAt(i);
                                                boolean allowed =
                                                        (c >= 'A' && c <= 'Z')
                                                        || (c >= '0' && c <= '9')
                                                        || c == '_'
                                                        || (allowSlashAndDollar
                                                                && (c == '/' || c == '$'));
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
                            resultHolder[0] = null;
                            return;
                        }
                        String value = dlg.getValue();
                        if (value != null && !value.trim().isEmpty()) {
                            // Defensive: ensure the accepted value is upper-case
                            String normalized = value.trim().toUpperCase(java.util.Locale.ROOT);
                            if (requireZyPrefix) {
                                char first = normalized.charAt(0);
                                if (first != 'Z' && first != 'Y') {
                                    MessageDialog.openWarning(shell, title,
                                            "The name must start with Z or Y "
                                                    + "(customer namespace). Please re-enter.");
                                    continue;
                                }
                            }
                            resultHolder[0] = normalized;
                            return;
                        }
                        MessageDialog.openWarning(shell, title,
                                "Input cannot be empty. Please enter a value.");
                    }
                }
            });
            return resultHolder[0];
        } catch (Exception e) {
            return null;
        }
    }

    private static Shell getShell() {
        try {
            if (PlatformUI.getWorkbench() == null) {
                return null;
            }
            Shell shell = PlatformUI.getWorkbench().getDisplay().getActiveShell();
            if (shell == null || shell.isDisposed()) {
                IWorkbenchWindow window =
                        PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window != null) {
                    shell = window.getShell();
                }
            }
            return (shell != null && !shell.isDisposed()) ? shell : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void showError(String title, String message) {
        try {
            Display.getDefault().asyncExec(() -> MessageDialog.openError(
                    getShell(), title, message));
        } catch (Exception ignore) {
        }
    }

    /**
     * 一个模板项：菜单显示名 与 对应的 zip 资源路径。
     */
    public static final class TemplateZipDef {
        private final String label;
        private final String zipResourcePath;
        private final String defaultRepoName;

        public TemplateZipDef(String label, String zipResourcePath, String defaultRepoName) {
            this.label = label;
            this.zipResourcePath = zipResourcePath;
            this.defaultRepoName = defaultRepoName;
        }

        public String getLabel() {
            return label;
        }

        public String getZipResourcePath() {
            return zipResourcePath;
        }

        public String getDefaultRepoName() {
            return defaultRepoName;
        }
    }
}
