package com.sap.abap.ai.completion.codesearch;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.texteditor.ITextEditor;

/**
 * Shared find/replace form of the ABAP Code Search feature. It is embedded
 * both in the standalone dialog (Ctrl+Shift+H) and in the first tab of the
 * standard Eclipse Search dialog (Ctrl+H).
 */
public final class AbapCodeSearchPanel {

    private Composite root;
    private Text txtPattern;
    private Button chkCaseSensitive;
    private Button chkReplace;
    private Text txtReplace;
    private Button rdoMainProgram;
    private Button rdoIncludeProgram;
    private Combo cmbInclude;
    private Label lblStatus;
    private Label lblPluginInfo;

    private CodeSearchContext context;
    private volatile CodeSearchSourceClient.PreparedSources prepared;
    private volatile String prepareError;
    private Job prepareJob;
    private boolean started;
    private boolean disposed;
    private Runnable replaceFinished;

    public Composite create(Composite parent) {
        root = new Composite(parent, SWT.NONE);
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        root.setLayout(new GridLayout(1, false));

        Label patternLabel = new Label(root, SWT.NONE);
        patternLabel.setText(Messages.get("label.pattern"));

        txtPattern = new Text(root, SWT.BORDER);
        txtPattern.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        chkCaseSensitive = new Button(root, SWT.CHECK);
        chkCaseSensitive.setText(Messages.get("chk.caseSensitive"));

        Composite replaceRow = new Composite(root, SWT.NONE);
        replaceRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        replaceRow.setLayout(new GridLayout(2, false));

        chkReplace = new Button(replaceRow, SWT.CHECK);
        chkReplace.setText(Messages.get("chk.replaceWith"));
        chkReplace.addListener(SWT.Selection, e -> updateReplaceEnabled());

        txtReplace = new Text(replaceRow, SWT.BORDER);
        txtReplace.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        txtReplace.setEnabled(false);

        org.eclipse.swt.widgets.Group scopeGroup = new org.eclipse.swt.widgets.Group(root, SWT.NONE);
        scopeGroup.setText(Messages.get("group.scope"));
        scopeGroup.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        scopeGroup.setLayout(new GridLayout(2, false));

        rdoMainProgram = new Button(scopeGroup, SWT.RADIO);
        rdoMainProgram.setText(Messages.get("rdo.mainProgram"));
        rdoMainProgram.setSelection(true);

        // Keeps the include radio button in the second row so that the combo
        // box can be placed right next to it.
        new Label(scopeGroup, SWT.NONE);

        rdoIncludeProgram = new Button(scopeGroup, SWT.RADIO);
        rdoIncludeProgram.setText(Messages.get("rdo.includeProgram"));

        cmbInclude = new Combo(scopeGroup, SWT.READ_ONLY | SWT.DROP_DOWN);
        GridData comboData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        comboData.horizontalIndent = 6;
        cmbInclude.setLayoutData(comboData);
        cmbInclude.setEnabled(false);

        // The radio buttons are separated by a spacer label in the layout, so
        // mutual exclusion is enforced explicitly to stay independent of the
        // platform specific radio grouping behavior.
        rdoMainProgram.addListener(SWT.Selection, e -> {
            if (rdoMainProgram.getSelection()) {
                rdoIncludeProgram.setSelection(false);
            }
            updateScopeEnabled();
        });
        rdoIncludeProgram.addListener(SWT.Selection, e -> {
            if (rdoIncludeProgram.getSelection()) {
                rdoMainProgram.setSelection(false);
            }
            updateScopeEnabled();
        });

        lblStatus = new Label(root, SWT.WRAP);
        lblStatus.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        // Attribution of the plugin. Hidden by default because the standalone
        // dialog already shows it in the message area; the Search dialog page
        // (Ctrl+H) has no message area and reveals it instead.
        lblPluginInfo = new Label(root, SWT.WRAP);
        lblPluginInfo.setText(Messages.get("info.plugin"));
        GridData infoData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        infoData.exclude = true;
        lblPluginInfo.setLayoutData(infoData);
        lblPluginInfo.setVisible(false);

        updateReplaceEnabled();
        updateScopeEnabled();
        return root;
    }

