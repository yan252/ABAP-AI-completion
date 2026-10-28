package com.sap.abap.ai.completion.recent;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IAdaptable;

/**
 * "最近修改" 根节点（每个 ABAP 项目下各一个）。
 *
 * <p>{@link #equals(Object)} / {@link #hashCode()} 按项目名判定相等，
 * 这样刷新时可以新建实例作为 {@code CommonViewer#refresh(Object)} 的目标，
 * 无需缓存节点实例。</p>
 */
public final class RecentlyModifiedRoot implements IAdaptable {

    private final IProject project;

    public RecentlyModifiedRoot(IProject project) {
        this.project = project;
    }

    public IProject getProject() {
        return project;
    }

    /**
     * 根节点适配：只暴露自身，不暴露底层的 IProject。
     *
     * <p>千万别把 IProject 从这里暴露出去——否则 CNF 会认为本节点也是一个项目，
     * 再次调用 content provider，从而无限套娃（"Recently Modified" 下面还是
     * "Recently Modified"）。</p>
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAdapter(Class<T> adapter) {
        if (adapter == RecentlyModifiedRoot.class) {
            return (T) this;
        }
        return null;
    }

    @Override
    public int hashCode() {
        return project.getName().hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof RecentlyModifiedRoot
                && ((RecentlyModifiedRoot) obj).project.getName().equals(project.getName());
    }

    @Override
    public String toString() {
        return "RecentlyModified[" + project.getName() + "]";
    }
}
