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
 * 右侧提供 "Add" / "Remove" / "Set as Default" / "Test Connection" / "Clear" 操作。
 * 被设为默认的连接在左侧列表的名称后显示 "(Default)" 标识，插件调用 AI 时使用该默认连接。</p>
 */
public class AIConnectionPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private List lstConnections;
    private Text txtConnName;
    private Text txtBaseUrl;
    private Text txtModel;
    private Text txtApiKey;
    private Text txtMaxTokens;
    private Text txtTemperature;

    private Button btnAdd;
    private Button btnRemove;
    private Button btnSetDefault;
    private Button btnTest;
    private Label lblTestResult;

    private IPreferenceStore store;
    private java.util.List<AIConnectionEntry> connections;
    private AIConnectionEntry currentEntry;

    /** 刷新左侧列表期间置为 true，避免列表控件清空/重填触发选中事件 */
    private boolean refreshingList;

    public AIConnectionPreferencePage() {
        super("AI Connections");
        setDescription("Manage multiple AI connections. Select a connection on the left "
                + "to edit its details on the right.");
        // 不提供 "Restore Defaults" 按钮（连接列表由用户自行维护）
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
        lblList.setText("AI Connections:");
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
        btnAdd.setText("Add");
        btnAdd.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> addConnection()));

        btnRemove = new Button(listBtns, SWT.PUSH);
        btnRemove.setText("Remove");
        btnRemove.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> removeConnection()));

        // ---- RIGHT PANEL: details of the selected connection ----
        Composite right = new Composite(container, SWT.NONE);
        right.setLayout(new GridLayout(2, false));
        right.setLayoutData(new GridData(GridData.FILL_BOTH));

        createLabel(right, "AI Connection Name:");
        txtConnName = createText(right);

        createLabel(right, "API Base URL:");
        txtBaseUrl = createText(right);

        createLabel(right, "Model Name:");
        txtModel = createText(right);

        createLabel(right, "API Key:");
        txtApiKey = createText(right);
        txtApiKey.setEchoChar('*');

        createLabel(right, "Max Tokens:");
        txtMaxTokens = createText(right);

        createLabel(right, "Temperature:");
        txtTemperature = createText(right);

        // Button row
        Composite btnRow = new Composite(right, SWT.NONE);
        btnRow.setLayout(new GridLayout(3, false));
        GridData btnRowGd = new GridData(GridData.FILL_HORIZONTAL);
        btnRowGd.horizontalSpan = 2;
        btnRow.setLayoutData(btnRowGd);

        btnSetDefault = new Button(btnRow, SWT.PUSH);
        btnSetDefault.setText("Set as Default");
        btnSetDefault.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> setAsDefault()));

        btnTest = new Button(btnRow, SWT.PUSH);
        btnTest.setText("Test Connection");
        btnTest.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> testConnection()));

        Button btnClear = new Button(btnRow, SWT.PUSH);
        btnClear.setText("Clear");
        btnClear.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> clearForm()));

        lblTestResult = new Label(right, SWT.NONE);
        lblTestResult.setText("");
        GridData testGd = new GridData(GridData.FILL_HORIZONTAL);
        testGd.horizontalSpan = 2;
        lblTestResult.setLayoutData(testGd);

        // Hint
        Label hint = new Label(right, SWT.WRAP);
        hint.setText("Add several AI connections and select any one in the list on the left.\n"
                + "Double-click a connection name to show its details on the right.\n"
                + "Click \"Set as Default\" to make the displayed connection the one used by the plugin; "
                + "its name is then marked with \"(Default)\".\n"
                + "\"Test Connection\" tests the connection currently shown on the right.");
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
                    displayName += " (Default)";
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
        txtBaseUrl.setText(entry.baseUrl);
        txtModel.setText(entry.model);
        txtApiKey.setText(entry.apiKey);
        txtMaxTokens.setText(entry.maxTokens);
        txtTemperature.setText(entry.temperature);
    }

    private void clearForm() {
        currentEntry = null;
        txtConnName.setText("");
        txtBaseUrl.setText("");
        txtModel.setText("");
        txtApiKey.setText("");
        txtMaxTokens.setText("");
        txtTemperature.setText("");
    }

    /**
     * 将右侧表单内容写回当前选中条目（未选中条目时不做任何操作）。
     */
    private void syncFormToEntry() {
        if (currentEntry == null) return;
        currentEntry.name = txtConnName.getText().trim();
        currentEntry.baseUrl = txtBaseUrl.getText().trim();
        currentEntry.model = txtModel.getText().trim();
        currentEntry.apiKey = txtApiKey.getText().trim();
        currentEntry.maxTokens = txtMaxTokens.getText().trim();
        currentEntry.temperature = txtTemperature.getText().trim();
    }

    private void addConnection() {
        syncFormToEntry(); // save any unsaved edits first
        AIConnectionEntry newEntry = new AIConnectionEntry();
        newEntry.name = uniqueName("New Connection");
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
            MessageDialog.openWarning(getShell(), "Remove Connection",
                    "Please select a connection to remove.");
            return;
        }
        String name = connections.get(idx).name;
        boolean confirm = MessageDialog.openConfirm(getShell(), "Remove Connection",
                "Remove the connection \"" + name + "\"?");
        if (!confirm) return;
        boolean removedWasDefault = connections.get(idx).isDefault;
        connections.remove(idx);
        if (connections.isEmpty()) {
            // 至少保留一个空默认连接，保证插件始终有可用连接
            AIConnectionEntry def = new AIConnectionEntry();
            def.name = "Default";
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
     * 将右侧当前显示的连接设为插件使用的默认 AI，其余连接取消默认标识。
     */
    private void setAsDefault() {
        syncFormToEntry();
        if (currentEntry == null) {
            MessageDialog.openWarning(getShell(), "Set as Default",
                    "Please select a connection first.");
            return;
        }
        if (currentEntry.name == null || currentEntry.name.trim().isEmpty()) {
            MessageDialog.openWarning(getShell(), "Set as Default",
                    "Please enter an AI connection name first.");
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
            setTestResult("Please select or add a connection first", SWT.COLOR_RED);
            return;
        }
        String baseUrl = currentEntry.baseUrl.trim();
        String model = currentEntry.model.trim();
        String apiKey = currentEntry.apiKey.trim();
        String maxTokensStr = currentEntry.maxTokens.trim();
        String tempStr = currentEntry.temperature.trim();

        if (baseUrl.isEmpty()) {
            setTestResult("Please enter API Base URL", SWT.COLOR_RED);
            return;
        }
        if (apiKey.isEmpty()) {
            setTestResult("Please enter API Key", SWT.COLOR_RED);
            return;
        }

        setTestResult("Testing connection to " + baseUrl + "...", SWT.COLOR_BLUE);

        new Thread(() -> {
            try {
                int maxTokens = 20;
                double temp = 0.1;
                try { maxTokens = Integer.parseInt(maxTokensStr); } catch (Exception ignored) {}
                try { temp = Double.parseDouble(tempStr); } catch (Exception ignored) {}

                String result = AIClient.testConnection(baseUrl,
                        model.isEmpty() ? "gpt-4" : model, apiKey, maxTokens, temp);

                Display.getDefault().asyncExec(() ->
                    setTestResult("SUCCESS: " + result, SWT.COLOR_DARK_GREEN));
            } catch (AIClientException ex) {
                Display.getDefault().asyncExec(() ->
                    setTestResult("FAILED: " + ex.getMessage(), SWT.COLOR_RED));
            } catch (Exception ex) {
                Display.getDefault().asyncExec(() ->
                    setTestResult("ERROR: " + ex.getMessage(), SWT.COLOR_RED));
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
