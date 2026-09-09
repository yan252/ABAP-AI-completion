package com.sap.abap.ai.completion.sap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import com.sap.abap.ai.completion.Activator;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoException;
import com.sap.conn.jco.JCoFunction;
import com.sap.conn.jco.JCoParameterList;

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
            throw new JCoException(JCoException.JCO_ERROR_INTERNAL, "zip 数据为空");
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
            throw new IOException("插件尚未初始化, 无法读取资源: " + resourcePath);
        }
        URL url = Activator.getDefault().getBundle().getEntry(resourcePath);
        if (url == null) {
            throw new IOException("bundle 中找不到资源: " + resourcePath);
        }
        try (InputStream in = url.openStream()) {
            if (in == null) {
                throw new IOException("资源不可读: " + resourcePath);
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
}
