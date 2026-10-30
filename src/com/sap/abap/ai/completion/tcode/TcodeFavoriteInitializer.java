package com.sap.abap.ai.completion.tcode;

import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;
import org.eclipse.ui.navigator.INavigatorContentService;

import com.sap.abap.ai.completion.Activator;

/**
 * "事务码收藏" 节点的启动初始化器。
 *
 * <p>与 "Recently Modified" 实测结论一致：仅靠 plugin.xml 声明
 * （activeByDefault + viewerContentBinding）CNF 有时不会调用本插件的
 * navigatorContent，导致节点不出现。这里在启动后显式执行一次
 * {@code activateExtensions} + 按项目 {@code refresh}（与
 * RecentlyModifiedOnSaveListener 相同的已验证做法），确保节点在
 * SAP 项目打开时即显示。</p>
 *
 * <p>注意：activateExtensions 只执行一次——重复调用会触发 CNF 重建整个
 * content pipeline，导致 ADT Project Explorer 暂时清空所有连接。</p>
 */
public final class TcodeFavoriteInitializer {

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    /** plugin.xml 中 navigatorContent 的扩展 ID。 */
    private static final String EXTENSION_ID =
            "com.sap.abap.ai.completion.tcodeFavoritesContent";

    /** ADT ABAP 项目 nature（IAdtCoreProject.ABAP_PROJECT_NATURE 的常量值）。 */
    private static final String ABAP_PROJECT_NATURE = "com.sap.adt.abapnature";

    private static final int MAX_ATTEMPTS = 10;
    private static final int RETRY_DELAY_MS = 2000;

    /** 是否已完成"激活 + 刷新"（整个会话只做一次）。 */
    private static final AtomicBoolean DONE = new AtomicBoolean(false);

    private TcodeFavoriteInitializer() {
    }

    /**
     * 调度启动激活（幂等，可从 start()/earlyStartup 多次调用）。
     * Project Explorer 尚未恢复打开时自动重试若干次。
     */
    public static void scheduleActivation() {
        try {
            Display display = PlatformUI.getWorkbench().getDisplay();
            if (display == null || display.isDisposed()) return;
            registerViewOpenHook();
            scheduleAttempt(display, 0);
        } catch (Throwable ignored) {
        }
    }

    private static void scheduleAttempt(final Display display, final int attempt) {
        if (DONE.get()) return;
        display.timerExec(RETRY_DELAY_MS, () -> {
            if (DONE.get() || display.isDisposed()) return;
            boolean done = tryActivateAndRefresh();
            if (!done && attempt + 1 < MAX_ATTEMPTS) {
                scheduleAttempt(display, attempt + 1);
            }
        });
    }

