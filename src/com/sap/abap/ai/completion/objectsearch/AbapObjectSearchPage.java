package com.sap.abap.ai.completion.objectsearch;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Adapters;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.DialogPage;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.search.ui.ISearchPage;
import org.eclipse.search.ui.ISearchPageContainer;
import org.eclipse.search.ui.NewSearchUI;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.recent.AdtObjectResolver;

/**
 * "ABAP Object Search" tab of the standard Eclipse Search dialog (Ctrl+H).
 *
 * <p>Left side: the object categories (multi select via check boxes). Right
 * side: the object name pattern. {@code *} matches any sequence of characters.
 * The Search button of the dialog runs the query; hits are rendered by
 * {@link AbapObjectSearchResultPage}.</p>
 */
public final class AbapObjectSearchPage extends DialogPage implements ISearchPage {

    private static final String PREF_LAST_PATTERN = "objectSearch.lastPattern";
    private static final String PREF_SELECTED_TYPES = "objectSearch.selectedTypes";
    private static final String PREF_LAST_PROJECT = "objectSearch.lastProject";
    private static final String ABAP_PROJECT_NATURE = "com.sap.adt.abapnature";

    /** Clicks left of this offset hit the check box, which SWT toggles itself. */
    private static final int CHECKBOX_CLICK_WIDTH = 22;

    private ISearchPageContainer container;
    private Table typeTable;
    private Text patternText;
    private Combo projectCombo;

    @Override
    public void setContainer(ISearchPageContainer container) {
        this.container = container;
    }

