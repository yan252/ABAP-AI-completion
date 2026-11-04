package com.sap.abap.ai.completion.codesearch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Literal / regular-expression matching used by the search and replace
 * operations. Case sensitivity is applied through the pattern flags.
 */
final class CodeSearchMatcher {

    private final Pattern pattern;
    private final String patternText;
    private final boolean regex;

    private CodeSearchMatcher(Pattern pattern, String patternText, boolean regex) {
        this.pattern = pattern;
        this.patternText = patternText;
        this.regex = regex;
    }

    static CodeSearchMatcher create(String text, boolean regex, boolean caseSensitive)
            throws PatternSyntaxException {
        int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        Pattern compiled = regex
                ? Pattern.compile(text, flags)
                : Pattern.compile(Pattern.quote(text), flags);
        return new CodeSearchMatcher(compiled, text, regex);
    }

    String getPatternText() {
        return patternText;
    }

    /** Compiled pattern, used to re-locate a match inside an editor document. */
    Pattern getPattern() {
        return pattern;
    }

    /**
     * Same expression but always case insensitive. Editor documents can differ
     * from the searched backend text in case only, so highlighting must not
     * depend on the case option.
     */
    Pattern getCaseInsensitivePattern() {
        int flags = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        return regex ? Pattern.compile(patternText, flags)
                : Pattern.compile(Pattern.quote(patternText), flags);
    }

    /** Match ranges as {@code [offset, length]} pairs, in document order. */
    List<int[]> findRanges(String source) {
        List<int[]> ranges = new ArrayList<>();
        Matcher matcher = pattern.matcher(source);
        int guard = 0;
        while (matcher.find() && guard++ < 100000) {
            if (matcher.end() > matcher.start()) {
                ranges.add(new int[] { matcher.start(), matcher.end() - matcher.start() });
            }
        }
        return ranges;
    }
}
