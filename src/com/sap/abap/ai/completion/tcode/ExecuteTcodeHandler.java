package com.sap.abap.ai.completion.tcode;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * 事务码子节点右键 "执行" 命令处理器：在嵌入式 SAP GUI 中直接运行事务码。
 */
public class ExecuteTcodeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (!(selection instanceof IStructuredSelection)) return null;
        Object first = ((IStructuredSelection) selection).getFirstElement();
        if (!(first instanceof TcodeFavoriteEntry)) return null;

        executeEntry(HandlerUtil.getActiveShell(event), (TcodeFavoriteEntry) first);
        return null;
    }

    /** 执行事务码（右键 "执行" 与双击共用）。须在 UI 线程调用。 */
    public static void executeEntry(Shell shell, TcodeFavoriteEntry entry) {
        IProject project = entry.getProject();
        String tcode = entry.getTcode();
        try {
            if (!TcodeExecutor.isAvailable(project)) {
                MessageDialog.openError(shell,
                        Messages.getString("tcodefav.action.execute"),
                        Messages.format("tcodefav.execute.unsupported",
                                project.getName()));
                return;
            }
            TcodeExecutor.execute(project, tcode);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName();
            MessageDialog.openError(shell,
                    Messages.getString("tcodefav.action.execute"),
                    Messages.format("tcodefav.execute.error", tcode, msg));
        }
    }
}