    /** Shows the plugin attribution inside the form (used by the Ctrl+H tab). */
    public void showPluginInfo() {
        if (lblPluginInfo == null || lblPluginInfo.isDisposed()) {
            return;
        }
        GridData data = (GridData) lblPluginInfo.getLayoutData();
        data.exclude = false;
        lblPluginInfo.setVisible(true);
        root.layout(true, true);
    }

    private void updateReplaceEnabled() {
        if (txtReplace != null && !txtReplace.isDisposed()) {
            txtReplace.setEnabled(chkReplace.getSelection());
        }
    }

    private void updateScopeEnabled() {
        if (cmbInclude == null || cmbInclude.isDisposed()) {
            return;
        }
        cmbInclude.setEnabled(rdoIncludeProgram.getSelection() && prepared != null);
    }

    /** Starts the (background) source loading; call once the form is visible. */
    public void startPrepareIfNeeded() {
        if (started || disposed) {
            return;
        }
        started = true;
        context = CodeSearchContext.fromActiveEditor();
        if (context == null) {
            setStatus(Messages.get("error.noAbapEditor"));
            return;
        }
        if (context.project == null) {
            setStatus(Messages.get("error.noSapProject"));
            return;
        }
        String editorSource = context.readCurrentSource();
        if (editorSource == null || editorSource.trim().isEmpty()) {
            setStatus(Messages.get("error.noEditorDocument"));
            return;
        }
        setBusy(true);
        // Forward the editor document only when it actually holds the main
        // program (or the INCLUDE being edited). For function modules the
        // document shows the function source, while the searched main program
        // is SAPL<group> and must be read from the backend.
        final String capturedSource = context.editorShowsMain || context.includeEntry
                ? editorSource : null;
        prepareJob = new Job("ABAP Code Search") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    prepared = CodeSearchSourceClient.prepare(
                            context.project, context.programName, context.programType,
                            capturedSource, context.includeEntry);
                } catch (Throwable t) {
                    prepareError = t.getMessage() == null ? t.toString() : t.getMessage();
                }
                Display.getDefault().asyncExec(AbapCodeSearchPanel.this::applyPreparedState);
                return Status.OK_STATUS;
            }
        };
        prepareJob.setUser(false);
        prepareJob.setPriority(Job.LONG);
        prepareJob.schedule();
    }

    private void applyPreparedState() {
        if (disposed || root == null || root.isDisposed()) {
            return;
        }
        if (prepared == null) {
            setBusy(false);
            setStatus(Messages.format("error.prepareFailed",
                    prepareError == null ? Messages.get("error.runtimeMissing") : prepareError));
            return;
        }
        cmbInclude.removeAll();
        for (CodeSearchSourceClient.IncludeProgram include : prepared.includes) {
            cmbInclude.add(include.name);
        }
        if (cmbInclude.getItemCount() == 0) {
            cmbInclude.add(Messages.get("combo.noIncludes"));
            cmbInclude.select(0);
        } else {
            int selected = 0;
            if (context != null && context.includeEntry) {
                for (int i = 0; i < cmbInclude.getItemCount(); i++) {
                    if (prepared.includes.get(i).name.equalsIgnoreCase(context.programName)) {
                        selected = i;
                        break;
                    }
                }
            }
            cmbInclude.select(selected);
        }
        // The scope always defaults to "In main program", no matter what the
        // active editor shows (main program, INCLUDE or function module).
        // The combo above still preselects the edited INCLUDE so switching
        // the scope manually is a single click.
        rdoMainProgram.setSelection(true);
        rdoIncludeProgram.setSelection(false);
        updateScopeEnabled();
        setBusy(false);
        setStatus(Messages.format("status.ready", prepared.includes.size()));
    }

    private void setBusy(boolean busy) {
        if (root == null || root.isDisposed()) {
            return;
        }
        // The pattern field stays enabled while the include list is loading:
        // a disabled control cannot take the focus, which would silently break
        // the "caret starts in the search string field" behavior.
        chkCaseSensitive.setEnabled(!busy);
        chkReplace.setEnabled(!busy);
        txtReplace.setEnabled(!busy && chkReplace.getSelection());
        rdoMainProgram.setEnabled(!busy);
        rdoIncludeProgram.setEnabled(!busy);
        cmbInclude.setEnabled(!busy && rdoIncludeProgram.getSelection() && prepared != null);
        if (busy) {
            setStatus(Messages.get("status.loading"));
        } else {
            updateScopeEnabled();
        }
    }

    private void setStatus(String message) {
        if (lblStatus != null && !lblStatus.isDisposed()) {
            lblStatus.setText(message == null ? "" : message);
        }
    }

    public String getPattern() {
        return txtPattern.getText();
    }

    /** Moves the caret into the search string field. */
    public void focusPattern() {
        if (txtPattern != null && !txtPattern.isDisposed()) {
            txtPattern.setFocus();
        }
    }

    public boolean isCaseSensitive() {
        return chkCaseSensitive.getSelection();
    }

    public boolean isReplaceEnabled() {
        return chkReplace.getSelection();
    }

    public String getReplacement() {
        return txtReplace.getText();
    }

    /** Validates the form; returns an error message or {@code null}. */
    private String validate() {
        String pattern = txtPattern.getText();
        if (pattern == null || pattern.isEmpty()) {
            return Messages.get("error.patternEmpty");
        }
        try {
            CodeSearchMatcher.create(pattern, true, chkCaseSensitive.getSelection());
        } catch (Throwable t) {
            return Messages.format("error.invalidRegex", t.getMessage());
        }
        if (prepared == null) {
            return prepareError == null ? Messages.get("error.stillLoading")
                    : Messages.format("error.prepareFailed", prepareError);
        }
        if (rdoIncludeProgram.getSelection() && selectedIncludeIndex() < 0) {
            return Messages.get("error.noIncludeSelected");
        }
        return null;
    }

    private int selectedIncludeIndex() {
        int index = cmbInclude.getSelectionIndex();
        if (prepared == null || index < 0 || index >= prepared.includes.size()) {
            return -1;
        }
        return index;
    }

    private List<AbapCodeProgram> getTargetPrograms() {
        List<AbapCodeProgram> targets = new ArrayList<>();
        if (prepared == null || context == null) {
            return targets;
        }
        if (rdoMainProgram.getSelection()) {
            // "In main program" means: the main program itself plus every
            // INCLUDE that belongs to it (the classic SE38 behaviour).
            if (prepared.mainSource != null && !prepared.mainSource.isEmpty()) {
                targets.add(new AbapCodeProgram(context.project, prepared.mainName,
                        prepared.mainType, prepared.mainSource));
            }
            for (CodeSearchSourceClient.IncludeProgram include : prepared.includes) {
                targets.add(new AbapCodeProgram(context.project, include.name, "PROG/I",
                        include.source, include.functionName, include.functionGroup));
            }
            return targets;
        }
        int index = selectedIncludeIndex();
        if (index >= 0) {
            CodeSearchSourceClient.IncludeProgram include = prepared.includes.get(index);
            targets.add(new AbapCodeProgram(context.project, include.name, "PROG/I",
                    include.source, include.functionName, include.functionGroup));
        }
        return targets;
    }

    /** Runs the search and shows the result in the Eclipse Search view. */
    public boolean performSearch(Shell shell) {
        String error = validate();
        if (error != null) {
            MessageDialog.openError(shell, Messages.get("dialog.title"), error);
            return false;
        }
        List<AbapCodeProgram> targets = getTargetPrograms();
        if (targets.isEmpty()) {
            MessageDialog.openError(shell, Messages.get("dialog.title"),
                    Messages.get("error.noIncludeSelected"));
            return false;
        }
        NewSearchUI.runQueryInBackground(new AbapCodeSearchQuery(targets,
                txtPattern.getText(), true, chkCaseSensitive.getSelection()));
        return true;
    }

    /** Replaces all matches in the affected editors. */
    public boolean performReplace(Shell shell) {
        if (!chkReplace.getSelection()) {
            MessageDialog.openInformation(shell, Messages.get("replace.title"),
                    Messages.get("error.replaceNotEnabled"));
            return false;
        }
        String error = validate();
        if (error != null) {
            MessageDialog.openError(shell, Messages.get("dialog.title"), error);
            return false;
        }
        List<AbapCodeProgram> targets = getTargetPrograms();
        if (targets.isEmpty()) {
            return false;
        }
        if (!MessageDialog.openConfirm(shell, Messages.get("replace.title"),
                Messages.get("replace.confirm"))) {
            return false;
        }
        new ReplaceRunner(shell, targets, txtReplace.getText(),
                CodeSearchMatcher.create(txtPattern.getText(), true, chkCaseSensitive.getSelection()))
                        .start();
        return true;
    }

    public void setReplaceFinished(Runnable replaceFinished) {
        this.replaceFinished = replaceFinished;
    }

    public void dispose() {
        disposed = true;
        if (prepareJob != null) {
            prepareJob.cancel();
        }
    }

    /**
     * Applies the replacement to the documents of the affected programs and
     * then shows the replaced lines in the Search view. Editors are left dirty
     * so that the user saves them through the regular ADT flow (transport
     * handling and activation stay with ADT).
     */
    private final class ReplaceRunner {

        private final Shell shell;
        private final List<AbapCodeProgram> targets;
        private final String replacement;
        private final CodeSearchMatcher matcher;
        private final List<AbapCodeProgram> updated = new ArrayList<>();

        private int index;
        private int replacedCount;
        private int changedPrograms;
        private int failedPrograms;

        ReplaceRunner(Shell shell, List<AbapCodeProgram> targets, String replacement,
                CodeSearchMatcher matcher) {
            this.shell = shell;
            this.targets = targets;
            this.replacement = replacement;
            this.matcher = matcher;
        }

        void start() {
            next();
        }

        private void next() {
            if (disposed) {
                return;
            }
            if (index >= targets.size()) {
                finish();
                return;
            }
            AbapCodeProgram program = targets.get(index++);
            ITextEditor editor = AbapEditorNavigator.openProgramEditor(program);
            if (editor == null) {
                failedPrograms++;
                next();
                return;
            }
            attempt(editor, program, 0);
        }

        private void attempt(ITextEditor editor, AbapCodeProgram program, int attemptCount) {
            if (disposed) {
                return;
            }
            IDocument document = AbapEditorNavigator.documentOf(editor);
            if (document == null || document.getLength() == 0) {
                if (attemptCount < 10) {
                    Display.getDefault().timerExec(500,
                            () -> attempt(editor, program, attemptCount + 1));
                } else {
                    failedPrograms++;
                    next();
                }
                return;
            }
            try {
                List<int[]> ranges = matcher.findRanges(document.get());
                for (int i = ranges.size() - 1; i >= 0; i--) {
                    int[] range = ranges.get(i);
                    document.replace(range[0], range[1], replacement);
                }
                if (!ranges.isEmpty()) {
                    replacedCount += ranges.size();
                    changedPrograms++;
                    updated.add(new AbapCodeProgram(program.getProject(), program.getName(),
                            program.getType(), document.get(),
                            program.getFunctionName(), program.getFunctionGroup()));
                }
            } catch (BadLocationException e) {
                failedPrograms++;
            }
            next();
        }

        private void finish() {
            if (!replacement.isEmpty() && !updated.isEmpty()) {
                NewSearchUI.runQueryInBackground(new AbapCodeSearchQuery(updated,
                        replacement, false, false));
            }
            if (shell != null && !shell.isDisposed()) {
                if (replacedCount == 0) {
                    MessageDialog.openInformation(shell, Messages.get("replace.title"),
                            Messages.get("replace.noMatches"));
                } else {
                    String message = Messages.format("replace.done", replacedCount, changedPrograms);
                    if (failedPrograms > 0) {
                        message = message + System.lineSeparator()
                                + Messages.format("replace.skipped", failedPrograms);
                    }
                    MessageDialog.openInformation(shell, Messages.get("replace.title"), message);
                }
            }
            Runnable finished = replaceFinished;
            if (finished != null && !disposed) {
                finished.run();
            }
        }
    }
}
