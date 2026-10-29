package com.sap.abap.ai.completion.recent;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;

/**
 * 通过反射构造 ADT 原生的 Repository Object 树节点。
 *
 * <p>反射路径：</p>
 * <pre>
 *   1. 如果 uri 为空，尝试从 filePath 构造 URI（无远程调用）
 *   2. RepositoryObjectListItem ← IRepositoryObject 实现（跨类继承链反射设字段）
 *   3. AbapRepositoryObjectGenericNode(IProject, IRepositoryObject)
 *   4. ADT semanticfs provider 自动接管显示/双击/子节点展开
 * </pre>
 */
public final class AdtNodeFactory {

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    private static final String BUNDLE_RIS_SEARCH = "com.sap.adt.ris.search";
    private static final String BUNDLE_PROJECT_EXPLORER_UI = "com.sap.adt.projectexplorer.ui";
    private static final String BUNDLE_TOOLS_CORE = "com.sap.adt.tools.core";

    private static final String CLS_URI_MAPPING_FACTORY =
            "com.sap.adt.tools.core.urimapping.AdtUriMappingServiceFactory";
    private static final String CLS_URI_MAPPING_SERVICE =
            "com.sap.adt.tools.core.urimapping.IAdtUriMappingService";

    private static final String CLS_REPOSITORY_OBJECT_LIST_ITEM =
            "com.sap.adt.ris.search.repositoryservice.RepositoryObjectListItem";
    private static final String CLS_REPOSITORY_OBJECT =
            "com.sap.adt.ris.search.repositoryservice.IRepositoryObject";
    private static final String CLS_ABAP_NODE =
            "com.sap.adt.projectexplorer.ui.internal.node.AbapRepositoryObjectGenericNode";

    /** 文件扩展名 → ADT URI 基础路径映射（小写对象名 + /source/main）。 */
    private static final Map<String, String> EXT_TO_URI_BASE = new HashMap<>();
    static {
        // 注意：程序的工作区路径是 .adt/programs/programs/{name}/{name}.asprog，
        // 对应 URI 必须也是 programs/programs 两级，否则 ADT 找不到处理器
        // （报 "<uri>" is not installed.）。
        EXT_TO_URI_BASE.put(".asprog", "/sap/bc/adt/programs/programs/");
        EXT_TO_URI_BASE.put(".asinc",  "/sap/bc/adt/programs/includes/");
        EXT_TO_URI_BASE.put(".asclas", "/sap/bc/adt/oo/classes/");
        EXT_TO_URI_BASE.put(".asintf", "/sap/bc/adt/oo/interfaces/");
        EXT_TO_URI_BASE.put(".asfunc", "/sap/bc/adt/functions/groups/");
        EXT_TO_URI_BASE.put(".astabl", "/sap/bc/adt/ddic/tables/");
        // 新版 ADT 的数据库表(TABL/DT)工作区路径是 .adt/wbobj2/dictionary/tabldt/{name}/，
        // URI 与之一致（source 关系见 .aptabldt 中的 sourceUri="./{name}/source/main"）。
        EXT_TO_URI_BASE.put(".astabldt", "/sap/bc/adt/wbobj2/dictionary/tabldt/");
        EXT_TO_URI_BASE.put(".asstru", "/sap/bc/adt/ddic/structures/");
        EXT_TO_URI_BASE.put(".asdom",  "/sap/bc/adt/ddic/domains/");
        EXT_TO_URI_BASE.put(".asdtel", "/sap/bc/adt/ddic/datatypes/");
        EXT_TO_URI_BASE.put(".asls",   "/sap/bc/adt/lockobjects/");
        EXT_TO_URI_BASE.put(".asauth", "/sap/bc/adt/objects/");
    }

    private AdtNodeFactory() {
    }

