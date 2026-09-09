package com.sap.abap.ai.completion.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import com.sap.abap.ai.completion.sap.AbapGITUploadService;

/**
 * “模板”菜单服务：将插件 <code>references/</code> 下的 zip(离线 abapGIT 仓库)
 * 通过 JCo 调用 RFC <code>Z_ABAPGIT_UPLOAD_FROM_XSTRING</code> 上传到 SAP 系统。
 *
 * <p>“模板”子菜单中的每个子项对应 <code>references/</code> 下的一个 zip 文件
 * (见 {@link #getTemplateDefs()})。点击后依次弹出输入框要求必填
 * IV_PACKAGE(开发包) 与 IV_REPO_NAME(仓库名)，随后在后台线程调用 RFC 上传并
 * 弹出结果。后续新增模板时，只需在 {@link #getTemplateDefs()} 中增加一项
 * (菜单名 + zip 资源路径) 即可复用同一套上传逻辑。</p>
 */
public final class ABAPTemplateService {

    /** “模板”zip 所在根目录(bundle 资源路径)。 */
    private static final String REFERENCES_BASE = "references/";

    /**
     * 模板定义：菜单显示名 与 对应的 zip 资源路径。
     * 新增模板只需在此追加一项。
     */
    private static final List<TemplateZipDef> TEMPLATE_DEFS = Collections.unmodifiableList(
            Arrays.asList(
                    // “简单查询处理程序模板”→ 上传 ZTEMPLATE10 目录下的离线仓库 zip
                    new TemplateZipDef(
                            "简单查询处理程序模板",
                            REFERENCES_BASE + "ZTEMPLATE10/supplier-delivery-main.zip",
                            "OFFLINE_REPO")
                    // TODO: 以后新增模板在此追加, 例如:
                    // , new TemplateZipDef("多标签查询处理程序模板", "references/ZTEMPLATE11/xxx.zip", "REPO2")
            ));

    private ABAPTemplateService() {
    }

    /**
     * 返回当前可用的模板定义列表(菜单项据此生成)。
     */
    public static List<TemplateZipDef> getTemplateDefs() {
        return TEMPLATE_DEFS;
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
            showError("模板上传",
                    "未找到模板定义：" + label + "\n请在 ABAPTemplateService.getTemplateDefs() 中配置。");
            return;
        }

        // 1. IV_PACKAGE —— 必填
        String ivPackage = promptRequired("模板上传",
                "请输入目标开发包 (IV_PACKAGE)：\n(如 ZDEV_PACKAGE 或 $TMP)");
        if (ivPackage == null) {
            return; // 用户取消
        }

        // 2. IV_REPO_NAME —— 必填(提供默认值)
        String ivRepoName = promptRequiredWithDefault("模板上传",
                "请输入仓库名称 (IV_REPO_NAME)：",
                def.getDefaultRepoName());
        if (ivRepoName == null) {
            return; // 用户取消
        }

        // 3. 后台线程调用 RFC, 避免阻塞 UI
        final String fPackage = ivPackage;
        final String fRepo = ivRepoName;
        showInfo("模板上传",
                "正在上传 “" + def.getLabel() + "” 对应的 zip 到 SAP 系统…\n"
                        + "  开发包   : " + fPackage + "\n"
                        + "  仓库名   : " + fRepo + "\n"
                        + "  RFC函数  : " + AbapGITUploadService.RFC_FUNCTION + "\n\n"
                        + "上传可能需要一些时间，请稍候。");

        new Thread(() -> {
            try {
                AbapGITUploadService.UploadResult result =
                        AbapGITUploadService.uploadResource(
                                def.getZipResourcePath(), fPackage, fRepo);
                String title = result.isSuccess() ? "上传成功" : "上传失败";
                String msg = (result.isSuccess()
                        ? "RFC 调用成功。\n\n"
                        : "RFC 调用完成，但 SAP 返回失败。\n\n")
                        + "  函数    : " + AbapGITUploadService.RFC_FUNCTION + "\n"
                        + "  开发包  : " + fPackage + "\n"
                        + "  仓库名  : " + fRepo + "\n\n"
                        + "SAP 返回消息：\n" + result.getMessage();
                Display.getDefault().asyncExec(
                        () -> MessageDialog.openInformation(getShell(), title, msg));
            } catch (Exception e) {
                String hint = buildErrorHint(e);
                Display.getDefault().asyncExec(
                        () -> MessageDialog.openError(getShell(), "模板上传失败",
                                "调用 " + AbapGITUploadService.RFC_FUNCTION
                                        + " 出错：\n\n" + hint));
            }
        }, "abap-ai-template-upload").start();
    }

    private static TemplateZipDef findTemplateDef(String label) {
        for (TemplateZipDef def : TEMPLATE_DEFS) {
            if (def.getLabel().equals(label)) {
                return def;
            }
        }
        return null;
    }

    /**
     * 将异常转为对用户友好的提示(常见问题: 未配置连接、JCo native 库缺失等)。
     */
    private static String buildErrorHint(Throwable t) {
        StringBuilder sb = new StringBuilder();
        if (t != null && t.getMessage() != null) {
            sb.append(t.getMessage()).append('\n');
        }
        sb.append("\n请检查：\n")
          .append("  1. 是否已在 “ABAP AI Completion → SAP 配置” 填好连接信息；\n")
          .append("  2. JCo 库(sapjco3.jar + native sapjco3.dll)是否正确安装。");
        return sb.toString();
    }

    // ==================== Dialogs ====================

    /**
     * 弹出必填输入框(空值或取消会一直循环直到输入合法值或取消)。
     */
    private static String promptRequired(String title, String message) {
        return promptRequiredWithDefault(title, message, null);
    }

    /**
     * 弹出必填输入框(带默认值); 用户必须输入非空内容, 取消返回 {@code null}。
     */
    private static String promptRequiredWithDefault(final String title,
                                                    final String message,
                                                    final String defaultValue) {
        final String[] resultHolder = { null };
        try {
            Display.getDefault().syncExec(new Runnable() {
                @Override
                public void run() {
                    Shell shell = getShell();
                    while (true) {
                        InputDialog dlg = new InputDialog(shell, title, message,
                                defaultValue == null ? "" : defaultValue, null);
                        if (dlg.open() != Window.OK) {
                            resultHolder[0] = null;
                            return;
                        }
                        String value = dlg.getValue();
                        if (value != null && !value.trim().isEmpty()) {
                            resultHolder[0] = value.trim();
                            return;
                        }
                        MessageDialog.openWarning(shell, title,
                                "输入不能为空，请重新输入。");
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

    private static void showInfo(String title, String message) {
        try {
            Display.getDefault().asyncExec(() -> MessageDialog.openInformation(
                    getShell(), title, message));
        } catch (Exception ignore) {
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
