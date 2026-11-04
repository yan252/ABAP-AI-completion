package com.sap.abap.ai.completion.codesearch;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

/** Command handler for the "ABAP Code Search" menu entry / Ctrl+Shift+H. */
public final class AbapCodeSearchHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            return null;
        }
        Shell shell = window.getShell();
        new AbapCodeSearchDialog(shell).open();
        return null;
    }
}
