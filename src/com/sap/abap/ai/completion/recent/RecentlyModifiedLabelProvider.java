package com.sap.abap.ai.completion.recent;

import java.net.URL;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;

/**
 * "最近修改" 根节点的标签提供器。
 *
 * <p>只处理 {@link RecentlyModifiedRoot}；子节点（ADT 原生 Repository Object 或
 * IFile）由 ADT 的 label provider 接管，这里不干预。</p>
 */
public class RecentlyModifiedLabelProvider extends LabelProvider {

    private static final String ICON_PATH = "icons/recently_modified.png";

    private Image rootImage;

    @Override
    public String getText(Object element) {
        if (element instanceof RecentlyModifiedRoot) {
            return Messages.getString("recent.content.name");
        }
        return null;
    }

    @Override
    public Image getImage(Object element) {
        if (element instanceof RecentlyModifiedRoot) {
            if (rootImage == null) {
                rootImage = createRootImage();
            }
            return rootImage;
        }
        return null; // 让 ADT 的 label provider 处理子节点图标
    }

    @Override
    public void dispose() {
        if (rootImage != null) {
            rootImage.dispose();
            rootImage = null;
        }
        super.dispose();
    }

    private static Image createRootImage() {
        try {
            Bundle bundle = Platform.getBundle(Activator.PLUGIN_ID);
            if (bundle == null) return null;
            URL url = bundle.getEntry(ICON_PATH);
            if (url == null) return null;
            return ImageDescriptor.createFromURL(url).createImage();
        } catch (Throwable t) {
            return null;
        }
    }
}
