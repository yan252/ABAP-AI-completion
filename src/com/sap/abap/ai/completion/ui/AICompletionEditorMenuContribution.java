package com.sap.abap.ai.completion.ui;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.action.ContributionItem;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

import com.sap.abap.ai.completion.Activator;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.sap.abap.ai.completion.parser.AbapLanguageDetector;

/**
 * Contributes an "ABAP AI Completion" submenu to the ABAP editor context menu.
 *
 * <p>The submenu reuses the exact same content as the status-bar popup via
 * {@link AICompletionMenuBuilder}, so both menus stay identical.</p>
 *
 * <p>It only contributes the submenu when the active editor is an ABAP source
 * editor (see {@link AbapLanguageDetector#isAbapContext}).</p>
 */
public class AICompletionEditorMenuContribution extends ContributionItem {

    public AICompletionEditorMenuContribution() {
        super();
    }

    public AICompletionEditorMenuContribution(String id) {
        super(id);
    }

    @Override
    public void fill(Menu menu, int index) {
        ITextEditor editor = findActiveAbapEditor();
        if (editor == null) {
            return;
        }

        try {
            MenuItem subMenuItem = new MenuItem(menu, SWT.CASCADE, index);
            subMenuItem.setText("ABAP AI Completion");

            // 在菜单项前显示 SAP Logo 图标(与状态栏一致的插件图标)
            Image icon = createIcon();
            if (icon != null) {
                subMenuItem.setImage(icon);
                subMenuItem.addListener(SWT.Dispose,
                        e -> { if (!icon.isDisposed()) icon.dispose(); });
            }

            Menu subMenu = new Menu(subMenuItem);
            AICompletionMenuBuilder.populateMenu(subMenu);
            subMenuItem.setMenu(subMenu);
        } catch (Exception e) {
            // 构建子菜单失败不影响其它菜单项
        }
    }

    /**
     * Loads the plugin {@code icons/SAPLogo.ico} as an {@link Image}, or
     * returns {@code null} if it cannot be loaded.
     */
    private Image createIcon() {
        if (Activator.getDefault() == null) {
            return null;
        }
        ImageDescriptor desc = Activator.getImageDescriptor("icons/SAPLogo.ico");
        if (desc == null) {
            return null;
        }
        return desc.createImage();
    }

    /**
     * Returns the active editor if it is an ABAP editor, otherwise {@code null}.
     */
    private ITextEditor findActiveAbapEditor() {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) {
                return null;
            }
            if (window.getActivePage() == null) {
                return null;
            }

            IWorkbenchPage page = window.getActivePage();
            IEditorPart active = page.getActiveEditor();
            if (active == null) {
                return null;
            }
            ITextEditor editor;
            if (active instanceof ITextEditor) {
                editor = (ITextEditor) active;
            } else {
                ITextEditor adapted = active.getAdapter(ITextEditor.class);
                if (adapted == null) {
                    return null;
                }
                editor = adapted;
            }

            if (!isAbapEditor(editor)) {
                return null;
            }
            return editor;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isAbapEditor(ITextEditor editor) {
        try {
            IEditorInput input = editor.getEditorInput();
            IFile file = null;
            if (input instanceof FileEditorInput) {
                file = ((FileEditorInput) input).getFile();
            }
            IDocument doc = null;
            IDocumentProvider dp = editor.getDocumentProvider();
            if (dp != null && input != null) {
                doc = dp.getDocument(input);
            }
            return AbapLanguageDetector.isAbapContext(editor, file, doc);
        } catch (Exception e) {
            return false;
        }
    }
}
