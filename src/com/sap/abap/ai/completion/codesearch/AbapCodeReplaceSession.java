package com.sap.abap.ai.completion.codesearch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.search.ui.text.Match;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.sap.abap.ai.completion.Activator;

/**
 * Interactive, match-by-match replace flow used by both search entry points
 * (Ctrl+H tab and the standalone Ctrl+Shift+H dialog).
 *
 * <p>Pressing "Replace" first runs the regular search (results appear in the
 * Search view) and then walks the matches in document order. For every match
 * the matching program editor is opened, the occurrence is selected and a
 * small prompt is shown:</p>
 * <ul>
 *   <li>Replace (Shift+F5): replace the selected occurrence, continue with the
 *       next one</li>
 *   <li>Next Match (Ctrl+Shift+F6): skip the occurrence, continue with the next
 *       one</li>
 *   <li>Replace All from Cursor (Ctrl+Shift+F7): replace the current and every
 *       remaining occurrence without further prompts</li>
 * </ul>
 */
final class AbapCodeReplaceSession {

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    private final List<Step> steps = new ArrayList<>();
    private final String pattern;
    private final String replacement;
    private final CodeSearchMatcher matcher;

    private final Set<AbapCodeProgram> failedPrograms = new HashSet<>();
    private final Set<AbapCodeProgram> changedPrograms = new LinkedHashSet<>();

    private int index;
    private int replacedCount;
    private int skippedPrograms;
    private boolean silent;
    private boolean finished;

    AbapCodeReplaceSession(List<AbapCodeProgram> targets, String pattern, String replacement,
            CodeSearchMatcher matcher) {
        this.pattern = pattern;
        this.replacement = replacement;
        this.matcher = matcher;
        collectSteps(targets);
    }

    private void collectSteps(List<AbapCodeProgram> targets) {
        for (AbapCodeProgram program : targets) {
            List<int[]> ranges = matcher.findRanges(program.getSource());
            if (ranges.isEmpty()) {
                continue;
            }
            Match[] all = new Match[ranges.size()];
            for (int i = 0; i < ranges.size(); i++) {
                all[i] = new Match(program, ranges.get(i)[0], ranges.get(i)[1]);
            }
            for (Match match : all) {
                steps.add(new Step(program, match, all));
            }
        }
    }

