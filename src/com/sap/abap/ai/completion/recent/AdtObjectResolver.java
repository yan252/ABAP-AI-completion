package com.sap.abap.ai.completion.recent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.preferences.AIConfiguration;

/**
 * 从 ADT 编辑器解析 ABAP 对象标识（名称 / 类型 / 所属项目 / 对象文件）。
 *
 * <p>ADT 的 API 位于 x-friends 半公开包，为保持本插件"零 ADT 编译期依赖"
 * 的现状，全部通过 OSGi bundle classloader 反射访问；ADT 缺席或 API 变更时
 * 返回 null，不影响其它功能。</p>
 */
public final class AdtObjectResolver {

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    private static final String BUNDLE_TOOLS_CORE = "com.sap.adt.tools.core";
    private static final String BUNDLE_TOOLS_CORE_UI = "com.sap.adt.tools.core.ui";
    private static final String BUNDLE_ADT_PROJECT = "com.sap.adt.project";

    private static final String CLS_ADT_OBJECT_REFERENCE =
            "com.sap.adt.tools.core.IAdtObjectReference";
    private static final String CLS_PROJECT_PROVIDER = "com.sap.adt.project.IProjectProvider";
    private static final String CLS_FORM_EDITOR =
            "com.sap.adt.tools.core.ui.editors.IAdtFormEditor";
    private static final String CLS_ADT_MAIN_OBJECT =
            "com.sap.adt.tools.core.model.adtcore.IAdtMainObject";

    /** ADT 自身也忽略的辅助对象类型（包、增强等），不作为"最近修改"条目。 */
    private static final Set<String> IGNORED_TYPES = new HashSet<>(Arrays.asList(
            "DEVC/K", "STOB/DO", "ENHS/XB"));

    private AdtObjectResolver() {
    }

    /** ADT 对象标识值对象。 */
    public static final class AdtObjectInfo {
        public final String name;
        public final String type;
        public final IProject project;
        /** 对象在工作区中的文件路径（用于定位）。 */
        public final String filePath;
        /** ADT 对象的 URI（构造 RepositoryObject 节点时需要）。可能为 null。 */
        public final String uri;

        AdtObjectInfo(String name, String type, IProject project, String filePath, String uri) {
            this.name = name;
            this.type = type;
            this.project = project;
            this.filePath = filePath;
            this.uri = uri;
        }
    }

    /**
     * 解析编辑器对应的 ADT 对象。
     *
     * @return 非 ADT 对象、被忽略类型或无法确定项目时返回 null
     */
    public static AdtObjectInfo getAdtObject(IEditorPart editor) {
        try {
            IEditorInput input = editor.getEditorInput();
            if (input == null) {
                logDebug("getAdtObject: editorInput is null");
                return null;
            }

            IFile file = getAdtFile(input);

            String name = null;
            String type = null;
            String uri = null;

            Class<?> refClass = loadClass(BUNDLE_TOOLS_CORE, CLS_ADT_OBJECT_REFERENCE);
            if (refClass != null) {
                Object ref = Adapters.adapt(input, refClass);
                if (ref == null) ref = Adapters.adapt(editor, refClass);
                if (ref == null && file != null) ref = Adapters.adapt(file, refClass);
                if (ref != null) {
                    name = (String) refClass.getMethod("getName").invoke(ref);
                    type = (String) refClass.getMethod("getType").invoke(ref);
                    try {
                        Object uriObj = refClass.getMethod("getUri").invoke(ref);
                        if (uriObj != null) uri = uriObj.toString();
                    } catch (Throwable ignored) { }
                } else {
                    logDebug("getAdtObject: no IAdtObjectReference on input/editor/file"
                            + " (inputClass=" + input.getClass().getName()
                            + ", editorClass=" + editor.getClass().getName()
                            + "), trying IAdtFormEditor");
                }
            }

            if (name == null || type == null) {
                // 备选路径: IAdtFormEditor.getModel() -> IAdtMainObject.getName()/getType()
                String[] resolved = viaFormEditor(editor);
                if (resolved != null) {
                    name = resolved[0];
                    type = resolved[1];
                }
            }

            if (name == null || name.trim().isEmpty()
                    || type == null || type.trim().isEmpty()) {
                logDebug("getAdtObject: name/type unavailable (name=" + name
                        + ", type=" + type + ", editorClass="
                        + editor.getClass().getName() + ")");
                return null;
            }
            if (IGNORED_TYPES.contains(type)) {
                logDebug("getAdtObject: ignored type " + type + " for " + name);
                return null;
            }

            IProject project = file != null ? file.getProject() : findProject(input, editor);
            if (project == null) {
                logDebug("getAdtObject: project unavailable for " + name + " (" + type + ")");
                return null;
            }

            String filePath = file != null ? file.getFullPath().toString() : null;
            logDebug("getAdtObject: resolved " + name + " (" + type + ") in "
                    + project.getName() + ", file=" + filePath + ", uri=" + uri);
            return new AdtObjectInfo(name, type, project, filePath, uri);
        } catch (Throwable t) {
            logDebug("getAdtObject failed: " + t.getMessage());
            return null;
        }
    }

