package com.sap.abap.ai.completion.ui;

import org.eclipse.jface.action.ContributionItem;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;

/**
 * Contributes an "ABAP AI Completion" cascade submenu to the Eclipse main
 * menu bar (placed before the Help menu).
 *
 * <p>It reuses the exact same content as the status-bar popup and the editor
 * context menu via {@link AICompletionMenuBuilder}, so all menus stay
 * identical (including the "模板" submenu).</p>
 *
 * <p>Unlike the editor context-menu submenu and the status-bar icon, this
 * main-menu item intentionally displays <b>no icon</b> (Eclipse main menu
 * convention).</p>
 */
public class AICompletionMainMenuContribution extends ContributionItem {

    public AICompletionMainMenuContribution() {
        super();
    }

    public AICompletionMainMenuContribution(String id) {
        super(id);
    }

    @Override
    public void fill(Menu menu, int index) {
        try {
            MenuItem subMenuItem = new MenuItem(menu, SWT.CASCADE, index);
            subMenuItem.setText("ABAP AI Completion");

            Menu subMenu = new Menu(subMenuItem);
            AICompletionMenuBuilder.populateMenu(subMenu);
            subMenuItem.setMenu(subMenu);
        } catch (Exception e) {
            // 构建子菜单失败不影响其它菜单项
        }
    }
}
