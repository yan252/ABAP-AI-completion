package com.sap.abap.ai.completion.objectsearch;

/**
 * Object categories offered by the "ABAP Object Search" tab of the standard
 * Search dialog (Ctrl+H). The label is localized; the icon is drawn at runtime
 * by {@link AbapObjectIcons}.
 */
public enum AbapObjectType {

    PROGRAM("type.program", "P", 0x2F6FB5),
    DATABASE_TABLE("type.table", "T", 0xC77A1B),
    VIEW("type.view", "V", 0x1F8A8A),
    CDS_VIEW("type.cdsView", "C", 0x6B4FA8),
    STRUCTURE("type.structure", "S", 0x8A6D3B),
    DATA_ELEMENT("type.dataElement", "D", 0x2E7D32),
    DOMAIN("type.domain", "O", 0x7A7A1F),
    CLASS_INTERFACE("type.classInterface", "K", 0xB23A48),
    METHOD("type.method", "M", 0x1F4E79),
    MESSAGE("type.message", "!", 0x5F6B7A);

    private final String labelKey;
    private final String iconText;
    private final int rgb;

    AbapObjectType(String labelKey, String iconText, int rgb) {
        this.labelKey = labelKey;
        this.iconText = iconText;
        this.rgb = rgb;
    }

    /** Localized label shown in the object type list. */
    String getLabel() {
        return Messages.get(labelKey);
    }

    /** One character drawn on the generated icon. */
    String getIconText() {
        return iconText;
    }

    /** Icon background colour as 0xRRGGBB. */
    int getRgb() {
        return rgb;
    }
}
