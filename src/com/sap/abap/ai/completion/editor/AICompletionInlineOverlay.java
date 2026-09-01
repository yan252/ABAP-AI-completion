package com.sap.abap.ai.completion.editor;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.MouseListener;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.widgets.Shell;

/**
 * An inline "ghost text" completion hint, similar to Copilot.
 *
 * <p>Instead of opening a separate popup window, this hint is painted
 * <b>directly onto the editor's {@link StyledText}</b> using the editor's own
 * font (so the font size and line height are identical to real code).
 * Only the foreground color differs (the configured completion color),
 * which makes it look like a suggestion rather than inserted code.</p>
 *
 * <p>The code is NOT inserted into the document. Pressing TAB or Enter accepts
 * it (handled by {@link AIOverlayManager}); any other key cancels the hint.</p>
 */
public class AICompletionInlineOverlay implements AICompletionOverlayBase {

    private final StyledText widget;
    private final String completionText;
    private final int cursorOffset;
    private final RGB displayColor;
    private final Runnable acceptAction;
    private final Runnable cancelAction;

    private PaintListener ghostPaintListener;
    private org.eclipse.swt.graphics.Color ghostColor;
    private MouseListener ghostMouseListener;
    private boolean visible = false;

    public AICompletionInlineOverlay(Shell parentShell, String completionText,
                                     int cursorOffset, RGB displayColor, ITextViewer viewer,
                                     IDocument document, Runnable acceptAction,
                                     Runnable cancelAction) {
        this.completionText = completionText;
        this.cursorOffset = cursorOffset;
        this.displayColor = displayColor;
        this.acceptAction = acceptAction;
        this.cancelAction = cancelAction;

        if (completionText == null || completionText.trim().isEmpty()
                || viewer == null) {
            this.widget = null;
            return;
        }
        StyledText w = viewer.getTextWidget();
        this.widget = (w == null || w.isDisposed()) ? null : w;
    }

    /**
     * Registers the paint listener that draws the ghost text on the editor
     * and triggers a repaint. Call on the UI thread.
     */
    private void installAndRedraw() {
        if (widget == null || widget.isDisposed() || visible) return;

        ghostPaintListener = this::paintGhostText;
        ghostColor = new org.eclipse.swt.graphics.Color(
                widget.getDisplay(),
                displayColor != null ? displayColor : new RGB(0, 128, 0));

        ghostMouseListener = new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent e) {
                if (!visible) return;
                if (isInHintArea(e.x, e.y)) {
                    if (acceptAction != null) acceptAction.run();
                } else {
                    if (cancelAction != null) cancelAction.run();
                }
            }
        };

        // Ensure our overlay is drawn on top of existing editor content:
        // listeners added later are invoked later (on top).
        widget.addPaintListener(ghostPaintListener);
        widget.addMouseListener(ghostMouseListener);
        visible = true;
        widget.redraw();
    }

    /**
     * Returns true when the given widget-client coordinates fall inside the
     * area covered by the drawn ghost text (i.e. the user clicked the hint).
     */
    private boolean isInHintArea(int mouseX, int mouseY) {
        if (widget == null || widget.isDisposed()) return false;

        int caretOffset = Math.max(0, Math.min(cursorOffset, widget.getCharCount()));
        Point caretLoc = widget.getLocationAtOffset(caretOffset);
        int lineHeight = widget.getLineHeight();

        String[] rawLines = completionText.split("\n", -1);
        int lineCount = rawLines.length;
        if (lineCount > 0 && rawLines[lineCount - 1].isEmpty()) {
            lineCount--;
        }
        if (lineCount <= 0) return false;

        GC gc = new GC(widget);
        try {
            gc.setFont(widget.getFont());
            for (int i = 0; i < lineCount; i++) {
                String line = rawLines[i];
                int x;
                if (i == 0) {
                    x = caretLoc.x;
                } else {
                    x = gc.stringExtent(getLeadingWhitespace(line)).x;
                }
                int y = caretLoc.y + i * lineHeight;
                int w = gc.stringExtent(line).x;
                if (mouseX >= x && mouseX <= x + w
                        && mouseY >= y && mouseY <= y + lineHeight) {
                    return true;
                }
            }
        } finally {
            gc.dispose();
        }
        return false;
    }

    /**
     * Paints the completion as ghost text right after the cursor.
     * Uses the editor's own font, so alignment with real code is exact.
     */
    private void paintGhostText(org.eclipse.swt.events.PaintEvent e) {
        if (widget == null || widget.isDisposed() || !visible) return;

        int caretOffset = Math.max(0, Math.min(cursorOffset, widget.getCharCount()));
        Point caretLoc = widget.getLocationAtOffset(caretOffset);

        e.gc.setFont(widget.getFont());
        e.gc.setTextAntialias(SWT.ON);
        if (ghostColor != null && !ghostColor.isDisposed()) {
            e.gc.setForeground(ghostColor);
        }

        String[] lines = completionText.split("\n", -1);
        int lineCount = lines.length;
        // Drop a single trailing empty line coming from a trailing newline
        if (lineCount > 0 && lines[lineCount - 1].isEmpty()) {
            lineCount--;
        }

        int lineHeight = widget.getLineHeight();
        int x0 = caretLoc.x;
        int y0 = caretLoc.y;

        for (int i = 0; i < lineCount; i++) {
            String line = lines[i];
            int x;
            if (i == 0) {
                // First line continues right after the cursor
                x = x0;
            } else {
                // Subsequent lines are new lines: their leading indentation
                // defines their starting column
                x = e.gc.stringExtent(getLeadingWhitespace(line)).x;
            }
            int y = y0 + i * lineHeight;
            e.gc.drawString(line, x, y, true);
        }
    }

    private static String getLeadingWhitespace(String line) {
        int i = 0;
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t') {
                i++;
            } else {
                break;
            }
        }
        return line.substring(0, i);
    }

    // ==================== AICompletionOverlayBase ====================

    @Override
    public void positionNearCursor(ITextViewer viewer) {
        // No window to position; the hint is painted in the editor itself.
    }

    @Override
    public void open() {
        if (widget != null && !widget.isDisposed()) {
            widget.getDisplay().asyncExec(this::installAndRedraw);
        }
    }

    @Override
    public void close() {
        hidden();
    }

    @Override
    public boolean isDisposed() {
        return widget == null || widget.isDisposed() || !visible;
    }

    @Override
    public int getCursorOffset() {
        return cursorOffset;
    }

    @Override
    public String getCompletionText() {
        return completionText;
    }

    @Override
    public Shell getShell() {
        // The hint lives inside the editor widget, so report its shell.
        return widget != null && !widget.isDisposed() ? widget.getShell() : null;
    }

    /**
     * Removes the paint listener and repaints to erase the ghost text.
     * Call on the UI thread.
     */
    public void hidden() {
        if (widget == null || widget.isDisposed()) {
            disposeGhostColor();
            visible = false;
            ghostPaintListener = null;
            ghostMouseListener = null;
            return;
        }
        if (visible && ghostPaintListener != null) {
            widget.removePaintListener(ghostPaintListener);
        }
        if (ghostMouseListener != null) {
            widget.removeMouseListener(ghostMouseListener);
        }
        ghostPaintListener = null;
        ghostMouseListener = null;
        disposeGhostColor();
        visible = false;
        widget.redraw();
    }

    private void disposeGhostColor() {
        if (ghostColor != null && !ghostColor.isDisposed()) {
            ghostColor.dispose();
        }
        ghostColor = null;
    }
}
