package com.sap.abap.ai.completion.ui;

import java.net.URL;

import org.eclipse.core.commands.Command;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.handlers.IHandlerService;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.preferences.PreferenceConstants;

/**
 * Builds the ABAP AI Completion popup menu.
 *
 * <p>The same menu content is used in two places so they stay identical:
 * <ul>
 *   <li>the status-bar icon popup (see {@link AbapAIStatusLineContribution})</li>
 *   <li>the "ABAP AI Completion" submenu inside the ABAP editor context menu
 *       (see {@link AICompletionEditorMenuContribution})</li>
 * </ul>
 * </p>
 */
public final class AICompletionMenuBuilder {

    private static final String COMMAND_ID = "com.sap.abap.ai.completion.completeCommand";
    private static final String HELP_URL = "https://github.com/yan252/ABAP-AI-completion";

    private AICompletionMenuBuilder() {
    }

    /**
     * Populates the given (already created) {@code menu} with the ABAP AI
     * Completion menu items.
     *
     * @param menu the menu to populate (caller is responsible for creation
     *             and for {@code setVisible(true)} afterwards)
     */
    public static void populateMenu(Menu menu) {
        if (menu == null || menu.isDisposed()) return;

        MenuItem prefsItem = new MenuItem(menu, SWT.PUSH);
        prefsItem.setText("Preferences...");
        prefsItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                openPreferences();
            }
        });

        new MenuItem(menu, SWT.SEPARATOR);

        final MenuItem enableItem = new MenuItem(menu, SWT.CHECK);
        enableItem.setText("Enable");
        enableItem.setSelection(isPluginEnabled());
        enableItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                setPluginEnabled(enableItem.getSelection());
            }
        });

        final MenuItem autoCompleteItem = new MenuItem(menu, SWT.CHECK);
        autoCompleteItem.setText("Auto-complete while typing (Temporary)");
        autoCompleteItem.setSelection(isAutoCompletionEnabled());
        autoCompleteItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                setAutoCompletionEnabled(autoCompleteItem.getSelection());
            }
        });

        new MenuItem(menu, SWT.SEPARATOR);

        final MenuItem skillItem = new MenuItem(menu, SWT.CHECK);
        skillItem.setText("Enable Skill Reference");
        skillItem.setSelection(isSkillEnabled());
        skillItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                setSkillEnabled(skillItem.getSelection());
            }
        });

        final MenuItem parentLookupItem = new MenuItem(menu, SWT.CHECK);
        parentLookupItem.setText("Enable Parent Program Lookup");
        parentLookupItem.setSelection(isParentProgramResolutionEnabled());
        parentLookupItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                setParentProgramResolutionEnabled(parentLookupItem.getSelection());
            }
        });

        final MenuItem workspaceCodeItem = new MenuItem(menu, SWT.CHECK);
        workspaceCodeItem.setText("AI Reference Workspace Code");
        workspaceCodeItem.setSelection(isWorkspaceCodeReferenceEnabled());
        workspaceCodeItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                setWorkspaceCodeReferenceEnabled(workspaceCodeItem.getSelection());
            }
        });

        new MenuItem(menu, SWT.SEPARATOR);

        // ==================== Templates 子菜单 ====================
        // Each child item corresponds to a zip file under references/,
        // uploaded to SAP via RFC Z_ABAPGIT_UPLOAD_FROM_XSTRING.
        // The "SAP Connection Config..." entry is placed at the top of this
        // submenu, followed by a separator and then the template items.
        MenuItem templateItem = new MenuItem(menu, SWT.CASCADE);
        templateItem.setText("Templates");
        Menu templateMenu = new Menu(templateItem);

        // SAP Connection Config as the first (top) child of the Templates submenu
        MenuItem sapPrefsItem = new MenuItem(templateMenu, SWT.PUSH);
        sapPrefsItem.setText("SAP Connection Config...");
        sapPrefsItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                openSapPreferences();
            }
        });

        new MenuItem(templateMenu, SWT.SEPARATOR);

        for (final ABAPTemplateService.TemplateZipDef def
                : ABAPTemplateService.getTemplateDefs()) {
            MenuItem item = new MenuItem(templateMenu, SWT.PUSH);
            item.setText(def.getLabel());
            item.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent e) {
                    ABAPTemplateService.runTemplateUpload(def.getLabel());
                }
            });
        }
        // Multi-Tab Query Handler Template: imports ZTEMPLATE10.zip via abap-cli
        // (stage directory + node), renaming ztemplate10 to the user-entered name.
        new MenuItem(templateMenu, SWT.SEPARATOR);
        final MenuItem multiTabItem = new MenuItem(templateMenu, SWT.PUSH);
        multiTabItem.setText("Multi-Tab Query Handler Template");
        multiTabItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                MultiTabTemplateImportService.runImport();
            }
        });

        templateItem.setMenu(templateMenu);

        new MenuItem(menu, SWT.SEPARATOR);

        MenuItem completeItem = new MenuItem(menu, SWT.PUSH);
        completeItem.setText("Code Completion\tCtrl+Shift+.");
        completeItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                triggerCompletion();
            }
        });

        new MenuItem(menu, SWT.SEPARATOR);

        MenuItem helpItem = new MenuItem(menu, SWT.PUSH);
        helpItem.setText("Help");
        helpItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent e) {
                openHelpUrl();
            }
        });
    }

    // ==================== Preferences ====================

    private static boolean isPluginEnabled() {
        try {
            IPreferenceStore store = Activator.staticGetPreferenceStore();
            if (store == null) return PreferenceConstants.DEFAULT_PLUGIN_ENABLED;
            return store.getBoolean(PreferenceConstants.PLUGIN_ENABLED);
        } catch (Exception e) {
            return PreferenceConstants.DEFAULT_PLUGIN_ENABLED;
        }
    }

    private static void setPluginEnabled(boolean enabled) {
        try {
            IPreferenceStore store = Activator.staticGetPreferenceStore();
            if (store == null) return;
            store.setValue(PreferenceConstants.PLUGIN_ENABLED, enabled);
            save();
        } catch (Exception e) {
        }
    }

    private static boolean isAutoCompletionEnabled() {
        try {
            IPreferenceStore store = Activator.staticGetPreferenceStore();
            if (store == null) return PreferenceConstants.DEFAULT_AUTO_COMPLETION_ENABLED;
            return store.getBoolean(PreferenceConstants.AUTO_COMPLETION_ENABLED);
        } catch (Exception e) {
            return PreferenceConstants.DEFAULT_AUTO_COMPLETION_ENABLED;
        }
    }

    private static void setAutoCompletionEnabled(boolean enabled) {
        try {
            IPreferenceStore store = Activator.staticGetPreferenceStore();
            if (store == null) return;
            store.setValue(PreferenceConstants.AUTO_COMPLETION_ENABLED, enabled);
            save();
        } catch (Exception e) {
        }
    }

    private static boolean isWorkspaceCodeReferenceEnabled() {
        try {
            IPreferenceStore store = Activator.staticGetPreferenceStore();
            if (store == null) return PreferenceConstants.DEFAULT_WORKSPACE_CODE_REFERENCE_ENABLED;
            return store.getBoolean(PreferenceConstants.WORKSPACE_CODE_REFERENCE_ENABLED);
        } catch (Exception e) {
            return PreferenceConstants.DEFAULT_WORKSPACE_CODE_REFERENCE_ENABLED;
        }
    }

    private static void setWorkspaceCodeReferenceEnabled(boolean enabled) {
        try {
            IPreferenceStore store = Activator.staticGetPreferenceStore();
            if (store == null) return;
            store.setValue(PreferenceConstants.WORKSPACE_CODE_REFERENCE_ENABLED, enabled);
            save();
        } catch (Exception e) {
        }
    }

    private static boolean isSkillEnabled() {
        try {
            IPreferenceStore store = Activator.staticGetPreferenceStore();
            if (store == null) return PreferenceConstants.DEFAULT_SKILL_ENABLED;
            return store.getBoolean(PreferenceConstants.SKILL_ENABLED);
        } catch (Exception e) {
            return PreferenceConstants.DEFAULT_SKILL_ENABLED;
        }
    }

    private static void setSkillEnabled(boolean enabled) {
        try {
            IPreferenceStore store = Activator.staticGetPreferenceStore();
            if (store == null) return;
            store.setValue(PreferenceConstants.SKILL_ENABLED, enabled);
            save();
        } catch (Exception e) {
        }
    }

    private static boolean isParentProgramResolutionEnabled() {
        try {
            IPreferenceStore store = Activator.staticGetPreferenceStore();
            if (store == null) return PreferenceConstants.DEFAULT_PARENT_PROGRAM_RESOLUTION_ENABLED;
            return store.getBoolean(PreferenceConstants.PARENT_PROGRAM_RESOLUTION_ENABLED);
        } catch (Exception e) {
            return PreferenceConstants.DEFAULT_PARENT_PROGRAM_RESOLUTION_ENABLED;
        }
    }

    private static void setParentProgramResolutionEnabled(boolean enabled) {
        try {
            IPreferenceStore store = Activator.staticGetPreferenceStore();
            if (store == null) return;
            store.setValue(PreferenceConstants.PARENT_PROGRAM_RESOLUTION_ENABLED, enabled);
            save();
        } catch (Exception e) {
        }
    }

    private static void save() {
        try {
            Activator.getDefault().savePluginPreferences();
        } catch (Exception ignored) {
        }
    }

    // ==================== Actions ====================

    static void triggerCompletion() {
        try {
            IHandlerService hs = PlatformUI.getWorkbench().getService(IHandlerService.class);
            ICommandService cs = PlatformUI.getWorkbench().getService(ICommandService.class);
            if (hs == null || cs == null) return;
            Command command = cs.getCommand(COMMAND_ID);
            if (command == null || !command.isDefined()) return;
            ExecutionEvent event = hs.createExecutionEvent(command, null);
            command.executeWithChecks(event);
        } catch (Exception ex) {
            try {
                IHandlerService hs = PlatformUI.getWorkbench().getService(IHandlerService.class);
                if (hs != null) {
                    hs.executeCommand(COMMAND_ID, null);
                }
            } catch (Exception ignored) {
            }
        }
    }

    static void openPreferences() {
        openPreferenceDialog("com.sap.abap.ai.completion.preferencePage");
    }

    static void openSapPreferences() {
        openPreferenceDialog("com.sap.abap.ai.completion.preferencePage.sap");
    }

    private static void openPreferenceDialog(final String pageId) {
        try {
            PlatformUI.getWorkbench().getDisplay().asyncExec(() -> {
                try {
                    Class<?> dialogCls = Class.forName("org.eclipse.ui.dialogs.PreferencesUtil");
                    java.lang.reflect.Method createDialog = dialogCls.getMethod(
                            "createPreferenceDialogOn",
                            org.eclipse.swt.widgets.Shell.class,
                            String.class,
                            String[].class,
                            Object.class);
                    Object dialog = createDialog.invoke(null, getShell(),
                            pageId, null, null);
                    if (dialog instanceof org.eclipse.jface.dialogs.Dialog) {
                        ((org.eclipse.jface.dialogs.Dialog) dialog).open();
                    }
                } catch (Exception fallback) {
                    try {
                        java.lang.reflect.Method m = PlatformUI.getWorkbench().getClass()
                                .getMethod("getPreferenceManager");
                        Object pm = m.invoke(PlatformUI.getWorkbench());
                        Object dlg = Class.forName("org.eclipse.ui.dialogs.WorkbenchPreferenceDialog")
                                .getConstructor(org.eclipse.swt.widgets.Shell.class,
                                        Class.forName("org.eclipse.jface.preference.IPreferenceManager"))
                                .newInstance(getShell(), pm);
                        if (dlg instanceof org.eclipse.jface.dialogs.Dialog) {
                            ((org.eclipse.jface.dialogs.Dialog) dlg).open();
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
        } catch (Exception e) {
        }
    }

    private static org.eclipse.swt.widgets.Shell getShell() {
        try {
            if (PlatformUI.getWorkbench() == null) return null;
            org.eclipse.swt.widgets.Shell shell = PlatformUI.getWorkbench().getDisplay().getActiveShell();
            if (shell == null || shell.isDisposed()) {
                org.eclipse.ui.IWorkbenchWindow window =
                        PlatformUI.getWorkbench().getActiveWorkbenchWindow();
                if (window != null) shell = window.getShell();
            }
            return shell;
        } catch (Exception e) {
            return null;
        }
    }

    static void openHelpUrl() {
        try {
            PlatformUI.getWorkbench().getBrowserSupport()
                    .getExternalBrowser()
                    .openURL(new URL(HELP_URL));
        } catch (Exception e) {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(new java.net.URI(HELP_URL));
                }
            } catch (Exception ignored) {
            }
        }
    }
}
