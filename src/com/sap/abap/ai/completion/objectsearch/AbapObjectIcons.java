package com.sap.abap.ai.completion.objectsearch;

import java.util.EnumMap;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;

/**
 * Small 16x16 icons for the object type list and the search result rows.
 *
 * <p>The icons are drawn at runtime (a coloured rounded square with one
 * character) so that every object category is visually distinguishable without
 * shipping bitmap resources or depending on ADT internal image registries.</p>
 */
final class AbapObjectIcons {

    private static final int SIZE = 16;
    private static final Map<AbapObjectType, Image> CACHE = new EnumMap<>(AbapObjectType.class);
    private static Font font;

    private AbapObjectIcons() {
    }

    /** Returns the icon of the category; must be called on the UI thread. */
    static Image get(AbapObjectType type) {
        if (type == null) {
            return null;
        }
        Image cached = CACHE.get(type);
        if (cached != null && !cached.isDisposed()) {
            return cached;
        }
        Display display = Display.getCurrent() != null ? Display.getCurrent() : Display.getDefault();
        if (display == null || display.isDisposed()) {
            return null;
        }
        Image image = draw(display, type);
        CACHE.put(type, image);
        return image;
    }

    private static Image draw(Display display, AbapObjectType type) {
        Image image = new Image(display, SIZE, SIZE);
        GC gc = new GC(image);
        Color background = new Color(display,
                (type.getRgb() >> 16) & 0xFF, (type.getRgb() >> 8) & 0xFF, type.getRgb() & 0xFF);
        Color foreground = new Color(display, 0xFF, 0xFF, 0xFF);
        try {
            gc.setBackground(background);
            gc.fillRoundRectangle(0, 0, SIZE - 1, SIZE - 1, 5, 5);
            gc.setForeground(foreground);
            gc.setFont(font(display));
            String text = type.getIconText();
            Point extent = gc.textExtent(text);
            gc.drawText(text, Math.max(0, (SIZE - extent.x) / 2),
                    Math.max(0, (SIZE - extent.y) / 2), true);
        } finally {
            gc.dispose();
            background.dispose();
            foreground.dispose();
        }
        return image;
    }

    private static Font font(Display display) {
        if (font == null || font.isDisposed()) {
            font = new Font(display, "Arial", 7, SWT.BOLD);
        }
        return font;
    }
}
