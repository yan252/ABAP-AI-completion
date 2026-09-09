package com.sap.abap.ai.completion.sap;

import java.io.File;

import com.sap.abap.ai.completion.preferences.AIConfiguration;

/**
 * JCo 本地(native)库加载辅助类。
 *
 * <p>JCo(Java Connector) 底层依赖 C 编写的 native 库(sapjco3.dll / libsapjco3.so)。
 * Eclipse 启动时若找不到该 native 库会报错
 * (<i>Can't load SAP JCo native library</i> 之类)。通常有两种来源：</p>
 * <ol>
 *   <li>通过 OSGi 方式安装的 JCo 插件(fragment 的 <code>Bundle-NativeCode</code> 声明)，
 *       Eclipse/OSGi 会自动加载 native 库，无需额外处理；</li>
 *   <li>开发者单独放置 sapjco3.jar + native 库时，需要保证 native 库位于系统
 *       <code>java.library.path</code> 中，或在这里显式加载。</li>
 * </ol>
 *
 * <p>此工具类在用户于“SAP 配置”页配置了 JCo Native 库目录时，尝试把该目录加入
 * <code>java.library.path</code> 并显式加载 sapjco3，以满足第 2 种场景；
 * 若目录未配置或加载失败(说明 OSGi 已提供 native)，则静默跳过。</p>
 */
public final class JCoNativeLibrary {

    /** Windows 平台 native 库文件名。 */
    private static final String NATIVE_DLL = "sapjco3.dll";
    /** macOS/Linux 平台 native 库文件名。 */
    private static final String NATIVE_SO = "libsapjco3.so";

    private static boolean loadAttempted = false;

    private JCoNativeLibrary() {
    }

    /**
     * 若用户在配置页指定了 JCo native 库目录，则尝试加载其中的 sapjco3 native 库。
     *
     * <p>该方法可被多次调用，内部保证只尝试一次。</p>
     */
    public static synchronized void loadIfConfigured() {
        if (loadAttempted) {
            return;
        }
        loadAttempted = true;

        String dir = AIConfiguration.getSapNativeLibDirectory();
        if (dir == null || dir.trim().isEmpty()) {
            // 未配置，认为由 OSGi/JCo 内建提供 native 库
            return;
        }

        File libDir = new File(dir);
        if (!libDir.isDirectory()) {
            System.err.println("[JCoNativeLibrary] 配置的 native 库目录不存在: " + libDir.getAbsolutePath());
            return;
        }

        // 1) 将该目录追加到 java.library.path(通过系统属性刷新)
        addToLibraryPath(libDir);

        // 2) 按平台显式加载 native 库
        String libName = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? NATIVE_DLL : NATIVE_SO;
        File libFile = new File(libDir, libName);
        if (!libFile.isFile()) {
            System.err.println("[JCoNativeLibrary] 未找到 " + libName
                    + "，目录: " + libDir.getAbsolutePath());
            return;
        }
        try {
            System.load(libFile.getAbsolutePath());
            System.out.println("[JCoNativeLibrary] 已加载 JCo native 库: " + libFile.getAbsolutePath());
        } catch (UnsatisfiedLinkError e) {
            // 可能已被 OSGi/JCo 以相同路径加载过，或版本不匹配
            System.err.println("[JCoNativeLibrary] 加载 native 库失败(可忽略，若 OSGi 已提供): "
                    + e.getMessage());
        }
    }

    /**
     * 将目录追加到 <code>java.library.path</code> 系统属性，并刷新
     * <code>ClassLoader.sys_paths</code>(通过反射),使 <code>System.loadLibrary</code>
     * 能够搜索到该目录。
     */
    private static void addToLibraryPath(File libDir) {
        try {
            String current = System.getProperty("java.library.path", "");
            String sep = System.getProperty("path.separator", ";");
            if (!current.isEmpty() && !current.contains(libDir.getAbsolutePath())) {
                System.setProperty("java.library.path",
                        current + sep + libDir.getAbsolutePath());
            }
            // 刷新已缓存的系统库路径
            java.lang.reflect.Field sysPaths =
                    ClassLoader.class.getDeclaredField("sys_paths");
            sysPaths.setAccessible(true);
            sysPaths.set(null, null);
        } catch (Throwable ignored) {
            // 反射刷新失败不影响后续 System.load 显式加载
        }
    }
}
