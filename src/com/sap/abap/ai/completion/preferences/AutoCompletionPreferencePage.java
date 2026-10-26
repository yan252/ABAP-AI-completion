package com.sap.abap.ai.completion.preferences;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPreferencePage;

import com.sap.abap.ai.completion.Activator;

/**
 * ABAP AI Auto-Completion Settings 配置页（"ABAP AI Completion" 的子页面）。
 *
 * <p>子页面按名称字母序排列，页面命名以 "ABAP AI " 开头，确保显示在
 * "AI Connections" 子页面的前面。承载自动补全开关、触发延迟以及"触发字符"
 * 维护三列列表（触发字符 / 是否激活 / 功能描述），支持增删与还原默认值（Restore Default）。</p>
 */
public class AutoCompletionPreferencePage extends PreferencePage
        implements IWorkbenchPreferencePage {

    private IPreferenceStore store;

    private Button chkAutoComplete;
    private Text txtAutoDelay;

    private Table table;
    /** 当前页面上正在编辑的触发字符列表 */
    private final List<TriggerChar> items = new ArrayList<>();
    /** 每个表格行对应的可编辑控件（用于读取与清理） */
    private final List<TableRowEditor> rowEditors = new ArrayList<>();

    /** 表格单行的可编辑控件封装 */
    private static final class TableRowEditor {
        Text charText;
        Button activeCheck;
        Text descText;
        TriggerChar data;
    }

    public AutoCompletionPreferencePage() {
        super(Messages.get("autoPage.title"));
        setDescription(Messages.get("autoPage.description"));
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

        createAutoCompletionGroup(main);
        createTriggerCharGroup(main);

        loadValues();
        return main;
    }

    // ==================== UI Groups ====================

    private void createAutoCompletionGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText(Messages.get("autoPage.title"));
        g.setLayout(new GridLayout(2, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        chkAutoComplete = new Button(g, SWT.CHECK);
        chkAutoComplete.setText(Messages.get("autoPage.checkbox.autoComplete"));
        GridData ckGd = new GridData(GridData.FILL_HORIZONTAL);
        ckGd.horizontalSpan = 2;
        chkAutoComplete.setLayoutData(ckGd);

        createLabel(g, Messages.get("autoPage.label.delay"));
        txtAutoDelay = createText(g);

        Label note = new Label(g, SWT.WRAP);
        note.setText(Messages.get("autoPage.note.delay"));
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 2;
        note.setLayoutData(nd);

        Label key = new Label(g, SWT.WRAP);
        key.setText(Messages.get("autoPage.note.manualKey"));
        GridData kd = new GridData(GridData.FILL_HORIZONTAL);
        kd.horizontalSpan = 2;
        kd.horizontalIndent = 10;
        key.setLayoutData(kd);
    }

    private void createTriggerCharGroup(Composite parent) {
        Group g = new Group(parent, SWT.NONE);
        g.setText(Messages.get("autoPage.group.triggerChars"));
        g.setLayout(new GridLayout(1, false));
        g.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        table = new Table(g, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tgd = new GridData(GridData.FILL_BOTH);
        tgd.heightHint = 8 * 18 + 30;
        tgd.widthHint = 520;
        table.setLayoutData(tgd);

        TableColumn colChar = new TableColumn(table, SWT.LEFT);
        colChar.setText(Messages.get("autoPage.column.triggerChar"));
        colChar.setWidth(150);

        TableColumn colActive = new TableColumn(table, SWT.CENTER);
        colActive.setText(Messages.get("autoPage.column.active"));
        colActive.setWidth(55);

        TableColumn colDesc = new TableColumn(table, SWT.LEFT);
        colDesc.setText(Messages.get("autoPage.column.description"));
        colDesc.setWidth(300);

        // Buttons
        Composite btnBar = new Composite(g, SWT.NONE);
        btnBar.setLayout(new GridLayout(2, false));
        btnBar.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_BEGINNING));

        Button addBtn = new Button(btnBar, SWT.PUSH);
        addBtn.setText(Messages.get("autoPage.button.add"));
        addBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                items.add(new TriggerChar("", true, Messages.get("autoPage.newTriggerDesc")));
                refreshTable();
                setValid(true);
            }
        });

        Button removeBtn = new Button(btnBar, SWT.PUSH);
        removeBtn.setText(Messages.get("autoPage.button.remove"));
        removeBtn.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                int[] sel = table.getSelectionIndices();
                if (sel.length > 0) {
                    items.remove(sel[sel.length - 1]);
                    refreshTable();
                    setValid(true);
                }
            }
        });

        Label note = new Label(g, SWT.WRAP);
        note.setText(Messages.get("autoPage.note.triggerChars"));
        GridData nd = new GridData(GridData.FILL_HORIZONTAL);
        nd.horizontalSpan = 1;
        note.setLayoutData(nd);
    }

    // ==================== Table building ====================

    private void refreshTable() {
        disposeEditorControls();
        table.removeAll();
        rowEditors.clear();

        for (TriggerChar data : items) {
            TableItem row = new TableItem(table, SWT.NONE);
            row.setText(0, data.triggerChar == null ? "" : data.triggerChar);
            row.setText(2, data.description == null ? "" : data.description);

            TableRowEditor ed = new TableRowEditor();
            ed.data = data;

            // Column 1: Active checkbox
            ed.activeCheck = new Button(table, SWT.CHECK);
            ed.activeCheck.setSelection(data.enabled);
            ed.activeCheck.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    ed.data.enabled = ed.activeCheck.getSelection();
                }
            });
            TableEditor activeEd = new TableEditor(table);
            activeEd.grabHorizontal = true;
            activeEd.grabVertical = true;
            activeEd.horizontalAlignment = SWT.CENTER;
            activeEd.verticalAlignment = SWT.CENTER;
            activeEd.setEditor(ed.activeCheck, row, 1);

            // Column 0: Trigger character text
            ed.charText = new Text(table, SWT.BORDER);
            ed.charText.setText(data.triggerChar == null ? "" : data.triggerChar);
            final TableRowEditor charOwner = ed;
            charOwner.charText.addModifyListener(e ->
                    charOwner.data.triggerChar = charOwner.charText.getText());
            TableEditor charEd = new TableEditor(table);
            charEd.horizontalAlignment = SWT.LEFT;
            charEd.minimumWidth = 130;
            charEd.setEditor(charOwner.charText, row, 0);

            // Column 2: Description text
            ed.descText = new Text(table, SWT.BORDER);
            ed.descText.setText(data.description == null ? "" : data.description);
            final TableRowEditor descOwner = ed;
            descOwner.descText.addModifyListener(e ->
                    descOwner.data.description = descOwner.descText.getText());
            TableEditor descEd = new TableEditor(table);
            descEd.horizontalAlignment = SWT.LEFT;
            descEd.minimumWidth = 280;
            descEd.setEditor(descOwner.descText, row, 2);

            rowEditors.add(ed);
        }
    }

    private void disposeEditorControls() {
        for (TableRowEditor ed : rowEditors) {
            disposeIfValid(ed.charText);
            disposeIfValid(ed.activeCheck);
            disposeIfValid(ed.descText);
        }
        rowEditors.clear();
    }

    private static void disposeIfValid(Control c) {
        if (c != null && !c.isDisposed()) {
            c.dispose();
        }
    }

    // ==================== Data loading / saving ====================

    private void loadValues() {
        chkAutoComplete.setSelection(store.getBoolean(PreferenceConstants.AUTO_COMPLETION_ENABLED));
        String delay = store.getString(PreferenceConstants.AUTO_COMPLETE_DELAY);
        txtAutoDelay.setText(delay == null || delay.isEmpty()
                ? PreferenceConstants.DEFAULT_AUTO_COMPLETE_DELAY : delay);

        items.clear();
        items.addAll(TriggerChar.loadAll(store));
        refreshTable();
    }

    private boolean validate() {
        // 触发字符不能为空或全为空格
        for (TriggerChar data : items) {
            if (data.triggerChar == null || data.triggerChar.trim().isEmpty()) {
                setErrorMessage(Messages.get("autoPage.error.triggerCharEmpty"));
                setValid(false);
                return false;
            }
        }
        // 延迟必须是正整数
        String delay = txtAutoDelay.getText().trim();
        try {
            if (Integer.parseInt(delay) <= 0) {
                setErrorMessage(Messages.get("autoPage.error.delayPositive"));
                setValid(false);
                return false;
            }
        } catch (NumberFormatException e) {
            setErrorMessage(Messages.get("autoPage.error.delayPositive"));
            setValid(false);
            return false;
        }
        setErrorMessage(null);
        setValid(true);
        return true;
    }

    @Override
    public boolean performOk() {
        if (!validate()) {
            return false;
        }
        store.setValue(PreferenceConstants.AUTO_COMPLETION_ENABLED, chkAutoComplete.getSelection());
        store.setValue(PreferenceConstants.AUTO_COMPLETE_DELAY, txtAutoDelay.getText().trim());
        // 触发字符统一转为大写后保存（ABAP 关键字大小写不敏感），表格同步刷新为大写
        for (TriggerChar data : items) {
            if (data.triggerChar != null) {
                data.triggerChar = data.triggerChar.toUpperCase();
            }
        }
        refreshTable();
        AIConfiguration.saveTriggerChars(items);
        return super.performOk();
    }

    @Override
    protected void performDefaults() {
        chkAutoComplete.setSelection(PreferenceConstants.DEFAULT_AUTO_COMPLETION_ENABLED);
        txtAutoDelay.setText(PreferenceConstants.DEFAULT_AUTO_COMPLETE_DELAY);
        items.clear();
        items.addAll(TriggerChar.defaults());
        refreshTable();
        setValid(true);
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
        gd.horizontalSpan = 2;
        txt.setLayoutData(gd);
        return txt;
    }
}