    /** Starts the flow after the search dialog has been closed. */
    void start() {
        if (steps.isEmpty()) {
            showInfo(Messages.get("replace.noMatches"));
            return;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(this::showCurrent);
    }

    private void showCurrent() {
        if (finished) {
            return;
        }
        if (index >= steps.size()) {
            finish(true);
            return;
        }
        final Step step = steps.get(index);
        AbapEditorNavigator.show(step.program, step.match, step.allMatches, matcher,
                (editor, document, offset, length) ->
                        onResolved(step, editor, document, offset, length));
    }

    private void onResolved(Step step, ITextEditor editor, IDocument document,
            int offset, int length) {
        if (finished) {
            return;
        }
        if (editor == null || document == null || offset < 0) {
            // The editor could not be opened or never became ready. Skip the
            // whole program so the flow does not stall on every match of it.
            failedPrograms.add(step.program);
            skippedPrograms++;
            advance();
            return;
        }
        int action = new AbapReplacePromptDialog(activeShell(), promptMessage(step)).open();
        if (action == AbapReplacePromptDialog.REPLACE) {
            replace(document, offset, length, step.program);
            advance();
        } else if (action == AbapReplacePromptDialog.NEXT) {
            advance();
        } else if (action == AbapReplacePromptDialog.ALL) {
            replaceAllFromCursor(step, document, offset);
        } else {
            finish(false);
        }
    }

    private String promptMessage(Step step) {
        return Messages.format("replace.prompt.message", pattern, replacement,
                step.program.getDisplayName(), step.program.getLineNumber(step.match.getOffset()));
    }

    private void advance() {
        index++;
        while (index < steps.size() && failedPrograms.contains(steps.get(index).program)) {
            index++;
        }
        Display display = Display.getDefault();
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(this::showCurrent);
    }

    private void replace(IDocument document, int offset, int length, AbapCodeProgram program) {
        try {
            int safeOffset = Math.max(0, Math.min(offset, document.getLength()));
            int safeLength = Math.max(0, Math.min(length, document.getLength() - safeOffset));
            document.replace(safeOffset, safeLength, replacement);
            replacedCount++;
            changedPrograms.add(program);
        } catch (BadLocationException e) {
            log("replace failed in " + program.getName() + ": " + e.getMessage());
            failedPrograms.add(program);
            skippedPrograms++;
        }
    }

    /**
     * Replaces the current occurrence and every later occurrence without any
     * further prompt. Inside the current program only occurrences at or after
     * the current offset are touched ("from cursor"); all occurrences of the
     * programs that follow in the search order are replaced completely.
     */
    private void replaceAllFromCursor(Step current, IDocument document, int offset) {
        silent = true;
        int from = index + 1;
        try {
            List<int[]> ranges = matcher.findRanges(document.get());
            for (int i = ranges.size() - 1; i >= 0; i--) {
                int[] range = ranges.get(i);
                if (range[0] >= offset) {
                    document.replace(range[0], range[1], replacement);
                    replacedCount++;
                    changedPrograms.add(current.program);
                }
            }
        } catch (BadLocationException e) {
            log("replace all failed in " + current.program.getName() + ": " + e.getMessage());
            skippedPrograms++;
        }

        Set<AbapCodeProgram> remaining = new LinkedHashSet<>();
        for (int i = from; i < steps.size(); i++) {
            AbapCodeProgram program = steps.get(i).program;
            // The current program was already handled above (everything from
            // the cursor on); replacing it again would touch skipped matches.
            if (program != current.program) {
                remaining.add(program);
            }
        }
        replacePrograms(new ArrayList<>(remaining), 0);
    }

    private void replacePrograms(List<AbapCodeProgram> programs, int position) {
        if (position >= programs.size()) {
            finish(true);
            return;
        }
        AbapCodeProgram program = programs.get(position);
        ITextEditor editor = AbapEditorNavigator.openProgramEditor(program);
        if (editor == null) {
            failedPrograms.add(program);
            skippedPrograms++;
            replacePrograms(programs, position + 1);
            return;
        }
        replaceProgramDocument(editor, program, programs, position, 0);
    }

    private void replaceProgramDocument(ITextEditor editor, AbapCodeProgram program,
            List<AbapCodeProgram> programs, int position, int attempt) {
        IDocument document = AbapEditorNavigator.documentOf(editor);
        if (document == null || document.getLength() == 0) {
            if (attempt < 10) {
                Display.getDefault().timerExec(500, () -> replaceProgramDocument(
                        editor, program, programs, position, attempt + 1));
            } else {
                failedPrograms.add(program);
                skippedPrograms++;
                replacePrograms(programs, position + 1);
            }
            return;
        }
        try {
            List<int[]> ranges = matcher.findRanges(document.get());
            for (int i = ranges.size() - 1; i >= 0; i--) {
                int[] range = ranges.get(i);
                document.replace(range[0], range[1], replacement);
                replacedCount++;
                changedPrograms.add(program);
            }
        } catch (BadLocationException e) {
            log("replace all failed in " + program.getName() + ": " + e.getMessage());
            failedPrograms.add(program);
            skippedPrograms++;
        }
        replacePrograms(programs, position + 1);
    }

    private void finish(boolean completed) {
        if (finished) {
            return;
        }
        finished = true;
        if (!completed) {
            log("replace stopped by user, replaced=" + replacedCount);
            return;
        }
        if (silent) {
            // "Replace all from cursor" must not prompt the user again.
            log("replace all from cursor finished, replaced=" + replacedCount
                    + " in " + changedPrograms.size() + " program(s)");
            return;
        }
        Shell shell = activeShell();
        if (shell == null) {
            return;
        }
        String title = Messages.get("replace.title");
        if (replacedCount == 0) {
            MessageDialog.openInformation(shell, title, Messages.get("replace.finishedNone"));
        } else {
            String message = Messages.format("replace.done", replacedCount, changedPrograms.size());
            if (skippedPrograms > 0) {
                message = message + System.lineSeparator()
                        + Messages.format("replace.skipped", skippedPrograms);
            }
            MessageDialog.openInformation(shell, title, message);
        }
    }

    private void showInfo(String message) {
        Shell shell = activeShell();
        if (shell != null) {
            MessageDialog.openInformation(shell, Messages.get("replace.title"), message);
        }
    }

    private Shell activeShell() {
        Shell shell = Display.getDefault().getActiveShell();
        if (shell != null && !shell.isDisposed()) {
            return shell;
        }
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        return window == null ? null : window.getShell();
    }

    private static void log(String message) {
        try {
            LOG.log(new Status(IStatus.INFO, Activator.PLUGIN_ID, message));
        } catch (Throwable ignored) {
            // logging must never break the replace flow
        }
    }

    /** One match together with every match of its program. */
    private static final class Step {
        final AbapCodeProgram program;
        final Match match;
        final Match[] allMatches;

        Step(AbapCodeProgram program, Match match, Match[] allMatches) {
            this.program = program;
            this.match = match;
            this.allMatches = allMatches;
        }
    }
}
