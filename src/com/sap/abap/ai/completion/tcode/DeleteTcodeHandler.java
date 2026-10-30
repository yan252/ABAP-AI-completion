package com.sap.abap.ai.completion.tcode;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.resources.IProject;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.dialogs.ListSelectionDialog;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.model.BaseWorkbenchContentProvider;

/**
 * "删除" 命令处理器：
 * <ul>
 *   <li>选中根节点 → 弹出事务码多选列表，删除选中的条目；</li>
 *   <li>选中事务码子节点（可多单）→ 直接删除选中的事务码。</li>
 * </ul>
 */
public class DeleteTcodeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        ISelection selection = HandlerUtil.getCurrentSelection(event);
        if (!(selection instanceof IStructuredSelection) || selection.isEmpty()) {
            return null;
        }
        List<?> elements = ((IStructuredSelection) selection).toList();
        Shell shell = HandlerUtil.getActiveShell(event);

        // 根节点：列表选择删除
        if (elements.size() == 1 && elements.get(0) instanceof TcodeFavoriteRoot) {
            deleteFromRoot((TcodeFavoriteRoot) elements.get(0), shell);
            return null;
        }

        // 事务码子节点（全部选中项都是条目时才处理）
        List<TcodeFavoriteEntry> entries = new ArrayList<>();
        for (Object element : elements) {
            if (element instanceof TcodeFavoriteEntry) {
                entries.add((TcodeFavoriteEntry) element);
            }
        }
        if (entries.isEmpty() || entries.size() != elements.size()) return null;

        TcodeFavoriteStore store = TcodeFavoriteStore.getInstance();
        for (TcodeFavoriteEntry entry : entries) {
            store.remove(entry.getProjectName(), entry.getTcode());
        }
        TcodeFavoriteNavigator.refreshRoot(entries.get(0).getProject());
        return null;
    }

    /** 根节点删除：多选列表对话框。 */
    private static void deleteFromRoot(TcodeFavoriteRoot root, Shell shell) {
        IProject project = root.getProject();
        List<TcodeFavoriteStore.Entry> entries =
                TcodeFavoriteStore.getInstance().getEntries(project.getName());
        if (entries.isEmpty()) {
            MessageDialog.openInformation(shell,
                    Messages.getString("tcodefav.dialog.delete.title"),
                    Messages.format("tcodefav.dialog.delete.empty",
                            project.getName()));
            return;
        }
        LabelProvider labelProvider = new LabelProvider() {
            @Override
            public String getText(Object element) {
                TcodeFavoriteStore.Entry e = (TcodeFavoriteStore.Entry) element;
                return e.getText().isEmpty()
                        ? e.getTcode()
                        : e.getTcode() + " - " + e.getText();
            }
        };
        ListSelectionDialog dialog = new ListSelectionDialog(shell,
                entries.toArray(), new BaseWorkbenchContentProvider(),
                labelProvider,
                Messages.getString("tcodefav.dialog.delete.prompt"));
        dialog.setTitle(Messages.getString("tcodefav.dialog.delete.title"));
        if (dialog.open() != Window.OK) return;

        for (Object selected : dialog.getResult()) {
            TcodeFavoriteStore.getInstance().remove(project.getName(),
                    ((TcodeFavoriteStore.Entry) selected).getTcode());
        }
        TcodeFavoriteNavigator.refreshRoot(project);
    }
}
