package com.sap.abap.ai.completion.ui;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;

import com.sap.abap.ai.completion.Activator;

/**
 * One-click installer dialog for the abap-cli runtime.
 *
 * <p>Since v1.1.49 the plugin no longer bundles the abap-cli runtime in the
 * JAR. Both entry points that need it — the "Test Connection" button on the
 * "SAP Connection Config" page and the SAP connection pre-check of every
 * "Templates" menu import — call {@link #ensureInstalledWithPrompt(Shell)}
 * first: when the runtime is missing, a dialog offers to download and install
 * it once from {@link AbapCliRuntime#GITHUB_REPO_URL}.</p>
 *
 * <p>Must be called from the UI thread (both call sites comply).</p>
 */
public final class AbapCliInstallPrompt {

    private AbapCliInstallPrompt() {
    }

    /**
     * Ensure the abap-cli runtime is installed; when missing, offer the
     * one-click download from GitHub.
     *
     * @param shell parent shell for the dialogs (may be {@code null}; the
     *              active shell is used as fallback)
     * @return {@code true} when the runtime is ready (installed now or already
     *         present), {@code false} when the caller must abort
     */
    public static boolean ensureInstalledWithPrompt(Shell shell) {
        try {
            AbapCliRuntime.ensureStage();
            return true;
        } catch (AbapCliRuntime.NotInstalledException missing) {
            // fall through: offer the one-click install below
        } catch (IOException e) {
            logError("Failed to prepare the abap-cli runtime", e);
            MessageDialog.openError(shellOrActive(shell), Messages.get("installPrompt.runtimeTitle"),
                    Messages.format("installPrompt.prepareFailed", e.getMessage()));
            return false;
        }

        Shell sh = shellOrActive(shell);
        MessageDialog dialog = new MessageDialog(sh, Messages.get("installPrompt.title"), null,
                Messages.format("installPrompt.message", AbapCliRuntime.RUNTIME_ZIP_URL),
                MessageDialog.QUESTION,
                new String[] { Messages.get("installPrompt.installNow"),
                        Messages.get("installPrompt.cancel") },
                0);
        if (dialog.open() != 0) {
            return false;
        }
        return installWithProgress(shell);
    }

    /**
     * Download and install the runtime unconditionally, showing a progress
     * dialog. The caller is responsible for any prior confirmation (e.g. the
     * "Download abap-cli" button asks whether to re-install an already present
     * runtime).
     *
     * @param shell parent shell for the dialogs (may be {@code null}; the
     *              active shell is used as fallback)
     * @return {@code true} on success, {@code false} on failure/cancel
     */
    public static boolean installWithProgress(Shell shell) {
        Shell sh = shellOrActive(shell);
        ProgressMonitorDialog pmd = new ProgressMonitorDialog(sh);
        try {
            pmd.run(true, true, new IRunnableWithProgress() {
                @Override
                public void run(IProgressMonitor monitor)
                        throws InvocationTargetException, InterruptedException {
                    try {
                        AbapCliRuntime.installFromGitHub(monitor);
                    } catch (IOException e) {
                        throw new InvocationTargetException(e);
                    }
                }
            });
        } catch (InvocationTargetException e) {
            Throwable t = e.getCause() != null ? e.getCause() : e;
            String msg = t.getMessage() != null ? t.getMessage() : t.toString();
            if (msg.contains("cancelled") || msg.contains(Messages.get("runtime.cancelled"))) {
                return false; // cancelled in the progress dialog — no error popup
            }
            logError("abap-cli runtime installation failed", t);
            MessageDialog.openError(shellOrActive(shell),
                    Messages.get("installPrompt.failedTitle"),
                    Messages.format("installPrompt.failedMessage", msg,
                            AbapCliRuntime.RUNTIME_ZIP_URL));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        MessageDialog.openInformation(shellOrActive(shell),
                Messages.get("installPrompt.runtimeTitle"),
                Messages.get("installPrompt.successMessage"));
        return true;
    }

    private static Shell shellOrActive(Shell shell) {
        if (shell != null && !shell.isDisposed()) {
            return shell;
        }
        Display display = Display.getDefault();
        return display != null ? display.getActiveShell() : null;
    }

    private static void logError(String message, Throwable t) {
        if (Activator.getDefault() != null) {
            Activator.getDefault().getLog().log(new Status(IStatus.ERROR, Activator.PLUGIN_ID,
                    message, t));
        }
    }
}