    /** 备选路径：编辑器适配为 IAdtFormEditor 后取模型中的名称/类型。 */
    private static String[] viaFormEditor(IEditorPart editor) {
        try {
            Class<?> feClass = loadClass(BUNDLE_TOOLS_CORE_UI, CLS_FORM_EDITOR);
            Class<?> moClass = loadClass(BUNDLE_TOOLS_CORE, CLS_ADT_MAIN_OBJECT);
            if (feClass == null || moClass == null) return null;
            Object fe = Adapters.adapt(editor, feClass);
            if (fe == null) return null;
            Object model = feClass.getMethod("getModel").invoke(fe);
            if (model == null) return null;
            String name = (String) moClass.getMethod("getName").invoke(model);
            String type = (String) moClass.getMethod("getType").invoke(model);
            return new String[] { name, type };
        } catch (Throwable t) {
            logDebug("viaFormEditor failed: " + t.getMessage());
            return null;
        }
    }

    /** 项目解析兜底（无法从文件反推时使用）。 */
    private static IProject findProject(IEditorInput input, IEditorPart editor) {
        try {
            IProject project = Adapters.adapt(input, IProject.class);
            if (project != null) return project;

            project = viaProjectProvider(input);
            if (project == null) project = viaProjectProvider(editor);
            if (project != null) return project;

            IFile adapted = Adapters.adapt(input, IFile.class);
            if (adapted != null) return adapted.getProject();
        } catch (Throwable t) {
            logDebug("findProject failed: " + t.getMessage());
        }
        return null;
    }

    /**
     * 依次尝试 getFile()、getIFile() 及 IAdtFileEditorInput 接口方法
     * （与 AICompletionListener.getFile 相同的反射路径）。
     */
    private static IFile getAdtFile(IEditorInput input) {
        if (input == null) return null;
        try {
            Object file = input.getClass().getMethod("getFile").invoke(input);
            if (file instanceof IFile) return (IFile) file;
        } catch (Throwable ignored) { }
        try {
            Object file = input.getClass().getMethod("getIFile").invoke(input);
            if (file instanceof IFile) return (IFile) file;
        } catch (Throwable ignored) { }
        try {
            Class<?> adtInputClass = input.getClass().getClassLoader()
                    .loadClass("com.sap.adt.tools.core.ui.editors.IAdtFileEditorInput");
            if (adtInputClass.isAssignableFrom(input.getClass())) {
                Object file = adtInputClass.getMethod("getFile").invoke(input);
                if (file instanceof IFile) return (IFile) file;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** 经 com.sap.adt.project.IProjectProvider 适配取 IProject。 */
    private static IProject viaProjectProvider(Object adaptable) {
        if (adaptable == null) return null;
        try {
            Class<?> ppClass = loadClass(BUNDLE_ADT_PROJECT, CLS_PROJECT_PROVIDER);
            if (ppClass == null) return null;
            Object provider = Adapters.adapt(adaptable, ppClass);
            if (provider == null) return null;
            Object project = ppClass.getMethod("getProject").invoke(provider);
            return project instanceof IProject ? (IProject) project : null;
        } catch (Throwable t) {
            return null;
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
            if (AIConfiguration.isInterfaceLogDebugEnabled()) {
                LOG.log(new Status(IStatus.INFO, Activator.PLUGIN_ID,
                        "[RecentlyModified] " + msg));
            }
        } catch (Exception ignored) {
        }
    }
}
