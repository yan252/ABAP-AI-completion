package com.sap.abap.ai.completion.preferences;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.sap.AbapGITUploadService;
import com.sap.abap.ai.completion.sap.SapConnectionManager;
import com.sap.conn.jco.JCoDestination;
import com.sap.conn.jco.JCoException;
import com.sap.conn.jco.JCoFunction;

/**
 * “SAP 配置”偏好页：配置 JCo 调用 SAP 系统所需的连接信息
 * (应用服务器、系统编号、Client、语言、用户、密码)，
 * 以及可选的 JCo native 库(sapjco3.dll / libsapjco3.so)所在目录。
 *
 * <p>保存后调用 {@link SapConnectionManager#refreshDestination()} 使新配置立即生效。
 * “模板”菜单上传 zip(调用 RFC {@code Z_ABAPGIT_UPLOAD_FROM_XSTRING})时
 * 均使用本页配置的 SAP 连接。</p>
 */
public class SapPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Text txtHost;
    private Text txtSystemNumber;
    private Text txtClient;
    private Text txtLanguage;
    private Text txtUser;
    private Text txtPassword;
    private Text txtNativeLibDir;
    private Label lblTestResult;

    private IPreferenceStore store;

    public SapPreferencePage() {
        super("SAP 配置");
        setDescription("配置 JCo 连接 SAP 系统的信息，用于“模板”菜单上传 abapGit 离线仓库。\n\n"
                + "说明：JCo 由 SAP 插件 com.sap.conn.jco 提供，native 库(sapjco3.dll)通常由\n"
                + "com.sap.conn.jco.win32.x86_64 fragment 自动加载，无需额外配置。");
    }

    @Override
    public void init(IWorkbench workbench) {
        store = Activator.getDefault().getPreferenceStore();
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite main = new Composite(parent, SWT.NONE);
        main.setLayout(new GridLayout(1, false));
        main.setLayoutData(new GridData(GridData.FILL_BOTH));

        createConnectionGroup(main);
        createNativeLibGroup(main);
        createHintGroup(main);

        loadValues();

        return main;
    }

    // ==================== UI Groups ====================

    private void createConnectionGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("SAP 连接信息");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        createLabel(g, "应用服务器 (Application Server):");
        txtHost = createText(g);

        createLabel(g, "系统编号 (System Number):");
        txtSystemNumber = createText(g);

        createLabel(g, "Client:");
        txtClient = createText(g);

        createLabel(g, "语言 (Language):");
        txtLanguage = createText(g);

        createLabel(g, "用户名 (User):");
        txtUser = createText(g);

        createLabel(g, "密码 (Password):");
        txtPassword = createText(g);
        txtPassword.setEchoChar('*');

        // 测试连接按钮与结果标签(SAP 登录信息在同一组内)
        Button testBtn = new Button(g, SWT.PUSH);
        testBtn.setText("Test Connection");
        testBtn.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> testConnection()));

        lblTestResult = new Label(g, SWT.NONE);
        lblTestResult.setText("");
        lblTestResult.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
    }

    private void createNativeLibGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("JCo Native 库(可选)");
        g.setLayout(new GridLayout(3, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        createLabel(g, "Native 库目录:");
        txtNativeLibDir = createText(g);

        Button browseBtn = new Button(g, SWT.PUSH);
        browseBtn.setText("浏览...");
        browseBtn.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> browseNativeLibDir()));

        Label note = new Label(g, SWT.WRAP);
        note.setText("仅当 Eclipse 无法自动加载 JCo native 库时配置，目录中需包含 sapjco3.dll"
                + "(Windows) 或 libsapjco3.so (Linux/macOS)。\n"
                + "留空表示由 OSGi fragment (com.sap.conn.jco.win32.x86_64) 自动加载。");
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 3;
        note.setLayoutData(nd);
    }

    private void createHintGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("使用提示");
        g.setLayout(new GridLayout(1, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label note = new Label(g, SWT.WRAP);
        note.setText("· “模板”菜单中的子项会将 references/ 下对应的 zip 通过 RFC "
                + "Z_ABAPGIT_UPLOAD_FROM_XSTRING 上传到此处配置的 SAP 系统。\n"
                + "· 上传时会弹出输入框，分别要求填写 IV_PACKAGE(目标开发包)与 "
                + "IV_REPO_NAME(仓库名称)。\n"
                + "· 前提：目标 SAP 系统中已安装 abapGIT 且存在函数模块 "
                + "Z_ABAPGIT_UPLOAD_FROM_XSTRING。");
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        note.setLayoutData(nd);
    }

    // ==================== Data Loading/Saving ====================

    private void loadValues() {
        txtHost.setText(store.getString(PreferenceConstants.SAP_HOST));
        txtSystemNumber.setText(store.getString(PreferenceConstants.SAP_SYSTEM_NUMBER));
        txtClient.setText(store.getString(PreferenceConstants.SAP_CLIENT));
        txtLanguage.setText(store.getString(PreferenceConstants.SAP_LANGUAGE));
        txtUser.setText(store.getString(PreferenceConstants.SAP_USER));
        txtPassword.setText(store.getString(PreferenceConstants.SAP_PASSWORD));
        txtNativeLibDir.setText(store.getString(PreferenceConstants.SAP_NATIVE_LIB_DIR));
    }

    private void saveValues() {
        store.setValue(PreferenceConstants.SAP_HOST, txtHost.getText().trim());
        store.setValue(PreferenceConstants.SAP_SYSTEM_NUMBER, txtSystemNumber.getText().trim());
        store.setValue(PreferenceConstants.SAP_CLIENT, txtClient.getText().trim());
        store.setValue(PreferenceConstants.SAP_LANGUAGE, txtLanguage.getText().trim());
        store.setValue(PreferenceConstants.SAP_USER, txtUser.getText().trim());
        store.setValue(PreferenceConstants.SAP_PASSWORD, txtPassword.getText().trim());
        store.setValue(PreferenceConstants.SAP_NATIVE_LIB_DIR, txtNativeLibDir.getText().trim());
    }

    @Override
    public boolean performOk() {
        saveValues();
        // 刷新 JCo destination，使新配置立即生效
        SapConnectionManager.refreshDestination();
        return true;
    }

    @Override
    protected void performDefaults() {
        txtHost.setText("");
        txtSystemNumber.setText(PreferenceConstants.DEFAULT_SAP_SYSTEM_NUMBER);
        txtClient.setText(PreferenceConstants.DEFAULT_SAP_CLIENT);
        txtLanguage.setText(PreferenceConstants.DEFAULT_SAP_LANGUAGE);
        txtUser.setText("");
        txtPassword.setText("");
        txtNativeLibDir.setText("");
    }

    // ==================== Test Connection ====================

    private void testConnection() {
        // 先用当前页面的输入值临时保存，再做 ping 测试
        saveValues();
        SapConnectionManager.refreshDestination();

        setTestResult("正在测试连接...", SWT.COLOR_BLUE);

        new Thread(() -> {
            try {
                JCoDestination destination = SapConnectionManager.getDestination();
                destination.ping();

                // 连接成功后，再检查 RFC 函数 Z_ABAPGIT_UPLOAD_FROM_XSTRING 是否存在
                boolean exists = functionExists(destination, AbapGITUploadService.RFC_FUNCTION);
                if (exists) {
                    Display.getDefault().asyncExec(() ->
                            setTestResult("SUCCESS: 连接成功!", SWT.COLOR_DARK_GREEN));
                } else {
                    final String msg = "SAP 连接成功，但 RFC 函数 " + AbapGITUploadService.RFC_FUNCTION
                            + " 不存在。\n\n请先在 SAP 系统中创建该函数模块，"
                            + "否则“模板”菜单上传将无法使用。";
                    Display.getDefault().asyncExec(() -> {
                        setTestResult("连接成功，但函数 " + AbapGITUploadService.RFC_FUNCTION
                                + " 不存在", SWT.COLOR_RED);
                        MessageDialog.openWarning(getShell(), "提示: 请创建函数",
                                "SAP 连接成功。\n\n" + msg);
                    });
                }
            } catch (Exception ex) {
                String msg = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                Display.getDefault().asyncExec(() ->
                        setTestResult("FAILED: " + msg, SWT.COLOR_RED));
            }
        }, "abap-ai-sap-test").start();
    }

    /**
     * 调用标准 RFC 函数 <code>FUNCTION_EXISTS</code> 判断指定函数模块是否存在。
     * SAP 在函数不存在时抛出 ABAP 异常 <code>FUNCTION_NOT_FOUND</code>。
     *
     * @return true 表示函数存在；false 表示函数不存在(FUNCTION_NOT_FOUND)。
     * @throws JCoException 连接错误等非'不存在'类异常继续向上抛出。
     */
    private static boolean functionExists(JCoDestination destination, String functionName)
            throws JCoException {
        JCoFunction fm = destination.getRepository().getFunction("FUNCTION_EXISTS");
        fm.getImportParameterList().setValue("FUNCNAME", functionName);
        try {
            fm.execute(destination);
            return true;
        } catch (JCoException e) {
            if (JCoException.JCO_ERROR_APPLICATION_EXCEPTION == e.getGroup()
                    && "FUNCTION_NOT_FOUND".equals(e.getKey())) {
                return false;
            }
            throw e;
        }
    }

    private void setTestResult(String text, int colorConstant) {
        if (lblTestResult != null && !lblTestResult.isDisposed()) {
            lblTestResult.setText(text);
            lblTestResult.setForeground(Display.getDefault().getSystemColor(colorConstant));
            lblTestResult.getParent().layout();
        }
    }

    // ==================== Helpers ====================

    private Label createLabel(Composite parent, String text) {
        Label lbl = new Label(parent, SWT.NONE);
        lbl.setText(text);
        return lbl;
    }

    private Text createText(Composite parent) {
        Text txt = new Text(parent, SWT.BORDER);
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        txt.setLayoutData(gd);
        return txt;
    }

    private void browseNativeLibDir() {
        DirectoryDialog dialog = new DirectoryDialog(getShell(), SWT.OPEN);
        dialog.setText("选择 JCo Native 库目录");
        dialog.setMessage("请选择包含 sapjco3.dll / libsapjco3.so 的目录:");

        String current = txtNativeLibDir.getText().trim();
        if (current != null && !current.isEmpty()) {
            dialog.setFilterPath(current);
        }

        String selected = dialog.open();
        if (selected != null && !selected.isEmpty()) {
            txtNativeLibDir.setText(selected);
        }
    }
}
