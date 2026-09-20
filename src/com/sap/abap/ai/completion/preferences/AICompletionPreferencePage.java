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
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.DirectoryDialog;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.sap.abap.ai.completion.Activator;

/**
 * Preference page for ABAP AI Completion.
 * Manually built UI (not FieldEditorPreferencePage) to avoid parent assertion issues.
 *
 * <p>Feature, auto-completion, prompt, style and logging settings are configured here.
 * The AI connection settings live on the separate "AI Connections" child page
 * (see {@link AIConnectionPreferencePage}).</p>
 */
public class AICompletionPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    // --- Other settings ---
    private Text txtSkillDir;
    private Button chkSkillEnabled;
    private Text txtSystemPrompt;
    private Text txtAutoDelay;
    private Button chkPluginEnabled;
    private Button chkAutoComplete;
    private ColorSelector colorSelector;
    private Label lblKeybinding;
    private Button chkParentResolution;
    private Text txtSearchDepth;
    private Text txtMaxContextChars;
    private Button chkWorkspaceCodeRef;
    private Text txtMaxWorkspaceChars;
    private Text txtWorkspaceFileLimit;
    private Combo cmbLogLevel;
    private Spinner spinnerOpacity;
    private Combo cmbDisplayType;

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

        createVersionHeader(main);
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

    /**
     * 在配置页顶部显示插件名称与版本号。
     */
    private void createVersionHeader(Composite parent) {
        Label version = new Label(parent, SWT.NONE);
        String v = getPluginVersion();
        version.setText("ABAP AI Completion  v" + v);
        version.setFont(new org.eclipse.swt.graphics.Font(parent.getDisplay(),
                version.getFont().getFontData()[0].getName(), 11, SWT.BOLD));
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        version.setLayoutData(gd);

        Label hint = new Label(parent, SWT.WRAP);
        hint.setText("Configure AI-powered ABAP code completion in the sections below.");
        GridData hd = new GridData(GridData.FILL_HORIZONTAL);
        hint.setLayoutData(hd);

        // Separator
        Label sep = new Label(parent, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridData sd = new GridData(GridData.FILL_HORIZONTAL);
        sep.setLayoutData(sd);
    }

    private static String getPluginVersion() {
        try {
            org.osgi.framework.Bundle bundle =
                    org.eclipse.core.runtime.Platform.getBundle(Activator.PLUGIN_ID);
            if (bundle == null) return "unknown";
            return bundle.getVersion().toString();
        } catch (Exception e) {
            return "unknown";
        }
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
        chkAutoComplete.setText("Auto-complete while typing (Temporary)");
        GridData ckGd = new GridData(GridData.FILL_HORIZONTAL);
        ckGd.horizontalSpan = 2;
        chkAutoComplete.setLayoutData(ckGd);

        createLabel(g, "Delay after typing (ms):");
        txtAutoDelay = createText(g);

        Label note = new Label(g, SWT.WRAP);
        note.setText("How long to wait after you stop typing before AI suggests code.\n"
                + "Recommended: 1500-3000 ms. Lower values = more requests to the API.");
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 2;
        note.setLayoutData(nd);

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

        chkSkillEnabled = new Button(g, SWT.CHECK);
        chkSkillEnabled.setText("Enable Skill reference for AI completion");
        GridData ckGd = new GridData(GridData.FILL_HORIZONTAL);
        ckGd.horizontalSpan = 3;
        chkSkillEnabled.setLayoutData(ckGd);

        createLabel(g, "Skill directory:");
        txtSkillDir = createText(g);

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
        txtSearchDepth = createText(g);

        createLabel(g, "Max context chars per parent:");
        txtMaxContextChars = createText(g);

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
        txtMaxWorkspaceChars = createText(g);

        createLabel(g, "Max workspace files:");
        txtWorkspaceFileLimit = createText(g);

        Label note = new Label(g, SWT.WRAP);
        note.setText("Workspace code reference sends other ABAP files from your workspace as AI context.");
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 2;
        note.setLayoutData(nd);
    }

    private void createLoggingGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("Interface Logging");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        createLabel(g, "Log level:");
        cmbLogLevel = new Combo(g, SWT.DROP_DOWN | SWT.READ_ONLY);
        cmbLogLevel.add("0 - No logging");
        cmbLogLevel.add("1 - Normal logging (interface request/response)");
        cmbLogLevel.add("2 - DEBUG logging");
        cmbLogLevel.select(PreferenceConstants.LOG_LEVEL_NONE);
        GridData cgGd = new GridData(GridData.FILL_HORIZONTAL);
        cmbLogLevel.setLayoutData(cgGd);

        Label note = new Label(g, SWT.WRAP);
        note.setText("0 = No logging; 1 = Record interface request/response logs; "
                + "2 = Additionally record DEBUG logs (parser, context collection, etc.).\n"
                + "Logs are written to the plugin state area, not the Eclipse error log.\n"
                + "See AILogger for the exact path under <workspace>/.metadata/.plugins/.");
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
        td.widthHint = 420;
        txtSystemPrompt.setLayoutData(td);
    }

    private void createStyleGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText("Overlay Style");
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        createLabel(g, "Completion text color:");
        colorSelector = new ColorSelector(g);

        createLabel(g, "Completion display type:");
        cmbDisplayType = new Combo(g, SWT.DROP_DOWN | SWT.READ_ONLY);
        cmbDisplayType.add("1 - Dialog display (show in popup window)");
        cmbDisplayType.add("2 - Inline display (show at cursor, like Copilot)");
        GridData cdtGd = new GridData(GridData.FILL_HORIZONTAL);
        cmbDisplayType.setLayoutData(cdtGd);

        Label displayNote = new Label(g, SWT.WRAP);
        displayNote.setText("How AI completion code is shown.\n"
                + "1 - Dialog display: shows the code in a floating popup window (default).\n"
                + "2 - Inline display: shows ghost text at the cursor position without inserting; "
                + "press TAB or Enter to accept, any other key cancels.");
        GridData dn = new GridData(GridData.FILL_HORIZONTAL);
        dn.horizontalSpan = 2;
        displayNote.setLayoutData(dn);

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

    // ==================== Data Loading/Saving (other settings) ====================

    private void loadValues() {
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

        cmbLogLevel.select(clampLogLevelIndex(
                Integer.parseInt(store.getString(PreferenceConstants.INTERFACE_LOG_LEVEL))));

        String colorStr = store.getString(PreferenceConstants.COMPLETION_COLOR);
        if (colorStr != null && !colorStr.isEmpty()) {
            colorSelector.setColorValue(AIConfiguration.getCompletionColor());
        }

        spinnerOpacity.setSelection(AIConfiguration.getOverlayOpacityPercent());
        cmbDisplayType.select(AIConfiguration.getCompletionDisplayType() - 1);
    }

    private void saveValues() {
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

        store.setValue(PreferenceConstants.INTERFACE_LOG_LEVEL,
                String.valueOf(cmbLogLevel.getSelectionIndex()));

        RGB rgb = colorSelector.getColorValue();
        store.setValue(PreferenceConstants.COMPLETION_COLOR,
                AIConfiguration.rgbToString(rgb));

        store.setValue(PreferenceConstants.OVERLAY_OPACITY,
                String.valueOf(spinnerOpacity.getSelection()));

        store.setValue(PreferenceConstants.COMPLETION_DISPLAY_TYPE,
                String.valueOf(cmbDisplayType.getSelectionIndex() + 1));
    }

    @Override
    public boolean performOk() {
        saveValues();
        return true;
    }

    @Override
    protected void performDefaults() {
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

        cmbLogLevel.select(clampLogLevelIndex(
                Integer.parseInt(PreferenceConstants.DEFAULT_INTERFACE_LOG_LEVEL)));

        colorSelector.setColorValue(new RGB(0, 128, 0));
        spinnerOpacity.setSelection(Integer.parseInt(PreferenceConstants.DEFAULT_OVERLAY_OPACITY));
        cmbDisplayType.select(PreferenceConstants.DEFAULT_COMPLETION_DISPLAY_TYPE - 1);
    }

    // ==================== Helpers ====================

    private static int clampLogLevelIndex(int value) {
        return Math.max(PreferenceConstants.LOG_LEVEL_NONE,
                Math.min(PreferenceConstants.LOG_LEVEL_DEBUG, value));
    }

    private Label createLabel(Composite parent, String text) {
        Label lbl = new Label(parent, SWT.NONE);
        lbl.setText(text);
        return lbl;
    }

    private Text createText(Composite parent) {
        Text txt = new Text(parent, SWT.BORDER);
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        gd.horizontalSpan = 2;
        txt.setLayoutData(gd);
        return txt;
    }

    private String getDisplaySkillDir() {
        String configured = store.getString(PreferenceConstants.SKILL_DIR);
        if (configured != null && !configured.trim().isEmpty()) {
            return configured;
        }
        return AIConfiguration.getDefaultSkillDirectory();
    }

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
