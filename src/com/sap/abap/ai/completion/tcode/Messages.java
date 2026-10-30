package com.sap.abap.ai.completion.tcode;

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * "事务码收藏" 功能的消息文案。
 */
public final class Messages {

    private static final String BUNDLE_NAME =
            "com.sap.abap.ai.completion.tcode.messages";

    private static final ResourceBundle RESOURCE_BUNDLE =
            ResourceBundle.getBundle(BUNDLE_NAME);

    private Messages() {
    }

    public static String getString(String key) {
        try {
            return RESOURCE_BUNDLE.getString(key);
        } catch (MissingResourceException e) {
            return '!' + key + '!';
        }
    }

    public static String format(String key, Object... args) {
        return MessageFormat.format(getString(key), args);
    }
}
