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
            MessageDialog.openError(shellOrActive(shell), "abap-cli Runtime",
                    "Failed to prepare the abap-cli runtime:\n\n" + e.getMessage());
            return false;
        }

        Shell sh = shellOrActive(shell);
        MessageDialog dialog = new MessageDialog(sh, "abap-cli Runtime Required", null,
                "The abap-cli runtime is not installed yet.\n\n"
                        + "It is required for the SAP connection test and the template import\n"
                        + "and is no longer bundled with the plugin. It will be downloaded once\n"
                        + "(about 17 MB) from the plugin's GitHub Releases page:\n"
                        + AbapCliRuntime.GITHUB_RELEASES_URL + "\n\n"
                        + "Download and install it now?\n"
                        + "(Node.js 18+ must already be available on PATH)",
                MessageDialog.QUESTION, new String[] { "Install Now", "Cancel" }, 0);
        if (dialog.open() != 0) {
            return false;
        }

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
            if (msg.contains("cancelled")) {
                return false; // cancelled in the progress dialog — no error popup
            }
            logError("abap-cli runtime installation failed", t);
            MessageDialog.openError(shellOrActive(shell),
                    "abap-cli Runtime Installation Failed",
                    "Could not download or install the abap-cli runtime:\n\n" + msg
                            + "\n\nPlease download the runtime zip manually from:\n"
                            + AbapCliRuntime.GITHUB_RELEASES_URL
                            + "\n\nExtract it, point the system property 'abap.cli.stage.dir'"
                            + "\n(or environment variable ABAP_CLI_STAGE_DIR) at the extracted"
                            + " folder,\nand restart Eclipse.");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        MessageDialog.openInformation(shellOrActive(shell), "abap-cli Runtime",
                "The abap-cli runtime has been installed successfully.");
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