    /**
     * 构造一个 ADT 原生的可展开/可双击的 Repository Object 树节点。
     *
     * <p>优先使用 4 参构造器
     * {@code AbapRepositoryObjectGenericNode(TreeViewer, IProject, TreeNode, IRepositoryObject)}：
     * ADT 展开子节点时会访问节点的 viewer 字段，2 参构造器会留下 null viewer 导致 NPE。</p>
     *
     * @param viewer 宿主 TreeViewer（Project Explorer 的 CommonViewer），可为 null（退回 2 参构造器）
     * @param parent 父 TreeNode（RecentlyModifiedRoot），可为 null
     * @return 可直接放进 CNF 的节点对象，失败返回 null
     */
    public static Object createObjectNode(Object viewer, IProject project, Object parent,
            String name, String type, String filePath, String uri) {
        if (project == null || name == null || name.isEmpty()) return null;

        // 优先让 ADT 自己解析 URI（IAdtUriMappingService.getAdtUri），
        // 它对每种对象类型（程序/类/表/结构/函数组…）都给出正确的规范 URI；
        // 失败时才回退到本地路径推断。
        String adtUri = resolveAdtUri(project, filePath);
        if (adtUri != null && !adtUri.isEmpty()) {
            uri = adtUri;
        }
        if (uri == null || uri.isEmpty()) {
            uri = buildUriFromFilePath(filePath, name);
        }

        try {
            Class<?> listItemClass = loadClass(BUNDLE_RIS_SEARCH, CLS_REPOSITORY_OBJECT_LIST_ITEM);
            Class<?> repoObjClass = loadClass(BUNDLE_RIS_SEARCH, CLS_REPOSITORY_OBJECT);
            if (listItemClass == null || repoObjClass == null) {
                logDebug("RepositoryObjectListItem/IRepositoryObject not available");
                return null;
            }
            Object repoObj = listItemClass.getDeclaredConstructor().newInstance();

            // 反射设字段（跨类继承链查找）
            String lowerName = name.toLowerCase();
            setFieldCrossHierarchy(repoObj, listItemClass, "name", name);
            setFieldCrossHierarchy(repoObj, listItemClass, "technicalName", name);
            setFieldCrossHierarchy(repoObj, listItemClass, "displayName", name);
            setFieldCrossHierarchy(repoObj, listItemClass, "type", type);
            // 注意：不要自造 nodeId！展开子节点时 AbapRepositoryObjectGenericNode
            // 会把 RepositoryObjectListItem.nodeId 作为 parentNodeIds 传给后端
            // readTreeContent；自造 id 后端无法解析 → 子节点为空。
            // 留空时 ADT 回退为 ["000000"]，后端按 parentName+parentType 解析顶层对象。

            // hasChildren() 读的是 expandable 字段：可展开类型设为 true，
            // 展开时 ADT 异步走 RepositoryTreeService.readTreeContent 加载子节点
            // （字典结构/类型组/类/字段等），与 "Favorite Objects" 下的对象树一致。
            try {
                listItemClass.getMethod("setExpandable", boolean.class)
                        .invoke(repoObj, isExpandableType(type));
            } catch (Throwable ignored) { }

            // 反射设 URI —— 关键：AdtObjectReference.getUri() 读的是父类 .uri 字段，
            // 不是 RepositoryObjectListItem.vitUri！先调 setter（继承链里能找到），
            // 再尝试反射设 .uri（父类字段），最后才 fallback 到 .vitUri。
            if (uri != null && !uri.isEmpty()) {
                URI uriObj = URI.create(uri);
                boolean uriSet = false;
                try {
                    listItemClass.getMethod("setUri", URI.class).invoke(repoObj, uriObj);
                    uriSet = true;
                } catch (Throwable ignored) { }
                if (!uriSet) {
                    // 反射设父类 AdtObjectReference.uri
                    setFieldCrossHierarchy(repoObj, listItemClass, "uri", uriObj);
                }
                // RepositoryObjectListItem 的 vitUri 也设一下（某些内部逻辑会用）
                setFieldCrossHierarchy(repoObj, listItemClass, "vitUri", uriObj);
            } else {
                logDebug("WARNING: uri still null after buildUriFromFilePath for " + name);
            }

            Class<?> nodeClass = loadClass(BUNDLE_PROJECT_EXPLORER_UI, CLS_ABAP_NODE);
            if (nodeClass == null) {
                logDebug("AbapRepositoryObjectGenericNode not available");
                return null;
            }
            Object node = null;
            // 优先 4 参构造器（带 TreeViewer + 父 TreeNode），展开子节点不 NPE
            if (viewer instanceof org.eclipse.jface.viewers.TreeViewer
                    && parent instanceof org.eclipse.jface.viewers.TreeNode) {
                try {
                    java.lang.reflect.Constructor<?> ctor4 = nodeClass.getConstructor(
                            org.eclipse.jface.viewers.TreeViewer.class,
                            IProject.class,
                            org.eclipse.jface.viewers.TreeNode.class,
                            repoObjClass);
                    node = ctor4.newInstance(viewer, project, parent, repoObj);
                } catch (NoSuchMethodException e) {
                    logDebug("4-arg constructor not found, fallback to 2-arg");
                }
            }
            if (node == null) {
                java.lang.reflect.Constructor<?> ctor = nodeClass
                        .getConstructor(IProject.class, repoObjClass);
                node = ctor.newInstance(project, repoObj);
            }
            logDebug("created ADT node for " + name + " (" + type + "), uri=" + uri);
            return node;
        } catch (Throwable t) {
            logDebug("createObjectNode failed for " + name + " (" + type + "): " + t.getMessage());
            return null;
        }
    }

