package com.sap.abap.ai.completion.tcode;

import java.util.Locale;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * "事务码收藏" 根节点右键 "添加事务码..." 命令处理器：
 * 输入事务码 → 后台 Job 通过 RFC 校验并读取事务文本 → 入库并刷新节点。
 */
public class AddTcodeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (!(selection instanceof IStructuredSelection)) return null;
        Object first = ((IStructuredSelection) selection).getFirstElement();
        if (!(first instanceof TcodeFavoriteRoot)) return null;

        TcodeFavoriteRoot root = (TcodeFavoriteRoot) first;
        final IProject project = root.getProject();
        final Shell shell = HandlerUtil.getActiveShell(event);

        InputDialog dialog = new InputDialog(shell,
                Messages.getString("tcodefav.dialog.add.title"),
                Messages.getString("tcodefav.dialog.add.prompt"),
                "", new TcodeValidator());
        if (dialog.open() != Window.OK) return null;

        final String tcode = dialog.getValue().trim().toUpperCase(Locale.ROOT);
        if (TcodeFavoriteStore.getInstance().contains(project.getName(), tcode)) {
            MessageDialog.openInformation(shell,
                    Messages.getString("tcodefav.dialog.add.title"),
                    Messages.format("tcodefav.dialog.add.exists", tcode));
            return null;
        }

        Job job = Job.create("Add favorite transaction " + tcode, monitor -> {
            TcodeTextService.FetchResult result =
                    TcodeTextService.fetchText(project, tcode);
            switch (result.status) {
            case OK:
                TcodeFavoriteStore.getInstance()
                        .add(project.getName(), tcode, result.text);
                TcodeFavoriteNavigator.refreshRoot(project);
                break;
            case NOT_FOUND:
                shell.getDisplay().asyncExec(() -> MessageDialog.openError(shell,
                        Messages.getString("tcodefav.dialog.add.title"),
                        Messages.format("tcodefav.dialog.add.notfound",
                                tcode, project.getName())));
                break;
            default: // ERROR：校验失败（连接/权限），告知用户，不入库
                shell.getDisplay().asyncExec(() -> MessageDialog.openError(shell,
                        Messages.getString("tcodefav.dialog.add.title"),
                        Messages.format("tcodefav.dialog.add.error",
                                tcode, result.error)));
                break;
            }
        });
        job.setUser(true);
        job.schedule();
        return null;
    }

    /** 事务码输入校验：非空；仅字母/数字和 / # _ *；最长 20。 */
    private static final class TcodeValidator
            implements org.eclipse.jface.dialogs.IInputValidator {

        private static final String PATTERN = "[A-Za-z0-9/#_*]{1,20}";

        @Override
        public String isValid(String newText) {
            String value = newText == null ? "" : newText.trim();
            if (value.isEmpty()) {
                return Messages.getString("tcodefav.dialog.add.validator.empty");
            }
            if (!value.matches(PATTERN)) {
                return Messages.getString("tcodefav.dialog.add.validator.invalid");
            }
            return null;
        }
    }
}
