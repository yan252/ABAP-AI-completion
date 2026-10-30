package com.sap.abap.ai.completion.tcode;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.TreeNode;

/**
 * "事务码收藏" 节点下的事务码子节点。
 *
 * <p>显示事务码及其事务文本（如 "SE38 - ABAP Editor"）。父节点为
 * {@link TcodeFavoriteRoot}；equals/hashCode 按 项目名 + 事务码 判定，
 * 保证 viewer 刷新时能正确匹配已有节点。</p>
 */
public final class TcodeFavoriteEntry extends TreeNode implements IAdaptable {

    private final String projectName;
    private final String tcode;
    /** 事务文本（可能为空字符串）。 */
    private final String text;

    public TcodeFavoriteEntry(TcodeFavoriteRoot parent, String projectName,
            String tcode, String text) {
        super(tcode);
        setParent(parent);
        this.projectName = projectName;
        this.tcode = tcode;
        this.text = text == null ? "" : text;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getTcode() {
        return tcode;
    }

    public String getText() {
        return text;
    }

    public IProject getProject() {
        return ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getAdapter(Class<T> adapter) {
        if (adapter == TcodeFavoriteEntry.class) {
            return (T) this;
        }
        return null;
    }

    @Override
    public int hashCode() {
        return (projectName + "/" + tcode.toUpperCase()).hashCode();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof TcodeFavoriteEntry)) {
            return false;
        }
        TcodeFavoriteEntry other = (TcodeFavoriteEntry) obj;
        return other.projectName.equals(projectName)
                && other.tcode.equalsIgnoreCase(tcode);
    }

    @Override
    public String toString() {
        return tcode;
    }
}
