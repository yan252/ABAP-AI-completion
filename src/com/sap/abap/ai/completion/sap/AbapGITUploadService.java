package com.sap.abap.ai.completion.sap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import com.sap.abap.ai.completion.Activator;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoException;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.JCoParameterList;
import com.sap.conn.jco.JCoTable;

/**
 * 基于 JCo 的通用 zip 上传服务。
 *
 * <p>调用 SAP 函数模块 <code>Z_ABAPGIT_UPLOAD_FROM_XSTRING</code>，将插件
 * <code>references/</code> 下打包的 zip(如 abapGIT 离线仓库)上传到 SAP 系统：
 * <ul>
 *   <li>IV_ZIP_DATA :  zip 的原始字节(XSTRING)</li>
 *   <li>IV_PACKAGE  :  目标开发包(必填)</li>
 *   <li>IV_REPO_NAME:  仓库名称(必填)</li>
 * </ul>
 * 返回 EV_SUCCESS / EV_MESSAGE。
 * </p>
 */
public final class AbapGITUploadService {

    /** 上传用 RFC 函数模块名称。 */
    public static final String RFC_FUNCTION = "Z_ABAPGIT_UPLOAD_FROM_XSTRING";

    private static final String P_IV_ZIP_DATA = "IV_ZIP_DATA";
    private static final String P_IV_PACKAGE = "IV_PACKAGE";
    private static final String P_IV_REPO_NAME = "IV_REPO_NAME";
    private static final String P_EV_SUCCESS = "EV_SUCCESS";
    private static final String P_EV_MESSAGE = "EV_MESSAGE";

    private AbapGITUploadService() {
    }

    /**
     * 判断指定函数模块是否为"远程启用"(RFC-enabled)。
     *
     * <p>通过标准 RFC {@code RFC_READ_TABLE} 查询表 {@code ENLFDIR}(函数模块目录)
     * 的 {@code FMODE} 字段: {@code 'R'} 表示远程启用, 其余(普通函数模块等)则不能
     * 被 JCo/RFC 调用(调用时报
     * "Function module ... cannot be used for remote calls")。</p>
     *
     * @param destination  已连通的 JCo 目标
     * @param functionName 函数模块名(大写)
     * @return {@code Boolean.TRUE}=远程启用; {@code Boolean.FALSE}=确认未启用;
     *         {@code null}=无法判断(如 RFC_READ_TABLE 不可用/无权限), 调用方应放行
     *         让实际 RFC 调用来报错。
     */
    public static Boolean isFunctionRemoteEnabled(JCoDestination destination,
                                                  String functionName) {
        try {
            JCoFunction fm = destination.getRepository().getFunction("RFC_READ_TABLE");
            if (fm == null) {
                return null; // 系统中无 RFC_READ_TABLE, 无法探测
            }
            JCoParameterList importing = fm.getImportParameterList();
            importing.setValue("QUERY_TABLE", "ENLFDIR");
            importing.setValue("DELIMITER", ",");

            JCoTable fields = fm.getTableParameterList().getTable("FIELDS");
            fields.appendRow();
            fields.setValue("FIELDNAME", "FMODE");

            // 函数名必须大写; ENLFDIR 中按大写存储
            JCoTable options = fm.getTableParameterList().getTable("OPTIONS");
            options.appendRow();
            options.setValue("TEXT",
                    "FUNCNAME = '" + functionName.toUpperCase() + "'");

            fm.execute(destination);

            JCoTable data = fm.getTableParameterList().getTable("DATA");
            if (data == null || data.getNumRows() == 0) {
                logInfo("RFC_READ_TABLE ENLFDIR 未查到 " + functionName
                        + " 的记录(FMODE 未知), 跳过远程启用预检。");
                return Boolean.FALSE; // 表中无记录(函数不存在或异常)
            }
            String wa = data.getString("WA");
            boolean remote = wa != null && wa.trim().startsWith("R");
            logInfo("远程启用预检 " + functionName + ": ENLFDIR-FMODE='"
                    + (wa == null ? "<null>" : wa.trim()) + "' => "
                    + (remote ? "远程启用" : "非远程启用"));
            return remote;
        } catch (Throwable t) {
            // RFC_READ_TABLE 被禁用/无权限等: 无法判断, 不阻断流程, 但记录日志
            logInfo("RFC_READ_TABLE 预检不可用(跳过远程启用检查): "
                    + t.getClass().getSimpleName() + " " + t.getMessage());
            return null;
        }
    }

