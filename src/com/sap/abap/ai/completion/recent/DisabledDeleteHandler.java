package com.sap.abap.ai.completion.recent;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;

/**
 * "Recently Modified" 节点上禁用 Delete 命令（org.eclipse.ui.edit.delete）的
 * 占位 handler。
 *
 * <p>activeWhen 命中本插件节点时本 handler 生效且始终 disabled：右键菜单中的
 * Delete 项显示为灰色，Del 快捷键同时失效，避免从该节点误删 SAP 服务器上的对象。</p>
 */
public class DisabledDeleteHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        return null; // 不可达：handler 始终 disabled
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
