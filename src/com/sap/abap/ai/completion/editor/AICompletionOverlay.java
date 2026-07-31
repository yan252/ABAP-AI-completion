package com.sap.abap.ai.completion.editor;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;

/**
 * A floating overlay window that shows AI completion suggestions
 * near the cursor position in the editor.
 *
 * The overlay is purely visual — it does NOT modify the document
 * until the user presses Tab to accept.
 */
public class AICompletionOverlay {

    private Shell shell;
    private Label completionLabel;
    private StyledText styledText;

    private String completionText;
    private boolean disposed = false;

    /** The original cursor offset where the overlay was shown. */
    private int cursorOffset;

    /**
     * Creates and shows the overlay at the given location.
     *
     * @param parentShell    the parent shell
     * @param completionText the AI completion text to display
     * @param cursorOffset   the editor cursor offset at the time of creation
     * @param displayColor   the RGB color for the completion text
     */
    public AICompletionOverlay(Shell parentShell, String completionText,
                               int cursorOffset, RGB displayColor) {
        this.completionText = completionText;
        this.cursorOffset = cursorOffset;

        if (completionText == null || completionText.trim().isEmpty()) return;

        // Compute display text (show first ~200 chars or full text)
        String displayText = completionText;
        if (displayText.length() > 300) {
            displayText = displayText.substring(0, 297) + "...";
        }

        shell = new Shell(parentShell, SWT.ON_TOP | SWT.TOOL | SWT.NO_FOCUS);
        shell.setBackground(Display.getDefault().getSystemColor(SWT.COLOR_INFO_BACKGROUND));
        shell.setLayout(new GridLayout(1, false));

        // Create a StyledText for code-like display
        styledText = new StyledText(shell, SWT.READ_ONLY | SWT.WRAP);
        styledText.setText(displayText);
        styledText.setEditable(false);
        styledText.setCaret(null);

        // Apply the configured color
        Color color = new Color(Display.getDefault(), displayColor);
        styledText.setForeground(color);

        // Set background
        Color bg = new Color(Display.getDefault(), 255, 255, 225); // light yellow
        styledText.setBackground(bg);

        // Use a monospace font matching the editor
        FontData fontData = new FontData("Consolas", 10, SWT.NORMAL);
        Font font = new Font(Display.getDefault(), fontData);
        styledText.setFont(font);

        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.widthHint = 500;
        gd.heightHint = Math.min(200, 20 + displayText.length() / 2);
        styledText.setLayoutData(gd);

        // Clean up resources
        shell.addDisposeListener(e -> {
            color.dispose();
            bg.dispose();
            font.dispose();
            disposed = true;
        });

        shell.pack();

        // Add a paint listener for a subtle border
        shell.addPaintListener(e -> {
            Rectangle r = shell.getClientArea();
            e.gc.setForeground(Display.getDefault().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
            e.gc.drawRectangle(0, 0, r.width - 1, r.height - 1);
        });
    }

    /**
     * Positions the overlay near the editor cursor.
     *
     * @param viewer the text viewer to get cursor location from
     */
    public void positionNearCursor(ITextViewer viewer) {
        if (shell == null || shell.isDisposed() || viewer == null) return;

        StyledText widget = viewer.getTextWidget();
        if (widget == null || widget.isDisposed()) return;

        // Get cursor location in display coordinates
        int caretOffset = widget.getCaretOffset();
        Point cursorLocation = widget.getLocationAtOffset(caretOffset);

        // Convert to display coordinates
        Point displayLocation = widget.toDisplay(cursorLocation);

        // Position the shell slightly below and to the right of cursor
        Rectangle shellBounds = shell.getBounds();
        int x = displayLocation.x + 20;
        int y = displayLocation.y + 20;

        // Ensure the overlay stays within the display bounds
        Rectangle displayBounds = Display.getDefault().getBounds();
        if (x + shellBounds.width > displayBounds.width) {
            x = displayBounds.width - shellBounds.width - 10;
        }
        if (y + shellBounds.height > displayBounds.height) {
            y = displayBounds.height - shellBounds.height - 10;
        }

        shell.setLocation(x, y);
    }

    /**
     * Opens the overlay shell.
     */
    public void open() {
        if (shell != null && !shell.isDisposed()) {
            shell.open();
        }
    }

    /**
     * Closes and disposes the overlay.
     */
    public void close() {
        disposed = true;
        if (shell != null && !shell.isDisposed()) {
            shell.close();
            shell.dispose();
        }
    }

    public boolean isDisposed() {
        return disposed || shell == null || shell.isDisposed();
    }

    public int getCursorOffset() {
        return cursorOffset;
    }

    public String getCompletionText() {
        return completionText;
    }

    /**
     * Returns the shell of this overlay (for keyboard listener registration).
     */
    public Shell getShell() {
        return shell;
    }

    /**
     * Returns the StyledText widget of this overlay (for mouse listener registration).
     */
    public StyledText getStyledText() {
        return styledText;
    }
}