    @Override
    public void createControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(2, false));
        root.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        createTypeList(root);
        createSearchArea(root);

        setControl(root);
        if (container != null) {
            container.setPerformActionEnabled(true);
        }
    }

    /** Left side: checkable list of object categories with icons. */
    private void createTypeList(Composite root) {
        Group group = new Group(root, SWT.NONE);
        group.setText(Messages.get("group.objectTypes"));
        group.setLayout(new GridLayout(1, false));
        GridData groupData = new GridData(SWT.FILL, SWT.FILL, false, true);
        groupData.widthHint = 210;
        groupData.heightHint = 250;
        group.setLayoutData(groupData);

        typeTable = new Table(group, SWT.CHECK | SWT.BORDER | SWT.SINGLE | SWT.V_SCROLL);
        typeTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Set<AbapObjectType> saved = savedSelectedTypes();
        for (AbapObjectType type : AbapObjectType.values()) {
            TableItem item = new TableItem(typeTable, SWT.NONE);
            item.setText(type.getLabel());
            Image icon = AbapObjectIcons.get(type);
            if (icon != null) {
                item.setImage(icon);
            }
            // Restore the selection of the previous search; without a stored
            // selection "Programs" is the most common entry and starts checked.
            item.setChecked(saved.isEmpty() ? type == AbapObjectType.PROGRAM : saved.contains(type));
            item.setData(type);
        }
        // SWT only toggles a check box when the box itself is clicked; clicking
        // the object type name should toggle it as well.
        typeTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseUp(MouseEvent e) {
                if (e.button != 1) {
                    return;
                }
                TableItem item = typeTable.getItem(new Point(e.x, e.y));
                if (item == null || e.x <= CHECKBOX_CLICK_WIDTH) {
                    return;
                }
                item.setChecked(!item.getChecked());
                rememberSelectedTypes();
            }
        });

        Composite buttons = new Composite(group, SWT.NONE);
        buttons.setLayout(new GridLayout(2, true));
        buttons.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Button selectAll = new Button(buttons, SWT.PUSH);
        selectAll.setText(Messages.get("button.selectAll"));
        selectAll.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        selectAll.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> setAllChecked(true)));
        Button clearAll = new Button(buttons, SWT.PUSH);
        clearAll.setText(Messages.get("button.clearAll"));
        clearAll.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        clearAll.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> setAllChecked(false)));
    }

    /** Right side: project selection, object name field and status line. */
    private void createSearchArea(Composite root) {
        Composite area = new Composite(root, SWT.NONE);
        area.setLayout(new GridLayout(1, false));
        area.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        Composite projectRow = new Composite(area, SWT.NONE);
        projectRow.setLayout(new GridLayout(2, false));
        projectRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label projectLabel = new Label(projectRow, SWT.NONE);
        projectLabel.setText(Messages.get("label.project"));
        projectCombo = new Combo(projectRow, SWT.READ_ONLY | SWT.DROP_DOWN);
        projectCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        projectCombo.setToolTipText(Messages.get("hint.project"));
        fillProjectCombo();

        Label separator = new Label(area, SWT.SEPARATOR | SWT.HORIZONTAL);
        separator.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label patternLabel = new Label(area, SWT.NONE);
        patternLabel.setText(Messages.get("label.pattern"));

        patternText = new Text(area, SWT.BORDER);
        patternText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        patternText.setText(savedPattern());
        patternText.setToolTipText(Messages.get("hint.wildcard"));

        Label hint = new Label(area, SWT.WRAP);
        hint.setText(Messages.get("hint.wildcard"));
        hint.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label about = new Label(area, SWT.WRAP);
        about.setText(Messages.get("hint.about"));
        about.setLayoutData(new GridData(SWT.FILL, SWT.END, true, true));
    }

    @Override
    public void setVisible(boolean visible) {
        super.setVisible(visible);
        if (visible) {
            fillProjectCombo();
            if (patternText != null && !patternText.isDisposed()) {
                patternText.setFocus();
                patternText.selectAll();
            }
        }
    }

    @Override
    public boolean performAction() {
        String pattern = patternText == null || patternText.isDisposed()
                ? "" : patternText.getText().trim();
        if (pattern.isEmpty()) {
            showError(Messages.get("error.patternEmpty"));
            return false;
        }
        Set<AbapObjectType> selectedTypes = selectedTypes();
        if (selectedTypes.isEmpty()) {
            showError(Messages.get("error.noType"));
            return false;
        }
        IProject project = selectedProject();
        if (project == null) {
            showError(Messages.get("error.noProject"));
            return false;
        }
        rememberPattern(pattern);
        rememberSelectedTypes();
        rememberProject(project.getName());
        // A project that is not logged on yet cannot answer RFC calls, so let
        // ADT's own logon dialog ask for user / password first.
        if (!ensureLogon(project)) {
            return false;
        }
        NewSearchUI.runQueryInBackground(new AbapObjectSearchQuery(project, pattern, selectedTypes));
        return true;
    }

    /**
     * Ensures the ADT project is logged on, using ADT's standard logon dialog
     * (asks for user / password when needed, does nothing when already logged
     * on). Returns false when the user cancels or the logon fails.
     */
    private boolean ensureLogon(IProject project) {
        try {
            Class<?> factoryClass = loadClass("com.sap.adt.destinations.ui",
                    "com.sap.adt.destinations.ui.logon.AdtLogonServiceUIFactory");
            if (factoryClass == null) {
                return true;
            }
            Object service = factoryClass.getMethod("createLogonServiceUI").invoke(null);
            if (service == null) {
                return true;
            }
            ClassLoader loader = service.getClass().getClassLoader();
            Class<?> serviceClass = Class.forName(
                    "com.sap.adt.destinations.ui.logon.IAdtLogonServiceUI", true, loader);
            Class<?> adaptableClass = Class.forName(
                    "org.eclipse.core.runtime.IAdaptable", true, loader);
            Class<?> contextClass = Class.forName(
                    "org.eclipse.jface.operation.IRunnableContext", true, loader);
            Object context = new ProgressMonitorDialog(getShell());
            Object status = serviceClass.getMethod("ensureLoggedOn", adaptableClass, contextClass)
                    .invoke(service, project, context);
            if (!(status instanceof IStatus)) {
                return true;
            }
            IStatus logonStatus = (IStatus) status;
            if (logonStatus.isOK()) {
                return true;
            }
            if (logonStatus.getSeverity() == IStatus.CANCEL) {
                return false;
            }
            showError(logonStatus.getMessage());
            return false;
        } catch (Throwable t) {
            // Logon UI unavailable: let the search report the connection error.
            return true;
        }
    }

    private static Class<?> loadClass(String bundleId, String className) {
        try {
            Bundle bundle = Platform.getBundle(bundleId);
            return bundle == null ? null : bundle.loadClass(className);
        } catch (Throwable t) {
            return null;
        }
    }

    private void showError(String message) {
        if (getControl() != null && getControl().getShell() != null) {
            MessageDialog.openError(getControl().getShell(), Messages.get("dialog.title"), message);
        }
    }

    private Set<AbapObjectType> selectedTypes() {
        Set<AbapObjectType> types = EnumSet.noneOf(AbapObjectType.class);
        if (typeTable == null || typeTable.isDisposed()) {
            return types;
        }
        for (TableItem item : typeTable.getItems()) {
            if (item.getChecked() && item.getData() instanceof AbapObjectType) {
                types.add((AbapObjectType) item.getData());
            }
        }
        return types;
    }

    /** Fills the project combo with all accessible ADT projects. */
    private void fillProjectCombo() {
        if (projectCombo == null || projectCombo.isDisposed()) {
            return;
        }
        String previous = projectCombo.getSelectionIndex() >= 0
                ? projectCombo.getItem(projectCombo.getSelectionIndex()) : "";
        projectCombo.removeAll();
        List<IProject> projects = adtProjects();
        for (IProject project : projects) {
            projectCombo.add(project.getName());
        }
        // Default is the current project (active editor / Project Explorer);
        // only when that cannot be determined remember the last used one.
        IProject current = resolveProject();
        String target = current != null ? current.getName() : previous;
        if (target == null || target.isEmpty()) {
            target = savedProject();
        }
        int index = target == null ? -1 : projectCombo.indexOf(target);
        if (index < 0 && !projects.isEmpty()) {
            index = 0;
        }
        if (index >= 0) {
            projectCombo.select(index);
        }
    }

    /** All accessible ABAP projects, sorted by name. */
    private static List<IProject> adtProjects() {
        List<IProject> projects = new ArrayList<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (isAbapProject(project)) {
                projects.add(project);
            }
        }
        projects.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return projects;
    }

    /** The project chosen in the combo box, or the resolved default. */
    private IProject selectedProject() {
        if (projectCombo != null && !projectCombo.isDisposed()
                && projectCombo.getSelectionIndex() >= 0) {
            String name = projectCombo.getItem(projectCombo.getSelectionIndex());
            for (IProject project : adtProjects()) {
                if (project.getName().equals(name)) {
                    return project;
                }
            }
        }
        return resolveProject();
    }

    private void setAllChecked(boolean checked) {
        if (typeTable == null || typeTable.isDisposed()) {
            return;
        }
        for (TableItem item : typeTable.getItems()) {
            item.setChecked(checked);
        }
        rememberSelectedTypes();
    }

    /**
     * Determines the ABAP project whose system is searched: the project of the
     * active editor, then the project selected in the Project Explorer, then
     * the first open ABAP project.
     */
    private IProject resolveProject() {
        IEditorPart editor = activeEditor();
        if (editor != null) {
            IProject project = AdtObjectResolver.findProject(editor.getEditorInput(), editor);
            if (isAbapProject(project)) {
                return project;
            }
            AdtObjectResolver.AdtObjectInfo info = AdtObjectResolver.getAdtObject(editor);
            if (info != null && isAbapProject(info.project)) {
                return info.project;
            }
        }
        if (container != null) {
            IProject project = projectOfSelection(container.getSelection());
            if (project != null) {
                return project;
            }
        }
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (isAbapProject(project)) {
                return project;
            }
        }
        return null;
    }

    private static IProject projectOfSelection(ISelection selection) {
        if (!(selection instanceof IStructuredSelection)) {
            return null;
        }
        for (Object element : ((IStructuredSelection) selection).toList()) {
            if (element instanceof IResource) {
                IProject project = ((IResource) element).getProject();
                if (isAbapProject(project)) {
                    return project;
                }
            }
            Object adapted = Adapters.adapt(element, IProject.class);
            if (adapted instanceof IProject && isAbapProject((IProject) adapted)) {
                return (IProject) adapted;
            }
        }
        return null;
    }

    private static IEditorPart activeEditor() {
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) {
            return null;
        }
        IWorkbenchPage page = window.getActivePage();
        return page == null ? null : page.getActiveEditor();
    }

    private static boolean isAbapProject(IProject project) {
        if (project == null || !project.isAccessible()) {
            return false;
        }
        try {
            return project.hasNature(ABAP_PROJECT_NATURE);
        } catch (Throwable t) {
            return false;
        }
    }

    private static String savedPattern() {
        try {
            if (Activator.staticGetPreferenceStore() == null) {
                return "";
            }
            return Activator.staticGetPreferenceStore().getString(PREF_LAST_PATTERN);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void rememberPattern(String pattern) {
        try {
            if (Activator.staticGetPreferenceStore() != null) {
                Activator.staticGetPreferenceStore().setValue(PREF_LAST_PATTERN, pattern);
            }
        } catch (Throwable ignored) {
            // preferences are optional
        }
    }

    /** Object types selected in the previous search. */
    private static Set<AbapObjectType> savedSelectedTypes() {
        Set<AbapObjectType> types = EnumSet.noneOf(AbapObjectType.class);
        try {
            if (Activator.staticGetPreferenceStore() == null) {
                return types;
            }
            String value = Activator.staticGetPreferenceStore().getString(PREF_SELECTED_TYPES);
            for (String name : value.split(",")) {
                String trimmed = name.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    types.add(AbapObjectType.valueOf(trimmed));
                } catch (IllegalArgumentException ignored) {
                    // type no longer exists
                }
            }
        } catch (Throwable ignored) {
            // preferences are optional
        }
        return types;
    }

    private void rememberSelectedTypes() {
        try {
            if (Activator.staticGetPreferenceStore() == null) {
                return;
            }
            StringBuilder value = new StringBuilder();
            for (AbapObjectType type : selectedTypes()) {
                if (value.length() > 0) {
                    value.append(',');
                }
                value.append(type.name());
            }
            Activator.staticGetPreferenceStore().setValue(PREF_SELECTED_TYPES, value.toString());
        } catch (Throwable ignored) {
            // preferences are optional
        }
    }

    private static String savedProject() {
        try {
            if (Activator.staticGetPreferenceStore() == null) {
                return "";
            }
            return Activator.staticGetPreferenceStore().getString(PREF_LAST_PROJECT);
        } catch (Throwable t) {
            return "";
        }
    }

    private static void rememberProject(String projectName) {
        try {
            if (Activator.staticGetPreferenceStore() != null) {
                Activator.staticGetPreferenceStore().setValue(PREF_LAST_PROJECT, projectName);
            }
        } catch (Throwable ignored) {
            // preferences are optional
        }
    }
}
