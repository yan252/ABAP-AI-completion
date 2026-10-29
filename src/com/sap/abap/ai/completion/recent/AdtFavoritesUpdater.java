package com.sap.abap.ai.completion.recent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;

/**
 * 保存 ABAP 对象时，同时把对象加入 ADT 标准 "Favorite Objects" 节点
 * （先 REMOVE 再 ADD，幂等）。
 *
 * <p>全程反射调用 ADT 内部 API，无编译期依赖：</p>
 * <pre>
 *   com.sap.adt.ris.search.favorites.AdtRisVfsFavoritesServiceFactory
 *     .createService(IProject) -&gt; IAdtRisVfsFavoritesService
 *   service.constructForUpdate("$", name, type, Operation.REMOVE/ADD) -&gt; IFavorite
 *   service.updateFavorites("$", favorites, monitor)
 * </pre>
 *
 * <p>"$" 是 {@code IAdtRisVfsFavoritesService.MY_FAVORITES} 常量（"Favorite Objects"
 * 默认列表）。updateFavorites 是网络调用，必须放在后台 Job 中执行。</p>
 */
public final class AdtFavoritesUpdater {

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    private static final String BUNDLE_RIS_SEARCH = "com.sap.adt.ris.search";
    private static final String BUNDLE_RIS_MODEL = "com.sap.adt.ris.model";

    private static final String CLS_FACTORY =
            "com.sap.adt.ris.search.favorites.AdtRisVfsFavoritesServiceFactory";
    private static final String CLS_SERVICE =
            "com.sap.adt.ris.search.favorites.IAdtRisVfsFavoritesService";
    private static final String CLS_OPERATION =
            "com.sap.adt.ris.model.favorites.Operation";

    /** "Favorite Objects" 默认列表 ID（IAdtRisVfsFavoritesService.MY_FAVORITES）。 */
    private static final String MY_FAVORITES = "$";

    /**
     * ADT 自身 AddToFavoritesHandler 忽略的类型（后端收藏不支持）。
     * 与 Recently Modified 的过滤相互独立。
     */
    private static final Set<String> IGNORED_TYPES = new HashSet<>(Arrays.asList(
            "TABL/DTT", "DEVC/K", "STOB/DO", "FUGR/FF", "ENHS/XB"));

    private AdtFavoritesUpdater() {
    }

    /**
     * 后台把对象加入 "Favorite Objects"（先移除再添加）。
     * 立即返回；实际更新在 Job 中执行，成功后刷新 Project Explorer。
     */
    public static void addToFavoritesAsync(final IProject project, final String name,
            final String type) {
        if (project == null || name == null || name.isEmpty()
                || type == null || type.isEmpty()) {
            return;
        }
        if (IGNORED_TYPES.contains(type)) {
            logInfo("favorites: type " + type + " ignored for " + name
                    + " (not supported by backend)");
            return;
        }

        Job job = Job.create("Add to Favorite Objects: " + name, monitor -> {
            updateFavorites(project, name, type, monitor);
        });
        job.setSystem(true);
        job.schedule();
    }

    private static void updateFavorites(IProject project, String name, String type,
            IProgressMonitor monitor) {
        try {
            Class<?> factoryClass = loadClass(BUNDLE_RIS_SEARCH, CLS_FACTORY);
            Class<?> serviceClass = loadClass(BUNDLE_RIS_SEARCH, CLS_SERVICE);
            Class<?> operationClass = loadClass(BUNDLE_RIS_MODEL, CLS_OPERATION);
            if (factoryClass == null || serviceClass == null || operationClass == null) {
                logInfo("favorites: ADT favorites API not available, skipped " + name);
                return;
            }

            Object service = factoryClass
                    .getMethod("createService", IProject.class)
                    .invoke(null, project);
            if (service == null) {
                logInfo("favorites: no service for project " + project.getName());
                return;
            }

            Boolean supported = (Boolean) serviceClass
                    .getMethod("isSupported")
                    .invoke(service);
            if (!Boolean.TRUE.equals(supported)) {
                logInfo("favorites: not supported by backend " + project.getName()
                        + ", skipped " + name);
                return;
            }

            Object opRemove = Enum.valueOf((Class<Enum>) operationClass, "REMOVE");
            Object opAdd = Enum.valueOf((Class<Enum>) operationClass, "ADD");

            java.lang.reflect.Method construct = serviceClass.getMethod(
                    "constructForUpdate", String.class, String.class, String.class,
                    operationClass);
            java.lang.reflect.Method update = serviceClass.getMethod(
                    "updateFavorites", String.class, List.class, IProgressMonitor.class);
            java.lang.reflect.Method containsIn = serviceClass.getMethod(
                    "containsIn", String.class, String.class, String.class,
                    IProgressMonitor.class);

            // 与 ADT 原生处理器一致：REMOVE 和 ADD 分开两次调用（合并到一次
            // updateFavorites 调用时后端会按对象去重，可能只留下 REMOVE）。
            boolean alreadyContained = Boolean.TRUE.equals(
                    containsIn.invoke(service, MY_FAVORITES, name, type, monitor));
            logInfo("favorites: containsIn(" + name + ") = " + alreadyContained);

            if (alreadyContained) {
                List<Object> removeList = new ArrayList<>(1);
                removeList.add(construct.invoke(service, MY_FAVORITES, name, type, opRemove));
                update.invoke(service, MY_FAVORITES, removeList, monitor);
                logInfo("favorites: removed " + name + " (" + type + ")");
            }

            List<Object> addList = new ArrayList<>(1);
            addList.add(construct.invoke(service, MY_FAVORITES, name, type, opAdd));
            update.invoke(service, MY_FAVORITES, addList, monitor);
            logInfo("favorites: added " + name + " (" + type + ") in "
                    + project.getName());

            // 校验：ADD 之后应存在
            boolean nowContained = Boolean.TRUE.equals(
                    containsIn.invoke(service, MY_FAVORITES, name, type, monitor));
            logInfo("favorites: containsIn after add = " + nowContained);

            refreshFavoritesNodes(project);
        } catch (Throwable t) {
            logInfo("favorites: update failed for " + name + " (" + type + "): "
                    + t.getMessage());
        }
    }

