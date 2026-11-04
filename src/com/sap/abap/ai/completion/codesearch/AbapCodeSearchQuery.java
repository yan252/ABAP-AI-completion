package com.sap.abap.ai.completion.codesearch;

import java.util.List;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.search.ui.ISearchQuery;
import org.eclipse.search.ui.ISearchResult;
import org.eclipse.search.ui.text.Match;

/**
 * In-memory search over already fetched ABAP sources. The sources are loaded
 * once per dialog / Search page invocation, so running the query does not hit
 * the SAP system again.
 */
public final class AbapCodeSearchQuery implements ISearchQuery {

    private final List<AbapCodeProgram> programs;
    private final String patternText;
    private final boolean regex;
    private final boolean caseSensitive;
    private final AbapCodeSearchResult result;

    public AbapCodeSearchQuery(List<AbapCodeProgram> programs, String patternText,
            boolean regex, boolean caseSensitive) {
        this.programs = programs;
        this.patternText = patternText;
        this.regex = regex;
        this.caseSensitive = caseSensitive;
        this.result = new AbapCodeSearchResult(this);
    }

    public boolean isRegex() {
        return regex;
    }

    public boolean isCaseSensitive() {
        return caseSensitive;
    }

    public String getPatternText() {
        return patternText;
    }

    /**
     * Returns a matcher created with the same options as this query. It is used
     * by the result page to re-locate a match inside the real editor document,
     * whose text may differ from the text that was searched (case, trailing
     * blanks, unsaved changes).
     */
    CodeSearchMatcher createMatcher() throws PatternSyntaxException {
        return CodeSearchMatcher.create(patternText, regex, caseSensitive);
    }

    @Override
    public IStatus run(IProgressMonitor monitor) {
        try {
            CodeSearchMatcher matcher = CodeSearchMatcher.create(patternText, regex, caseSensitive);
            for (AbapCodeProgram program : programs) {
                if (monitor.isCanceled()) {
                    break;
                }
                for (int[] range : matcher.findRanges(program.getSource())) {
                    result.addMatch(new Match(program, range[0], range[1]));
                }
            }
            return Status.OK_STATUS;
        } catch (PatternSyntaxException e) {
            return new Status(IStatus.ERROR, "com.sap.abap.ai.completion",
                    Messages.format("error.invalidRegex", e.getDescription()), e);
        }
    }

    @Override
    public String getLabel() {
        return Messages.format("query.label", patternText);
    }

    @Override
    public boolean canRerun() {
        return true;
    }

    @Override
    public boolean canRunInBackground() {
        return true;
    }

    @Override
    public ISearchResult getSearchResult() {
        return result;
    }
}
