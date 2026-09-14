package com.sap.abap.ai.completion.sap;

import java.util.Properties;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.preferences.AIConfiguration;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoDestinationManager;
import com.sap.conn.jco.JCoException;
import com.sap.conn.jco.ext.DestinationDataEventListener;
import com.sap.conn.jco.ext.DestinationDataProvider;
import com.sap.conn.jco.ext.Environment;

/**
 * SAP 连接(JCo 目标)管理。
 *
 * <p>SAP 连接信息(应用服务器、系统号、Client、用户、密码等)来自“ABAP AI Completion →
 * SAP 配置”偏好页，本类负责将配置组装成 JCo destination 并注册，供 RFC 调用使用。</p>
 *
 * <p><b>目标注册策略</b>(两段式，避免与 Eclipse/ADT 内置 JCo 冲突)：</p>
 * <ol>
 *   <li><b>首选</b>：若 Eclipse 中安装了 ADT 集成插件
 *       <code>com.sap.conn.jco.eclipse</code>(其 <code>Registry</code> 已向 JCo
 *       Environment 注册全局 DestinationDataProvider)，则<b>先显式激活该插件</b>，
 *       再通过 <code>com.sap.mw.jco3.eclipse.Registry.getDestinationDataRegistry()</code>
 *       注册/更新我们的命名目标，注册后还会校验目标确实可被 JCo 解析；</li>
 *   <li><b>回退</b>：若未安装该插件、或注册后校验失败，且 Environment 中尚无
 *       DestinationDataProvider，则由本插件注册自己的 {@link DestinationDataProvider}。</li>
 * </ol>
 *
 * <p>关键诊断会同时写入 Eclipse Error Log(Window → Show View → Error Log)，
 * 便于排查“Destination ... does not exist”一类问题。</p>
 */
public final class SapConnectionManager {

    /** JCo destination 名称(任意合法名, 与 ADT 目标区分)。 */
    public static final String DESTINATION_NAME = "ABAP_AI_COMPLETION";

    /** ADT 集成插件的 Registry 门面类名(反射使用, 避免硬依赖)。 */
    private static final String ADT_REGISTRY_CLASS =
            "com.sap.mw.jco3.eclipse.Registry";

    /**
     * ADT 目标数据注册表的<b>公共接口</b>类名(反射使用)。
     *
     * <p>反射调用 register/isRegistered 时必须从该接口获取 Method 对象,
     * <b>不能</b>用 {@code registry.getClass()}(其运行时类型是包级私有的
     * {@code com.sap.mw.jco3.eclipse.internal.DestinationDataRegistry},
     * 所在 internal 包未被 ADT 插件导出, 对其方法反射 invoke 会抛
     * IllegalAccessException)。详见 {@link #tryRegisterViaAdtRegistry}。</p>
     */
    private static final String ADT_REGISTRY_IFACE =
            "com.sap.mw.jco3.eclipse.IDestinationDataRegistry";

    /** ADT 的 JCo 集成插件 Bundle SymbolicName(该插件随 ADT 一起安装)。 */
    private static final String ADT_JCO_BUNDLE = "com.sap.conn.jco.eclipse";

    private static boolean ensureAttempted = false;

    private SapConnectionManager() {
    }

    /**
     * 确保 JCo destination 已注册(幂等, 内部只执行一次)。
     * 若配置在运行期被修改, 请调用 {@link #refreshDestination()}。
     */
    public static synchronized void ensureDestinationRegistered() {
        if (ensureAttempted) {
            return;
        }
        ensureAttempted = true;
        refreshDestination();
    }

