package com.sap.abap.ai.completion.tcode;

import java.net.URL;

import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.swt.graphics.Image;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;

/**
 * "事务码收藏" 节点的标签提供器。
 *
 * <p>根节点：显示 i18n 名称（Favorite Transactions / 事务码收藏）与收藏图标；
 * 子节点：显示 "事务码 - 事务文本"（如 "SE38 - ABAP Editor"）与事务码图标。</p>
 */
public class TcodeFavoriteLabelProvider extends LabelProvider {

    private static final String ROOT_ICON_PATH = "icons/tcode_favorites.png";
    private static final String ENTRY_ICON_PATH = "icons/tcode.png";

    private Image rootImage;
    private Image entryImage;

    @Override
    public String getText(Object element) {
        if (element instanceof TcodeFavoriteRoot) {
            return Messages.getString("tcodefav.content.name");
        }
        if (element instanceof TcodeFavoriteEntry) {
            TcodeFavoriteEntry entry = (TcodeFavoriteEntry) element;
            String text = entry.getText();
            return text.isEmpty()
                    ? entry.getTcode()
                    : entry.getTcode() + " - " + text;
        }
        return null;
    }

    @Override
    public Image getImage(Object element) {
        if (element instanceof TcodeFavoriteRoot) {
            if (rootImage == null) {
                rootImage = createImage(ROOT_ICON_PATH);
            }
            return rootImage;
        }
        if (element instanceof TcodeFavoriteEntry) {
            if (entryImage == null) {
                entryImage = createImage(ENTRY_ICON_PATH);
            }
            return entryImage;
        }
        return null;
    }

    @Override
    public void dispose() {
        if (rootImage != null) {
            rootImage.dispose();
            rootImage = null;
        }
        if (entryImage != null) {
            entryImage.dispose();
            entryImage = null;
        }
        super.dispose();
    }

    private static Image createImage(String path) {
        try {
            Bundle bundle = Platform.getBundle(Activator.PLUGIN_ID);
            if (bundle == null) return null;
            URL url = bundle.getEntry(path);
            if (url == null) return null;
            return ImageDescriptor.createFromURL(url).createImage();
        } catch (Throwable t) {
            return null;
        }
    }
}
