package com.sap.abap.ai.completion.tcode;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoDestinationManager;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.JCoTable;

/**
 * 通过 RFC 从 SAP 后端读取事务码文本（表 TSTCT）。
 *
 * <p>JCo destination 复用 ADT 项目的连接（ADT 已把项目目标注册到全局
 * DestinationDataProvider），通过反射调用
 * {@code com.sap.adt.project.AdtCoreProjectServiceFactory#createCoreProjectService()
 * .getDestinationId(IProject)} 取得目标名，保持本插件"零 ADT 编译期依赖"。</p>
 *
 * <p>查询语句：RFC_READ_TABLE(QUERY_TABLE='TSTCT', FIELDS=TCODE/SPRSL/TTEXT,
 * OPTIONS="TCODE = '&lt;tcode&gt;'")，再按登录语言（回退英语、首行）挑选文本。</p>
 */
public final class TcodeTextService {

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    private static final String BUNDLE_ADT_PROJECT = "com.sap.adt.project";
    private static final String CLS_FACTORY = "com.sap.adt.project.AdtCoreProjectServiceFactory";
    private static final String CLS_SERVICE = "com.sap.adt.project.IAdtCoreProjectService";

    /** ISO 语言码 -> SAP 单字节语言码（SPRSL）。 */
    private static final Map<String, String> ISO_TO_SAP_LANG = new HashMap<>();
    static {
        ISO_TO_SAP_LANG.put("EN", "E");
        ISO_TO_SAP_LANG.put("DE", "D");
        ISO_TO_SAP_LANG.put("ZH", "1");
        ISO_TO_SAP_LANG.put("JA", "J");
        ISO_TO_SAP_LANG.put("FR", "F");
        ISO_TO_SAP_LANG.put("IT", "I");
        ISO_TO_SAP_LANG.put("ES", "S");
        ISO_TO_SAP_LANG.put("PT", "P");
        ISO_TO_SAP_LANG.put("RU", "R");
        ISO_TO_SAP_LANG.put("KO", "3");
        ISO_TO_SAP_LANG.put("PL", "L");
        ISO_TO_SAP_LANG.put("NL", "N");
        ISO_TO_SAP_LANG.put("TR", "T");
        ISO_TO_SAP_LANG.put("CS", "C");
        ISO_TO_SAP_LANG.put("HU", "H");
    }

    /** 查询结果。 */
    public static final class FetchResult {
        public enum Status {
            /** 查询成功，text 为事务文本（可能为空字符串）。 */
            OK,
            /** 查询成功但系统中不存在该事务码。 */
            NOT_FOUND,
            /** 查询本身失败（连接、权限等），error 含原因。 */
            ERROR
        }

        public final Status status;
        public final String text;
        public final String error;

        private FetchResult(Status status, String text, String error) {
            this.status = status;
            this.text = text;
            this.error = error;
        }

        static FetchResult ok(String text) {
            return new FetchResult(Status.OK, text == null ? "" : text, null);
        }

        static FetchResult notFound() {
            return new FetchResult(Status.NOT_FOUND, null, null);
        }

        static FetchResult error(String message) {
            return new FetchResult(Status.ERROR, null, message);
        }
    }

    private TcodeTextService() {
    }

    /**
     * 读取事务码文本。<b>网络调用，必须在后台线程执行。</b>
     *
     * @param project ABAP 项目（决定目标系统）
     * @param tcode   事务码（建议调用前已转大写）
     */
    public static FetchResult fetchText(IProject project, String tcode) {
        try {
            String destinationId = resolveDestinationId(project);
            if (destinationId == null) {
                return FetchResult.error("no ADT destination for project "
                        + project.getName());
            }
            JCoDestination destination = JCoDestinationManager.getDestination(destinationId);

            JCoFunction function = destination.getRepository().getFunction("RFC_READ_TABLE");
            if (function == null) {
                return FetchResult.error("RFC_READ_TABLE not available");
            }
            function.getImportParameterList().setValue("QUERY_TABLE", "TSTCT");
            function.getImportParameterList().setValue("DELIMITER", "|");

            JCoTable options = function.getTableParameterList().getTable("OPTIONS");
            options.appendRow();
            options.setValue("TEXT", "TCODE = '" + tcode.replace("'", "''") + "'");

            JCoTable fields = function.getTableParameterList().getTable("FIELDS");
            fields.appendRow();
            fields.setValue("FIELDNAME", "TCODE");
            fields.appendRow();
            fields.setValue("FIELDNAME", "SPRSL");
            fields.appendRow();
            fields.setValue("FIELDNAME", "TTEXT");

            function.execute(destination);

            JCoTable data = function.getTableParameterList().getTable("DATA");
            if (data == null || data.getNumRows() == 0) {
                return FetchResult.notFound();
            }

            String preferredLang = toSapLanguage(destination.getLanguage());
            String fallback = null;
            String english = null;
            data.firstRow();
            do {
                String wa = data.getString("WA");
                if (wa == null) continue;
                String[] parts = wa.split("\\|", -1);
                if (parts.length < 3) continue;
                String sprsl = parts[1].trim();
                String ttext = parts[2].trim();
                if (fallback == null) fallback = ttext;
                if ("E".equalsIgnoreCase(sprsl)) english = ttext;
                if (sprsl.equalsIgnoreCase(preferredLang)) {
                    return FetchResult.ok(ttext);
                }
            } while (data.nextRow());

            return FetchResult.ok(english != null ? english : fallback);
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            String msg = cause.getMessage();
            logWarn("fetchText(" + tcode + ") failed: " + msg);
            return FetchResult.error(cause.getClass().getSimpleName()
                    + (msg != null ? ": " + msg : ""));
        }
    }

    /**
     * 经 ADT API 取项目的 JCo destination 名。ADT 缺席时返回 null。
     *
     * <p>从公共接口 IAdtCoreProjectService 取 Method 再 invoke——直接对实现类
     * 反射可能因 internal 包未导出而抛 IllegalAccessException。</p>
     */
    private static String resolveDestinationId(IProject project) {
        try {
            Bundle bundle = Platform.getBundle(BUNDLE_ADT_PROJECT);
            if (bundle == null) return null;
            Class<?> factoryClass = bundle.loadClass(CLS_FACTORY);
            Class<?> serviceIface = bundle.loadClass(CLS_SERVICE);
            Object service = factoryClass.getMethod("createCoreProjectService").invoke(null);
            if (service == null) return null;
            Method getDestinationId = serviceIface.getMethod("getDestinationId", IProject.class);
            Object id = getDestinationId.invoke(service, project);
            return id instanceof String && !((String) id).isEmpty() ? (String) id : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** JCo 语言（ISO 或 SAP 单字节）-> SAP 单字节 SPRSL。 */
    private static String toSapLanguage(String language) {
        if (language == null || language.isEmpty()) return "E";
        String upper = language.trim().toUpperCase(Locale.ROOT);
        if (upper.length() == 1) return upper;
        String mapped = ISO_TO_SAP_LANG.get(upper);
        return mapped != null ? mapped : "E";
    }

    private static void logWarn(String msg) {
        LOG.log(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                "[TcodeFavorites] " + msg));
    }
}
