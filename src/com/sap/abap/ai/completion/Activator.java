package com.sap.abap.ai.completion;

import java.util.Locale;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

import com.sap.abap.ai.completion.editor.AICompletionListener;
import com.sap.abap.ai.completion.preferences.AIConfiguration;
import com.sap.abap.ai.completion.preferences.PluginLocale;
import com.sap.abap.ai.completion.recent.RecentlyModifiedOnSaveListener;
import com.sap.abap.ai.completion.preferences.PreferenceConstants;

public class Activator extends AbstractUIPlugin implements IStartup {

    public static final String PLUGIN_ID = "com.sap.abap.ai.completion";

    private static Activator plugin;

    /** 自动补全监听器（整个插件生命周期单例） */
    private AICompletionListener autoCompletionListener;

    /** 保存时记录到"最近修改"节点的监听器（整个插件生命周期单例） */
    private RecentlyModifiedOnSaveListener recentlyModifiedOnSaveListener;

    /** 已注册过 part listener 的页面（防止同一 listener 被重复注册导致事件触发两次）。 */
    private final java.util.Set<IWorkbenchPage> registeredPages =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** 是否已注册窗口监听器 */
    private boolean windowListenerRegistered = false;

    /**
     * 获取自动补全监听器（必要时创建）。
     */
    public synchronized AICompletionListener getAutoCompletionListener() {
        if (autoCompletionListener == null) {
            autoCompletionListener = new AICompletionListener();
        }
        return autoCompletionListener;
    }

    public static AICompletionListener staticGetAutoCompletionListener() {
        return plugin != null ? plugin.getAutoCompletionListener() : null;
    }

    /**
     * 获取"保存时记录到最近修改节点"监听器（必要时创建）。
     */
    public synchronized RecentlyModifiedOnSaveListener getRecentlyModifiedOnSaveListener() {
        if (recentlyModifiedOnSaveListener == null) {
            recentlyModifiedOnSaveListener = new RecentlyModifiedOnSaveListener();
        }
        return recentlyModifiedOnSaveListener;
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;

        // 自动补全与接口日志级别的默认值仅在首次安装时生效（PreferenceInitializer）；
        // 此后尊重用户在首选项中保存的值，不再每次启动强制覆盖。
        IPreferenceStore store = getPreferenceStore();

        // Align the JVM default locale with the plugin UI language preference so
        // that lazily-resolved texts (e.g. plugin.xml page labels via
        // Bundle-Localization) follow the selected language after a restart,
        // even when the OS language differs.
        String uiLang = store.getString(PreferenceConstants.PLUGIN_LANGUAGE);
        // 本次启动时 JVM 实际使用的语言（来自 eclipse.ini 的 -nl 或操作系统语言）。
        // 必须在下面的 setDefault 之前取，否则读到的已经是改过的值。
        String startupLang = Locale.getDefault().getLanguage();
        if (PreferenceConstants.LANG_ZH.equals(uiLang)) {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
        } else if (PreferenceConstants.LANG_EN.equals(uiLang)) {
            Locale.setDefault(Locale.ENGLISH);
        }
        // plugin.xml 里的 %prefpage.* 标签（首选项树中的配置页名称）由 Equinox 在
        // 启动时按 startupLang 解析并缓存，插件语言偏好在启动后才生效，二者不一致时
        // 页名会显示成"另一个语言"（如英文设置下显示中文页名）。这里把 -nl 写进
        // eclipse.ini，使下次启动时解析语言与插件语言一致；首次安装时二者都来自
        // 操作系统语言（英文系统 → 英文页名），因此无需额外处理。
        boolean langMismatch = PreferenceConstants.LANG_ZH.equals(uiLang)
                ? !startupLang.toLowerCase(Locale.ROOT).startsWith("zh")
                : startupLang.toLowerCase(Locale.ROOT).startsWith("zh");
        if (langMismatch) {
            PluginLocale.syncEclipseIniNl(uiLang);
        }

        // 输出启动日志到 Eclipse Error Log（不依赖插件自身日志级别）
        ILog log = Platform.getLog(getBundle());
        log.log(new Status(IStatus.INFO, PLUGIN_ID,
                "ABAP AI Completion plugin started. autoCompletion="
                        + store.getBoolean(PreferenceConstants.AUTO_COMPLETION_ENABLED)
                        + ", logLevel=" + store.getString(PreferenceConstants.INTERFACE_LOG_LEVEL)));

        // 确保 Skill 目录存在(默认: <workspace>/.metadata/.plugins/com.sap.abap.ai.completion/skills)
        AIConfiguration.ensureSkillDirectoryExists();

        // 在 UI 线程注册窗口监听器并尝试附加当前编辑器（不依赖 earlyStartup）
        Display.getDefault().asyncExec(this::registerWindowListenerAndAttach);
    }

