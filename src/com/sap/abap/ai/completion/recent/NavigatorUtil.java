package com.sap.abap.ai.completion.recent;

import org.eclipse.core.resources.IProject;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.IViewReference;

/**
 * 刷新 Project Explorer 的工具方法。
 */
public final class NavigatorUtil {

    private NavigatorUtil() {
    }

    /**
     * 刷新指定项目在 Project Explorer 中的显示。
     */
    public static void refreshProjectExplorer(IProject project) {
        if (project == null) return;
        try {
            CommonViewer viewer = findProjectExplorerViewer();
            if (viewer != null) {
                viewer.refresh(project);
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * 找到 Project Explorer 的 {@link CommonViewer}（本身是 TreeViewer）。
     *
     * <p>ADT 原生 Repository Object 节点的构造器需要传入宿主 TreeViewer，
     * 否则展开子节点时 NPE（{@code AbapRepositoryAsyncListLoaderBaseNode.viewer} 为空）。</p>
     */
    public static CommonViewer findProjectExplorerViewer() {
        try {
            for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows()) {
                if (w.getActivePage() == null) continue;
                for (IViewReference ref : w.getActivePage().getViewReferences()) {
                    String id = ref.getId();
                    if (id != null && (id.contains("ProjectExplorer")
                            || id.contains("projectexplorer"))) {
                        Object view = ref.getView(false);
                        if (view instanceof CommonNavigator) {
                            CommonViewer viewer = ((CommonNavigator) view).getCommonViewer();
                            if (viewer != null) return viewer;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