    private static void logInfo(String message) {
        System.out.println("[AbapGITUploadService] " + message);
        try {
            if (Activator.getDefault() != null) {
                Activator.getDefault().getLog().log(new org.eclipse.core.runtime.Status(
                        org.eclipse.core.runtime.IStatus.INFO, Activator.PLUGIN_ID,
                        "[AbapGITUploadService] " + message, null));
            }
        } catch (Throwable ignored) {
            // 日志失败不影响主流程
        }
    }

    /**
     * 判断异常是否为"函数模块未远程启用"错误。
     *
     * <p>SAP 对非 RFC-enabled 函数发起远程调用时，按登录语言返回的消息为：</p>
     * <ul>
     *   <li>英文: {@code Function module "..." cannot be used for remote calls.}</li>
     *   <li>中文: {@code 函数模块 "..." 不能用于远程调用。}</li>
     * </ul>
     *
     * <p>实际消息可能夹带引号/撇号等标点(如 {@code remote'calls} 转写)，
     * 因此先去除所有非字母字符再匹配，避免标点把关键词切断。</p>
     */
    public static boolean isNotRemoteEnabledError(Throwable t) {
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null) {
                String lower = msg.toLowerCase();
                // 仅保留小写字母(ASCII), 消除引号/撇号/换行/空格等干扰
                String lettersOnly = lower.replaceAll("[^a-z]", "");
                if (lettersOnly.contains("remotecall")
                        || lettersOnly.contains("remotecalls")
                        || lettersOnly.contains("cannotbeusedforremote")) {
                    return true;
                }
                // 中文登录语言: "不能用于远程调用" / "远程调用" 类表述
                if (msg.contains("远程调用")
                        || msg.contains("不能用于远程")
                        || msg.contains("远程") && msg.contains("调用")) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }

    /**
     * "函数模块未远程启用"的标准修复指引(用于界面提示)。
     */
    public static String remoteEnabledFixHint() {
        return "The function module exists but is not marked as [Remote-Enabled], "
                + "so it cannot be called via JCo/RFC.\n\n"
                + "Please do the following in the SAP system:\n"
                + "  1. Run transaction SE37, enter function module " + RFC_FUNCTION + ", choose [Change];\n"
                + "  2. Open the [Attributes] tab (or menu Goto -> Attributes);\n"
                + "  3. Under [Processing Type], select [Remote-Enabled Module];\n"
                + "  4. Go back to the [Import]/[Export] parameter tabs and make sure the\n"
                + "     [Pass Value] checkbox is ticked for the IV_*/EV_* parameters\n"
                + "     (remote functions require pass-by-value);\n"
                + "  5. Save (you may save to the local package $TMP or a development package)\n"
                + "     and [Activate] (Ctrl+F3).\n\n"
                + "After that, run the menu upload again.";
    }

    /**
     * 上传 {@code zipResourcePath}(插件 bundle 内资源路径, 如
     * "references/ZTEMPLATE10/supplier-delivery-main.zip")到 SAP 系统。
     *
     * @param zipResourcePath bundle 内的 zip 资源路径
     * @param ivPackage       目标开发包(如 $TMP 或 ZDEV 包)
     * @param ivRepoName      目标仓库名称(如 OFFLINE_REPO)
     * @return 上传结果(success + message)
     * @throws JCoException    JCo 调用失败(连接、native 库、RFC 不存在等)
     * @throws IOException     zip 资源读取失败
     */
    public static UploadResult uploadResource(String zipResourcePath,
                                              String ivPackage,
                                              String ivRepoName)
            throws JCoException, IOException {
        byte[] zipData = readBundleResource(zipResourcePath);
        return upload(zipData, ivPackage, ivRepoName);
    }

    /**
     * 读取插件 bundle 内资源为字节数组(公共入口)。
     *
     * @param resourcePath bundle 内资源路径(如 "references/.../x.zip")
     * @return 资源字节
     * @throws IOException 资源不存在或不可读
     */
    public static byte[] readResourceBytes(String resourcePath) throws IOException {
        return readBundleResource(resourcePath);
    }

