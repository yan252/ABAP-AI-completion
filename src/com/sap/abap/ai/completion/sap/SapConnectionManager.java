package com.sap.abap.ai.completion.sap;

import java.util.Properties;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.preferences.AIConfiguration;
import com.sap.abap.ai.completion.preferences.PreferenceConstants;
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
 *       Environment 注册全局 DestinationDataProvider)，则通过
 *       <code>com.sap.mw.jco3.eclipse.Registry.getDestinationDataRegistry()</code>
 *       注册/更新我们的命名目标，完全复用 ADT 的连接管理、不破坏其已有目标；</li>
 *   <li><b>回退</b>：若未安装该插件且 Environment 中尚无 DestinationDataProvider，
 *       则由本插件注册自己的 {@link DestinationDataProvider}。</li>
 * </ol>
 *
 * <p>两种方式下，调用方都统一通过 {@link #getDestination()} 获取目标，
 * 然后 <code>JCoFunction</code> 即可执行 RFC。</p>
 */
public final class SapConnectionManager {

    /** JCo destination 名称(任意合法名, 与 ADT 目标区分)。 */
    public static final String DESTINATION_NAME = "ABAP_AI_COMPLETION";

    /** ADT 集成插件的 Registry 类名(反射使用, 避免硬依赖)。 */
    private static final String ADT_REGISTRY_CLASS =
            "com.sap.mw.jco3.eclipse.Registry";

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
            System.out.println("[SapConnectionManager] 已通过 ADT JCo 插件注册目标: "
                    + DESTINATION_NAME);
            return;
        }

        // 回退: 自行注册 DestinationDataProvider
        if (!Environment.isDestinationDataProviderRegistered()) {
            try {
                Environment.registerDestinationDataProvider(
                        new DefaultDestinationDataProvider());
            } catch (IllegalStateException e) {
                System.err.println("[SapConnectionManager] 注册 DestinationDataProvider 失败: "
                        + e.getMessage());
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
     * <p><b>注意</b>：不能用 {@code Class.forName("com.sap.mw.jco3.eclipse.Registry")}
     * 加载该类。该包由 <code>com.sap.conn.jco.eclipse</code> 导出，而本插件并未
     * Require-Bundle / Import-Package 它，因此从本插件的类加载器按名字查找必然抛出
     * ClassNotFoundException(即使该插件实际已安装)。这里改用
     * {@link Platform#getBundle(String)} 定位 ADT 插件后，再通过其自身类加载器
     * ({@link Bundle#loadClass(String)}) 加载。</p>
     *
     * <p>这同时会触发 ADT JCo 插件的懒激活：其 Activator 会向 JCo Environment
     * 注册全局的 {@link DestinationDataProvider}，随后通过该 Registry 注册的目标
     * 即可被 {@link JCoDestinationManager#getDestination(String)} 解析。</p>
     *
     * @return true 表示该插件存在且注册成功(不再走回退分支)。
     */
    private static boolean tryRegisterViaAdtRegistry(Properties props) {
        try {
            Bundle adtBundle = Platform.getBundle(ADT_JCO_BUNDLE);
            if (adtBundle == null) {
                // ADT 的 JCo 集成插件未安装
                return false;
            }
            Class<?> registryClass = adtBundle.loadClass(ADT_REGISTRY_CLASS);
            Object registry = registryClass.getMethod("getDestinationDataRegistry")
                    .invoke(null);
            if (registry == null) {
                return false;
            }
            registry.getClass().getMethod("register", String.class, Properties.class)
                    .invoke(registry, DESTINATION_NAME, props);
            return true;
        } catch (Throwable t) {
            System.err.println("[SapConnectionManager] ADT Registry 注册目标失败, "
                    + "改用回退策略: " + t.getMessage());
            return false;
        }
    }

    /**
     * 回退用的 DestinationDataProvider：为已知的 {@link #DESTINATION_NAME}
     * 返回偏好中的连接属性, 其余目标返回空属性集。
     */
    private static final class DefaultDestinationDataProvider
            implements DestinationDataProvider {

        @Override
        public Properties getDestinationProperties(String destinationName)
                throws com.sap.conn.jco.ext.DataProviderException {
            if (DESTINATION_NAME.equals(destinationName)) {
                return buildDestinationProperties();
            }
            return new Properties();
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
