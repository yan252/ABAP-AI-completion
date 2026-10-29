package com.sap.abap.ai.completion.recent;

import org.eclipse.core.expressions.PropertyTester;

/**
 * 判断选中节点是否位于 "Recently Modified" 根节点之下（含根节点本身）。
 *
 * <p>用于 plugin.xml：disabled 的 Delete handler 与 navigatorContent 的
 * triggerPoints 都通过本 tester 命中本插件节点，从而把 Delete 菜单置灰。</p>
 *
 * <p>子节点是 ADT 的 AbapRepositoryObjectGenericNode，其父节点即
 * {@link RecentlyModifiedRoot}（JFace TreeNode）。统一用反射调用 public
 * getParent()——TreeNode 与 ADT 节点都有该方法，避免编译期依赖
 * org.eclipse.jface.viewers.ITreeNode（新版 JFace 已移除该接口）。</p>
 */
public class RecentlyModifiedNodeTester extends PropertyTester {

    /** 父链向上最多查找的层数，防御异常环链。 */
    private static final int MAX_DEPTH = 16;

    @Override
    public boolean test(Object receiver, String property, Object[] args, Object expectedValue) {
        return isUnderRecentlyModifiedRoot(receiver);
    }

    /**
     * 沿 getParent() 链判断节点是否位于 "Recently Modified" 根节点之下
     * （含根节点本身）。property tester 与 Delete 菜单守卫共用。
     */
    public static boolean isUnderRecentlyModifiedRoot(Object node) {
        for (int depth = 0; node != null && depth < MAX_DEPTH; depth++) {
            if (node instanceof RecentlyModifiedRoot) {
                return true;
            }
            node = parentOf(node);
        }
        return false;
    }

    private static Object parentOf(Object node) {
        try {
            return node.getClass().getMethod("getParent").invoke(node);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