    /**
     * 模板占位符(对象名)。简单查询模板中主程序/相关对象以此命名，
     * 实例化时替换为用户输入的新程序名。文件名中为小写，代码/XML 中常见大写。
     */
    public static final String TEMPLATE_PLACEHOLDER_LOWER = "ztemplate10";
    public static final String TEMPLATE_PLACEHOLDER_UPPER = "ZTEMPLATE10";

    /** abapGit 离线仓库中文本类文件的扩展名(小写, 不含点)。二进制(如 .ui5<hash>)不在此列。 */
    private static final Set<String> TEXT_EXTENSIONS = new HashSet<>(Arrays.asList(
            "abap", "xml", "ddls", "asddls", "ddlx", "asddlxs", "bdef", "asbdef",
            "srvd", "srvdsrv", "dtel", "doma", "tabl", "nrob", "clas", "intf",
            "prog", "sicf", "sush", "smim", "g4ba", "srvb", "iwsv", "iwmo",
            "iwvb", "devc", "baseinfo", "json", "js", "map", "properties",
            "html", "md", "txt", "css", "service"));

    /**
     * 将模板 zip 实例化为一个以 {@code newProgramName} 命名的新离线仓库 zip。
     *
     * <p>占位符(主程序名)会从 zip 内部<b>自动检测</b>：找到带 include 的主程序
     * (形如 <code>&lt;base&gt;.prog.abap</code> 与 <code>&lt;base&gt;_top.prog.abap</code>
     * 等 include)，以其 <code>&lt;base&gt;</code> 作为待替换的对象名。这样不同模板
     * (如简单查询 / 多标签查询，主程序名不同)无需改代码即可正确改名。检测不到时
     * 回退到内置占位符 {@link #TEMPLATE_PLACEHOLDER_LOWER}。</p>
     *
     * <p>处理规则：</p>
     * <ul>
     *   <li><b>文件名(条目路径)</b>：占位符(忽略大小写)替换为新程序名的<b>小写</b>形式；
     *       因而 <code>&lt;base&gt;_top</code> 等 include 一并改名；</li>
     *   <li><b>文本文件内容</b>：大写占位符→新名大写、小写→新名小写，保证交叉引用一致；</li>
     *   <li>二进制文件原样拷贝。</li>
     * </ul>
     *
     * @param templateZip    原始模板 zip 字节
     * @param newProgramName 新程序名(调用方保证为大写、Z/Y 开头)
     * @return 实例化结果(含新 zip 字节、检测到的占位符、改名文件/内容计数)
     * @throws IOException 解压/重打包失败
     */
    public static TemplateInstantiationResult instantiateTemplate(byte[] templateZip,
                                                                  String newProgramName)
            throws IOException {
        if (templateZip == null || templateZip.length == 0) {
            throw new IOException("template zip is empty");
        }
        if (newProgramName == null || newProgramName.trim().isEmpty()) {
            throw new IOException("new program name is empty");
        }
        final String newUpper = newProgramName.trim().toUpperCase(Locale.ROOT);
        final String newLower = newUpper.toLowerCase(Locale.ROOT);

        // Pass 1: read all entries into memory
        List<ZipEntryData> entries = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(
                new java.io.ByteArrayInputStream(templateZip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zis.getNextEntry()) != null) {
                ByteArrayOutputStream co = new ByteArrayOutputStream();
                int n;
                while ((n = zis.read(buf)) > 0) {
                    co.write(buf, 0, n);
                }
                entries.add(new ZipEntryData(e.getName(), e.isDirectory(),
                        e.getTime(), co.toByteArray()));
                zis.closeEntry();
            }
        }

        // Detect the placeholder (main program base name) from entry names
        String placeholderLower = detectPlaceholderLower(entries);
        String placeholderUpper = placeholderLower.toUpperCase(Locale.ROOT);

        int renamedFiles = 0;
        int renamedContent = 0;

