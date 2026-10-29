package com.sap.abap.ai.completion.recent;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.preferences.AIConfiguration;

/**
 * "最近修改" 节点的内容提供器。
 *
 * <p>根节点返回 ADT 能处理的 {@link IFile} 对象数组——ADT 的 semanticfs
 * content provider 会为这些 IFile 提供图标、标签、双击打开和子节点展开
 * （字典结构/类型组/类/事物码等）的原生行为，不需要本插件重新实现。</p>
 */
public class RecentlyModifiedContentProvider implements ITreeContentProvider {

    private static final Object[] EMPTY = new Object[0];

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    private static void logDebug(String msg) {
        try {
            if (AIConfiguration.isInterfaceLogDebugEnabled()) {
                LOG.log(new Status(IStatus.INFO, Activator.PLUGIN_ID,
                        "[RecentlyModified] " + msg));
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public Object[] getElements(Object inputElement) {
        return getChildren(inputElement);
    }

    @Override
    public Object[] getChildren(Object parentElement) {
        if (parentElement instanceof IProject) {
            IProject project = (IProject) parentElement;
            if (!project.isAccessible()) return EMPTY;
            boolean hasEntries = RecentlyModifiedStore.getInstance()
                    .hasEntries(project.getName());
            logDebug("getChildren(IProject=" + project.getName()
                    + ", hasEntries=" + hasEntries + ")");
            return hasEntries ? new Object[] { new RecentlyModifiedRoot(project) } : EMPTY;
        }
        if (parentElement instanceof RecentlyModifiedRoot) {
            RecentlyModifiedRoot root = (RecentlyModifiedRoot) parentElement;
            return getFilesForProject(root);
        }
        // 其他任何父节点（包括 ADT 的虚拟包装对象）一律返回空，避免套娃。
        return EMPTY;
    }

    /**
     * 返回指定项目下最近修改的 ABAP 对象节点数组。
     *
     * <p>优先构造 ADT 原生的 Repository Object 节点（可双击打开、可展开子节点）；
     * ADT bundle 缺失时回退到返回 IFile（只能双击打开、不能展开）。</p>
     */
    private static Object[] getFilesForProject(RecentlyModifiedRoot root) {
        String projectName = root.getProject().getName();
        List<RecentlyModifiedEntry> entries = RecentlyModifiedStore.getInstance()
                .getEntries(projectName);
        if (entries.isEmpty()) return EMPTY;

        // ADT 原生节点构造需要宿主 TreeViewer（否则展开子节点 NPE）
        Object viewer = NavigatorUtil.findProjectExplorerViewer();
        // 兜底安装菜单守卫：正常情况下 inputChanged 已安装，此处防止漏装。
        if (viewer instanceof Viewer) {
            RecentlyModifiedActionProvider.installMenuGuard((Viewer) viewer);
        }

        java.util.ArrayList<Object> nodes = new java.util.ArrayList<>();
        for (RecentlyModifiedEntry entry : entries) {
            // 路径 1：反射构造 ADT 原生节点（优先，支持展开/双击）
            IProject project = ResourcesPlugin.getWorkspace().getRoot()
                    .getProject(projectName);
            Object node = AdtNodeFactory.createObjectNode(
                    viewer, project, root, entry.getName(), entry.getType(),
                    entry.getFilePath(), entry.getUri());
            if (node != null) {
                nodes.add(node);
                continue;
            }
            // 路径 2（兜底）：返回 IFile —— ADT 缺失时至少双击能打开
            if (entry.getFilePath() != null && !entry.getFilePath().isEmpty()) {
                IFile file = ResourcesPlugin.getWorkspace().getRoot()
                        .getFile(new Path(entry.getFilePath()));
                if (file != null && file.exists()) nodes.add(file);
            }
        }
        logDebug("getChildren(root of " + projectName + ") -> " + nodes.size() + " nodes");
        return nodes.toArray();
    }

    @Override
    public Object getParent(Object element) {
        if (element instanceof IFile) {
            IFile file = (IFile) element;
            if (file.getProject() != null) {
                return new RecentlyModifiedRoot(file.getProject());
            }
        }
        if (element instanceof RecentlyModifiedEntry) {
            String projectName = ((RecentlyModifiedEntry) element).getProjectName();
            IProject project = ResourcesPlugin.getWorkspace().getRoot()
                    .getProject(projectName);
            return project != null && project.exists()
                    ? new RecentlyModifiedRoot(project) : null;
        }
        if (element instanceof RecentlyModifiedRoot) {
            return ((RecentlyModifiedRoot) element).getProject();
        }
        return null;
    }

    @Override
    public boolean hasChildren(Object element) {
        return getChildren(element).length > 0;
    }

    @Override
    public void dispose() {
    }

    @Override
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
        // 给宿主 Tree 安装 Delete 菜单守卫（置灰 Recently Modified 下的 Delete）。
        // 走这里而不是依赖 actionProvider.fillContextMenu：后者能否被 CNF 调用
        // 取决于对 ADT 子节点的适用性判断，1.2.4 实测对子节点不触发。
        RecentlyModifiedActionProvider.installMenuGuard(viewer);
    }
}

