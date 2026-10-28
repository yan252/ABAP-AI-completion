package com.sap.abap.ai.completion.recent;

import java.util.Map;
import java.util.WeakHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IPropertyListener;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartConstants;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.navigator.CommonNavigator;
import org.eclipse.ui.navigator.CommonViewer;
import org.eclipse.ui.navigator.INavigatorContentDescriptor;
import org.eclipse.ui.navigator.INavigatorContentService;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.parser.AbapLanguageDetector;
import com.sap.abap.ai.completion.preferences.AIConfiguration;

/**
 * 监听 ABAP 编辑器"保存成功"事件（dirty 标志 true→false），把当前对象写入
 * 插件自建的 "最近修改" 节点（{@link RecentlyModifiedRoot}）。
 *
 * <p>数据完全保存在本地（{@link RecentlyModifiedStore}），不访问 ABAP 后端；
 * 顺序为"最近修改在最前"。节点的显示由 {@link RecentlyModifiedContentProvider}
 * 通过 Project Explorer 的 navigatorContent 扩展提供。</p>
 *
 * <p>注册路径与 {@code AICompletionListener} 相同：由 Activator 作为 part listener
 * 挂到每个工作台页面；对已打开的编辑器通过 {@link #attachOpenEditors(IWorkbenchPage)}
 * 补挂。</p>
 */
public class RecentlyModifiedOnSaveListener implements IPartListener2 {

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    /** Project Explorer 视图 ID。 */
    private static final String PROJECT_EXPLORER_ID = "org.eclipse.ui.navigator.ProjectExplorer";

    /** 本插件 navigatorContent 扩展 ID。 */
    private static final String EXTENSION_ID =
            "com.sap.abap.ai.completion.recentlyModifiedContent";

    /** 同一对象在该时间窗口内重复保存只处理一次（防止 dirty 事件重复触发）。 */
    private static final long DEDUP_WINDOW_MS = 5000;

    /** 已挂接的编辑器 -> 其属性监听器（WeakHashMap：编辑器关闭后条目可被回收）。 */
    private final Map<IEditorPart, IPropertyListener> attachedEditors = new WeakHashMap<>();

    private static volatile String lastKey = "";
    private static volatile long lastTime = 0L;

    // ==================== IPartListener2 ====================

    @Override
    public void partOpened(IWorkbenchPartReference partRef) {
        attachEditor(partRef.getPart(false));
    }

    @Override
    public void partActivated(IWorkbenchPartReference partRef) {
        attachEditor(partRef.getPart(false));
    }

    @Override
    public void partClosed(IWorkbenchPartReference partRef) {
        IWorkbenchPart part = partRef.getPart(false);
        if (part instanceof IEditorPart) {
            synchronized (attachedEditors) {
                IPropertyListener listener = attachedEditors.remove(part);
                if (listener != null) {
                    ((IEditorPart) part).removePropertyListener(listener);
                }
            }
        }
    }

    @Override
    public void partBroughtToTop(IWorkbenchPartReference partRef) {
    }

    @Override
    public void partDeactivated(IWorkbenchPartReference partRef) {
    }

    @Override
    public void partHidden(IWorkbenchPartReference partRef) {
    }

    @Override
    public void partVisible(IWorkbenchPartReference partRef) {
    }

    @Override
    public void partInputChanged(IWorkbenchPartReference partRef) {
    }

    // ==================== attach ====================

    /** 补挂当前页面中所有已打开的编辑器（插件启动时编辑器可能已存在）。 */
    public void attachOpenEditors(IWorkbenchPage page) {
        if (page == null) return;
        for (IEditorReference ref : page.getEditorReferences()) {
            attachEditor(ref.getPart(false));
        }
    }

    /** 若是 ABAP 编辑器且尚未挂接，则注册 dirty 属性监听。 */
    public void attachEditor(IWorkbenchPart part) {
        if (!(part instanceof IEditorPart)) return;
        if (!AIConfiguration.isPluginEnabled()
                || !AIConfiguration.isRecentlyModifiedOnSaveEnabled()) {
            return;
        }
        IEditorPart editor = (IEditorPart) part;
        synchronized (attachedEditors) {
            if (attachedEditors.containsKey(editor)) return;
            if (!isAbapEditor(editor)) return;

            IPropertyListener listener = (source, propId) -> {
                if (propId == IWorkbenchPartConstants.PROP_DIRTY && !editor.isDirty()) {
                    onEditorSaved(editor);
                }
            };
            editor.addPropertyListener(listener);
            attachedEditors.put(editor, listener);
            logDebug("attached to editor: " + editor.getTitle());
        }
    }