    /**
     * 注册 IWindowListener 监听所有工作台窗口的打开/关闭，
     * 并尝试将自动补全监听器附加到当前激活的 ABAP 编辑器。
     */
    private void registerWindowListenerAndAttach() {
        ILog log = Platform.getLog(getBundle());
        try {
            IWorkbench workbench = PlatformUI.getWorkbench();
            if (workbench == null) {
                log.log(new Status(IStatus.WARNING, PLUGIN_ID,
                        "registerWindowListenerAndAttach: workbench is null, will retry via earlyStartup"));
                return;
            }

            if (!windowListenerRegistered) {
                workbench.addWindowListener(new IWindowListener() {
                    @Override
                    public void windowOpened(IWorkbenchWindow window) {
                        log.log(new Status(IStatus.INFO, PLUGIN_ID,
                                "windowOpened: attaching part listener"));
                        attachListenerToWindow(window);
                    }

                    @Override
                    public void windowClosed(IWorkbenchWindow window) { }

                    @Override
                    public void windowActivated(IWorkbenchWindow window) { }

                    @Override
                    public void windowDeactivated(IWorkbenchWindow window) { }
                });
                windowListenerRegistered = true;
                log.log(new Status(IStatus.INFO, PLUGIN_ID,
                        "IWindowListener registered"));
            }

            // 对当前已打开的所有窗口注册 part listener 并尝试附加
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
                attachListenerToWindow(window);
            }
        } catch (Exception e) {
            log.log(new Status(IStatus.ERROR, PLUGIN_ID,
                    "registerWindowListenerAndAttach failed: " + e.getMessage(), e));
        }
    }

    /**
     * 将自动补全监听器的 part listener 注册到指定窗口的所有页面，并尝试附加到当前激活编辑器。
     * 同时注册 IPageListener 以捕获后续创建的页面。
     */
    private void attachListenerToWindow(IWorkbenchWindow window) {
        try {
            if (window == null) return;
            final AICompletionListener listener = staticGetAutoCompletionListener();
            if (listener == null) return;
            final RecentlyModifiedOnSaveListener recentListener =
                    getRecentlyModifiedOnSaveListener();

            // 注册 page listener，捕获后续创建的页面
            window.addPageListener(new org.eclipse.ui.IPageListener() {
                @Override
                public void pageOpened(IWorkbenchPage page) {
                    if (registerPartListeners(page, listener, recentListener)) {
                        Platform.getLog(getBundle()).log(new Status(IStatus.INFO, PLUGIN_ID,
                                "pageOpened: part listener registered"));
                    }
                }
                @Override
                public void pageClosed(IWorkbenchPage page) { }
                @Override
                public void pageActivated(IWorkbenchPage page) { }
            });

            // 遍历窗口中已有页面注册 part listener
            for (IWorkbenchPage page : window.getPages()) {
                registerPartListeners(page, listener, recentListener);
            }

            // 尝试附加到当前激活编辑器
            listener.attachToActiveEditor();
        } catch (Exception e) {
            Platform.getLog(getBundle()).log(new Status(IStatus.ERROR, PLUGIN_ID,
                    "attachListenerToWindow failed: " + e.getMessage(), e));
        }
    }

    /**
     * 向页面注册 part listener（同一页面只注册一次）。
     *
     * <p>attachListenerToWindow 会由 windowOpened、已有窗口遍历和 earlyStartup
     * 多次触发，若不记录已注册页面，同一个 listener 实例会被重复添加到同一页面，
     * 导致每次事件触发两次回调。</p>
     *
     * @return true 表示本次执行了注册，false 表示该页面已注册过
     */
    private boolean registerPartListeners(IWorkbenchPage page,
            AICompletionListener listener, RecentlyModifiedOnSaveListener recentListener) {
        if (page == null) return false;
        synchronized (registeredPages) {
            if (!registeredPages.add(page)) return false;
        }
        page.addPartListener(listener);
        if (recentListener != null) {
            page.addPartListener(recentListener);
            recentListener.attachOpenEditors(page);
        }
        return true;
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        autoCompletionListener = null;
        recentlyModifiedOnSaveListener = null;
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }

    public static ImageDescriptor getImageDescriptor(String path) {
        return imageDescriptorFromPlugin(PLUGIN_ID, path);
    }

    public static IPreferenceStore staticGetPreferenceStore() {
        if (getDefault() != null) {
            return getDefault().getPreferenceStore();
        }
        return null;
    }

    @Override
    public void earlyStartup() {
        // earlyStartup 作为备份机制：如果 start() 中的 asyncExec 因工作台未就绪而失败，
        // 这里再尝试一次注册窗口监听器并附加。
        ILog log = Platform.getLog(getBundle());
        log.log(new Status(IStatus.INFO, PLUGIN_ID, "earlyStartup: backup registration"));
        Display.getDefault().asyncExec(this::registerWindowListenerAndAttach);
        // 确保 "事务码收藏" 的 CNF 扩展已激活并刷新，使节点在项目打开时即显示
        com.sap.abap.ai.completion.tcode.TcodeFavoriteInitializer.scheduleActivation();
    }
}
