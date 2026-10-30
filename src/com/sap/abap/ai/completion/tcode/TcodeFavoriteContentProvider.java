package com.sap.abap.ai.completion.tcode;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.ui.navigator.CommonViewer;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.preferences.AIConfiguration;

/**
 * "事务码收藏" 节点的内容提供器。
 *
 * <p>与 "Recently Modified" 不同：根节点<b>不依赖任何数据</b>，只要项目是可访问的
 * ABAP 项目（含 com.sap.adt.abapnature nature）即返回，保证 SAP 项目打开时
 * 节点就存在。子节点为 {@link TcodeFavoriteEntry}（本插件自有模型，不经过 ADT）。</p>
 */
public class TcodeFavoriteContentProvider implements ITreeContentProvider {

    private static final Object[] EMPTY = new Object[0];

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    /** ADT ABAP 项目 nature（IAdtCoreProject.ABAP_PROJECT_NATURE 的常量值）。 */
    private static final String ABAP_PROJECT_NATURE = "com.sap.adt.abapnature";

    @Override
    public Object[] getElements(Object inputElement) {
        return getChildren(inputElement);
    }

    @Override
    public Object[] getChildren(Object parentElement) {
        if (parentElement instanceof IProject) {
            IProject project = (IProject) parentElement;
            if (!project.isAccessible() || !isAbapProject(project)) return EMPTY;
            logDebug("getChildren(IProject=" + project.getName() + ") -> root node");
            return new Object[] { new TcodeFavoriteRoot(project) };
        }
        if (parentElement instanceof TcodeFavoriteRoot) {
            TcodeFavoriteRoot root = (TcodeFavoriteRoot) parentElement;
            String projectName = root.getProject().getName();
            List<TcodeFavoriteStore.Entry> entries =
                    TcodeFavoriteStore.getInstance().getEntries(projectName);
            List<Object> nodes = new ArrayList<>(entries.size());
            for (TcodeFavoriteStore.Entry e : entries) {
                nodes.add(new TcodeFavoriteEntry(root, projectName,
                        e.getTcode(), e.getText()));
            }
            return nodes.toArray();
        }
        return EMPTY;
    }

    @Override
    public Object getParent(Object element) {
        if (element instanceof TcodeFavoriteRoot) {
            return ((TcodeFavoriteRoot) element).getProject();
        }
        if (element instanceof TcodeFavoriteEntry) {
            TcodeFavoriteEntry entry = (TcodeFavoriteEntry) element;
            IProject project = entry.getProject();
            return project != null && project.exists()
                    ? new TcodeFavoriteRoot(project) : null;
        }
        return null;
    }

    @Override
    public boolean hasChildren(Object element) {
        if (element instanceof IProject) {
            IProject project = (IProject) element;
            // 根节点始终存在，以便用户通过右键 "添加" 添加第一个事务码
            return project.isAccessible() && isAbapProject(project);
        }
        if (element instanceof TcodeFavoriteRoot) {
            String projectName = ((TcodeFavoriteRoot) element).getProject().getName();
            return TcodeFavoriteStore.getInstance().hasEntries(projectName);
        }
        return false;
    }

    /** 是否为 ABAP 项目（ADT 缺席或项目关闭时返回 false）。 */
    private static boolean isAbapProject(IProject project) {
        try {
            return project.hasNature(ABAP_PROJECT_NATURE);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void dispose() {
    }

    @Override
    public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
        // CNF 在把本 content provider 绑定到 Project Explorer 的 CommonViewer 时
        // 必然回调此方法——在这里挂"双击执行事务码"监听是最可靠的挂载点，
        // 不依赖启动时序。幂等（viewer 上有标记）。
        if (viewer instanceof CommonViewer) {
            TcodeFavoriteInitializer.attachDoubleClick((CommonViewer) viewer);
        }
    }

    private static void logDebug(String msg) {
        try {
            if (AIConfiguration.isInterfaceLogDebugEnabled()) {
                LOG.log(new Status(IStatus.INFO, Activator.PLUGIN_ID,
                        "[TcodeFavorites] " + msg));
            }
        } catch (Exception ignored) {
        }
    }
}
