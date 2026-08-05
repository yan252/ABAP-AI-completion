package com.sap.abap.ai.completion.editor;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
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
 * <p>The overlay shows the full code with a vertical scrollbar.
 * TAB or Enter accepts the full completion text, Esc dismisses.</p>
 */
public class AICompletionOverlay {

    private static final int VISIBLE_LINES = 12;

    private Shell shell;
    private Label headerLabel;
    private StyledText styledText;
    private Label footerLabel;

    private String completionText;
    private boolean hasMoreContent;
    private boolean disposed = false;

    private int cursorOffset;
    private int overlayAlpha;

    public AICompletionOverlay(Shell parentShell, String completionText,
                               int cursorOffset, RGB displayColor, int overlayAlpha) {
        this.completionText = completionText;
        this.cursorOffset = cursorOffset;
        this.overlayAlpha = overlayAlpha;

        if (completionText == null || completionText.trim().isEmpty()) return;

        shell = new Shell(parentShell, SWT.ON_TOP | SWT.TOOL | SWT.NO_FOCUS);
        shell.setBackground(Display.getDefault().getSystemColor(SWT.COLOR_INFO_BACKGROUND));
        shell.setLayout(new GridLayout(1, false));

        // Apply opacity
        shell.setAlpha(Math.max(25, Math.min(255, overlayAlpha)));

        // Header label - shows scroll hint if content exceeds visible area
        headerLabel = new Label(shell, SWT.NONE);
        headerLabel.setText("AI Completion");
        headerLabel.setFont(new Font(Display.getDefault(), new FontData("Segoe UI", 8, SWT.NORMAL)));

        // StyledText with vertical scrollbar - contains FULL completion text
        styledText = new StyledText(shell, SWT.READ_ONLY | SWT.V_SCROLL | SWT.BORDER);
        styledText.setText(completionText);
        styledText.setEditable(false);
        styledText.setCaret(null);

        // Apply the configured color
        Color color = new Color(Display.getDefault(), displayColor);
        styledText.setForeground(color);

        // Background
        Color bg = new Color(Display.getDefault(), 255, 255, 225);
        styledText.setBackground(bg);

        // Monospace font
        FontData fontData = new FontData("Consolas", 10, SWT.NORMAL);
        Font font = new Font(Display.getDefault(), fontData);
        styledText.setFont(font);

        // Calculate height based on visible lines
        int lineHeight = styledText.getLineHeight();
        int heightHint = lineHeight * VISIBLE_LINES + 4;

        GridData gd = new GridData(GridData.FILL_BOTH);
        gd.widthHint = 550;
        gd.heightHint = heightHint;
        styledText.setLayoutData(gd);

        // Footer with hint
        footerLabel = new Label(shell, SWT.NONE);
        footerLabel.setText("Tab = accept  |  Enter = accept  |  Esc = dismiss  |  Scrollbar to view full code");
        footerLabel.setFont(new Font(Display.getDefault(), new FontData("Segoe UI", 8, SWT.NORMAL)));
        footerLabel.setForeground(new Color(Display.getDefault(), 100, 100, 100));

        // Clean up resources
        shell.addDisposeListener(e -> {
            color.dispose();
            bg.dispose();
            font.dispose();
            disposed = true;
        });

        shell.pack();

        // Check if content exceeds visible area and update header
        int totalLines = styledText.getLineCount();
        hasMoreContent = totalLines > VISIBLE_LINES;
        if (hasMoreContent) {
            headerLabel.setText("AI Completion  (scroll down for more)");
        }

        // Border
        shell.addPaintListener(e -> {
            Rectangle r = shell.getClientArea();
            e.gc.setForeground(Display.getDefault().getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
            e.gc.drawRectangle(0, 0, r.width - 1, r.height - 1);
        });
    }

    public void positionNearCursor(ITextViewer viewer) {
        if (shell == null || shell.isDisposed() || viewer == null) return;

        StyledText widget = viewer.getTextWidget();
        if (widget == null || widget.isDisposed()) return;

        int caretOffset = widget.getCaretOffset();
        Point cursorLocation = widget.getLocationAtOffset(caretOffset);
        Point displayLocation = widget.toDisplay(cursorLocation);

        Rectangle shellBounds = shell.getBounds();
        int x = displayLocation.x + 20;
        int y = displayLocation.y + 20;

        Rectangle displayBounds = Display.getDefault().getBounds();
        if (x + shellBounds.width > displayBounds.width) {
            x = displayBounds.width - shellBounds.width - 10;
        }
        if (y + shellBounds.height > displayBounds.height) {
            y = displayBounds.height - shellBounds.height - 10;
        }

        shell.setLocation(x, y);
    }

    public void open() {
        if (shell != null && !shell.isDisposed()) {
            shell.open();
        }
    }

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

    public Shell getShell() {
        return shell;
    }

    public StyledText getStyledText() {
        return styledText;
    }
}