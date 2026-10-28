package com.sap.abap.ai.completion.recent;

import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerComparator;

/**
 * "最近修改" 条目的排序器：最近修改在最前。
 *
 * <p>ADT 原生节点（RepositoryObject 节点）通过反射取 technicalName/nodeId，
 * IFile 兜底节点通过 filePath 反查 store 里的 timestamp。</p>
 */
public class RecentlyModifiedSorter extends ViewerComparator {

    @Override
    public int compare(Viewer viewer, Object e1, Object e2) {
        long t1 = findTimestamp(e1);
        long t2 = findTimestamp(e2);
        if (t1 != t2) {
            return Long.compare(t2, t1); // 新的在前
        }
        return 0;
    }

    private static long findTimestamp(Object element) {
        if (element == null) return 0L;
        String key = null;
        String projectName = null;

        if (element instanceof IFile) {
            IFile file = (IFile) element;
            key = file.getFullPath().toString();
            projectName = file.getProject().getName();
        } else {
            // ADT 原生节点：反射取 technicalName（即对象名）
            try {
                java.lang.reflect.Method m = element.getClass()
                        .getMethod("getTechnicalName");
                key = (String) m.invoke(element);
            } catch (Throwable ignored) { }
            if (key == null) {
                try {
                    java.lang.reflect.Method m = element.getClass()
                            .getMethod("getNodeId");
                    key = (String) m.invoke(element);
                } catch (Throwable ignored) { }
            }
            // 从 IProject 取项目名
            try {
                java.lang.reflect.Method m = element.getClass().getMethod("getProject");
                Object proj = m.invoke(element);
                if (proj != null) {
                    try {
                        java.lang.reflect.Method pm = proj.getClass().getMethod("getName");
                        projectName = (String) pm.invoke(proj);
                    } catch (Throwable ignored) { }
                }
            } catch (Throwable ignored) { }
        }

        if (key == null || projectName == null) return 0L;

        List<RecentlyModifiedEntry> entries = RecentlyModifiedStore.getInstance()
                .getEntries(projectName);
        for (RecentlyModifiedEntry entry : entries) {
            String entryKey = entry.getFilePath() != null ? entry.getFilePath() : entry.getName();
            if (entryKey.equals(key)) {
                return entry.getTimestamp();
            }
            // 也试试直接按 name 匹配
            if (entry.getName().equals(key)) {
                return entry.getTimestamp();
            }
        }
        return 0L;
    }
}