        // Pass 2: rename + rewrite
        ByteArrayOutputStream bos = new ByteArrayOutputStream(templateZip.length * 2);
        try (ZipOutputStream zos = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (ZipEntryData ed : entries) {
                String origName = ed.name;

                // 1) file name: placeholder -> new program name (lowercase)
                String newName = replaceToken(origName, placeholderLower, newLower);
                if (!newName.equals(origName)) {
                    renamedFiles++;
                }

                byte[] data = ed.data;
                // 2) text file content: upper->upper, lower->lower
                if (!ed.directory && isTextEntry(origName)) {
                    String text = new String(data, StandardCharsets.UTF_8);
                    if (text.contains(placeholderUpper) || text.contains(placeholderLower)) {
                        text = text.replace(placeholderUpper, newUpper)
                                   .replace(placeholderLower, newLower);
                        data = text.getBytes(StandardCharsets.UTF_8);
                        renamedContent++;
                    }
                }

                ZipEntry out = new ZipEntry(newName);
                if (ed.time >= 0) {
                    out.setTime(ed.time);
                }
                zos.putNextEntry(out);
                if (!ed.directory) {
                    zos.write(data);
                }
                zos.closeEntry();
            }
        }

        boolean renamed = renamedFiles > 0 || renamedContent > 0;
        logInfo("Template instantiation: placeholder=" + placeholderUpper
                + " -> " + newUpper + ", renamed files=" + renamedFiles
                + ", renamed text contents=" + renamedContent
                + (renamed ? "" : "  [WARNING: no placeholder found, zip unchanged!]"));
        return new TemplateInstantiationResult(bos.toByteArray(), placeholderUpper,
                newUpper, renamedFiles, renamedContent, renamed);
    }

    /**
     * 从 zip 条目名中自动检测待替换的主程序占位符(小写)。
     *
     * <p>规则：abapGit 中主程序文件为 <code>&lt;base&gt;.prog.abap</code>，
     * 其 include 为 <code>&lt;base&gt;_&lt;suffix&gt;.prog.abap</code>。
     * 因此“带 include 的主程序”即存在其它条目名以 <code>&lt;base&gt;_</code>
     * 开头的 &lt;base&gt;。独立辅助程序(无 include)不会被选中。</p>
     */
    private static String detectPlaceholderLower(List<ZipEntryData> entries) {
        List<String> progStems = new ArrayList<>();
        for (ZipEntryData ed : entries) {
            if (ed.directory) {
                continue;
            }
            String fn = baseName(ed.name).toLowerCase(Locale.ROOT);
            if (fn.endsWith(".prog.abap")) {
                progStems.add(fn.substring(0, fn.length() - ".prog.abap".length()));
            }
        }
        // A main program = a stem for which some OTHER stem starts with "<stem>_".
        for (String candidate : progStems) {
            for (String other : progStems) {
                if (!other.equals(candidate) && other.startsWith(candidate + "_")) {
                    return candidate;
                }
            }
        }
        // Fallback 1: built-in constant present in file names
        for (ZipEntryData ed : entries) {
            if (baseName(ed.name).toLowerCase(Locale.ROOT).contains(TEMPLATE_PLACEHOLDER_LOWER)) {
                return TEMPLATE_PLACEHOLDER_LOWER;
            }
        }
        // Fallback 2: exactly one program -> use it; otherwise built-in constant
        if (progStems.size() == 1) {
            return progStems.get(0);
        }
        return TEMPLATE_PLACEHOLDER_LOWER;
    }

    /** 取条目路径中的文件名部分(去掉目录)。 */
    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return path.substring(slash + 1);
    }

    /** zip 条目内存表示。 */
    private static final class ZipEntryData {
        final String name;
        final boolean directory;
        final long time;
        final byte[] data;

        ZipEntryData(String name, boolean directory, long time, byte[] data) {
            this.name = name;
            this.directory = directory;
            this.time = time;
            this.data = data;
        }
    }

    /**
     * 不区分大小写地把字符串中的占位符替换为 {@code replacement}。
     * 用于文件名(abapGit 文件名固定小写，这里统一输出小写替换值)。
     */
    private static String replaceToken(String source, String token, String replacement) {
        if (source == null || source.isEmpty()) {
            return source;
        }
        StringBuilder sb = new StringBuilder(source.length());
        int len = token.length();
        int idx = 0;
        while (idx <= source.length() - len) {
            if (source.regionMatches(true, idx, token, 0, len)) {
                sb.append(replacement);
                idx += len;
            } else {
                sb.append(source.charAt(idx));
                idx++;
            }
        }
        sb.append(source.substring(idx));
        return sb.toString();
    }

    /** 根据条目扩展名判断是否为可做文本替换的文件。 */
    private static boolean isTextEntry(String entryName) {
        if (entryName == null || entryName.endsWith("/")) {
            return false;
        }
        int slash = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
        String fileName = entryName.substring(slash + 1);
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false; // 无扩展名 -> 视为二进制(如 .ui5<hash> MIME 对象)
        }
        String ext = fileName.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return TEXT_EXTENSIONS.contains(ext);
    }

    /**
     * 上传 zip 字节到 SAP 系统(核心调用)。
     *
     * @param zipData     zip 原始字节(XSTRING)
     * @param ivPackage   目标开发包(必填)
     * @param ivRepoName  目标仓库名称(必填)
     * @return 上传结果
     * @throws JCoException RFC 执行失败
     */
    public static UploadResult upload(byte[] zipData,
                                      String ivPackage,
                                      String ivRepoName)
            throws JCoException {
        if (zipData == null || zipData.length == 0) {
            throw new JCoException(JCoException.JCO_ERROR_INTERNAL, "zip data is empty");
        }

        JCoDestination destination = SapConnectionManager.getDestination();
        JCoFunction function = destination.getRepository()
                .getFunction(RFC_FUNCTION);

        JCoParameterList importing = function.getImportParameterList();
        importing.setValue(P_IV_ZIP_DATA, zipData);
        importing.setValue(P_IV_PACKAGE, ivPackage);
        importing.setValue(P_IV_REPO_NAME, ivRepoName);

        function.execute(destination);

        JCoParameterList exporting = function.getExportParameterList();
        boolean success = "X".equalsIgnoreCase(exporting.getString(P_EV_SUCCESS));
        String message = exporting.getString(P_EV_MESSAGE);
        if (message == null) {
            message = "";
        }
        return new UploadResult(success, message);
    }

    /**
     * 从插件 bundle 读取资源为字节数组。
     */
    private static byte[] readBundleResource(String resourcePath) throws IOException {
        if (Activator.getDefault() == null) {
            throw new IOException("Plugin is not initialized yet, cannot read resource: " + resourcePath);
        }
        URL url = Activator.getDefault().getBundle().getEntry(resourcePath);
        if (url == null) {
            throw new IOException("Resource not found in bundle: " + resourcePath);
        }
        try (InputStream in = url.openStream()) {
            if (in == null) {
                throw new IOException("Resource is not readable: " + resourcePath);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    /**
     * RFC 上传结果。
     */
    public static final class UploadResult {
        private final boolean success;
        private final String message;

        UploadResult(boolean success, String message) {
            this.success = success;
            this.message = message == null ? "" : message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * 模板实例化结果：新 zip 字节 + 改名情况。
     */
    public static final class TemplateInstantiationResult {
        private final byte[] zipData;
        private final String placeholder;
        private final String newProgramName;
        private final int renamedFiles;
        private final int renamedContent;
        private final boolean renamed;

        TemplateInstantiationResult(byte[] zipData, String placeholder, String newProgramName,
                                    int renamedFiles, int renamedContent, boolean renamed) {
            this.zipData = zipData;
            this.placeholder = placeholder;
            this.newProgramName = newProgramName;
            this.renamedFiles = renamedFiles;
            this.renamedContent = renamedContent;
            this.renamed = renamed;
        }

        /** 实例化(重命名)后的新 zip 字节。 */
        public byte[] getZipData() {
            return zipData;
        }

        /** 检测到的占位符(主程序原名, 大写)。 */
        public String getPlaceholder() {
            return placeholder;
        }

        /** 新程序名(大写)。 */
        public String getNewProgramName() {
            return newProgramName;
        }

        /** 文件名被改名的条目数。 */
        public int getRenamedFiles() {
            return renamedFiles;
        }

        /** 内容被替换的文本文件数。 */
        public int getRenamedContent() {
            return renamedContent;
        }

        /** 是否确实发生了改名(为 false 说明 zip 中未找到占位符, 内容原样上传)。 */
        public boolean isRenamed() {
            return renamed;
        }
    }
}
