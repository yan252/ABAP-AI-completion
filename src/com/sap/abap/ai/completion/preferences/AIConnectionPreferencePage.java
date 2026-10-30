package com.sap.abap.ai.completion.preferences;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.client.AIClient;
import com.sap.abap.ai.completion.client.AIClientException;

/**
 * AI 连接配置页（"ABAP AI Completion" 下的子页面，位于 "SAP Connection Config" 之前）。
 *
 * <p>左侧为 AI 连接名称列表，可选中其中任意一个；双击连接名称后在右侧显示该连接的详细信息。
 * 右侧提供 "Add" / "Remove" / "Set as Default" / "Test Connection" / "Clear" 操作，
 * 左侧提供 "Restore Default" 操作（还原 FREE_AI / deepseek 两个内置连接）。
 * 被设为默认的连接在左侧列表的名称后显示 "(Default)" 标识，插件调用 AI 时使用该默认连接。</p>
 *
 * <p>首次打开且无已保存连接时自动种入两个内置连接：FREE_AI（默认）与 deepseek。
 * "Restore Default" / "Clear" / "Remove" 三个按钮执行前均会弹出确认对话框。</p>
 */
public class AIConnectionPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private List lstConnections;
    private Text txtConnName;
    private Combo cmbVendor;
    private Text txtBaseUrl;
    private Text txtModel;
    private Text txtApiKey;
    private Text txtMaxTokens;
    private Text txtTemperature;

    private Button btnAdd;
    private Button btnRemove;
    private Button btnRestore;
    private Button btnSetDefault;
    private Button btnTest;
    private Label lblTestResult;

    private IPreferenceStore store;
    private java.util.List<AIConnectionEntry> connections;
    private AIConnectionEntry currentEntry;

    /** 刷新左侧列表期间置为 true，避免列表控件清空/重填触发选中事件 */
    private boolean refreshingList;
    /** 程序化设置厂商下拉框选中的期间置为 true，避免触发 URL / 模型自动覆盖 */
    private boolean refreshingVendor;

    public AIConnectionPreferencePage() {
        super(Messages.get("connPage.title"));
        setDescription(Messages.get("connPage.description"));
        // 隐藏 Eclipse 预置页底部的 "Restore Defaults" 按钮；
        // 本页在列表下方提供自定义的 "Restore Default" 按钮（还原内置连接）
        noDefaultButton();
    }

    @Override
    public void init(IWorkbench workbench) {
        store = Activator.getDefault().getPreferenceStore();
    }

    @Override
    protected Composite createContents(Composite parent) {
        Composite container = new Composite(parent, SWT.NONE);
        container.setLayout(new GridLayout(2, false));
        container.setLayoutData(new GridData(GridData.FILL_BOTH));

        // ---- LEFT PANEL: AI connection name list ----
        Composite left = new Composite(container, SWT.NONE);
        left.setLayout(new GridLayout(1, false));
        GridData leftGd = new GridData(GridData.FILL_BOTH);
        leftGd.widthHint = 200;
        left.setLayoutData(leftGd);

        Label lblList = new Label(left, SWT.NONE);
        lblList.setText(Messages.get("connPage.label.list"));
        lblList.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        lstConnections = new List(left, SWT.BORDER | SWT.V_SCROLL);
        GridData lstGd = new GridData(GridData.FILL_BOTH);
        lstGd.heightHint = 220;
        lstConnections.setLayoutData(lstGd);

        // 单击选中即在右侧显示；双击同样在右侧显示该连接信息
        lstConnections.addSelectionListener(
                SelectionListener.widgetSelectedAdapter(e -> {
                    if (refreshingList) return;
                    onConnectionSelected();
                }));
        lstConnections.addListener(SWT.DefaultSelection, e -> {
            if (refreshingList) return;
            onConnectionSelected();
        });

        Composite listBtns = new Composite(left, SWT.NONE);
        listBtns.setLayout(new GridLayout(2, false));
        listBtns.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        btnAdd = new Button(listBtns, SWT.PUSH);
        btnAdd.setText(Messages.get("connPage.button.add"));
        btnAdd.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> addConnection()));

        btnRemove = new Button(listBtns, SWT.PUSH);
        btnRemove.setText(Messages.get("connPage.button.remove"));
        btnRemove.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> removeConnection()));

        // "Restore Default" 横跨两列：丢弃当前全部连接，还原 FREE_AI / deepseek 两个内置连接
        btnRestore = new Button(listBtns, SWT.PUSH);
        btnRestore.setText(Messages.get("connPage.button.restoreDefault"));
        GridData restoreGd = new GridData(GridData.FILL_HORIZONTAL);
        restoreGd.horizontalSpan = 2;
        btnRestore.setLayoutData(restoreGd);
        btnRestore.addSelectionListener(
                SelectionListener.widgetSelectedAdapter(e -> restoreDefaults()));

        // ---- RIGHT PANEL: details of the selected connection ----
        Composite right = new Composite(container, SWT.NONE);
        right.setLayout(new GridLayout(2, false));
        right.setLayoutData(new GridData(GridData.FILL_BOTH));

        createLabel(right, Messages.get("connPage.label.name"));
        txtConnName = createText(right);

        // 模型厂商下拉框：选择内置厂商时自动填充对应的 API Base URL 与推荐模型
        createLabel(right, Messages.get("connPage.label.vendor"));
        cmbVendor = new Combo(right, SWT.DROP_DOWN | SWT.READ_ONLY);
        GridData vendorGd = new GridData(GridData.FILL_HORIZONTAL);
        vendorGd.widthHint = 260;
        cmbVendor.setLayoutData(vendorGd);
        for (AIProvider provider : AIProvider.all()) {
            cmbVendor.add(provider.displayName());
        }
        cmbVendor.addSelectionListener(
                SelectionListener.widgetSelectedAdapter(e -> onVendorSelected()));

        createLabel(right, Messages.get("connPage.label.baseUrl"));
        txtBaseUrl = createText(right);

        createLabel(right, Messages.get("connPage.label.model"));
        txtModel = createText(right);

        createLabel(right, Messages.get("connPage.label.apiKey"));
        txtApiKey = createText(right);
        txtApiKey.setEchoChar('*');

        createLabel(right, Messages.get("connPage.label.maxTokens"));
        txtMaxTokens = createText(right);

        createLabel(right, Messages.get("connPage.label.temperature"));
        txtTemperature = createText(right);

        // Button row
        Composite btnRow = new Composite(right, SWT.NONE);
        btnRow.setLayout(new GridLayout(3, false));
        GridData btnRowGd = new GridData(GridData.FILL_HORIZONTAL);
        btnRowGd.horizontalSpan = 2;
        btnRow.setLayoutData(btnRowGd);

        btnSetDefault = new Button(btnRow, SWT.PUSH);
        btnSetDefault.setText(Messages.get("connPage.button.setDefault"));
        btnSetDefault.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> setAsDefault()));

        btnTest = new Button(btnRow, SWT.PUSH);
        btnTest.setText(Messages.get("connPage.button.test"));
        btnTest.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> testConnection()));

        Button btnClear = new Button(btnRow, SWT.PUSH);
        btnClear.setText(Messages.get("connPage.button.clear"));
        btnClear.addSelectionListener(
                SelectionListener.widgetSelectedAdapter(e -> clearFormWithConfirm()));

        lblTestResult = new Label(right, SWT.NONE);
        lblTestResult.setText("");
        GridData testGd = new GridData(GridData.FILL_HORIZONTAL);
        testGd.horizontalSpan = 2;
        lblTestResult.setLayoutData(testGd);

        // Hint
        Label hint = new Label(right, SWT.WRAP);
        hint.setText(Messages.get("connPage.hint"));
        GridData hintGd = new GridData(GridData.FILL_HORIZONTAL);
        hintGd.horizontalSpan = 2;
        hint.setLayoutData(hintGd);

        // Load and display
        loadConnections();

        return container;
    }

    // ==================== Connection Operations ====================

    private void loadConnections() {
        connections = AIConnectionEntry.loadAll(store);
        refreshConnectionList();
        if (connections.isEmpty()) {
            clearForm();
            return;
        }
        // 默认选中当前默认连接（无默认标识时选第一个）
        int defaultIdx = 0;
        for (int i = 0; i < connections.size(); i++) {
            if (connections.get(i).isDefault) {
                defaultIdx = i;
                break;
            }
        }
        lstConnections.select(defaultIdx);
        onConnectionSelected();
    }

    private void saveConnections() {
        AIConnectionEntry.saveAll(connections, store);
    }

    /**
     * 刷新左侧连接列表的显示内容（默认连接的名称后追加 "(Default)" 标识）。
     */
    private void refreshConnectionList() {
        if (lstConnections == null || lstConnections.isDisposed()) return;
        refreshingList = true;
        try {
            lstConnections.removeAll();
            if (connections == null) return;
            for (AIConnectionEntry entry : connections) {
                String displayName = entry.name;
                if (entry.isDefault) {
                    displayName += Messages.get("connPage.defaultSuffix");
                }
                lstConnections.add(displayName);
            }
        } finally {
            refreshingList = false;
        }
    }

    /**
     * 刷新左侧列表并保持当前选中项（Apply / OK 后同步最新名称，例如修改后的 AI 名称）。
     */
    private void refreshListAndKeepSelection() {
        int idx = (currentEntry == null || connections == null) ? -1 : connections.indexOf(currentEntry);
        refreshConnectionList();
        if (idx >= 0 && idx < lstConnections.getItemCount()) {
            lstConnections.select(idx);
        }
    }

    /**
     * 列表选中项变化（单击 / 双击）时，将右侧表单填充为对应连接的信息。
     */
    private void onConnectionSelected() {
        // 切换前先把表单中的编辑内容写回原条目，避免丢失未保存的修改
        syncFormToEntry();
        int idx = lstConnections.getSelectionIndex();
        if (idx < 0 || connections == null || idx >= connections.size()) {
            clearForm();
            return;
        }
        currentEntry = connections.get(idx);
        fillForm(currentEntry);
    }

    private void fillForm(AIConnectionEntry entry) {
        txtConnName.setText(entry.name);
        selectVendor(entry.vendor);
        txtBaseUrl.setText(entry.baseUrl);
        txtModel.setText(entry.model);
        txtApiKey.setText(entry.apiKey);
        txtMaxTokens.setText(entry.maxTokens);
        txtTemperature.setText(entry.temperature);
    }

    private void clearForm() {
        currentEntry = null;
        txtConnName.setText("");
        selectVendor(AIProvider.CUSTOM);
        txtBaseUrl.setText("");
        txtModel.setText("");
        txtApiKey.setText("");
        txtMaxTokens.setText("");
        txtTemperature.setText("");
    }

    /**
     * 选择厂商下拉框中的指定厂商（程序化设置，不触发 URL / 模型自动覆盖）。
     */
    private void selectVendor(String vendor) {
        AIProvider provider = AIProvider.byName(vendor);
        int index = AIProvider.all().indexOf(provider);
        refreshingVendor = true;
        try {
            cmbVendor.select(index < 0 ? AIProvider.all().size() - 1 : index);
        } finally {
            refreshingVendor = false;
        }
    }

    /**
     * 用户在厂商下拉框中切换厂商时，把该厂商的默认 API Base URL 与推荐模型填入表单。
     * 选择 "Custom" 时不覆盖，保留用户手工填写的 URL / 模型。
     */
    private void onVendorSelected() {
        if (refreshingVendor) return;
        int index = cmbVendor.getSelectionIndex();
        if (index < 0 || index >= AIProvider.all().size()) return;
        AIProvider provider = AIProvider.all().get(index);
        if (provider.isCustom()) return;
        txtBaseUrl.setText(provider.baseUrl);
        txtModel.setText(provider.model);
    }

    /**
     * "Clear" 按钮入口：仅清空右侧表单字段（不从列表删除连接），执行前弹出确认对话框。
     */
    private void clearFormWithConfirm() {
        boolean confirm = MessageDialog.openConfirm(getShell(), Messages.get("connPage.dialog.clear.title"),
                Messages.get("connPage.dialog.clear.message"));
        if (!confirm) return;
        clearForm();
    }

    /**
     * 将右侧表单内容写回当前选中条目（未选中条目时不做任何操作）。
     */
    private void syncFormToEntry() {
        if (currentEntry == null) return;
        currentEntry.name = txtConnName.getText().trim();
        currentEntry.vendor = selectedVendorName();
        currentEntry.baseUrl = txtBaseUrl.getText().trim();
        currentEntry.model = txtModel.getText().trim();
        currentEntry.apiKey = txtApiKey.getText().trim();
        currentEntry.maxTokens = txtMaxTokens.getText().trim();
        currentEntry.temperature = txtTemperature.getText().trim();
    }

    private void addConnection() {
        syncFormToEntry(); // save any unsaved edits first
        AIConnectionEntry newEntry = new AIConnectionEntry();
        newEntry.name = uniqueName(Messages.get("connPage.newConnectionName"));
        connections.add(newEntry);
        saveConnections();
        refreshConnectionList();
        int idx = connections.indexOf(newEntry);
        lstConnections.select(idx);
        onConnectionSelected();
    }

    /**
     * 生成不与现有连接重名的默认名称。默认连接以 name 作为标识，
     * 重名会导致插件解析默认连接时取到错误的条目。
     */
    private String uniqueName(String base) {
        String candidate = base;
        int suffix = 2;
        while (AIConnectionEntry.findBy_name(connections, candidate) != null) {
            candidate = base + " " + suffix;
            suffix++;
        }
        return candidate;
    }

    private void removeConnection() {
        int idx = lstConnections.getSelectionIndex();
        if (idx < 0 || connections == null || connections.isEmpty()) {
            MessageDialog.openWarning(getShell(), Messages.get("connPage.dialog.remove.title"),
                    Messages.get("connPage.dialog.remove.selectConnection"));
            return;
        }
        String name = connections.get(idx).name;
        boolean confirm = MessageDialog.openConfirm(getShell(), Messages.get("connPage.dialog.remove.title"),
                Messages.format("connPage.dialog.remove.confirm", name));
        if (!confirm) return;
        boolean removedWasDefault = connections.get(idx).isDefault;
        connections.remove(idx);
        if (connections.isEmpty()) {
            // 至少保留一个空默认连接，保证插件始终有可用连接
            AIConnectionEntry def = new AIConnectionEntry();
            def.name = Messages.get("connPage.defaultConnectionName");
            def.isDefault = true;
            connections.add(def);
        } else if (removedWasDefault) {
            // 删除的是默认连接时，把列表中第一个连接提升为默认
            connections.get(0).isDefault = true;
        }
        saveConnections();
        refreshConnectionList();
        lstConnections.select(Math.min(idx, connections.size() - 1));
        onConnectionSelected();
    }

    /**
     * 还原内置默认连接：丢弃当前全部 AI 连接，恢复为 FREE_AI（默认）与 deepseek，
     * 立即持久化、刷新左侧列表并选中默认连接（FREE_AI）。执行前弹出确认对话框。
     */
    private void restoreDefaults() {
        boolean confirm = MessageDialog.openConfirm(getShell(),
                Messages.get("connPage.dialog.restore.title"),
                Messages.get("connPage.dialog.restore.message"));
        if (!confirm) return;

        // 丢弃右侧表单中尚未保存的编辑，避免写回已被还原的旧条目
        currentEntry = null;
        connections = new java.util.ArrayList<>(AIConnectionEntry.defaultConnections());
        saveConnections();
        refreshConnectionList();

        // 选中默认连接（FREE_AI）；无默认标识时回退到第一个
        int defaultIdx = 0;
        for (int i = 0; i < connections.size(); i++) {
            if (connections.get(i).isDefault) {
                defaultIdx = i;
                break;
            }
        }
        lstConnections.select(defaultIdx);
        onConnectionSelected();
    }

    /**
     * 将右侧当前显示的连接设为插件使用的默认 AI，其余连接取消默认标识。
     */
    private void setAsDefault() {
        syncFormToEntry();
        if (currentEntry == null) {
            MessageDialog.openWarning(getShell(), Messages.get("connPage.dialog.setDefault.title"),
                    Messages.get("connPage.dialog.setDefault.selectFirst"));
            return;
        }
        if (currentEntry.name == null || currentEntry.name.trim().isEmpty()) {
            MessageDialog.openWarning(getShell(), Messages.get("connPage.dialog.setDefault.title"),
                    Messages.get("connPage.dialog.setDefault.enterName"));
            return;
        }
        for (AIConnectionEntry e : connections) {
            e.isDefault = (e == currentEntry);
        }
        saveConnections();
        refreshConnectionList();
        int idx = connections.indexOf(currentEntry);
        lstConnections.select(idx);
        onConnectionSelected();
    }

    private void testConnection() {
        syncFormToEntry();
        if (currentEntry == null) {
            setTestResult(Messages.get("connPage.test.selectFirst"), SWT.COLOR_RED);
            return;
        }
        String baseUrl = currentEntry.baseUrl.trim();
        String model = currentEntry.model.trim();
        String apiKey = currentEntry.apiKey.trim();
        String maxTokensStr = currentEntry.maxTokens.trim();
        String tempStr = currentEntry.temperature.trim();

        if (baseUrl.isEmpty()) {
            setTestResult(Messages.get("connPage.test.enterBaseUrl"), SWT.COLOR_RED);
            return;
        }
        if (apiKey.isEmpty()) {
            setTestResult(Messages.get("connPage.test.enterApiKey"), SWT.COLOR_RED);
            return;
        }

        setTestResult(Messages.format("connPage.test.testing", baseUrl), SWT.COLOR_BLUE);

        new Thread(() -> {
            try {
                int maxTokens = 20;
                double temp = 0.1;
                try { maxTokens = Integer.parseInt(maxTokensStr); } catch (Exception ignored) {}
                try { temp = Double.parseDouble(tempStr); } catch (Exception ignored) {}

                String result = AIClient.testConnection(baseUrl,
                        model.isEmpty() ? "gpt-4" : model, apiKey, maxTokens, temp);

                Display.getDefault().asyncExec(() ->
                    setTestResult(Messages.format("connPage.test.success", result), SWT.COLOR_DARK_GREEN));
            } catch (AIClientException ex) {
                Display.getDefault().asyncExec(() ->
                    setTestResult(Messages.format("connPage.test.failed", ex.getMessage()), SWT.COLOR_RED));
            } catch (Exception ex) {
                Display.getDefault().asyncExec(() ->
                    setTestResult(Messages.format("connPage.test.error", ex.getMessage()), SWT.COLOR_RED));
            }
        }).start();
    }

    private void setTestResult(String text, int colorConstant) {
        if (lblTestResult != null && !lblTestResult.isDisposed()) {
            lblTestResult.setText(text);
            lblTestResult.setForeground(Display.getDefault().getSystemColor(colorConstant));
            lblTestResult.getParent().layout();
        }
    }

    // ==================== PreferencePage lifecycle ====================

    @Override
    public boolean performOk() {
        // 确保右侧表单中未提交的修改被保存
        syncFormToEntry();
        saveConnections();
        refreshListAndKeepSelection();
        return true;
    }

    @Override
    protected void performApply() {
        // "Apply" 后立即保存，并刷新左侧列表中显示的 AI 名称（改名后同步更新）
        syncFormToEntry();
        saveConnections();
        refreshListAndKeepSelection();
    }

    // ==================== Helpers ====================

    /** 当前厂商下拉框选中项的持久化名称。 */
    private String selectedVendorName() {
        int index = cmbVendor.getSelectionIndex();
        if (index < 0 || index >= AIProvider.all().size()) return AIProvider.CUSTOM;
        return AIProvider.all().get(index).name;
    }

    private Label createLabel(Composite parent, String text) {
        Label lbl = new Label(parent, SWT.NONE);
        lbl.setText(text);
        return lbl;
    }

    private Text createText(Composite parent) {
        Text txt = new Text(parent, SWT.BORDER);
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.widthHint = 260;
        txt.setLayoutData(gd);
        return txt;
    }
}