    /**
     * 基于当前偏好配置(重新)注册 JCo destination。
     * 注册完成后 JCo 会通过 {@link JCoDestinationManager#getDestination(String)}
     * 动态取回连接属性, 因此配置变更后调用一次本方法即可生效。
     */
    public static synchronized void refreshDestination() {
        // 可选: 手动加载 native 库(适用于 JCo 非 OSGi fragment 安装的场景)
        JCoNativeLibrary.loadIfConfigured();

        Properties props = buildDestinationProperties();

        // 首选: 通过 ADT 集成 Registry 注册/更新
        if (tryRegisterViaAdtRegistry(props)) {
            logInfo("已通过 ADT JCo 插件注册目标: " + DESTINATION_NAME);
            return;
        }

        // 回退: 自行注册 DestinationDataProvider
        if (Environment.isDestinationDataProviderRegistered()) {
            // JCo 全局只允许注册一个 DestinationDataProvider。此时已存在提供程序
            // (通常是 ADT 的), 但通过 ADT Registry 注册未成功/未通过校验, 我们无法
            // 替换它。记录显著诊断, 便于在 Error Log 中定位。
            logError("JCo Environment 中已存在其他 DestinationDataProvider, "
                    + "无法注册内置提供程序。目标 " + DESTINATION_NAME
                    + " 可能无法解析。诊断信息: " + getDiagnostics(), null);
        } else {
            try {
                Environment.registerDestinationDataProvider(new DefaultDestinationDataProvider());
                logInfo("已注册内置 DestinationDataProvider(回退策略), 目标: "
                        + DESTINATION_NAME);
            } catch (IllegalStateException e) {
                logError("注册内置 DestinationDataProvider 失败: " + e, e);
            }
        }
    }

    /**
     * 返回可用于执行 RFC 的 JCo 目标。
     *
     * @throws JCoException 目标注册/获取失败时抛出(常见于未配置 SAP 连接或
     *                      JCo native 库缺失)。
     */
    public static JCoDestination getDestination() throws JCoException {
        ensureDestinationRegistered();
        return JCoDestinationManager.getDestination(DESTINATION_NAME);
    }

