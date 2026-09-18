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
import com.sap.abap.ai.completion.sap.AbapCliConnectionTester;
import com.sap.abap.ai.completion.sap.SapConnectionManager;

/**
 * “SAP 配置”偏好页：配置 abapGit（abap-cli）连接 URL —— 供 “Test Connection”
 * 与模板导入使用；下方保留遗留 JCo 字段（应用服务器号码、Client、语言、用户、密码），
 * 仅供基于 RFC 的 “Templates” 菜单使用，另可配置可选的 JCo native 库目录。
 *
 * <p>保存后调用 {@link SapConnectionManager#refreshDestination()} 使新配置立即生效。</p>
 *
 * <p><b>测试连接</b>：使用页面顶部的 abap-cli URL，按 abapGit（abap-cli）方式探测
 * tls / auth / adt 等分层（见 {@link AbapCliConnectionTester}）。
 * 不涉及 JCo，也不读取 {@code ~/.abap-cli/systems.json} 中的 profile。</p>
 */
public class SapPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Text txtAbapCliUrl;
    private Text txtSystemNumber;
    private Text txtClient;
    private Text txtLanguage;
    private Text txtUser;
    private Text txtPassword;
    private Text txtNativeLibDir;
    private Label lblTestResult;

    private IPreferenceStore store;

    public SapPreferencePage() {
        super("SAP Connection Config");
        setDescription("Configure the abapGit (abap-cli) connection URL used by \"Test Connection\" "
                + "and the template import, plus the legacy JCo fields used by the RFC-based "
                + "\"Templates\" menu items.\n\n"
                + "Note: \"Test Connection\" uses the abap-cli URL at the top and probes it via the "
                + "abapGit (abap-cli) approach -- no JCo, no ~/.abap-cli/systems.json profile involved.");
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
        g.setText("SAP Connection Settings");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        createLabel(g, "abap-cli URL (Optional):");
        txtAbapCliUrl = createText(g);

        createLabel(g, "System Number:");
        txtSystemNumber = createText(g);

        createLabel(g, "Client:");
        txtClient = createText(g);

        createLabel(g, "Language:");
        txtLanguage = createText(g);

        createLabel(g, "User:");
        txtUser = createText(g);

        createLabel(g, "Password:");
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
        g.setText("JCo Native Library (Optional)");
        g.setLayout(new GridLayout(3, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        createLabel(g, "Native library directory:");
        txtNativeLibDir = createText(g);

        Button browseBtn = new Button(g, SWT.PUSH);
        browseBtn.setText("Browse...");
        browseBtn.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> browseNativeLibDir()));

        Label note = new Label(g, SWT.WRAP);
        note.setText("Configure this only when Eclipse cannot auto-load the JCo native "
                + "library; the directory must contain sapjco3.dll (Windows) or "
                + "libsapjco3.so (Linux/macOS).\n"
                + "Leave empty to let the OSGi fragment (com.sap.conn.jco.win32.x86_64) load it automatically.");
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 3;
        note.setLayoutData(nd);
    }

    private void createHintGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("Usage Hints");
        g.setLayout(new GridLayout(1, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label note = new Label(g, SWT.WRAP);
        note.setText("- Template import now uses the abapGit (abap-cli) approach from the\n"
                + "  stage directory (node abap-cli + prog-* extensions).\n"
                + "- \"Test Connection\" uses the abap-cli URL at the top (e.g.\n"
                + "  https://s4devapp.app.com.cn:1443) and probes tls / auth / adt.\n"
                + "  It never touches JCo or ~/.abap-cli/systems.json.\n"
                + "- The JCo fields below are only used by the legacy RFC-based \"Templates\"\n"
                + "  menu items. The Application Server field has been removed; the abapGit\n"
                + "  test / import no longer derives a URL from host + system number.");
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.verticalSpan = 1;
        nd.widthHint = 500;
        note.setLayoutData(nd);
    }

    // ==================== Data Loading/Saving ====================

    private void loadValues() {
        txtAbapCliUrl.setText(store.getString(PreferenceConstants.SAP_ABAP_CLI_URL));
        txtSystemNumber.setText(store.getString(PreferenceConstants.SAP_SYSTEM_NUMBER));
        txtClient.setText(store.getString(PreferenceConstants.SAP_CLIENT));
        txtLanguage.setText(store.getString(PreferenceConstants.SAP_LANGUAGE));
        txtUser.setText(store.getString(PreferenceConstants.SAP_USER));
        txtPassword.setText(store.getString(PreferenceConstants.SAP_PASSWORD));
        txtNativeLibDir.setText(store.getString(PreferenceConstants.SAP_NATIVE_LIB_DIR));
    }

    private void saveValues() {
        store.setValue(PreferenceConstants.SAP_ABAP_CLI_URL, txtAbapCliUrl.getText().trim());
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
        txtAbapCliUrl.setText(PreferenceConstants.DEFAULT_SAP_ABAP_CLI_URL);
        txtSystemNumber.setText(PreferenceConstants.DEFAULT_SAP_SYSTEM_NUMBER);
        txtClient.setText(PreferenceConstants.DEFAULT_SAP_CLIENT);
        txtLanguage.setText(PreferenceConstants.DEFAULT_SAP_LANGUAGE);
        txtUser.setText("");
        txtPassword.setText("");
        txtNativeLibDir.setText("");
    }

    // ==================== Test Connection ====================

    /**
     * abapGit（abap-cli）方式测试连接。
     *
     * <p>使用页面顶部的 abap-cli URL（如 https://s4devapp.app.com.cn:1443），
     * 在后台线程逐层探测 tls / auth / adt 等；tls+auth+adt 全部 ok 即视为成功。
     * 不涉及 JCo，也不使用 {@code ~/.abap-cli/systems.json} 中的 profile。</p>
     */
    private void testConnection() {
        final String url = txtAbapCliUrl.getText().trim();
        if (url.isEmpty()) {
            setTestResult("FAILED: please fill in the abap-cli URL", SWT.COLOR_RED);
            MessageDialog.openError(getShell(), "abapGit Connection Test Failed",
                    "Please fill in the 'abap-cli URL' field before testing the connection.");
            return;
        }
        final String client = txtClient.getText().trim();
        final String user = txtUser.getText().trim();
        final String password = txtPassword.getText().trim();
        final String language = txtLanguage.getText().trim();

        setTestResult("Testing abapGit (abap-cli) connection...", SWT.COLOR_BLUE);

        new Thread(() -> {
            AbapCliConnectionTester.Result r = AbapCliConnectionTester.testConnection(
                    url, client, user, password, language);
            Display.getDefault().asyncExec(() -> {
                if (r.success) {
                    setTestResult("SUCCESS: abapGit connection OK (" + r.layersSummary + ")",
                            SWT.COLOR_DARK_GREEN);
                    MessageDialog.openInformation(getShell(),
                            "abapGit Connection Test Succeeded",
                            "abapGit (abap-cli) connection succeeded.\n\n"
                                    + "Target: " + url + "\n\n"
                                    + "Layers: " + r.layersSummary + "\n\n"
                                    + "Details:\n" + r.detail);
                } else {
                    setTestResult("FAILED: abapGit connection failed", SWT.COLOR_RED);
                    Activator.getDefault().getLog().log(new org.eclipse.core.runtime.Status(
                            org.eclipse.core.runtime.IStatus.ERROR, Activator.PLUGIN_ID,
                            "abapGit (abap-cli) connection test failed: " + r.detail, null));
                    MessageDialog.openError(getShell(),
                            "abapGit Connection Test Failed",
                            "abapGit (abap-cli) connection test failed"
                                    + (url.isEmpty() ? "" : " (target '" + url + "')")
                                    + ".\n\n" + r.detail
                                    + "\n\nSee Window -> Show View -> Error Log for details.");
                }
            });
        }, "abap-git-conn-test").start();
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
        dialog.setText("Select JCo Native Library Directory");
        dialog.setMessage("Please select the directory containing sapjco3.dll / libsapjco3.so:");

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