    /** 与 AICompletionListener.isAbapEditor 相同的判定路径。 */
    private boolean isAbapEditor(IEditorPart editor) {
        try {
            ITextEditor textEditor = editor instanceof ITextEditor
                    ? (ITextEditor) editor : editor.getAdapter(ITextEditor.class);
            if (textEditor == null) return false;
            IEditorInput input = textEditor.getEditorInput();
            IFile file = input instanceof FileEditorInput
                    ? ((FileEditorInput) input).getFile() : null;
            IDocumentProvider provider = textEditor.getDocumentProvider();
            IDocument document = (provider != null && input != null)
                    ? provider.getDocument(input) : null;
            return AbapLanguageDetector.isAbapContext(textEditor, file, document);
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== save handling ====================

    private void onEditorSaved(IEditorPart editor) {
        try {
            if (!AIConfiguration.isPluginEnabled()
                    || !AIConfiguration.isRecentlyModifiedOnSaveEnabled()) {
                return;
            }

            AdtObjectResolver.AdtObjectInfo object = AdtObjectResolver.getAdtObject(editor);
            if (object == null || object.project == null) {
                logDebug("saved editor skipped (see getAdtObject diagnostics above)");
                return;
            }

            String key = object.project.getName() + "|" + object.type + "|" + object.name;
            long now = System.currentTimeMillis();
            // 静态去重：即使监听器被注册了多个实例，同一次保存也只处理一次。
            synchronized (RecentlyModifiedOnSaveListener.class) {
                if (key.equals(lastKey) && now - lastTime < DEDUP_WINDOW_MS) {
                    return;
                }
                lastKey = key;
                lastTime = now;
            }

            String projectName = object.project.getName();
            RecentlyModifiedStore.getInstance().add(object.project, object.name,
                    object.type, object.filePath, object.uri);

            logInfo("recorded " + object.name + " (" + object.type + ") in " + projectName);

            // 始终刷新项目节点：CNF 只在项目节点的子节点被刷新后才会重新调用
            // content provider。若仅刷新"最近修改"根节点，而该节点尚未出现在
            // 树中（上一次缓存为"无子节点"），刷新是空操作，节点永远不会出现。
            refreshProjectExplorer(object.project);
        } catch (Throwable t) {
            logDebug("onEditorSaved failed: " + t.getMessage());
        }
    }

    /**
     * 在 UI 线程刷新 Project Explorer 的项目节点，使 CNF 重新调用本插件的
     * content provider（"最近修改" 节点据此出现/更新）。
     */
    private static void refreshProjectExplorer(final IProject project) {
        try {
            Display display = PlatformUI.getWorkbench().getDisplay();
            if (display == null || display.isDisposed()) return;

            display.asyncExec(() -> {
                boolean anyView = false;
                for (IWorkbenchWindow window
                        : PlatformUI.getWorkbench().getWorkbenchWindows()) {
                    for (IWorkbenchPage page : window.getPages()) {
                        for (String vid : java.util.Arrays.asList(PROJECT_EXPLORER_ID,
                                "org.eclipse.ui.navigator.resources.ProjectExplorer")) {
                            IViewPart view = page.findView(vid);
                            if (view instanceof CommonNavigator) {
                                anyView = true;
                                CommonNavigator navigator = (CommonNavigator) view;
                                ensureExtensionActive(navigator);
                                CommonViewer viewer = navigator.getCommonViewer();
                                if (viewer != null && viewer.getControl() != null
                                        && !viewer.getControl().isDisposed()) {
                                    viewer.refresh(project);
                                    logInfo("refreshProjectExplorer(" + vid + "): refreshed project "
                                            + project.getName());
                                }
                                break;
                            }
                        }
                    }
                }
                if (!anyView) {
                    logInfo("refreshProjectExplorer: neither '" + PROJECT_EXPLORER_ID
                            + "' nor 'org.eclipse.ui.navigator.resources.ProjectExplorer'"
                            + " found - node not refreshed");
                }
            });
        } catch (Throwable ignored) {
        }
    }

    /**
     * 确保 CNF 已激活本插件的 navigatorContent 扩展。
     *
     * <p>注意：activateExtensions 只需做一次——后续保存不要再调用，否则会触发
     * CNF 重建整个 content pipeline，导致 ADT 的 Project Explorer 暂时清空
     * 所有连接（重启 Eclipse 才恢复）。</p>
     */
    private static void ensureExtensionActive(CommonNavigator navigator) {
        try {
            INavigatorContentService service = navigator.getNavigatorContentService();
            if (service == null) return;

            if (service.isActive(EXTENSION_ID)) {
                return; // 已激活过，不再重复
            }

            logInfo("extension not active yet, activating now...");
            INavigatorContentDescriptor[] activated =
                    service.getActivationService().activateExtensions(
                            new String[] { EXTENSION_ID }, true);
            // 注意：不调 service.update()——它会重建整个 pipeline，可能影响 ADT
            logInfo("activateExtensions returned " + activated.length
                    + " descriptor(s), isActive now=" + service.isActive(EXTENSION_ID));
        } catch (Throwable t) {
            logInfo("ensureExtensionActive failed: " + t.getMessage());
        }
    }

    /** 记录流程关键节点：INFO 级、无条件写入 Error Log 视图。 */
    private static void logInfo(String msg) {
        try {
            LOG.log(new Status(IStatus.INFO, Activator.PLUGIN_ID,
                    "[RecentlyModified] " + msg));
        } catch (Exception ignored) {
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
