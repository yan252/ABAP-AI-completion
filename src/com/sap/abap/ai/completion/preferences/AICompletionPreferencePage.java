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
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
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
        createSkillGroup(main);
        createPromptGroup(main);
        createStyleGroup(main);

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

        chkAutoComplete = new Button(g, SWT.CHECK);
        chkAutoComplete.setText("Auto-complete while typing");

        // Keybinding info
        lblKeybinding = new Label(g, SWT.WRAP);
        lblKeybinding.setText(
            "Manual trigger key: Ctrl+Shift+.\n"
            + "To change this keybinding: Window > Preferences > General > Keys\n"
            + "Search for 'ABAP AI completion'");
        GridData kd = new GridData(GridData.FILL_HORIZONTAL);
        kd.horizontalIndent = 10;
        lblKeybinding.setLayoutData(kd);
    }

    private void createAutoCompletionGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("Auto-Completion Settings");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        createLabel(g, "Delay after typing (ms):");
        txtAutoDelay = createText(g, 1);

        Label note = new Label(g, SWT.WRAP);
        note.setText("How long to wait after you stop typing before AI suggests code.\n"
                + "Recommended: 1500-3000 ms. Lower values = more requests to the API.");
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 2;
        note.setLayoutData(nd);
    }

    private void createSkillGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("Skill Directory");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        createLabel(g, "Skill directory:");
        txtSkillDir = createText(g, 1);
        createLabel(g, "", 2); // spacer

        Label note = new Label(g, SWT.WRAP);
        note.setText("Place .abap, .txt or .skill files in this directory.\n"
                + "AI will use them as reference for code completion patterns.");
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 2;
        note.setLayoutData(nd);
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
    }

    // ==================== Data Loading/Saving ====================

    private void loadValues() {
        txtBaseUrl.setText(store.getString(PreferenceConstants.API_BASE_URL));
        txtModel.setText(store.getString(PreferenceConstants.API_MODEL));
        txtApiKey.setText(store.getString(PreferenceConstants.API_KEY));
        txtMaxTokens.setText(store.getString(PreferenceConstants.MAX_TOKENS));
        txtTemperature.setText(store.getString(PreferenceConstants.TEMPERATURE));
        txtSkillDir.setText(store.getString(PreferenceConstants.SKILL_DIR));
        txtSystemPrompt.setText(store.getString(PreferenceConstants.SYSTEM_PROMPT));

        chkPluginEnabled.setSelection(store.getBoolean(PreferenceConstants.PLUGIN_ENABLED));
        chkAutoComplete.setSelection(store.getBoolean(PreferenceConstants.AUTO_COMPLETION_ENABLED));

        txtAutoDelay.setText(store.getString(PreferenceConstants.AUTO_COMPLETE_DELAY));

        // Color
        String colorStr = store.getString(PreferenceConstants.COMPLETION_COLOR);
        if (colorStr != null && !colorStr.isEmpty()) {
            colorSelector.setColorValue(AIConfiguration.getCompletionColor());
        }
    }

    private void saveValues() {
        store.setValue(PreferenceConstants.API_BASE_URL, txtBaseUrl.getText());
        store.setValue(PreferenceConstants.API_MODEL, txtModel.getText());
        store.setValue(PreferenceConstants.API_KEY, txtApiKey.getText());
        store.setValue(PreferenceConstants.MAX_TOKENS, txtMaxTokens.getText());
        store.setValue(PreferenceConstants.TEMPERATURE, txtTemperature.getText());
        store.setValue(PreferenceConstants.SKILL_DIR, txtSkillDir.getText());
        store.setValue(PreferenceConstants.SYSTEM_PROMPT, txtSystemPrompt.getText());

        store.setValue(PreferenceConstants.PLUGIN_ENABLED, chkPluginEnabled.getSelection());
        store.setValue(PreferenceConstants.AUTO_COMPLETION_ENABLED, chkAutoComplete.getSelection());

        store.setValue(PreferenceConstants.AUTO_COMPLETE_DELAY, txtAutoDelay.getText());

        RGB rgb = colorSelector.getColorValue();
        store.setValue(PreferenceConstants.COMPLETION_COLOR,
                AIConfiguration.rgbToString(rgb));
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
        txtSkillDir.setText(PreferenceConstants.DEFAULT_SKILL_DIR);
        txtSystemPrompt.setText(PreferenceConstants.DEFAULT_SYSTEM_PROMPT);

        chkPluginEnabled.setSelection(PreferenceConstants.DEFAULT_PLUGIN_ENABLED);
        chkAutoComplete.setSelection(PreferenceConstants.DEFAULT_AUTO_COMPLETION_ENABLED);

        txtAutoDelay.setText(PreferenceConstants.DEFAULT_AUTO_COMPLETE_DELAY);

        colorSelector.setColorValue(new RGB(0, 128, 0));
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
}
