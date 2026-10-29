package com.sap.abap.ai.completion.recent;

import java.util.Locale;

import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.ui.navigator.CommonActionProvider;

/**
 * "Recently Modified" 节点右键菜单的 Delete 守卫。
 *
 * <p>核心入口是 {@link #installMenuGuard(Viewer)}：直接监听宿主 Tree 的
 * SWT.MenuDetect，仅当选择的全部节点都位于 Recently Modified 根节点之下时
 * 置灰 Delete；选择离开后置灰会被撤销（通过菜单项标记识别本插件置灰的项），
 * 不影响 "Favorite Objects" 等普通节点的 Delete。</p>
 */
public class RecentlyModifiedActionProvider extends CommonActionProvider {

    /**
     * 给 Project Explorer 的宿主 Tree 安装 Delete 菜单守卫。
     *
     * <p>ADT 3.62 的 Delete 是 DeleteActionProvider 贡献的 DeleteResourceAction
     * （Action 式菜单项，priority=highest），不走 org.eclipse.ui.edit.delete 命令，
     * 因此基于命令的 disabled handler 够不着它；而本 provider 的 fillContextMenu
     * 对 ADT 子节点又不会被 CNF 调用（1.2.4 实测）。可靠的做法是直接监听宿主
     * Tree 的 SWT.MenuDetect：每次右键都会触发，与 CNF 的 provider 适用性无关。</p>
     *
     * <p>仅当当前选择的全部节点都位于 Recently Modified 根节点之下时才置灰
     * Delete，普通项目树的 Delete 不受影响。Tree 销毁后允许重新安装。</p>
     */
    public static synchronized void installMenuGuard(Viewer viewer) {
        if (viewer == null) {
            return;
        }
        Control control = viewer.getControl();
        if (!(control instanceof Tree) || control.isDisposed()) {
            return;
        }
        final Tree tree = (Tree) control;
        if (tree == guardedTree) {
            return;
        }
        guardedTree = tree;
        tree.addDisposeListener(e -> {
            synchronized (RecentlyModifiedActionProvider.class) {
                if (guardedTree == tree) {
                    guardedTree = null;
                }
            }
        });
        tree.addListener(SWT.MenuDetect, event -> {
            Display display = tree.getDisplay();
            if (display == null) {
                return;
            }
            // MenuDetect 触发时菜单项尚未填充；排到事件队列末尾执行，此时
            // aboutToShow 已完成，所有贡献（含 ADT 的 Delete action）都已创建。
            display.asyncExec(() -> {
                if (tree.isDisposed()) {
                    return;
                }
                Menu menu = tree.getMenu();
                if (menu == null || menu.isDisposed()) {
                    return;
                }
                updateDeleteItems(menu, isSelectionUnderRecentlyModified(viewer));
            });
        });
    }

    /** 已安装守卫的 Tree；视图重建（dispose 后新建）时需要重新安装。 */
    private static Tree guardedTree;

    /** 当前选择非空且全部位于 Recently Modified 根节点之下。 */
    private static boolean isSelectionUnderRecentlyModified(Viewer viewer) {
        final ISelection selection;
        try {
            selection = viewer.getSelection();
        } catch (Throwable t) {
            return false;
        }
        if (!(selection instanceof IStructuredSelection) || selection.isEmpty()) {
            return false;
        }
        for (Object element : ((IStructuredSelection) selection).toArray()) {
            if (!RecentlyModifiedNodeTester.isUnderRecentlyModifiedRoot(element)) {
                return false;
            }
        }
        return true;
    }

    /** 标记由本插件置灰的菜单项；选择离开 Recently Modified 时只恢复这些项。 */
    private static final String TAG_DISABLED_BY_US =
            "com.sap.abap.ai.completion.deleteDisabledByUs";

    /**
     * disable=true：置灰所有 Delete 项并打标记。
     * disable=false：只把带标记的项恢复可用——SWT 菜单项可能被跨次复用，
     * 不恢复的话 "Favorite Objects" 等普通节点的 Delete 会一直灰着；
     * 不带标记的项（本身就该灰的）保持原样。
     */
    private static void updateDeleteItems(Menu menu, boolean disable) {
        for (MenuItem item : menu.getItems()) {
            if (isDeleteItem(item)) {
                if (disable) {
                    item.setData(TAG_DISABLED_BY_US, Boolean.TRUE);
                    item.setEnabled(false);
                } else if (Boolean.TRUE.equals(item.getData(TAG_DISABLED_BY_US))) {
                    item.setData(TAG_DISABLED_BY_US, null);
                    item.setEnabled(true);
                }
            }
            Menu sub = item.getMenu();
            if (sub != null) {
                updateDeleteItems(sub, disable);
            }
        }
    }

    private static boolean isDeleteItem(MenuItem item) {
        String text = item.getText();
        if (text == null) {
            return false;
        }
        String t = text.replace("&", "").trim().toLowerCase(Locale.ROOT);
        return t.startsWith("delete") || t.startsWith("删除");
    }
}
