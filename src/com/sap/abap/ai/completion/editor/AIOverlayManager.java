package com.sap.abap.ai.completion.editor;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

import com.sap.abap.ai.completion.preferences.AIConfiguration;

/**
 * Manages the lifecycle of the AI completion overlay.
 * Handles showing, hiding, keyboard interception (Tab to accept),
 * mouse click to accept, and dismissing on other key press or click outside.
 */
public class AIOverlayManager {

    private static AIOverlayManager instance;

    public static synchronized AIOverlayManager getInstance() {
        if (instance == null) {
            instance = new AIOverlayManager();
        }
        return instance;
    }

    private AICompletionOverlay currentOverlay;
    private ITextViewer currentViewer;
    private IDocument currentDocument;
    private KeyAdapter overlayKeyListener;
    private Listener globalMouseFilter;
    private boolean globalFilterInstalled = false;

    /**
     * Shows a completion suggestion in the overlay.
     */
    public void showOverlay(ITextViewer viewer, IDocument document,
                            String completionText, int cursorOffset) {
        hideOverlay();

        if (viewer == null || document == null || completionText == null
                || completionText.trim().isEmpty()) {
            return;
        }

        this.currentViewer = viewer;
        this.currentDocument = document;

        StyledText widget = viewer.getTextWidget();
        if (widget == null || widget.isDisposed()) return;

        Shell parentShell = widget.getShell();
        currentOverlay = new AICompletionOverlay(
                parentShell,
                completionText,
                cursorOffset,
                AIConfiguration.getCompletionColor());

        if (currentOverlay.getShell() == null || currentOverlay.getShell().isDisposed()) {
            currentOverlay = null;
            return;
        }

        // Position and show
        currentOverlay.positionNearCursor(viewer);
        currentOverlay.open();

        // Register keyboard interceptor
        registerKeyInterceptor(viewer);

        // Register global mouse filter - click inside overlay -> accept, click outside -> dismiss
        registerGlobalMouseFilter();
    }

    /**
     * Hides and disposes the current overlay.
     */
    public void hideOverlay() {
        unregisterGlobalMouseFilter();
        if (currentOverlay != null) {
            currentOverlay.close();
            currentOverlay = null;
        }
        unregisterKeyInterceptor();
    }

    public boolean isOverlayVisible() {
        return currentOverlay != null && !currentOverlay.isDisposed();
    }

    // ==================== Global Mouse Click Detection ====================

    /**
     * Detects mouse clicks via a global Display filter.
     * - Click inside overlay (on its Shell or StyledText) → accept suggestion
     * - Click anywhere else → dismiss overlay
     */
    private void registerGlobalMouseFilter() {
        unregisterGlobalMouseFilter();
        globalMouseFilter = event -> {
            if (!isOverlayVisible()) {
                unregisterGlobalMouseFilter();
                return;
            }
            if (event.type != SWT.MouseDown) return;

            Shell overlayShell = currentOverlay.getShell();
            if (overlayShell == null || overlayShell.isDisposed()) return;
            if (event.widget == null || event.widget.isDisposed()) return;

            // Walk up widget hierarchy to find the widget's shell
            org.eclipse.swt.widgets.Widget w = event.widget;
            Shell widgetShell = null;
            while (w != null) {
                if (w instanceof Shell) {
                    widgetShell = (Shell) w;
                    break;
                }
                if (w instanceof org.eclipse.swt.widgets.Control) {
                    w = ((org.eclipse.swt.widgets.Control) w).getParent();
                } else {
                    break;
                }
            }

            // If the click is on the overlay shell itself → accept suggestion and insert code
            if (widgetShell == overlayShell) {
                acceptSuggestion();
                return;
            }

            // Click anywhere else → dismiss overlay
            hideOverlay();
        };

        Display.getDefault().addFilter(SWT.MouseDown, globalMouseFilter);
        globalFilterInstalled = true;
    }

    private void unregisterGlobalMouseFilter() {
        if (globalMouseFilter != null && globalFilterInstalled) {
            Display display = Display.getCurrent();
            if (display == null) display = Display.getDefault();
            if (!display.isDisposed()) {
                display.removeFilter(SWT.MouseDown, globalMouseFilter);
            }
            globalMouseFilter = null;
            globalFilterInstalled = false;
        }
    }

    // ==================== Keyboard Interceptor ====================

    private void registerKeyInterceptor(ITextViewer viewer) {
        unregisterKeyInterceptor();

        StyledText widget = viewer.getTextWidget();
        if (widget == null || widget.isDisposed()) return;

        overlayKeyListener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!isOverlayVisible()) {
                    unregisterKeyInterceptor();
                    return;
                }

                // Tab = accept
                if (e.keyCode == SWT.TAB) {
                    e.doit = false;
                    acceptSuggestion();
                    return;
                }

                // Enter = accept
                if (e.keyCode == SWT.CR || e.keyCode == SWT.KEYPAD_CR) {
                    e.doit = false;
                    acceptSuggestion();
                    return;
                }

                // Esc = dismiss
                if (e.keyCode == SWT.ESC) {
                    e.doit = false;
                    hideOverlay();
                    return;
                }

                // Navigation and modifier keys = keep overlay
                switch (e.keyCode) {
                    case SWT.SHIFT:
                    case SWT.CONTROL:
                    case SWT.ALT:
                    case SWT.COMMAND:
                    case SWT.CAPS_LOCK:
                    case SWT.NUM_LOCK:
                    case SWT.SCROLL_LOCK:
                    case SWT.ARROW_UP:
                    case SWT.ARROW_DOWN:
                    case SWT.ARROW_LEFT:
                    case SWT.ARROW_RIGHT:
                    case SWT.PAGE_UP:
                    case SWT.PAGE_DOWN:
                    case SWT.HOME:
                    case SWT.END:
                        return;
                }

                // Any other key -> dismiss
                e.doit = true;
                hideOverlay();
            }
        };

        widget.addKeyListener(overlayKeyListener);
    }

    private void unregisterKeyInterceptor() {
        if (overlayKeyListener != null && currentViewer != null) {
            StyledText widget = currentViewer.getTextWidget();
            if (widget != null && !widget.isDisposed()) {
                widget.removeKeyListener(overlayKeyListener);
            }
            overlayKeyListener = null;
        }
    }

    // ==================== Accept Suggestion ====================

    private void acceptSuggestion() {
        if (currentOverlay == null || currentDocument == null) return;

        String completionText = currentOverlay.getCompletionText();
        int offset = currentOverlay.getCursorOffset();

        if (currentViewer != null) {
            org.eclipse.jface.viewers.ISelection sel = currentViewer.getSelectionProvider().getSelection();
            int currentOffset = (sel instanceof ITextSelection)
                    ? ((ITextSelection) sel).getOffset()
                    : offset;

            if (Math.abs(currentOffset - offset) > 5) {
                offset = currentOffset;
            }
        }

        final int insertOffset = offset;
        final String text = completionText;

        Display.getDefault().asyncExec(() -> {
            try {
                currentDocument.replace(insertOffset, 0, text);
            } catch (Exception ex) {
                // ignore insert errors
            }
            hideOverlay();
        });
    }

    public void dispose() {
        hideOverlay();
        currentViewer = null;
        currentDocument = null;
    }
}