    private static final String BUNDLE_PROJECT_EXPLORER_UI = "com.sap.adt.projectexplorer.ui";
    private static final String CLS_TREE_FACTORY =
            "com.sap.adt.projectexplorer.ui.internal.virtualfolders.VirtualTreeFactory";
    private static final String CLS_UI_PLUGIN =
            "com.sap.adt.projectexplorer.ui.AdtProjectExplorerUiPlugin";
    private static final String CLS_UI_PLUGIN_IFACE =
            "com.sap.adt.projectexplorer.ui.IAdtProjectExplorerUiPlugin";

    /**
     * 刷新 "Favorite Objects" 虚拟文件夹节点。
     *
     * <p>复制 ADT {@code UpdateJob} 的做法（updateFavorites 之后的 UIJob）：</p>
     * <pre>
     *   VirtualTreeFactory.getFactoryForProject(project)
     *   filter = factory.buildFilterStringForFavoriteObjectsOfCurrentUser()
     *   for each VirtualTreeNode in factory.getTrees() where filterString contains filter:
     *       node.getFolder().disposeSubfoldersAndObjects(false)   // 清子节点缓存
     *       IAdtProjectExplorerUiPlugin.refreshExplorer(project, node, false)
     * </pre>
     * <p>只刷新 IProject 不会失效虚拟文件夹的子节点缓存（这就是需要手动 F5 的原因）。</p>
     */
    private static void refreshFavoritesNodes(final IProject project) {
        try {
            org.eclipse.swt.widgets.Display display =
                    org.eclipse.ui.PlatformUI.getWorkbench().getDisplay();
            if (display == null || display.isDisposed()) return;
            display.asyncExec(() -> doRefreshFavoritesNodes(project));
        } catch (Throwable t) {
            logInfo("favorites: refresh scheduling failed: " + t.getMessage());
        }
    }

    private static void doRefreshFavoritesNodes(IProject project) {
        try {
            Class<?> factoryClass = loadClass(BUNDLE_PROJECT_EXPLORER_UI, CLS_TREE_FACTORY);
            Class<?> pluginClass = loadClass(BUNDLE_PROJECT_EXPLORER_UI, CLS_UI_PLUGIN);
            Class<?> pluginIface = loadClass(BUNDLE_PROJECT_EXPLORER_UI, CLS_UI_PLUGIN_IFACE);
            if (factoryClass == null || pluginClass == null || pluginIface == null) {
                logInfo("favorites: tree factory not available, fallback to project refresh");
                NavigatorUtil.refreshProjectExplorer(project);
                return;
            }

            Object factory = factoryClass
                    .getMethod("getFactoryForProject", IProject.class)
                    .invoke(null, project);
            if (factory == null) {
                NavigatorUtil.refreshProjectExplorer(project);
                return;
            }

            String filter = (String) factoryClass
                    .getMethod("buildFilterStringForFavoriteObjectsOfCurrentUser")
                    .invoke(factory);
            List<?> trees = (List<?>) factoryClass.getMethod("getTrees").invoke(factory);

            List<Object> favoriteNodes = new ArrayList<>();
            if (trees != null) {
                for (Object node : trees) {
                    if (node == null) continue;
                    String nodeFilter = (String) node.getClass()
                            .getMethod("getFilterString").invoke(node);
                    if (filter != null && nodeFilter != null
                            && nodeFilter.contains(filter)) {
                        favoriteNodes.add(node);
                    }
                }
            }

            if (favoriteNodes.isEmpty()) {
                // "Favorite Objects" 文件夹尚未建立——不自动创建，退化为整项目刷新
                logInfo("favorites: no favorites folder node found, refreshed project instead");
                NavigatorUtil.refreshProjectExplorer(project);
                return;
            }

            Object plugin = pluginClass.getMethod("getDefault").invoke(null);
            java.lang.reflect.Method refreshExplorer = pluginIface.getMethod(
                    "refreshExplorer", IProject.class,
                    org.eclipse.jface.viewers.TreeNode.class, boolean.class);

            for (Object node : favoriteNodes) {
                Object folder = node.getClass().getMethod("getFolder").invoke(node);
                if (folder != null) {
                    folder.getClass()
                            .getMethod("disposeSubfoldersAndObjects", boolean.class)
                            .invoke(folder, false);
                }
                refreshExplorer.invoke(plugin, project, node, false);
            }
            logInfo("favorites: refreshed " + favoriteNodes.size()
                    + " favorites node(s) in " + project.getName());
        } catch (Throwable t) {
            logInfo("favorites: refresh failed for " + project.getName() + ": "
                    + t.getMessage());
            NavigatorUtil.refreshProjectExplorer(project);
        }
    }

    private static Class<?> loadClass(String bundleId, String className) {
        try {
            Bundle bundle = Platform.getBundle(bundleId);
            return bundle == null ? null : bundle.loadClass(className);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void logInfo(String msg) {
        try {
            LOG.log(new Status(IStatus.INFO, Activator.PLUGIN_ID,
                    "[RecentlyModified] " + msg));
        } catch (Exception ignored) {
        }
    }
}
