package com.sap.abap.ai.completion.preferences;

import org.eclipse.jface.preference.ColorSelector;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.client.AIClient;
import com.sap.abap.ai.completion.client.AIClientException;

/**
 * Preference page for ABAP AI Completion.
 * Manually built UI (not FieldEditorPreferencePage) to avoid parent assertion issues.
 */
public class AICompletionPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Text txtBaseUrl;
    private Text txtModel;
    private Text txtApiKey;
    private Text txtMaxTokens;
    private Text txtTemperature;
    private Text txtSkillDir;
    private Text txtSystemPrompt;
    private Text txtAutoDelay;
    private Button chkPluginEnabled;
    private Button chkAutoComplete;
    private ColorSelector colorSelector;
    private Label lblTestResult;
    private Label lblKeybinding;
    private Button chkParentResolution;
    private Text txtSearchDepth;
    private Text txtMaxContextChars;
    private Button chkWorkspaceCodeRef;
    private Text txtMaxWorkspaceChars;
    private Text txtWorkspaceFileLimit;
    private Button chkInterfaceLogging;
    private Button chkSkillEnabled;
    private Spinner spinnerOpacity;

    private IPreferenceStore store;

    public AICompletionPreferencePage() {
        super("ABAP AI Completion");
        setDescription("Configure AI-powered ABAP code completion.\n"
                + "The AI suggests code in a floating overlay (like Copilot).\n"
                + "Press TAB to accept, any other key to dismiss.");
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
        createFeatureGroup(main);
        createAutoCompletionGroup(main);
        createParentProgramGroup(main);
        createContextGroup(main);
        createSkillGroup(main);
        createPromptGroup(main);
        createStyleGroup(main);
        createLoggingGroup(main);

        loadValues();

        return main;
    }

    // ==================== UI Groups ====================

    private void createConnectionGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("AI Connection Settings");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        createLabel(g, "API Base URL:");
        txtBaseUrl = createText(g, 1);

        createLabel(g, "Model Name:");
        txtModel = createText(g, 1);

        createLabel(g, "API Key:");
        txtApiKey = createText(g, 1);
        txtApiKey.setEchoChar('*');

        createLabel(g, "Max Tokens:");
        txtMaxTokens = createText(g, 1);

        createLabel(g, "Temperature:");
        txtTemperature = createText(g, 1);

        // Test button
        Button testBtn = new Button(g, SWT.PUSH);
        testBtn.setText("Test Connection");
        testBtn.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> testConnection()));

        lblTestResult = new Label(g, SWT.NONE);
        lblTestResult.setText("");
        lblTestResult.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
    }

    private void createFeatureGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("Feature Settings");
        g.setLayout(new GridLayout(1, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        chkPluginEnabled = new Button(g, SWT.CHECK);
        chkPluginEnabled.setText("Enable ABAP AI Completion plugin");
    }

    private void createAutoCompletionGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("Auto-Completion Settings");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        chkAutoComplete = new Button(g, SWT.CHECK);
        chkAutoComplete.setText("Auto-complete while typing (临时)");
        GridData ckGd = new GridData(GridData.FILL_HORIZONTAL);
        ckGd.horizontalSpan = 2;
        chkAutoComplete.setLayoutData(ckGd);

        createLabel(g, "Delay after typing (ms):");
        txtAutoDelay = createText(g, 1);

        Label note = new Label(g, SWT.WRAP);
        note.setText("How long to wait after you stop typing before AI suggests code.\n"
                + "Recommended: 1500-3000 ms. Lower values = more requests to the API.");
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 2;
        note.setLayoutData(nd);

        // Keybinding info
        lblKeybinding = new Label(g, SWT.WRAP);
        lblKeybinding.setText(
            "Manual trigger key: Ctrl+Shift+.\n"
            + "To change this keybinding: Window > Preferences > General > Keys\n"
            + "Search for 'ABAP AI completion'");
        GridData kd = new GridData(GridData.FILL_HORIZONTAL);
        kd.horizontalSpan = 2;
        kd.horizontalIndent = 10;
        lblKeybinding.setLayoutData(kd);
    }

    private void createSkillGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("Skill Directory");
        g.setLayout(new GridLayout(3, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        // 启用 SKILL 复选框 (放在 Skill Directory 输入框前面)
        chkSkillEnabled = new Button(g, SWT.CHECK);
        chkSkillEnabled.setText("Enable Skill reference for AI completion");
        GridData ckGd = new GridData(GridData.FILL_HORIZONTAL);
        ckGd.horizontalSpan = 3;
        chkSkillEnabled.setLayoutData(ckGd);

        createLabel(g, "Skill directory:");
        txtSkillDir = createText(g, 1);

        Button browseBtn = new Button(g, SWT.PUSH);
        browseBtn.setText("Browse...");
        browseBtn.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> browseSkillDir()));

        Label note = new Label(g, SWT.WRAP);
        note.setText("This directory contains skill subdirectories.\n"
                + "Each subdirectory is a skill with SKILL.md and reference files (.abap, .txt, .md, etc).\n"
                + "Skill files are filtered by code type (ABAP/CDS).\n"
                + "Leave empty to use default: <workspace>/.metadata/.plugins/com.sap.abap.ai.completion/skills");
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 3;
        note.setLayoutData(nd);
    }

    private void createParentProgramGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("Parent Program Resolution");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        chkParentResolution = new Button(g, SWT.CHECK);
        chkParentResolution.setText("Enable parent program reverse lookup");
        GridData ckGd = new GridData(GridData.FILL_HORIZONTAL);
        ckGd.horizontalSpan = 2;
        chkParentResolution.setLayoutData(ckGd);

        createLabel(g, "ABAP search depth (levels):");
        txtSearchDepth = createText(g, 1);

        createLabel(g, "Max context chars per parent:");
        txtMaxContextChars = createText(g, 1);

        Label note = new Label(g, SWT.WRAP);
        note.setText("Parent lookup searches ABAP files containing INCLUDE <current file>.\n"
                + "Search depth 0 = disable parent lookup, only current file code is sent.");
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 2;
        note.setLayoutData(nd);
    }

    private void createContextGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("Workspace Code Reference Set");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        chkWorkspaceCodeRef = new Button(g, SWT.CHECK);
        chkWorkspaceCodeRef.setText("Use workspace ABAP code as AI reference");
        GridData wsGd = new GridData(GridData.FILL_HORIZONTAL);
        wsGd.horizontalSpan = 2;
        chkWorkspaceCodeRef.setLayoutData(wsGd);

        createLabel(g, "Max workspace chars:");
        txtMaxWorkspaceChars = createText(g, 1);

        createLabel(g, "Max workspace files:");
        txtWorkspaceFileLimit = createText(g, 1);

        Label note = new Label(g, SWT.WRAP);
        note.setText("Workspace code reference sends other ABAP files from your workspace as AI context.");
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 2;
        note.setLayoutData(nd);
    }

    private void createLoggingGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("Interface Logging");
        g.setLayout(new GridLayout(1, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        chkInterfaceLogging = new Button(g, SWT.CHECK);
        chkInterfaceLogging.setText("Enable interface logging (system/user prompt + completion)");

        Label note = new Label(g, SWT.WRAP);
        note.setText("Logs are written to the plugin state area, not the Eclipse error log.\n"
                + "Log files are rotated hourly, named yyyyMMddHH_ai_abap.log (e.g. 2026080409_ai_abap.log).\n"
                + "Logs older than 7 days are automatically deleted.");
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 1;
        note.setLayoutData(nd);

        // 显示日志文件所在目录
        createLabel(g, "Log directory:");
        Text txtLogDir = new Text(g, SWT.BORDER | SWT.READ_ONLY);
        txtLogDir.setText(com.sap.abap.ai.completion.logging.AILogger.getLogDirectoryPath());
        txtLogDir.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label fileNote = new Label(g, SWT.WRAP);
        fileNote.setText("Current log file: <log directory>/yyyyMMddHH_ai_abap.log");
        GridData fnd = new GridData(GridData.FILL_HORIZONTAL);
        fnd.horizontalSpan = 1;
        fileNote.setLayoutData(fnd);
    }

    private void createPromptGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("Custom System Prompt");
        g.setLayout(new GridLayout(1, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        txtSystemPrompt = new Text(g, SWT.MULTI | SWT.BORDER | SWT.WRAP | SWT.V_SCROLL);
        GridData td = new GridData(GridData.FILL_HORIZONTAL);
        td.heightHint = 120;
        td.widthHint = 500;
        txtSystemPrompt.setLayoutData(td);
    }

    private void createStyleGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("Overlay Style");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        createLabel(g, "Completion text color:");
        colorSelector = new ColorSelector(g);

        createLabel(g, "Overlay opacity (%):");
        spinnerOpacity = new Spinner(g, SWT.BORDER);
        spinnerOpacity.setMinimum(10);
        spinnerOpacity.setMaximum(100);
        spinnerOpacity.setIncrement(5);
        spinnerOpacity.setPageIncrement(20);

        Label opacityNote = new Label(g, SWT.WRAP);
        opacityNote.setText("Opacity of the AI completion overlay window.\n"
                + "Lower values = more transparent. Range: 10-100%.");
        GridData ond = new GridData(GridData.FILL_HORIZONTAL);
        ond.horizontalSpan = 2;
        opacityNote.setLayoutData(ond);
    }

    // ==================== Data Loading/Saving ====================

    private void loadValues() {
        txtBaseUrl.setText(store.getString(PreferenceConstants.API_BASE_URL));
        txtModel.setText(store.getString(PreferenceConstants.API_MODEL));
        txtApiKey.setText(store.getString(PreferenceConstants.API_KEY));
        txtMaxTokens.setText(store.getString(PreferenceConstants.MAX_TOKENS));
        txtTemperature.setText(store.getString(PreferenceConstants.TEMPERATURE));
        txtSkillDir.setText(getDisplaySkillDir());
        chkSkillEnabled.setSelection(store.getBoolean(PreferenceConstants.SKILL_ENABLED));
        txtSystemPrompt.setText(store.getString(PreferenceConstants.SYSTEM_PROMPT));

        chkPluginEnabled.setSelection(store.getBoolean(PreferenceConstants.PLUGIN_ENABLED));
        chkAutoComplete.setSelection(store.getBoolean(PreferenceConstants.AUTO_COMPLETION_ENABLED));

        txtAutoDelay.setText(store.getString(PreferenceConstants.AUTO_COMPLETE_DELAY));

        chkParentResolution.setSelection(
                store.getBoolean(PreferenceConstants.PARENT_PROGRAM_RESOLUTION_ENABLED));
        txtSearchDepth.setText(store.getString(PreferenceConstants.ABAP_SEARCH_DEPTH));
        txtMaxContextChars.setText(store.getString(PreferenceConstants.MAX_CONTEXT_CHARS));

        chkWorkspaceCodeRef.setSelection(
                store.getBoolean(PreferenceConstants.WORKSPACE_CODE_REFERENCE_ENABLED));
        txtMaxWorkspaceChars.setText(store.getString(PreferenceConstants.MAX_WORKSPACE_CODE_CHARS));
        txtWorkspaceFileLimit.setText(store.getString(PreferenceConstants.WORKSPACE_CODE_FILE_LIMIT));

        chkInterfaceLogging.setSelection(
                store.getBoolean(PreferenceConstants.INTERFACE_LOGGING_ENABLED));

        // Color
        String colorStr = store.getString(PreferenceConstants.COMPLETION_COLOR);
        if (colorStr != null && !colorStr.isEmpty()) {
            colorSelector.setColorValue(AIConfiguration.getCompletionColor());
        }

        // Opacity
        spinnerOpacity.setSelection(AIConfiguration.getOverlayOpacityPercent());
    }

    private void saveValues() {
        store.setValue(PreferenceConstants.API_BASE_URL, txtBaseUrl.getText());
        store.setValue(PreferenceConstants.API_MODEL, txtModel.getText());
        store.setValue(PreferenceConstants.API_KEY, txtApiKey.getText());
        store.setValue(PreferenceConstants.MAX_TOKENS, txtMaxTokens.getText());
        store.setValue(PreferenceConstants.TEMPERATURE, txtTemperature.getText());
        String skillDirValue = txtSkillDir.getText().trim();
        String defaultSkillDir = AIConfiguration.getDefaultSkillDirectory();
        if (skillDirValue.isEmpty() || skillDirValue.equals(defaultSkillDir)) {
            store.setValue(PreferenceConstants.SKILL_DIR, "");
        } else {
            store.setValue(PreferenceConstants.SKILL_DIR, skillDirValue);
        }
        store.setValue(PreferenceConstants.SKILL_ENABLED, chkSkillEnabled.getSelection());
        store.setValue(PreferenceConstants.SYSTEM_PROMPT, txtSystemPrompt.getText());

        store.setValue(PreferenceConstants.PLUGIN_ENABLED, chkPluginEnabled.getSelection());
        store.setValue(PreferenceConstants.AUTO_COMPLETION_ENABLED, chkAutoComplete.getSelection());

        store.setValue(PreferenceConstants.AUTO_COMPLETE_DELAY, txtAutoDelay.getText());

        store.setValue(PreferenceConstants.PARENT_PROGRAM_RESOLUTION_ENABLED,
                chkParentResolution.getSelection());
        store.setValue(PreferenceConstants.ABAP_SEARCH_DEPTH, txtSearchDepth.getText());
        store.setValue(PreferenceConstants.MAX_CONTEXT_CHARS, txtMaxContextChars.getText());

        store.setValue(PreferenceConstants.WORKSPACE_CODE_REFERENCE_ENABLED,
                chkWorkspaceCodeRef.getSelection());
        store.setValue(PreferenceConstants.MAX_WORKSPACE_CODE_CHARS, txtMaxWorkspaceChars.getText());
        store.setValue(PreferenceConstants.WORKSPACE_CODE_FILE_LIMIT, txtWorkspaceFileLimit.getText());

        store.setValue(PreferenceConstants.INTERFACE_LOGGING_ENABLED,
                chkInterfaceLogging.getSelection());

        RGB rgb = colorSelector.getColorValue();
        store.setValue(PreferenceConstants.COMPLETION_COLOR,
                AIConfiguration.rgbToString(rgb));

        store.setValue(PreferenceConstants.OVERLAY_OPACITY,
                String.valueOf(spinnerOpacity.getSelection()));
    }

    @Override
    public boolean performOk() {
        saveValues();
        return true;
    }

    @Override
    protected void performDefaults() {
        txtBaseUrl.setText(PreferenceConstants.DEFAULT_API_BASE_URL);
        txtModel.setText(PreferenceConstants.DEFAULT_API_MODEL);
        txtApiKey.setText("");
        txtMaxTokens.setText(PreferenceConstants.DEFAULT_MAX_TOKENS);
        txtTemperature.setText(PreferenceConstants.DEFAULT_TEMPERATURE);
        txtSkillDir.setText(AIConfiguration.getDefaultSkillDirectory());
        chkSkillEnabled.setSelection(PreferenceConstants.DEFAULT_SKILL_ENABLED);
        txtSystemPrompt.setText(PreferenceConstants.DEFAULT_SYSTEM_PROMPT);

        chkPluginEnabled.setSelection(PreferenceConstants.DEFAULT_PLUGIN_ENABLED);
        chkAutoComplete.setSelection(PreferenceConstants.DEFAULT_AUTO_COMPLETION_ENABLED);

        txtAutoDelay.setText(PreferenceConstants.DEFAULT_AUTO_COMPLETE_DELAY);

        chkParentResolution.setSelection(
                PreferenceConstants.DEFAULT_PARENT_PROGRAM_RESOLUTION_ENABLED);
        txtSearchDepth.setText(PreferenceConstants.DEFAULT_ABAP_SEARCH_DEPTH);
        txtMaxContextChars.setText(PreferenceConstants.DEFAULT_MAX_CONTEXT_CHARS);

        chkWorkspaceCodeRef.setSelection(
                PreferenceConstants.DEFAULT_WORKSPACE_CODE_REFERENCE_ENABLED);
        txtMaxWorkspaceChars.setText(PreferenceConstants.DEFAULT_MAX_WORKSPACE_CODE_CHARS);
        txtWorkspaceFileLimit.setText(PreferenceConstants.DEFAULT_WORKSPACE_CODE_FILE_LIMIT);

        chkInterfaceLogging.setSelection(
                PreferenceConstants.DEFAULT_INTERFACE_LOGGING_ENABLED);

        colorSelector.setColorValue(new RGB(0, 128, 0));
        spinnerOpacity.setSelection(Integer.parseInt(PreferenceConstants.DEFAULT_OVERLAY_OPACITY));
    }

    // ==================== Test Connection ====================

    private void testConnection() {
        String baseUrl = txtBaseUrl.getText().trim();
        String model = txtModel.getText().trim();
        String apiKey = txtApiKey.getText().trim();
        String maxTokensStr = txtMaxTokens.getText().trim();
        String tempStr = txtTemperature.getText().trim();

        if (baseUrl.isEmpty()) {
            setTestResult("Please enter API Base URL", SWT.COLOR_RED);
            return;
        }
        if (apiKey.isEmpty()) {
            setTestResult("Please enter API Key", SWT.COLOR_RED);
            return;
        }

        setTestResult("Testing connection...", SWT.COLOR_BLUE);

        new Thread(() -> {
            try {
                int maxTokens = 20;
                double temp = 0.1;
                try { maxTokens = Integer.parseInt(maxTokensStr); } catch (Exception ignored) {}
                try { temp = Double.parseDouble(tempStr); } catch (Exception ignored) {}

                String result = AIClient.testConnection(baseUrl, model.isEmpty() ? "gpt-4" : model,
                        apiKey, maxTokens, temp);

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

    // ==================== Helpers ====================

    private Label createLabel(Composite parent, String text) {
        return createLabel(parent, text, 1);
    }

    private Label createLabel(Composite parent, String text, int hSpan) {
        Label lbl = new Label(parent, SWT.NONE);
        lbl.setText(text);
        GridData gd = new GridData();
        gd.horizontalSpan = hSpan;
        lbl.setLayoutData(gd);
        return lbl;
    }

    private Text createText(Composite parent, int hSpan) {
        Text txt = new Text(parent, SWT.BORDER);
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.horizontalSpan = hSpan;
        txt.setLayoutData(gd);
        return txt;
    }

    /**
     * 获取用于显示的 Skill 目录路径。
     * 如果用户未配置(空字符串),则返回计算出的默认路径用于显示。
     */
    private String getDisplaySkillDir() {
        String configured = store.getString(PreferenceConstants.SKILL_DIR);
        if (configured != null && !configured.trim().isEmpty()) {
            return configured;
        }
        return AIConfiguration.getDefaultSkillDirectory();
    }

    /**
     * 打开目录选择对话框,让用户选择本机目录作为 Skill 目录。
     */
    private void browseSkillDir() {
        DirectoryDialog dialog = new DirectoryDialog(getShell(), SWT.OPEN);
        dialog.setText("Select Skill Directory");
        dialog.setMessage("Select a directory containing .abap, .txt or .skill files:");

        String currentPath = txtSkillDir.getText().trim();
        if (currentPath != null && !currentPath.isEmpty()) {
            dialog.setFilterPath(currentPath);
        }

        String selected = dialog.open();
        if (selected != null && !selected.isEmpty()) {
            txtSkillDir.setText(selected);
        }
    }
}
