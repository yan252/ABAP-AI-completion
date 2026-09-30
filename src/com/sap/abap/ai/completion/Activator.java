package com.sap.abap.ai.completion;

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
import com.sap.abap.ai.completion.preferences.PreferenceConstants;

public class Activator extends AbstractUIPlugin implements IStartup {

    public static final String PLUGIN_ID = "com.sap.abap.ai.completion";

    private static Activator plugin;

    /** 自动补全监听器（整个插件生命周期单例） */
    private AICompletionListener autoCompletionListener;

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

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;

        // 强制开启自动补全与接口日志（覆盖此前保存的关闭值，确保开箱即用且可诊断）
        IPreferenceStore store = getPreferenceStore();
        if (!store.getBoolean(PreferenceConstants.AUTO_COMPLETION_ENABLED)) {
            store.setValue(PreferenceConstants.AUTO_COMPLETION_ENABLED, true);
        }
        String logLevel = store.getString(PreferenceConstants.INTERFACE_LOG_LEVEL);
        if (logLevel == null || logLevel.isEmpty()
                || Integer.parseInt(logLevel) == PreferenceConstants.LOG_LEVEL_NONE) {
            store.setValue(PreferenceConstants.INTERFACE_LOG_LEVEL,
                    String.valueOf(PreferenceConstants.LOG_LEVEL_DEBUG));
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

            // 注册 page listener，捕获后续创建的页面
            window.addPageListener(new org.eclipse.ui.IPageListener() {
                @Override
                public void pageOpened(IWorkbenchPage page) {
                    page.addPartListener(listener);
                    Platform.getLog(getBundle()).log(new Status(IStatus.INFO, PLUGIN_ID,
                            "pageOpened: part listener registered"));
                }
                @Override
                public void pageClosed(IWorkbenchPage page) { }
                @Override
                public void pageActivated(IWorkbenchPage page) { }
            });

            // 遍历窗口中已有页面注册 part listener
            for (IWorkbenchPage page : window.getPages()) {
                page.addPartListener(listener);
            }

            // 尝试附加到当前激活编辑器
            listener.attachToActiveEditor();
        } catch (Exception e) {
            Platform.getLog(getBundle()).log(new Status(IStatus.ERROR, PLUGIN_ID,
                    "attachListenerToWindow failed: " + e.getMessage(), e));
        }
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        autoCompletionListener = null;
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
    }
}
