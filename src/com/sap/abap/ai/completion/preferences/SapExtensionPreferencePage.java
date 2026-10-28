package com.sap.abap.ai.completion.preferences;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.recent.NavigatorUtil;
import com.sap.abap.ai.completion.recent.RecentlyModifiedStore;

/**
 * "SAP 扩展 / SAP Extensions" 偏好页：SAP 相关扩展功能的配置。
 *
 * <p>目前包含 "Recently Modified" 组：</p>
 * <ul>
 *   <li>最大记录修改对象数（每个项目，默认 10）——超出时移除最旧的对象，
 *       保证 Project Explorer 中 "Recently Modified" 节点下数量不大于此配置。</li>
 * </ul>
 */
public class SapExtensionPreferencePage extends PreferencePage implements IWorkbenchPreferencePage {

    private Text txtMaxEntries;

    private IPreferenceStore store;

    public SapExtensionPreferencePage() {
        super(Messages.get("extPage.title"));
        setDescription(Messages.get("extPage.description"));
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

        Group g = new Group(main, SWT.NONE);
        g.setText(Messages.get("extPage.group.recentlyModified"));
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Label label = new Label(g, SWT.NONE);
        label.setText(Messages.get("extPage.label.maxEntries"));

        txtMaxEntries = new Text(g, SWT.BORDER);
        GridData td = new GridData(SWT.BEGINNING, SWT.CENTER, false, false);
        td.widthHint = 80;
        txtMaxEntries.setLayoutData(td);
        txtMaxEntries.addModifyListener(onModify);

        Label note = new Label(g, SWT.WRAP);
        note.setText(Messages.get("extPage.note.recentlyModified"));
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 2;
        nd.widthHint = 500;
        note.setLayoutData(nd);

        loadValues();
        return main;
    }

    private final ModifyListener onModify = e -> validate();

    private void loadValues() {
        txtMaxEntries.setText(String.valueOf(RecentlyModifiedStore.getMaxEntries()));
    }

    private boolean validate() {
        String text = txtMaxEntries.getText().trim();
        try {
            int value = Integer.parseInt(text);
            if (value < 1) throw new NumberFormatException();
            setErrorMessage(null);
            setValid(true);
            return true;
        } catch (NumberFormatException ex) {
            setErrorMessage(Messages.get("extPage.error.maxEntriesPositive"));
            setValid(false);
            return false;
        }
    }

    @Override
    public boolean performOk() {
        if (!validate()) return false;
        int max = Integer.parseInt(txtMaxEntries.getText().trim());
        store.setValue(PreferenceConstants.RECENTLY_MODIFIED_MAX_ENTRIES, max);

        // 立即裁剪并刷新受影响的项目节点
        for (String projectName : RecentlyModifiedStore.getInstance().trimAllToMax()) {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project != null) {
                NavigatorUtil.refreshProjectExplorer(project);
            }
        }
        return true;
    }

    @Override
    protected void performDefaults() {
        txtMaxEntries.setText(String.valueOf(
                PreferenceConstants.DEFAULT_RECENTLY_MODIFIED_MAX_ENTRIES));
        validate();
        super.performDefaults();
    }
}