    /**
     * 找到 Project Explorer，激活本扩展并刷新所有 ABAP 项目。
     *
     * @return true 表示任务完成（视图已找到且已刷新）
     */
    private static boolean tryActivateAndRefresh() {
        CommonNavigator navigator = findProjectExplorer();
        if (navigator == null) return false; // 视图尚未打开，稍后重试
        CommonViewer viewer = navigator.getCommonViewer();
        if (viewer == null || viewer.getControl() == null
                || viewer.getControl().isDisposed()) {
            return false;
        }

        try {
            INavigatorContentService service = navigator.getNavigatorContentService();
            if (service != null && !service.isActive(EXTENSION_ID)) {
                logInfo("extension not active yet, activating now...");
                service.getActivationService().activateExtensions(
                        new String[] { EXTENSION_ID }, true);
                logInfo("activateExtensions done, isActive="
                        + service.isActive(EXTENSION_ID));
            }
        } catch (Throwable t) {
            logInfo("ensureExtensionActive failed: " + t.getMessage());
        }

        // CNF 只在项目节点的子节点被刷新后才会重新调用 content provider；
        // 逐个刷新可访问的 ABAP 项目，使 "事务码收藏" 根节点出现。
        int refreshed = 0;
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isAccessible() && isAbapProject(project)) {
                viewer.refresh(project);
                refreshed++;
            }
        }
        logInfo("startup refresh done, refreshed " + refreshed + " ABAP project(s)");
        attachDoubleClick(navigator);
        DONE.set(true);
        return true;
    }

    /** viewer 上记录"双击监听已挂"的标记键。 */
    private static final String DBLCLICK_HOOKED =
            "com.sap.abap.ai.completion.tcode.dblclickHooked";

    /**
     * 给 viewer 挂双击监听：双击事务码子节点直接执行事务（与右键 "执行" 等价）。
     * 幂等——同一 viewer 只挂一次。由两处调用：
     * <ul>
     *   <li>{@link TcodeFavoriteContentProvider#inputChanged}（CNF 绑定 viewer 时，
     *       最可靠的挂载点）；</li>
     *   <li>本类启动激活 / partOpened 兜底。</li>
     * </ul>
     */
    public static void attachDoubleClick(final CommonViewer viewer) {
        if (viewer == null || viewer.getControl() == null
                || viewer.getControl().isDisposed()) {
            return;
        }
        if (Boolean.TRUE.equals(viewer.getData(DBLCLICK_HOOKED))) {
            return;
        }
        viewer.setData(DBLCLICK_HOOKED, Boolean.TRUE);
        viewer.addDoubleClickListener(event -> {
            Object first = event.getSelection() instanceof IStructuredSelection
                    ? ((IStructuredSelection) event.getSelection()).getFirstElement()
                    : null;
            if (first instanceof TcodeFavoriteEntry) {
                ExecuteTcodeHandler.executeEntry(viewer.getControl().getShell(),
                        (TcodeFavoriteEntry) first);
            }
        });
        logInfo("double-click listener attached");
    }

    private static void attachDoubleClick(final CommonNavigator navigator) {
        if (navigator != null) {
            attachDoubleClick(navigator.getCommonViewer());
        }
    }

    /**
     * 注册页面级 part 监听：Project Explorer 若在启动重试结束后才被打开，
     * 打开时补上双击监听（幂等）。
     */
    private static void registerViewOpenHook() {
        try {
            for (IWorkbenchWindow window
                    : PlatformUI.getWorkbench().getWorkbenchWindows()) {
                for (final IWorkbenchPage page : window.getPages()) {
                    synchronized (HOOKED_PAGES) {
                        if (!HOOKED_PAGES.add(page)) continue;
                    }
                    page.addPartListener(new org.eclipse.ui.IPartListener2() {
                        @Override
                        public void partOpened(org.eclipse.ui.IWorkbenchPartReference ref) {
                            String id = ref.getId();
                            if (id != null && (id.contains("ProjectExplorer")
                                    || id.contains("projectexplorer"))) {
                                Object part = ref.getPart(false);
                                if (part instanceof CommonNavigator) {
                                    attachDoubleClick((CommonNavigator) part);
                                }
                            }
                        }
                    });
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 已注册 part 监听的页面（防重复注册）。 */
    private static final java.util.Set<IWorkbenchPage> HOOKED_PAGES =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** 找到 ADT/Eclipse Project Explorer 视图（未打开时返回 null）。 */
    private static CommonNavigator findProjectExplorer() {
        try {
            for (IWorkbenchWindow w : PlatformUI.getWorkbench().getWorkbenchWindows()) {
                if (w.getActivePage() == null) continue;
                for (IViewReference ref : w.getActivePage().getViewReferences()) {
                    String id = ref.getId();
                    if (id != null && (id.contains("ProjectExplorer")
                            || id.contains("projectexplorer"))) {
                        Object view = ref.getView(false);
                        if (view instanceof CommonNavigator) {
                            return (CommonNavigator) view;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isAbapProject(IProject project) {
        try {
            return project.hasNature(ABAP_PROJECT_NATURE);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void logInfo(String msg) {
        try {
            LOG.log(new Status(IStatus.INFO, Activator.PLUGIN_ID,
                    "[TcodeFavorites] " + msg));
        } catch (Exception ignored) {
        }
    }
}
