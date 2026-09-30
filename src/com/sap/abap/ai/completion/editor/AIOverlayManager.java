package com.sap.abap.ai.completion.editor;

import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.FocusAdapter;
import org.eclipse.swt.events.FocusEvent;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Shell;

import com.sap.abap.ai.completion.preferences.AIConfiguration;
import com.sap.abap.ai.completion.preferences.PreferenceConstants;

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

    private AICompletionOverlayBase currentOverlay;
    private ITextViewer currentViewer;
    private IDocument currentDocument;
    private Listener keyDownFilter;
    private Listener traverseFilter;
    private boolean keyFilterInstalled = false;
    private Listener globalMouseFilter;
    private boolean globalFilterInstalled = false;
    /** 补全结果回调(用户互动结果): 1=ESC取消, 2=确认, 3=其它 */
    private java.util.function.IntConsumer resultListener;

    /**
     * 注册补全结果回调。 1=用户按 ESC 取消，2=用户确认，3=其它方式关闭。
     */
    public void setCompletionResultListener(java.util.function.IntConsumer listener) {
        this.resultListener = listener;
    }

    /**
     * Shows a completion suggestion in the overlay.
     * The display style (dialog popup vs. inline ghost text, like Copilot)
     * is chosen based on the "completion display type" preference.
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

        boolean inline = AIConfiguration.getCompletionDisplayType()
                == PreferenceConstants.COMPLETION_DISPLAY_INLINE;

        if (inline) {
            showInlineOverlay(parentShell, viewer, completionText, cursorOffset);
        } else {
            showDialogOverlay(parentShell, viewer, completionText, cursorOffset);
        }
    }

    /**
     * Builds and shows the dialog-style overlay (a floating popup window).
     */
    private void showDialogOverlay(Shell parentShell, ITextViewer viewer,
                                   String completionText, int cursorOffset) {
        currentOverlay = new AICompletionOverlay(
                parentShell,
                completionText,
                cursorOffset,
                AIConfiguration.getCompletionColor(),
                AIConfiguration.getOverlayOpacity());

        if (currentOverlay.getShell() == null || currentOverlay.getShell().isDisposed()) {
            currentOverlay = null;
            return;
        }

        // Position and show
        currentOverlay.positionNearCursor(viewer);
        currentOverlay.open();

        // Register keyboard interceptor
        registerKeyInterceptor(viewer);

        // Register focus redirection: when user finishes interacting with overlay
        // (mouseUp on StyledText or scrollbar), redirect focus back to editor
        // so Tab/Enter/Esc keys are handled by our interceptor
        if (currentOverlay instanceof AICompletionOverlay) {
            StyledText overlayStyledText = ((AICompletionOverlay) currentOverlay).getStyledText();
            if (overlayStyledText != null && !overlayStyledText.isDisposed()) {
            overlayStyledText.addMouseListener(new org.eclipse.swt.events.MouseAdapter() {
                @Override
                public void mouseUp(org.eclipse.swt.events.MouseEvent e) {
                    // After mouse release, redirect focus back to editor
                    if (currentViewer != null) {
                        StyledText editorWidget = currentViewer.getTextWidget();
                        if (editorWidget != null && !editorWidget.isDisposed()) {
                            Display.getDefault().asyncExec(() -> {
                                if (!editorWidget.isDisposed()) {
                                    editorWidget.setFocus();
                                }
                            });
                        }
                    }
                }
            });

            // Also redirect when overlay loses focus (e.g., user clicks scrollbar area)
            overlayStyledText.addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent e) {
                    // If focus is gained non-drag (simple click), redirect immediately
                    // The mouseUp handler will also handle the drag case
                    if (currentViewer != null) {
                        StyledText editorWidget = currentViewer.getTextWidget();
                        if (editorWidget != null && !editorWidget.isDisposed()) {
                            Display.getDefault().timerExec(200, () -> {
                                if (!editorWidget.isDisposed() && !overlayStyledText.isDisposed()) {
                                    editorWidget.setFocus();
                                }
                            });
                        }
                    }
                }
            });
            }
        }

        // Register global mouse filter - click inside overlay -> accept, click outside -> dismiss
        registerGlobalMouseFilter();
    }

    /**
     * Builds and shows the inline (Copilot-like) overlay at the cursor position.
     * The code is NOT inserted; only TAB/Enter accepts it (other keys cancel).
     * Mouse: clicking the hint text accepts it, any other mouse click cancels it.
     */
    private void showInlineOverlay(Shell parentShell, ITextViewer viewer,
                                   String completionText, int cursorOffset) {
        currentOverlay = new AICompletionInlineOverlay(
                parentShell,
                completionText,
                cursorOffset,
                AIConfiguration.getCompletionColor(),
                viewer,
                currentDocument,
                this::acceptSuggestion,
                () -> hideOverlayWithResult(PreferenceConstants.COMPLETION_RESULT_OTHER));

        if (currentOverlay.getShell() == null || currentOverlay.getShell().isDisposed()) {
            currentOverlay = null;
            return;
        }

        // Position and show
        currentOverlay.positionNearCursor(viewer);
        currentOverlay.open();

        // Register keyboard interceptor (TAB/Enter accept, other keys cancel)
        registerKeyInterceptor(viewer);

        // Register global mouse filter: click on suggestion -> accept (handled by the
        // inline overlay's own mouse listener); click anywhere else -> dismiss
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

    /** 提交补全处理结果给监听者（仅在用户互动时调用）。 */
    private void notifyResult(int result) {
        if (resultListener != null) {
            try { resultListener.accept(result); } catch (Exception ignored) {}
        }
    }

    /** 以指定结果关闭 overlay，并先提交结果回调。 */
    private void hideOverlayWithResult(int result) {
        notifyResult(result);
        hideOverlay();
    }

    public boolean isOverlayVisible() {
        return currentOverlay != null && !currentOverlay.isDisposed();
    }

    // ==================== Global Mouse Click Detection ====================

    /**
     * Detects mouse clicks via a global Display filter.
     * <ul>
     *   <li>Dialog overlay: click inside overlay → accept suggestion; click anywhere else → dismiss.</li>
     *   <li>Inline overlay: click on the editor StyledText is left to the inline overlay's own
     *       mouse listener (click on hint → accept / click elsewhere in editor → dismiss);
     *       any click outside the editor → dismiss.</li>
     * </ul>
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

            // Inline overlay: clicks on the editor widget are handled by the inline overlay's
            // own mouse listener; clicks anywhere else dismiss the hint.
            if (currentOverlay instanceof AICompletionInlineOverlay) {
                if (isClickOnEditorWidget(event.widget)) {
                    return;
                }
                hideOverlayWithResult(PreferenceConstants.COMPLETION_RESULT_OTHER);
                return;
            }

            // Dialog overlay path below.
            // Check if click is on the overlay's StyledText scrollbar
            // (scrollbar clicks should scroll, not accept/dismiss)
            if (isScrollbarClick(event.widget, event.x)) {
                return;
            }

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

            // If the click is on the overlay shell itself (but not on scrollbar) → accept suggestion
            if (widgetShell == overlayShell) {
                acceptSuggestion();
                return;
            }

            // Click anywhere else → dismiss overlay
            hideOverlayWithResult(PreferenceConstants.COMPLETION_RESULT_OTHER);
        };

        Display.getDefault().addFilter(SWT.MouseDown, globalMouseFilter);
        globalFilterInstalled = true;
    }

    /**
     * Returns true when the click originated on (or inside) the current editor's StyledText
     * widget. Used by the inline overlay path so its own hint-area mouse listener can
     * decide accept vs. cancel, rather than the global filter.
     */
    private boolean isClickOnEditorWidget(org.eclipse.swt.widgets.Widget widget) {
        if (currentViewer == null) return false;
        StyledText st = currentViewer.getTextWidget();
        if (st == null || st.isDisposed()) return false;

        org.eclipse.swt.widgets.Widget w = widget;
        while (w != null) {
            if (w == st) return true;
            if (w instanceof org.eclipse.swt.widgets.Control) {
                w = ((org.eclipse.swt.widgets.Control) w).getParent();
            } else {
                break;
            }
        }
        return false;
    }

    /**
     * Check if the clicked widget is a scrollbar of the overlay's StyledText.
     * Scrollbar clicks should be allowed to pass through for scrolling.
     */
    private boolean isScrollbarClick(org.eclipse.swt.widgets.Widget widget, int clickX) {
        // Only the dialog overlay has a scrollable StyledText; inline overlay is not mouse-handled
        if (!(currentOverlay instanceof AICompletionOverlay)) return false;
        StyledText st = ((AICompletionOverlay) currentOverlay).getStyledText();
        if (st == null || st.isDisposed()) return false;

        // Check if the widget is the StyledText's vertical or horizontal scrollbar
        if (widget instanceof org.eclipse.swt.widgets.ScrollBar) {
            org.eclipse.swt.widgets.ScrollBar sb = (org.eclipse.swt.widgets.ScrollBar) widget;
            org.eclipse.swt.widgets.Control parent = sb.getParent();
            if (parent == st) {
                return true;
            }
        }

        // Check if the click is in the StyledText's scrollbar area (right edge for V_SCROLL)
        if (widget == st) {
            Rectangle bounds = st.getBounds();
            if (clickX >= bounds.width - 20) {
                return true;
            }
        }

        return false;
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

        Display display = widget.getDisplay();

        // 使用 Display 过滤器（在事件到达控件之前全局捕获），而不是
        // StyledText.addKeyListener：后者在编辑器失焦时收不到 TAB/Enter/Esc，
        // 且 KeyDown 中 doit=false 无法取消 TAB 随后触发的 SWT.Traverse。
        keyDownFilter = event -> {
            if (!isOverlayVisible() || !isKeyEventForOverlay(event)) {
                return;
            }

            // Tab = accept
            if (event.keyCode == SWT.TAB) {
                event.doit = false;
                acceptSuggestion();
                return;
            }

            // Enter = accept
            if (event.keyCode == SWT.CR || event.keyCode == SWT.KEYPAD_CR) {
                event.doit = false;
                acceptSuggestion();
                return;
            }

            // Esc = dismiss
            if (event.keyCode == SWT.ESC) {
                event.doit = false;
                hideOverlayWithResult(PreferenceConstants.COMPLETION_RESULT_ESC);
                return;
            }

            // Navigation and modifier keys = keep overlay
            switch (event.keyCode) {
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

            // Any other key -> let the editor process it and dismiss
            event.doit = true;
            hideOverlayWithResult(PreferenceConstants.COMPLETION_RESULT_OTHER);
        };

        // TAB/Enter 除了产生 KeyDown，还会产生 SWT.Traverse（默认把焦点跳到下一个控件）。
        // 必须拦截并取消 Traverse，否则按下 TAB 后焦点先离开编辑器，随后异步执行的
        // document.replace 在 ABAP ADT 编辑器失焦时不会被提交（窗口关闭但代码未插入）。
        traverseFilter = event -> {
            if (!isOverlayVisible() || !isKeyEventForOverlay(event)) {
                return;
            }
            if (event.keyCode == SWT.TAB || event.keyCode == SWT.CR
                    || event.keyCode == SWT.KEYPAD_CR) {
                event.doit = false;
                event.detail = SWT.TRAVERSE_NONE;
            }
        };

        display.addFilter(SWT.KeyDown, keyDownFilter);
        display.addFilter(SWT.Traverse, traverseFilter);
        keyFilterInstalled = true;
    }

    /**
     * 判断按键事件是否属于当前补全上下文：事件发生在编辑器 StyledText 上，
     * 或发生在对话框式 overlay 的 shell 内。避免全局过滤器误吞其他控件的按键。
     */
    private boolean isKeyEventForOverlay(Event event) {
        if (currentViewer == null || event.widget == null) return false;

        StyledText editorWidget = currentViewer.getTextWidget();
        if (editorWidget != null && !editorWidget.isDisposed()
                && event.widget == editorWidget) {
            return true;
        }

        Shell overlayShell = currentOverlay != null ? currentOverlay.getShell() : null;
        if (overlayShell != null && !overlayShell.isDisposed()) {
            org.eclipse.swt.widgets.Widget w = event.widget;
            while (w != null) {
                if (w == overlayShell) return true;
                if (w instanceof org.eclipse.swt.widgets.Control) {
                    w = ((org.eclipse.swt.widgets.Control) w).getParent();
                } else {
                    break;
                }
            }
        }
        return false;
    }

    private void unregisterKeyInterceptor() {
        if (!keyFilterInstalled) return;
        Display display = Display.getCurrent();
        if (display == null) display = Display.getDefault();
        if (!display.isDisposed()) {
            if (keyDownFilter != null) {
                display.removeFilter(SWT.KeyDown, keyDownFilter);
            }
            if (traverseFilter != null) {
                display.removeFilter(SWT.Traverse, traverseFilter);
            }
        }
        keyDownFilter = null;
        traverseFilter = null;
        keyFilterInstalled = false;
    }

    // ==================== Accept Suggestion ====================

    private void acceptSuggestion() {
        if (currentOverlay == null || currentDocument == null) return;

        notifyResult(PreferenceConstants.COMPLETION_RESULT_CONFIRMED);

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
                // 插入前确保焦点在编辑器上：ABAP ADT 编辑器在失焦时可能不提交
                // 编程式的文档修改。
                if (currentViewer != null) {
                    StyledText editorWidget = currentViewer.getTextWidget();
                    if (editorWidget != null && !editorWidget.isDisposed()) {
                        editorWidget.setFocus();
                    }
                }
                currentDocument.replace(insertOffset, 0, text);
            } catch (Exception ex) {
                com.sap.abap.ai.completion.logging.AILogger.logError(
                        "Overlay", "accept suggestion insert failed: " + ex);
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
