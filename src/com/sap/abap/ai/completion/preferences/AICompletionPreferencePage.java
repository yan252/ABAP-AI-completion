package com.sap.abap.ai.completion.preferences;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.preference.ColorSelector;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.jface.dialogs.MessageDialog;
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
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Spinner;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;

/**
 * Preference page for ABAP AI Completion.
 * Manually built UI (not FieldEditorPreferencePage) to avoid parent assertion issues.
 *
 * <p>Feature, prompt, style and logging settings are configured here.
 * The auto-completion trigger settings live on the separate top-level
 * "ABAP AI Auto-Completion Settings" page
 * (see {@link AutoCompletionPreferencePage}); the AI connection settings live
 * on the "AI Connections" child page (see {@link AIConnectionPreferencePage}).</p>
 */
public class AICompletionPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    // --- Other settings ---
    private Text txtSkillDir;
    private Button chkSkillEnabled;
    private Text txtSystemPrompt;    private Button chkPluginEnabled;    private ColorSelector colorSelector;    private Button chkParentResolution;
    private Button chkRecentlyModified;
    private Combo cmbLanguage;
    private Text txtSearchDepth;
    private Text txtMaxContextChars;
    private Button chkWorkspaceCodeRef;
    private Text txtMaxWorkspaceChars;
    private Text txtWorkspaceFileLimit;
    private Combo cmbLogLevel;
    private Spinner spinnerOpacity;
    private Combo cmbDisplayType;

    private IPreferenceStore store;
    private IWorkbench workbench;
    /** 打开页面时已保存的语言偏好值，用于检测用户是否修改了插件语言 */
    private String loadedLanguage;

    public AICompletionPreferencePage() {
        super(Messages.get("mainPage.title"));
        setDescription(Messages.get("mainPage.description"));
    }

    @Override
    public void init(IWorkbench workbench) {
        this.workbench = workbench;
        store = Activator.getDefault().getPreferenceStore();
    }

    @Override
    protected Control createContents(Composite parent) {
        Composite main = new Composite(parent, SWT.NONE);
        main.setLayout(new GridLayout(1, false));
        main.setLayoutData(new GridData(GridData.FILL_BOTH));

        createVersionHeader(main);
        createFeatureGroup(main);
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
        version.setText(Messages.format("mainPage.versionHeader", v));
        version.setFont(new org.eclipse.swt.graphics.Font(parent.getDisplay(),
                version.getFont().getFontData()[0].getName(), 11, SWT.BOLD));
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        version.setLayoutData(gd);

        Label hint = new Label(parent, SWT.WRAP);
        hint.setText(Messages.get("mainPage.headerHint"));
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
        g.setText(Messages.get("mainPage.group.feature"));
        g.setLayout(new GridLayout(1, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        chkPluginEnabled = new Button(g, SWT.CHECK);
        chkPluginEnabled.setText(Messages.get("mainPage.checkbox.enablePlugin"));

        chkRecentlyModified = new Button(g, SWT.CHECK);
        chkRecentlyModified.setText(Messages.get("mainPage.checkbox.recentlyModified"));

        Label favNote = new Label(g, SWT.WRAP);
        favNote.setText(Messages.get("mainPage.note.recentlyModified"));
        GridData fnGd = new GridData(GridData.FILL_HORIZONTAL);
        favNote.setLayoutData(fnGd);

        Composite langRow = new Composite(g, SWT.NONE);
        langRow.setLayout(new GridLayout(2, false));
        langRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        createLabel(langRow, Messages.get("mainPage.label.pluginLanguage"));
        cmbLanguage = new Combo(langRow, SWT.DROP_DOWN | SWT.READ_ONLY);
        cmbLanguage.add(Messages.get("mainPage.lang.zh"));
        cmbLanguage.add(Messages.get("mainPage.lang.en"));

        Label langNote = new Label(g, SWT.WRAP);
        langNote.setText(Messages.get("mainPage.note.language"));
        GridData lnGd = new GridData(GridData.FILL_HORIZONTAL);
        langNote.setLayoutData(lnGd);
    }

    private void createSkillGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText(Messages.get("mainPage.group.skill"));
        g.setLayout(new GridLayout(3, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        chkSkillEnabled = new Button(g, SWT.CHECK);
        chkSkillEnabled.setText(Messages.get("mainPage.checkbox.enableSkill"));
        GridData ckGd = new GridData(GridData.FILL_HORIZONTAL);
        ckGd.horizontalSpan = 3;
        chkSkillEnabled.setLayoutData(ckGd);

        createLabel(g, Messages.get("mainPage.label.skillDir"));
        txtSkillDir = createText(g);

        Button browseBtn = new Button(g, SWT.PUSH);
        browseBtn.setText(Messages.get("mainPage.button.browse"));
        browseBtn.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> browseSkillDir()));

        Label note = new Label(g, SWT.WRAP);
        note.setText(Messages.get("mainPage.note.skill"));
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 3;
        note.setLayoutData(nd);
    }

    private void createParentProgramGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText(Messages.get("mainPage.group.parent"));
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        chkParentResolution = new Button(g, SWT.CHECK);
        chkParentResolution.setText(Messages.get("mainPage.checkbox.enableParentResolution"));
        GridData ckGd = new GridData(GridData.FILL_HORIZONTAL);
        ckGd.horizontalSpan = 2;
        chkParentResolution.setLayoutData(ckGd);

        createLabel(g, Messages.get("mainPage.label.searchDepth"));
        txtSearchDepth = createText(g);

        createLabel(g, Messages.get("mainPage.label.maxContextChars"));
        txtMaxContextChars = createText(g);

        Label note = new Label(g, SWT.WRAP);
        note.setText(Messages.get("mainPage.note.parent"));
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 2;
        note.setLayoutData(nd);
    }

    private void createContextGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText(Messages.get("mainPage.group.workspace"));
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        chkWorkspaceCodeRef = new Button(g, SWT.CHECK);
        chkWorkspaceCodeRef.setText(Messages.get("mainPage.checkbox.useWorkspace"));
        GridData wsGd = new GridData(GridData.FILL_HORIZONTAL);
        wsGd.horizontalSpan = 2;
        chkWorkspaceCodeRef.setLayoutData(wsGd);

        createLabel(g, Messages.get("mainPage.label.maxWorkspaceChars"));
        txtMaxWorkspaceChars = createText(g);

        createLabel(g, Messages.get("mainPage.label.maxWorkspaceFiles"));
        txtWorkspaceFileLimit = createText(g);

        Label note = new Label(g, SWT.WRAP);
        note.setText(Messages.get("mainPage.note.workspace"));
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 2;
        note.setLayoutData(nd);
    }

    private void createLoggingGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText(Messages.get("mainPage.group.logging"));
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        createLabel(g, Messages.get("mainPage.label.logLevel"));
        cmbLogLevel = new Combo(g, SWT.DROP_DOWN | SWT.READ_ONLY);
        cmbLogLevel.add(Messages.get("mainPage.combo.logLevel0"));
        cmbLogLevel.add(Messages.get("mainPage.combo.logLevel1"));
        cmbLogLevel.add(Messages.get("mainPage.combo.logLevel2"));
        cmbLogLevel.select(PreferenceConstants.LOG_LEVEL_NONE);
        GridData cgGd = new GridData(GridData.FILL_HORIZONTAL);
        cmbLogLevel.setLayoutData(cgGd);

        Label note = new Label(g, SWT.WRAP);
        note.setText(Messages.get("mainPage.note.logging"));
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 2;
        note.setLayoutData(nd);
    }

    private void createPromptGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText(Messages.get("mainPage.group.prompt"));
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
        g.setText(Messages.get("mainPage.group.style"));
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        createLabel(g, Messages.get("mainPage.label.color"));
        colorSelector = new ColorSelector(g);

        createLabel(g, Messages.get("mainPage.label.displayType"));
        cmbDisplayType = new Combo(g, SWT.DROP_DOWN | SWT.READ_ONLY);
        cmbDisplayType.add(Messages.get("mainPage.combo.displayType1"));
        cmbDisplayType.add(Messages.get("mainPage.combo.displayType2"));
        GridData cdtGd = new GridData(GridData.FILL_HORIZONTAL);
        cmbDisplayType.setLayoutData(cdtGd);

        Label displayNote = new Label(g, SWT.WRAP);
        displayNote.setText(Messages.get("mainPage.note.displayType"));
        GridData dn = new GridData(GridData.FILL_HORIZONTAL);
        dn.horizontalSpan = 2;
        displayNote.setLayoutData(dn);

        createLabel(g, Messages.get("mainPage.label.opacity"));
        spinnerOpacity = new Spinner(g, SWT.BORDER);
        spinnerOpacity.setMinimum(10);
        spinnerOpacity.setMaximum(100);
        spinnerOpacity.setIncrement(5);
        spinnerOpacity.setPageIncrement(20);

        Label opacityNote = new Label(g, SWT.WRAP);
        opacityNote.setText(Messages.get("mainPage.note.opacity"));
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
        chkRecentlyModified.setSelection(
                store.getBoolean(PreferenceConstants.RECENTLY_MODIFIED_ON_SAVE_ENABLED));
        cmbLanguage.select(PreferenceConstants.LANG_ZH.equals(
                store.getString(PreferenceConstants.PLUGIN_LANGUAGE)) ? 0 : 1);
        loadedLanguage = store.getString(PreferenceConstants.PLUGIN_LANGUAGE);


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
        store.setValue(PreferenceConstants.RECENTLY_MODIFIED_ON_SAVE_ENABLED,
                chkRecentlyModified.getSelection());
        store.setValue(PreferenceConstants.PLUGIN_LANGUAGE, cmbLanguage.getSelectionIndex() == 0
                ? PreferenceConstants.LANG_ZH : PreferenceConstants.LANG_EN);


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
        String newLanguage = cmbLanguage.getSelectionIndex() == 0
                ? PreferenceConstants.LANG_ZH : PreferenceConstants.LANG_EN;
        boolean languageChanged = loadedLanguage != null
                && !newLanguage.equals(loadedLanguage);
        saveValues();
        if (languageChanged) {
            // 先对齐 Locale + 清缓存 → 让 TriggerChar.defaults() 返回新语言描述
            Locale locale = newLanguage.equals(PreferenceConstants.LANG_ZH)
                    ? Locale.SIMPLIFIED_CHINESE : Locale.ENGLISH;
            Locale.setDefault(locale);
            Messages.clearCache();
            com.sap.abap.ai.completion.ui.Messages.clearCache();
            com.sap.abap.ai.completion.ui.ImportMessages.clearCache();
            com.sap.abap.ai.completion.editor.Messages.clearCache();
            com.sap.abap.ai.completion.client.Messages.clearCache();
            com.sap.abap.ai.completion.sap.Messages.clearCache();
            // trigger char 的 description 持久化在偏好 JSON 里，语言切换后需要
            // 按新语言的 defaults() 重新覆盖——否则 .prefs 里存的还是旧语言字符串。
            // 必须在 Messages.clearCache() 之后调，确保 defaults() 返回新语言描述。
            List<TriggerChar> currentChars = TriggerChar.loadAll(store);
            TriggerChar.refreshDefaultDescriptions(currentChars);
            TriggerChar.saveAll(currentChars, store);

            // 硬重启（ProcessBuilder + System.exit）必须在退出前把所有偏好 flush 到磁盘，
            // 否则 JVM 被杀掉后内存里的新值（含 trigger char 新描述、pluginLanguage）
            // 就丢了——新进程启动时读磁盘上的旧值等于没改。
            try {
                org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE
                        .getNode(Activator.PLUGIN_ID).flush();
            } catch (org.osgi.service.prefs.BackingStoreException e) {
                // ignore - hard restart below means loss is acceptable
            }
            promptRestartForLanguageChange();
        }
        return true;
    }

    /**
     * 插件语言被修改并保存后，询问用户是否立即重启 Eclipse。
     * 重启时传递 -nl 命令行参数强制 Eclipse 在 OSGi 初始化之前就设置好 Locale，
     * 这样 Equinox 翻译 plugin.xml（含三个子配置页的树形节点名称）时就能用正确语言。
     * 直接 workbench.restart() 虽然会启动新 JVM，但新进程的 Locale 来自 OS 语言，
     * Activator.start() 里 Locale.setDefault() 执行时 Equinox 注册表早已缓存好翻译，
     * plugin.xml 名称无法切换。
     */
    private void promptRestartForLanguageChange() {
        boolean restart = MessageDialog.openQuestion(getShell(),
                Messages.get("mainPage.restart.title"),
                Messages.get("mainPage.restart.question"));
        if (!restart || workbench == null) {
            return;
        }
        // 等偏好对话框完全关闭后再执行重启
        Display.getDefault().asyncExec(() -> {
            try {
                restartEclipseWithLocale();
            } catch (Exception e) {
                // 硬重启失败则回退 workbench.restart()
                workbench.restart();
            }
        });
    }

    /**
     * 用指定语言的 -nl 参数启动新的 Eclipse 进程并退出当前 JVM。
     * -nl 参数在 Eclipse 启动最早期（OSGi 初始化之前）被解析，
     * 此时 Locale 就被设置好，Equinox 后续翻译 plugin.xml 时用正确语言。
     */
    private void restartEclipseWithLocale() throws Exception {
        // 目标语言：zh→zh_CN, en→en
        String nlCode = Locale.getDefault().getLanguage().equals("zh")
                ? "zh_CN" : "en";

        // 1) 找 Eclipse 可执行文件
        String launcher = System.getProperty("eclipse.launcher");
        if (launcher == null || launcher.isEmpty()) {
            String installArea = System.getProperty("osgi.install.area");
            if (installArea != null) {
                URI uri = new URI(installArea);
                File dir = new File(uri);
                String exeName = System.getProperty("os.name", "")
                        .toLowerCase().contains("win") ? "eclipse.exe" : "eclipse";
                launcher = new File(dir, exeName).getAbsolutePath();
            }
        }
        if (launcher == null || launcher.isEmpty() || !new File(launcher).exists()) {
            throw new IllegalStateException(
                    "Cannot locate Eclipse launcher (tried eclipse.launcher and osgi.install.area)");
        }

        // 2) 取当前命令行参数，替换（或追加）-nl 为目标语言
        List<String> cmd = new ArrayList<>();
        cmd.add(launcher);
        String[] currentArgs = Platform.getCommandLineArgs();
        boolean replacedNl = false;
        for (int i = 0; i < currentArgs.length; i++) {
            if ("-nl".equals(currentArgs[i]) && i + 1 < currentArgs.length) {
                cmd.add("-nl");
                cmd.add(nlCode);
                replacedNl = true;
                i++; // 跳过旧的 -nl 值
            } else {
                cmd.add(currentArgs[i]);
            }
        }
        if (!replacedNl) {
            cmd.add("-nl");
            cmd.add(nlCode);
        }

        // 3) 启动新进程并退出
        new ProcessBuilder(cmd).start();
        System.exit(0);
    }

    @Override
    protected void performDefaults() {
        txtSkillDir.setText(AIConfiguration.getDefaultSkillDirectory());
        chkSkillEnabled.setSelection(PreferenceConstants.DEFAULT_SKILL_ENABLED);
        txtSystemPrompt.setText(PreferenceConstants.DEFAULT_SYSTEM_PROMPT);

        chkPluginEnabled.setSelection(PreferenceConstants.DEFAULT_PLUGIN_ENABLED);
        chkRecentlyModified.setSelection(
                PreferenceConstants.DEFAULT_RECENTLY_MODIFIED_ON_SAVE_ENABLED);
        cmbLanguage.select(PreferenceConstants.LANG_ZH.equals(
                PreferenceConstants.DEFAULT_PLUGIN_LANGUAGE) ? 0 : 1);


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
        dialog.setText(Messages.get("mainPage.dialog.selectSkillDir.title"));
        dialog.setMessage(Messages.get("mainPage.dialog.selectSkillDir.message"));
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
