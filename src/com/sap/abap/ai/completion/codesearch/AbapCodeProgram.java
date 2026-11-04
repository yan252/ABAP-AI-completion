package com.sap.abap.ai.completion.codesearch;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IProject;

/**
 * One searched ABAP program or include program. Holds the source text so the
 * result tree can show the matched line content without querying the backend
 * again.
 */
public final class AbapCodeProgram {

    private final IProject project;
    private final String name;
    private final String type;
    private final String source;
    private final int[] lineStarts;

    public AbapCodeProgram(IProject project, String name, String type, String source) {
        this.project = project;
        this.name = name;
        this.type = type;
        this.source = normalizeLineEndings(source);
        this.lineStarts = computeLineStarts(this.source);
    }

    /**
     * The ADT editors display their source with CR/LF line delimiters. Match
     * offsets are handed to the Eclipse Search framework, which paints them
     * directly into the editor document. The searched text must therefore use
     * the same separator, otherwise every match after the first line is shifted
     * by one character per preceding line (the "TYP" instead of "alv" symptom).
     */
    private static String normalizeLineEndings(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n");
    }

    public IProject getProject() {
        return project;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getSource() {
        return source;
    }

    public boolean isInclude() {
        return "PROG/I".equalsIgnoreCase(type);
    }

    /** Localized program type used in the result tree. */
    public String getTypeLabel() {
        return Messages.get(isInclude() ? "type.include" : "type.program");
    }

    /** 1 based line of the given offset. */
    public int getLineNumber(int offset) {
        if (lineStarts.length == 0) {
            return 1;
        }
        int low = 0;
        int high = lineStarts.length - 1;
        int clamped = Math.max(0, Math.min(offset, source.length()));
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lineStarts[mid] <= clamped) {
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return Math.max(0, high) + 1;
    }

    /** 1 based column of the given offset inside its line. */
    public int getColumn(int offset) {
        int line = getLineNumber(offset) - 1;
        int start = line >= 0 && line < lineStarts.length ? lineStarts[line] : 0;
        return Math.max(1, offset - start + 1);
    }

    public String getLineText(int offset) {
        if (source.isEmpty()) {
            return "";
        }
        int line = getLineNumber(offset) - 1;
        if (line < 0 || line >= lineStarts.length) {
            return "";
        }
        int start = lineStarts[line];
        int end = line + 1 < lineStarts.length ? lineStarts[line + 1] : source.length();
        String text = source.substring(start, end);
        return text.replace("\r", "").replace("\n", "");
    }

    private static int[] computeLineStarts(String text) {
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n' && i + 1 < text.length()) {
                starts.add(i + 1);
            }
        }
        int[] result = new int[starts.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = starts.get(i);
        }
        return result;
    }
}