    /**
     * 根据偏好配置组装 JCo destination 属性。
     */
    static Properties buildDestinationProperties() {
        Properties props = new Properties();
        props.setProperty(DestinationDataProvider.JCO_ASHOST,
                nullToEmpty(AIConfiguration.getSapHost()));
        props.setProperty(DestinationDataProvider.JCO_SYSNR,
                nullToEmpty(AIConfiguration.getSapSystemNumber()));
        props.setProperty(DestinationDataProvider.JCO_CLIENT,
                nullToEmpty(AIConfiguration.getSapClient()));
        props.setProperty(DestinationDataProvider.JCO_LANG,
                nullToEmpty(AIConfiguration.getSapLanguage()));
        props.setProperty(DestinationDataProvider.JCO_USER,
                nullToEmpty(AIConfiguration.getSapUser()));
        props.setProperty(DestinationDataProvider.JCO_PASSWD,
                nullToEmpty(AIConfiguration.getSapPassword()));
        // 连接池参数(默认即可, 不强制要求)
        props.setProperty(DestinationDataProvider.JCO_POOL_CAPACITY, "3");
        props.setProperty(DestinationDataProvider.JCO_PEAK_LIMIT, "10");
        return props;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * 尝试通过 ADT 集成插件(com.sap.conn.jco.eclipse) 注册目标。
     *
     * <p><b>注意 1(类加载)</b>：不能用 {@code Class.forName("com.sap.mw.jco3.eclipse.Registry")}
     * 加载该类。该包由 <code>com.sap.conn.jco.eclipse</code> 导出，而本插件并未
     * Require-Bundle / Import-Package 它，因此从本插件的类加载器按名字查找必然抛出
     * ClassNotFoundException(即使该插件实际已安装)。这里改用
     * {@link Platform#getBundle(String)} 定位 ADT 插件后，再通过其自身类加载器
     * ({@link Bundle#loadClass(String)}) 加载。</p>
     *
     * <p><b>注意 2(懒激活)</b>：ADT 插件声明了
     * <code>Bundle-ActivationPolicy: lazy</code>。若插件仅处于 RESOLVED 状态
     * (从未被 start)，{@code loadClass} 不会触发其 Activator 执行，JCo Environment
     * 中也就没有 ADT 的全局 DestinationDataProvider，随后
     * {@code getDestinationDataRegistry()} 会抛
     * {@code IllegalStateException("Plug-in 'com.sap.conn.jco.eclipse' is not
     * initialized")}。因此这里先显式 {@code bundle.start(START_ACTIVATION_POLICY)}
     * 使其进入 STARTING 状态，后续 loadClass 即会同步完成激活。</p>
     *
     * <p><b>注意 3(注册校验)</b>：register 反射调用成功不代表 JCo 一定能解析目标
     * (例如插件被 OSGi refresh 后存在新旧两个 classloader/两个 provider 实例)。
     * 注册后通过 {@code isRegistered(String)} 与
     * {@link Environment#isDestinationDataProviderRegistered()} 双重校验，
     * 任一不通过则返回 false 走回退分支。</p>
     *
     * @return true 表示该插件存在、注册成功且校验通过(不再走回退分支)。
     */
    private static boolean tryRegisterViaAdtRegistry(Properties props) {
        try {
            Bundle adtBundle = Platform.getBundle(ADT_JCO_BUNDLE);
            if (adtBundle == null) {
                // ADT 的 JCo 集成插件未安装
                logInfo("未检测到 ADT JCo 插件(" + ADT_JCO_BUNDLE
                        + "), 将使用内置 DestinationDataProvider。");
                return false;
            }

            // 显式激活懒加载的 ADT 插件(已激活时 start() 为空操作)
            if (adtBundle.getState() != Bundle.ACTIVE) {
                try {
                    adtBundle.start(Bundle.START_ACTIVATION_POLICY);
                    logInfo("已请求启动 ADT JCo 插件, 当前状态: "
                            + bundleState(adtBundle.getState()));
                } catch (BundleException e) {
                    logError("显式启动 ADT JCo 插件失败: " + e, e);
                }
            }

            Class<?> registryClass = adtBundle.loadClass(ADT_REGISTRY_CLASS);
            Object registry = registryClass.getMethod("getDestinationDataRegistry")
                    .invoke(null);
            if (registry == null) {
                logError("ADT Registry.getDestinationDataRegistry() 返回 null", null);
                return false;
            }

            // 关键: 必须从【公共接口】IDestinationDataRegistry 获取 Method。
            // getDestinationDataRegistry() 返回对象的运行时类型是包级私有的
            // internal.DestinationDataRegistry(final class, internal 包未导出),
            // 若用 registry.getClass().getMethod(...).invoke(...) 反射调用,
            // 访问检查会因调用方不可访问该 internal 实现类而抛
            // IllegalAccessException(...cannot access a member...with modifiers
            // "public synchronized")。从公共接口取 Method 后, 访问检查基于
            // 接口(调用方可访问), 动态分派照常到达实现类。
            Class<?> registryIface = adtBundle.loadClass(ADT_REGISTRY_IFACE);
            registryIface.getMethod("register", String.class, Properties.class)
                    .invoke(registry, DESTINATION_NAME, props);

            // 注册后校验: 目标确实存在于 ADT 注册表, 且 JCo Environment 中已有 provider
            Boolean registered = (Boolean) registryIface
                    .getMethod("isRegistered", String.class)
                    .invoke(registry, DESTINATION_NAME);
            boolean providerInEnv = Environment.isDestinationDataProviderRegistered();
            logInfo("ADT Registry 注册结果: isRegistered=" + registered
                    + ", env.providerRegistered=" + providerInEnv
                    + ", adtBundleState=" + bundleState(adtBundle.getState()));

            if (Boolean.TRUE.equals(registered) && providerInEnv) {
                return true;
            }
            logError("ADT Registry 注册后校验未通过(isRegistered=" + registered
                    + ", env.providerRegistered=" + providerInEnv
                    + "), 改用回退策略。", null);
            return false;
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            logError("ADT Registry 注册目标失败, 改用回退策略: " + cause, cause);
            return false;
        }
    }

    /**
     * 返回当前 JCo 目标注册链路的诊断摘要(用于界面提示与 Error Log)。
     * 本方法只读、无副作用(不激活插件、不注册任何内容)。
     */
    public static String getDiagnostics() {
        StringBuilder sb = new StringBuilder();
        Bundle adtBundle = Platform.getBundle(ADT_JCO_BUNDLE);
        sb.append("ADT JCo plugin: ");
        if (adtBundle == null) {
            sb.append("not installed");
        } else {
            sb.append("installed, state=").append(bundleState(adtBundle.getState()));
            try {
                Object registry = adtBundle.loadClass(ADT_REGISTRY_CLASS)
                        .getMethod("getDestinationDataRegistry").invoke(null);
                // 同样必须从公共接口取 Method(见 tryRegisterViaAdtRegistry 说明)
                Class<?> registryIface = adtBundle.loadClass(ADT_REGISTRY_IFACE);
                Object registered = registryIface
                        .getMethod("isRegistered", String.class)
                        .invoke(registry, DESTINATION_NAME);
                sb.append(", destination [").append(DESTINATION_NAME).append("] registered=")
                        .append(registered);
            } catch (Throwable t) {
                sb.append(", Registry unavailable: ").append(rootMessage(t));
            }
        }
        sb.append("; JCo global DestinationDataProvider registered: ")
                .append(Environment.isDestinationDataProviderRegistered());
        return sb.toString();
    }

    /** 将 OSGi Bundle 状态码转为可读名称。 */
    private static String bundleState(int state) {
        switch (state) {
            case Bundle.UNINSTALLED: return "UNINSTALLED";
            case Bundle.INSTALLED: return "INSTALLED";
            case Bundle.RESOLVED: return "RESOLVED";
            case Bundle.STARTING: return "STARTING";
            case Bundle.STOPPING: return "STOPPING";
            case Bundle.ACTIVE: return "ACTIVE";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    /** 解包反射调用的根因消息。 */
    private static String rootMessage(Throwable t) {
        Throwable cause = t.getCause() != null ? t.getCause() : t;
        String msg = cause.getMessage();
        return cause.getClass().getSimpleName()
                + (msg != null ? ": " + msg : "");
    }

    // ==================== Logging ====================

    private static void logInfo(String message) {
        log(IStatus.INFO, message, null);
    }

    private static void logError(String message, Throwable t) {
        log(IStatus.ERROR, message, t);
    }

    /**
     * 同时输出到标准输出(控制台启动时可见)与 Eclipse Error Log
     * (Window → Show View → Error Log)。
     */
    private static void log(int severity, String message, Throwable t) {
        String text = "[SapConnectionManager] " + message;
        if (severity == IStatus.ERROR) {
            System.err.println(text);
        } else {
            System.out.println(text);
        }
        try {
            Activator activator = Activator.getDefault();
            if (activator != null) {
                activator.getLog().log(
                        new Status(severity, Activator.PLUGIN_ID, text, t));
            }
        } catch (Throwable ignored) {
            // 日志失败不影响主流程
        }
    }

    /**
     * 回退用的 DestinationDataProvider：为已知的 {@link #DESTINATION_NAME}
     * 返回偏好中的连接属性, 其余目标返回 null(让 JCo 报标准的 does not exist)。
     */
    private static final class DefaultDestinationDataProvider
            implements DestinationDataProvider {

        @Override
        public Properties getDestinationProperties(String destinationName)
                throws com.sap.conn.jco.ext.DataProviderException {
            if (DESTINATION_NAME.equals(destinationName)) {
                return buildDestinationProperties();
            }
            return null;
        }

        @Override
        public boolean supportsEvents() {
            return false;
        }

        @Override
        public void setDestinationDataEventListener(
                DestinationDataEventListener listener) {
            // 事件式更新非必需(每次调用前都从偏好读取)
        }
    }
}
