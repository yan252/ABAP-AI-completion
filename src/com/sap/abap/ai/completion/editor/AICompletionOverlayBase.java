package com.sap.abap.ai.completion.editor;

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.widgets.Shell;

/**
 * Common contract implemented by both AI completion overlay styles
 * (dialog popup and inline ghost text). {@link AIOverlayManager} operates
 * on this abstraction to show, position and dismiss the current overlay.
 */
public interface AICompletionOverlayBase {

    void positionNearCursor(ITextViewer viewer);

    void open();

    void close();

    boolean isDisposed();

    int getCursorOffset();

    String getCompletionText();

    Shell getShell();
}
