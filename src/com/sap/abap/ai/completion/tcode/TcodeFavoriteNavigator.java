package com.sap.abap.ai.completion.tcode;

import org.eclipse.core.resources.IProject;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.navigator.CommonViewer;

import com.sap.abap.ai.completion.recent.NavigatorUtil;

/**
 * "事务码收藏" 节点的 Project Explorer 刷新工具。
 */
public final class TcodeFavoriteNavigator {

    private TcodeFavoriteNavigator() {
    }

    /**
     * 刷新指定项目下的 "事务码收藏" 根节点（数据变更后调用）。
     *
     * <p>必须在 UI 线程调用；根节点按项目名判定相等，可直接新建实例作为
     * 刷新目标，无需缓存节点。</p>
     */
    public static void refreshRoot(final IProject project) {
        if (project == null) return;
        try {
            Display display = Display.getDefault();
            if (display == null || display.isDisposed()) return;
            Runnable refresh = () -> {
                CommonViewer viewer = NavigatorUtil.findProjectExplorerViewer();
                if (viewer != null && viewer.getControl() != null
                        && !viewer.getControl().isDisposed()) {
                    // 刷新项目层：根节点的 hasChildren 状态可能随之变化（从无到有）
                    viewer.refresh(project);
                }
            };
            if (Display.getCurrent() != null) {
                refresh.run();
            } else {
                display.asyncExec(refresh);
            }
        } catch (Throwable ignored) {
        }
    }
}
