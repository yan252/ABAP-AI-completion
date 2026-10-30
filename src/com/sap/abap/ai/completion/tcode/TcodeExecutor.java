package com.sap.abap.ai.completion.tcode;

import java.lang.reflect.Method;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;

/**
 * 在嵌入式 SAP GUI 编辑器中直接执行事务码。
 *
 * <p>反射调用 ADT 公共 API（保持本插件"零 ADT 编译期依赖"）：</p>
 * <pre>
 *   com.sap.adt.sapgui.ui.SapGuiPlugin.getDefault() -&gt; ISapGuiPlugin
 *   ISapGuiPlugin.openEditorAndStartTransaction(
 *       IProject, String tcode, boolean enableEclipseNavigation, Map, Map)
 * </pre>
 *
 * <p>Method 从公共接口 ISapGuiPlugin 取得（其所在包已导出），避免实现类
 * internal 包访问限制。须在 UI 线程调用（会打开编辑器）。</p>
 */
public final class TcodeExecutor {

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    private static final String BUNDLE_SAPGUI_UI = "com.sap.adt.sapgui.ui";
    private static final String CLS_PLUGIN = "com.sap.adt.sapgui.ui.SapGuiPlugin";
    private static final String CLS_PLUGIN_IFACE = "com.sap.adt.sapgui.ui.ISapGuiPlugin";

    private TcodeExecutor() {
    }

    /** ADT SAP GUI 集成是否可用。 */
    public static boolean isAvailable(IProject project) {
        try {
            Object plugin = sapGuiPlugin();
            if (plugin == null) return false;
            Bundle bundle = Platform.getBundle(BUNDLE_SAPGUI_UI);
            Class<?> iface = bundle.loadClass(CLS_PLUGIN_IFACE);
            Method supported = iface.getMethod("isSapGuiSupported", IProject.class);
            return Boolean.TRUE.equals(supported.invoke(plugin, project));
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 执行事务码（打开嵌入式 SAP GUI 并启动事务）。
     *
     * @throws Exception 执行失败（未登录、GUI 不可用等），message 可直接展示
     */
    public static void execute(IProject project, String tcode) throws Exception {
        Object plugin = sapGuiPlugin();
        if (plugin == null) {
            throw new IllegalStateException("ADT SAP GUI plugin (com.sap.adt.sapgui.ui) not available");
        }
        Bundle bundle = Platform.getBundle(BUNDLE_SAPGUI_UI);
        Class<?> iface = bundle.loadClass(CLS_PLUGIN_IFACE);
        Method open = iface.getMethod("openEditorAndStartTransaction",
                IProject.class, String.class, boolean.class, Map.class, Map.class);
        try {
            open.invoke(plugin, project, tcode, true, null, null);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof Exception ? (Exception) cause : e;
        }
    }

    private static Object sapGuiPlugin() {
        try {
            Bundle bundle = Platform.getBundle(BUNDLE_SAPGUI_UI);
            if (bundle == null) return null;
            Class<?> pluginClass = bundle.loadClass(CLS_PLUGIN);
            return pluginClass.getMethod("getDefault").invoke(null);
        } catch (Throwable t) {
            logWarn("SapGuiPlugin.getDefault() failed: " + t.getMessage());
            return null;
        }
    }

    private static void logWarn(String msg) {
        LOG.log(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                "[TcodeFavorites] " + msg));
    }
}
