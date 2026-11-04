package com.sap.abap.ai.completion.recent;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
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
    // 收藏夹(VirtualFolder)用的是 AbapRepositoryObjectGenericLaunchableNode
    // （实现 ILaunchableNode），不是 AbapRepositoryObjectGenericNode。
    // 实测用基类节点时 PROG/I 展开返回空树，换成 Launchable 子类后与收藏夹行为一致。
    private static final String CLS_ABAP_NODE =
            "com.sap.adt.projectexplorer.ui.internal.node.AbapRepositoryObjectGenericLaunchableNode";
    private static final String CLS_ABAP_NODE_BASE =
            "com.sap.adt.projectexplorer.ui.internal.node.AbapRepositoryObjectGenericNode";

    // ADT 虚拟文件夹搜索服务——与收藏夹(VirtualFolder)完全相同的对象发现机制。
    // 收藏夹通过搜索结果 IObject 构造 RepositoryObjectListItem，type/uri/version
    // 等字段均由后端搜索返回，保证 readTreeContent 能正确解析子节点。
    private static final String CLS_VF_SERVICE_FACTORY =
            "com.sap.adt.ris.search.virtualfolders.AdtRisVirtualFoldersServiceFactory";
    private static final String CLS_VF_SEARCH_SERVICE =
            "com.sap.adt.ris.search.virtualfolders.IAdtRisVirtualFoldersSearchService";
    private static final String CLS_VF_REQUEST_PARAMS =
            "com.sap.adt.ris.search.virtualfolders.IAdtRisVirtualFoldersRequestParameters";
    private static final String CLS_VF_RESULT =
            "com.sap.adt.ris.model.virtualfolders.IVirtualFoldersResult";
    private static final String CLS_VF_OBJECT =
            "com.sap.adt.ris.model.virtualfolders.IObject";
    private static final String ATOM_REL_OBJECTS = "http://www.sap.com/adt/relations/objects";
    private static final String ATOM_TYPE_SAPGUI = "application/vnd.sap.sapgui";

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

        // === 硬诊断（IStatus.WARNING 不会被 Eclipse 日志过滤）===
        // 确保我们能确认 V19 是否真的在跑。
        try {
            LOG.log(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                    "[RecentlyModified] V19 createObjectNode START name=" + name
                            + " type=" + type + " project="
                            + (project != null ? project.getName() : "null")));
        } catch (Throwable ignored) { }

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
        // 关键：收藏夹(VirtualFolder)用的是对象自链接 URI（不含 /source/main），
        // 如 /sap/bc/adt/programs/includes/zewme00120_dyn。
        // 我们之前传的是 source URI（.../source/main），虽然后端 readTreeContent
        // 请求参数不直接用 URI，但对象自链接 URI 才是 ADT 内部各环节（标签、
        // 导航、类型解析）公认的对象标识。统一为自链接 URI 与收藏夹行为对齐。
        if (uri != null && uri.endsWith("/source/main")) {
            uri = uri.substring(0, uri.length() - "/source/main".length());
        }

        try {
            Class<?> listItemClass = loadClass(BUNDLE_RIS_SEARCH, CLS_REPOSITORY_OBJECT_LIST_ITEM);
            Class<?> repoObjClass = loadClass(BUNDLE_RIS_SEARCH, CLS_REPOSITORY_OBJECT);
            if (listItemClass == null || repoObjClass == null) {
                logDebug("RepositoryObjectListItem/IRepositoryObject not available");
                return null;
            }
            final URI uriObj = (uri != null && !uri.isEmpty()) ? URI.create(uri) : null;

            // 优先通过 ADT 虚拟文件夹搜索服务获取对象——与收藏夹(VirtualFolder)
            // 完全相同的对象发现机制。收藏夹用搜索结果 IObject 的 atom link
            // （self-link URI + SAP GUI vitUri）和 type/version 字段构造
            // RepositoryObjectListItem，保证后端 readTreeContent 能正确解析子节点。
            // 搜索失败或服务不可用时回退到本地手动构造。
            Object repoObj = createRepoObjFromSearch(project, listItemClass, name, type);
            boolean fromSearch = repoObj != null;
            if (!fromSearch) {
                // 回退：本地手动构造（与收藏夹 7 参构造器等价）
                repoObj = createRepoObjManually(listItemClass, uriObj, type, name);
            }
            if (repoObj == null) {
                logDebug("Failed to create RepositoryObjectListItem for " + name);
                return null;
            }

            // === 硬诊断：repoObj 构造完成后反射读所有关键字段 ===
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("V19 repoObj DUMP src=").append(fromSearch ? "search" : "manual");
                sb.append(" name=").append(name).append(" inputType=").append(type);
                // 反射读 AOR 字段（type/name/uri 等）
                sb.append(" AOR.name=").append(readFieldStr(repoObj, "name"));
                sb.append(" AOR.type=").append(readFieldStr(repoObj, "type"));
                sb.append(" AOR.uri=").append(readFieldStr(repoObj, "uri"));
                // ROLI 字段
                sb.append(" ROLI.technicalName=").append(readFieldStr(repoObj, "technicalName"));
                sb.append(" ROLI.vitUri=").append(readFieldStr(repoObj, "vitUri"));
                sb.append(" ROLI.expandable=").append(readFieldStr(repoObj, "expandable"));
                sb.append(" ROLI.nodeId=").append(readFieldStr(repoObj, "nodeId"));
                sb.append(" ROLI.parentName=").append(readFieldStr(repoObj, "parentName"));
                sb.append(" ROLI.userName=").append(readFieldStr(repoObj, "userName"));
                sb.append(" ROLI.version=").append(readFieldStr(repoObj, "version"));
                sb.append(" ROLI.description=").append(readFieldStr(repoObj, "description"));
                LOG.log(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                        "[RecentlyModified] " + sb));
            } catch (Throwable ignored) { }

            // 注意：不设 parentName，与收藏夹(VirtualFolderContentProvider.createObjects)
            // 完全一致——收藏夹的 7 参构造器不设 parentName（留 null）。
            // AbapRepositoryObjectGenericNode.getParentName() 会回退到 getDisplayName()=getName()，
            // 效果与显式设 parentName=name 相同，但保持字段为 null 可避免后端
            // accept 解析时与 parentName 非空产生的差异。

            // 对象描述：与 "Favorite Objects" 一致，在名称后显示 ADT 对象描述。
            // ADT 标签提供器的 getDescription(Object) 直接读
            // IRepositoryObject.getDescription()（继承自 AdtObjectReference.description），
            // 描述保存在工作区同目录的属性文件里（zewme00120.asprog ↔ zewme00120.approg，
            // 内容是 XML，含 adtcore:description 属性），因此可离线读取，无需后端调用。
            String description = readDescriptionFromMetadata(filePath);
            if (description != null && !description.isEmpty()) {
                try {
                    listItemClass.getMethod("setDescription", String.class)
                            .invoke(repoObj, description);
                } catch (Throwable ignored) {
                    setFieldCrossHierarchy(repoObj, listItemClass, "description", description);
                }
            }
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
            // 不是 RepositoryObjectListItem.vitUri！6 参构造器已设好两者；
            // 回退路径（无参构造器）需要在这里补上。
            // 搜索路径已由 atom link 设好 uri/vitUri（vitUri 是 SAP GUI 链接，
            // 收藏夹同样保留它），不能再用本地 URI 覆盖，否则丢失 vitUri。
            if (!fromSearch) {
                if (uriObj != null) {
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
            }

            // 收藏夹用 AbapRepositoryObjectGenericLaunchableNode（实现 ILaunchableNode），
            // 不是基类 AbapRepositoryObjectGenericNode。优先用 Launchable 子类，
            // 不可用时回退到基类（基类有 2 参构造器，Launchable 只有 4 参）。
            Class<?> nodeClass = loadClass(BUNDLE_PROJECT_EXPLORER_UI, CLS_ABAP_NODE);
            if (nodeClass == null) {
                nodeClass = loadClass(BUNDLE_PROJECT_EXPLORER_UI, CLS_ABAP_NODE_BASE);
            }
            if (nodeClass == null) {
                logDebug("AbapRepositoryObjectGeneric(Launchable)Node not available");
                return null;
            }
            Object node = null;
            // 优先 4 参构造器（带 TreeViewer + 父 TreeNode），展开子节点不 NPE。
            // AbapRepositoryObjectGenericLaunchableNode 只有 4 参构造器。
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
                    logDebug("4-arg constructor not found for " + nodeClass.getSimpleName());
                }
            }
            if (node == null) {
                // 回退到基类的 2 参构造器（Launchable 子类没有 2 参构造器）
                Class<?> baseClass = loadClass(BUNDLE_PROJECT_EXPLORER_UI, CLS_ABAP_NODE_BASE);
                if (baseClass != null) {
                    java.lang.reflect.Constructor<?> ctor = baseClass
                            .getConstructor(IProject.class, repoObjClass);
                    node = ctor.newInstance(project, repoObj);
                }
            }
            if (node == null) {
                logDebug("Failed to create ADT node for " + name);
                return null;
            }
            // 对齐 ADT 收藏夹做法（TreeContentAndLabelProvider.getObjectNodes）：
            // 构造后显式 setParent，保证 getParent() 链完整——RecentlyModifiedNodeTester
            // 靠它判定节点位于 Recently Modified 根下（hasChildren 聚合的前提）。
            if (parent instanceof org.eclipse.jface.viewers.TreeNode) {
                try {
                    node.getClass().getMethod("setParent",
                            org.eclipse.jface.viewers.TreeNode.class)
                            .invoke(node, parent);
                } catch (Throwable ignored) { }
            }
            logDebug("created ADT node for " + name + " (" + type + "), src="
                    + (fromSearch ? "search" : "manual") + ", uri=" + uri
                    + ", description=" + description);
            return node;
        } catch (Throwable t) {
            logDebug("createObjectNode failed for " + name + " (" + type + "): " + t.getMessage());
            return null;
        }
    }

    /**
     * 通过 ADT 虚拟文件夹搜索服务查找对象，并用搜索结果 IObject 构造
     * RepositoryObjectListItem——与收藏夹(VirtualFolderContentProvider.createObjects)
     * 完全一致的构造方式。
     *
     * <p>搜索结果的 IObject 带有：</p>
     * <ul>
     *   <li>atom: rel="objects" 的 self-link URI 和 type="sapgui" 的 vitUri</li>
     *   <li>后端返回的 type（如 PROG/I）、name、version</li>
     *   <li>isExpandable() 标志和 text（描述）</li>
     * </ul>
     * <p>这些字段保证后端 readTreeContent 能正确识别父对象并返回子节点。</p>
     *
     * @return 构造好的 RepositoryObjectListItem；搜索失败返回 null
     */
    private static Object createRepoObjFromSearch(IProject project, Class<?> listItemClass,
            String name, String type) {
        try {
            Object iObject = searchIObject(project, name, type);
            if (iObject == null) return null;

            // 从 IObject 的 atom links 提取 self-link URI 和 SAP GUI vitUri，
            // 与 VirtualFolderContentProvider.createObjects 完全一致。
            Object links = iObject.getClass().getMethod("getLinks").invoke(iObject);
            URI selfUri = findAtomLinkAsUri(links, ATOM_REL_OBJECTS, null);
            URI vitUri = findAtomLinkAsUri(links, ATOM_REL_OBJECTS, ATOM_TYPE_SAPGUI);
            if (selfUri == null) {
                logDebug("search: no self-link atom link for " + name);
                return null;
            }
            String objType = (String) iObject.getClass().getMethod("getType").invoke(iObject);
            String objName = (String) iObject.getClass().getMethod("getName").invoke(iObject);
            String version = (String) iObject.getClass().getMethod("getVersion").invoke(iObject);

            // RepositoryObjectListItem(uri, vitUri, type, name, technicalName, "")
            // 6 参构造器委托 7 参（version=null）。收藏夹用的是 7 参带 version，
            // 这里也尝试用 7 参构造器以完全对齐。
            Object repoObj = null;
            try {
                Class<?> versionClass = loadClass(BUNDLE_RIS_SEARCH,
                        "com.sap.adt.ris.search.repositoryservice.IRepositoryObject$Version");
                if (versionClass != null && version != null) {
                    Object versionObj = versionClass.getMethod("parse", String.class)
                            .invoke(null, version);
                    repoObj = listItemClass.getConstructor(URI.class, URI.class,
                            String.class, String.class, String.class, String.class, versionClass)
                            .newInstance(selfUri, vitUri, objType, objName, objName, "", versionObj);
                }
            } catch (Throwable ignored) { }
            if (repoObj == null) {
                repoObj = listItemClass.getConstructor(URI.class, URI.class,
                        String.class, String.class, String.class, String.class)
                        .newInstance(selfUri, vitUri, objType, objName, objName, "");
            }

            // 对齐收藏夹：setDescription(IObject.getText()) + setExpandable(IObject.isExpandable())
            try {
                String text = (String) iObject.getClass().getMethod("getText").invoke(iObject);
                if (text != null) {
                    listItemClass.getMethod("setDescription", String.class).invoke(repoObj, text);
                }
                Boolean expandable = (Boolean) iObject.getClass().getMethod("isExpandable").invoke(iObject);
                listItemClass.getMethod("setExpandable", boolean.class).invoke(repoObj, expandable);
            } catch (Throwable ignored) { }

            logDebug("search: built RepositoryObjectListItem for " + name + " (" + objType
                    + "), uri=" + selfUri + ", version=" + version);
            return repoObj;
        } catch (Throwable t) {
            logDebug("createRepoObjFromSearch failed for " + name + ": " + t.getMessage());
            return null;
        }
    }

    /**
     * 通过 ADT 虚拟文件夹搜索服务按名称查找对象。
     *
     * @return 匹配 name（忽略大小写）和 type 的 IObject；未找到返回 null
     */
    private static Object searchIObject(IProject project, String name, String type) {
        try {
            Class<?> factoryClass = loadClass(BUNDLE_RIS_SEARCH, CLS_VF_SERVICE_FACTORY);
            if (factoryClass == null) {
                logDebug("searchIObject: factory class not found: " + CLS_VF_SERVICE_FACTORY);
                return null;
            }

            // isVirtualFolderSearchServiceAvailable(IProject)
            Object available = factoryClass.getMethod("isVirtualFolderSearchServiceAvailable",
                    IProject.class).invoke(null, project);
            if (!Boolean.TRUE.equals(available)) {
                logDebug("searchIObject: virtual folder search service unavailable for "
                        + project.getName());
                return null;
            }

            Object service = factoryClass.getMethod("createVirtualFoldersSearchService",
                    IProject.class).invoke(null, project);
            if (service == null) {
                logDebug("searchIObject: service instance null");
                return null;
            }

            Object params = factoryClass.getMethod("createVirtualFolderRequestParameters")
                    .invoke(null);
            if (params == null) {
                logDebug("searchIObject: request parameters null");
                return null;
            }

            params.getClass().getMethod("setObjectSearchPattern", String.class)
                    .invoke(params, name);

            // IAdtRisVirtualFoldersSearchService.getContent(IAdtRisVirtualFoldersRequestParameters, IProgressMonitor)
            Class<?> paramsClass = loadClass(BUNDLE_RIS_SEARCH, CLS_VF_REQUEST_PARAMS);
            Object result = service.getClass().getMethod("getContent", paramsClass,
                    org.eclipse.core.runtime.IProgressMonitor.class)
                    .invoke(service, params, null);
            if (result == null) {
                logDebug("searchIObject: empty result for " + name);
                return null;
            }

            Object objects = result.getClass().getMethod("getObject").invoke(result);
            if (!(objects instanceof java.util.List)) return null;

            Object nameOnlyMatch = null;
            for (Object obj : (java.util.List<?>) objects) {
                String objName = (String) obj.getClass().getMethod("getName").invoke(obj);
                String objType = (String) obj.getClass().getMethod("getType").invoke(obj);
                if (!name.equalsIgnoreCase(objName)) continue;
                if (type.equals(objType)) return obj;
                // 类型串可能因版本差异不一致（如 INCLUDE 的搜索类型），
                // 先记住同名对象，全部遍历完仍未精确匹配时用它兜底。
                if (nameOnlyMatch == null) nameOnlyMatch = obj;
            }
            if (nameOnlyMatch != null) {
                logDebug("searchIObject: name-only match for " + name + " (type mismatch)");
            } else {
                logDebug("searchIObject: no match for " + name + " (" + type + ")");
            }
            return nameOnlyMatch;
        } catch (Throwable t) {
            logDebug("searchIObject failed for " + name + ": " + t);
        }
        return null;
    }

    /**
     * 从 atom link 列表中按 rel（和可选 type）提取 URI。
     *
     * <p>替代 ADT 的 IAdtAtomUtil.findAtomLinkAsUri，避免额外依赖。</p>
     */
    private static URI findAtomLinkAsUri(Object links, String rel, String type) {
        if (!(links instanceof java.util.List)) return null;
        for (Object link : (java.util.List<?>) links) {
            try {
                String linkRel = (String) link.getClass().getMethod("getRel").invoke(link);
                if (!rel.equals(linkRel)) continue;
                if (type != null) {
                    String linkType = (String) link.getClass().getMethod("getType").invoke(link);
                    if (!type.equals(linkType)) continue;
                }
                String href = (String) link.getClass().getMethod("getHref").invoke(link);
                if (href != null && !href.isEmpty()) {
                    return URI.create(href);
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    /**
     * 本地手动构造 RepositoryObjectListItem（搜索服务不可用时的回退路径）。
     *
     * <p>与收藏夹 6 参构造器等价：uri=vitUri=本地解析的 URI、technicalName=name、
     * userName=""、expandable=true（6 参构造器硬编码）。</p>
     */
    private static Object createRepoObjManually(Class<?> listItemClass, URI uriObj,
            String type, String name) {
        try {
            Object repoObj = null;
            if (uriObj != null) {
                try {
                    repoObj = listItemClass.getConstructor(URI.class, URI.class,
                            String.class, String.class, String.class, String.class)
                            .newInstance(uriObj, uriObj, type, name, name, "");
                } catch (NoSuchMethodException e) {
                    logDebug("6-arg RepositoryObjectListItem ctor not found, fallback to no-arg");
                }
            }
            if (repoObj == null) {
                repoObj = listItemClass.getDeclaredConstructor().newInstance();
                setFieldCrossHierarchy(repoObj, listItemClass, "name", name);
                setFieldCrossHierarchy(repoObj, listItemClass, "technicalName", name);
                setFieldCrossHierarchy(repoObj, listItemClass, "type", type);
                setFieldCrossHierarchy(repoObj, listItemClass, "userName", "");
            }
            return repoObj;
        } catch (Throwable t) {
            logDebug("createRepoObjManually failed: " + t.getMessage());
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

    /** 该类型在 ADT 对象树中是否有下级子节点（叶子类型如函数模块返回 false）。
     *
     * <p>注意：INCLUDE 程序（PROG/I）在标准 "Favorite Objects" 下同样可展开
     * （子节点为全局变量、FORM、dynpro 状态等 include 组成对象），这里与之一致；
     * 是否真的有子节点由后端 readTreeContent 决定，expandable 只控制展开箭头。</p> */
    static boolean isExpandableType(String type) {
        if (type == null) return false;
        return type.startsWith("PROG/P")     // 可执行程序（字典结构/类型组等）
                || type.startsWith("PROG/I") // INCLUDE 程序（变量/FORM/状态等）
                || type.startsWith("CLAS/")  // 类（方法/属性等）
                || type.startsWith("INTF/")  // 接口
                || type.startsWith("TABL/")  // 表（字段）
                || type.startsWith("STRU/")  // 结构（字段）
                || type.startsWith("DDLS/")  // CDS 视图
                || type.startsWith("FUGR/F");// 函数组（函数模块）
    }

    /**
     * 从 ADT 工作区属性文件读取对象描述。
     *
     * <p>源文件 {@code name.asXxx} 的属性文件是同目录的 {@code name.apXxx}
     * （如 {@code zewme00120.asprog ↔ zewme00120.approg}），其中含有
     * {@code adtcore:description="..."} 属性。文件不存在或解析失败返回 null，
     * 节点照常显示，只是名称后没有描述。</p>
     */
    static String readDescriptionFromMetadata(String filePath) {
        if (filePath == null || filePath.isEmpty()) return null;
        try {
            IResource src = ResourcesPlugin.getWorkspace().getRoot()
                    .findMember(new Path(filePath));
            if (src == null) return null;
            String name = src.getName();
            int dot = name.lastIndexOf('.');
            if (dot < 0) return null;
            String ext = name.substring(dot + 1);   // 如 "asprog"
            if (!ext.startsWith("as")) return null;
            String metaName = name.substring(0, dot) + ".ap" + ext.substring(2);

            // 优先按工作区资源读；semantic FS 资源可能尚未在索引中，退回本地磁盘文件
            String xml = null;
            if (src instanceof IFile && src.getParent() != null) {
                IFile meta = src.getParent().getFile(new Path(metaName));
                if (meta.exists()) {
                    try (java.io.InputStream in = meta.getContents()) {
                        xml = new String(in.readAllBytes(),
                                java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
                if (xml == null && src.getParent().getLocation() != null) {
                    java.io.File raw = new java.io.File(
                            src.getParent().getLocation().toFile(), metaName);
                    if (raw.isFile()) {
                        xml = new String(java.nio.file.Files.readAllBytes(raw.toPath()),
                                java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
            return extractDescription(xml);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从 ADT 属性 XML 中提取 {@code adtcore:description} 的值。 */
    static String extractDescription(String xml) {
        if (xml == null) return null;
        String marker = "adtcore:description=\"";
        int idx = xml.indexOf(marker);
        if (idx < 0) return null;
        int start = idx + marker.length();
        int end = xml.indexOf('"', start);
        if (end < 0) return null;
        return unescapeXml(xml.substring(start, end)).trim();
    }

    private static String unescapeXml(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&amp;", "&");
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

    /** 跨类继承链反射读字段，转 String（失败返回 "ERR"）。 */
    private static String readFieldStr(Object obj, String fieldName) {
        if (obj == null) return "null";
        Class<?> current = obj.getClass();
        while (current != null && current != Object.class) {
            try {
                java.lang.reflect.Field f = current.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(obj);
                return v == null ? "null" : v.toString();
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            } catch (Throwable t) {
                return "ERR:" + t.getClass().getSimpleName();
            }
        }
        return "NF";
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