    /**
     * 让 ADT 的 URI 映射服务解析对象文件对应的 ADT URI。
     *
     * <p>ADT 按对象类型注册不同的映射（programs/includes、oo/classes、
     * wbobj2/dictionary/tabldt 等），因此比本地硬编码更可靠。</p>
     *
     * @return ADT URI 字符串；不可用返回 null
     */
    static String resolveAdtUri(IProject project, String filePath) {
        if (project == null || filePath == null || filePath.isEmpty()) return null;
        try {
            Class<?> factoryClass = loadClass(BUNDLE_TOOLS_CORE, CLS_URI_MAPPING_FACTORY);
            Class<?> serviceClass = loadClass(BUNDLE_TOOLS_CORE, CLS_URI_MAPPING_SERVICE);
            if (factoryClass == null || serviceClass == null) {
                logDebug("resolveAdtUri: ADT URI mapping service unavailable");
                return null;
            }
            Object service = factoryClass.getMethod("createUriMappingService").invoke(null);
            if (service == null) return null;

            // /PROJECT/.adt/... → 项目内相对路径
            IFile file = project.getFile(new Path(filePath).removeFirstSegments(1));
            Object uri = serviceClass.getMethod("getAdtUri", IResource.class).invoke(service, file);
            if (uri == null) {
                logDebug("resolveAdtUri: null for " + filePath);
                return null;
            }
            return uri.toString();
        } catch (Throwable t) {
            logDebug("resolveAdtUri failed for " + filePath + ": " + t);
            return null;
        }
    }

    /**
     * 从 ADT 工作区文件路径构造对象 URI。
     *
     * <p>路径规律：{project}/.adt/{category}/{type}/{name-dir}/{name}{ext}</p>
     * <p>URI 规律：/sap/bc/adt/{uri-base}/{name-lower}/source/main</p>
     */
    static String buildUriFromFilePath(String filePath, String name) {
        if (filePath == null || filePath.isEmpty()) return null;
        String lower = filePath.toLowerCase();
        // 找最后一个扩展名
        int dotIdx = lower.lastIndexOf('.');
        if (dotIdx < 0) return null;
        String ext = lower.substring(dotIdx);
        String base = EXT_TO_URI_BASE.get(ext);
        if (base == null) return null;

        // 从 filePath 里提取小写对象名（取最后一段目录名）
        String lowerPath = filePath.toLowerCase();
        // 去掉扩展名
        String pathNoExt = lowerPath.substring(0, dotIdx);
        // 取最后一个 / 后的部分
        int slash = pathNoExt.lastIndexOf('/');
        String lowerName = slash >= 0 ? pathNoExt.substring(slash + 1) : pathNoExt;
        if (lowerName.isEmpty()) {
            lowerName = name != null ? name.toLowerCase() : "";
        }

        // 函数模块(FUGR/FF) 的路径是
        // .adt/functions/groups/{group}/fmodules/{name}/{name}.asfunc，
        // URI 需要带上函数组：/sap/bc/adt/functions/groups/{group}/fmodules/{name}/source/main
        int fmIdx = lower.indexOf("/fmodules/");
        int grpIdx = lower.indexOf("/functions/groups/");
        if (fmIdx > 0 && grpIdx > 0 && fmIdx > grpIdx) {
            String group = lower.substring(grpIdx + "/functions/groups/".length(), fmIdx);
            return "/sap/bc/adt/functions/groups/" + group + "/fmodules/" + lowerName + "/source/main";
        }

        return base + lowerName + "/source/main";
    }

    /** 该类型在 ADT 对象树中是否有下级子节点（叶子类型如 include、函数模块返回 false）。 */
    private static boolean isExpandableType(String type) {
        if (type == null) return false;
        return type.startsWith("PROG/P")     // 程序（含 include/字典结构/类型组等）
                || type.startsWith("CLAS/")  // 类（方法/属性等）
                || type.startsWith("INTF/")  // 接口
                || type.startsWith("TABL/")  // 表（字段）
                || type.startsWith("STRU/")  // 结构（字段）
                || type.startsWith("DDLS/")  // CDS 视图
                || type.startsWith("FUGR/F");// 函数组（函数模块）
    }

    /** 跨类继承链反射设字段：子类没有就找父类。 */
    private static void setFieldCrossHierarchy(Object obj, Class<?> cls, String fieldName, Object value) {
        Class<?> current = cls;
        while (current != null && current != Object.class) {
            try {
                java.lang.reflect.Field f = current.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (Throwable t) {
                return;
            }
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

    private static void logDebug(String msg) {
        try {
            LOG.log(new Status(IStatus.INFO, Activator.PLUGIN_ID,
                    "[RecentlyModified] " + msg));
        } catch (Exception ignored) {
        }
    }
}
