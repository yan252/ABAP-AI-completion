package com.sap.abap.ai.completion.tcode;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.TreeNode;

/**
 * "事务码收藏" 根节点（每个 ABAP 项目下各一个，项目打开时即存在）。
 *
 * <p>与 RecentlyModifiedRoot 相同的约定：{@link #equals(Object)} /
 * {@link #hashCode()} 按项目名判定相等，刷新时可直接新建实例作为
 * {@code CommonViewer#refresh(Object)} 的目标。</p>
 */
public final class TcodeFavoriteRoot extends TreeNode implements IAdaptable {

    private final IProject project;

    public TcodeFavoriteRoot(IProject project) {
        super(project);
        this.project = project;
    }

    public IProject getProject() {
        return project;
    }

    /**
     * 根节点适配：只暴露自身，不暴露底层的 IProject——否则 CNF 会把本节点
     * 当作项目再次展开，造成无限套娃。
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAdapter(Class<T> adapter) {
        if (adapter == TcodeFavoriteRoot.class) {
            return (T) this;
        }
        return null;
    }

    @Override
    public int hashCode() {
        return ("TcodeFavoriteRoot/" + project.getName()).hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof TcodeFavoriteRoot
                && ((TcodeFavoriteRoot) obj).project.getName().equals(project.getName());
    }

    @Override
    public String toString() {
        return "TcodeFavorites[" + project.getName() + "]";
    }
}
