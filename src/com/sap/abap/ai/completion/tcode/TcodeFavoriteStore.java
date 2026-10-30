package com.sap.abap.ai.completion.tcode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;

import com.sap.abap.ai.completion.Activator;

/**
 * "事务码收藏" 本地存储：按项目名分组，持久化到工作区 .metadata 下。
 *
 * <p>格式（一行一条，竖线分隔）：</p>
 * <pre>projectName|tcode|text</pre>
 *
 * <p>条目按事务码字母序保存，保证树中顺序稳定。</p>
 */
public final class TcodeFavoriteStore {

    private static final String LINE_SEP = "\n";
    private static final String FIELD_SEP = "|";

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    private static volatile TcodeFavoriteStore INSTANCE;

    /** 按项目名分组的事务码条目。 */
    private final Map<String, List<Entry>> projectEntries = new HashMap<>();

    private TcodeFavoriteStore() {
        load();
    }

    public static synchronized TcodeFavoriteStore getInstance() {
        if (INSTANCE == null) INSTANCE = new TcodeFavoriteStore();
        return INSTANCE;
    }

    /** 存储条目（tcode + 事务文本）。 */
    public static final class Entry {
        private final String tcode;
        private final String text;

        Entry(String tcode, String text) {
            this.tcode = tcode;
            this.text = text == null ? "" : text;
        }

        public String getTcode() {
            return tcode;
        }

        public String getText() {
            return text;
        }
    }

    // ==================== API ====================

    public synchronized boolean hasEntries(String projectName) {
        return !getEntries(projectName).isEmpty();
    }

    /** 返回该项目下的事务码条目（按事务码字母序）。只读。 */
    public synchronized List<Entry> getEntries(String projectName) {
        List<Entry> list = projectEntries.get(projectName);
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    /** 判断指定项目下是否已存在该事务码。 */
    public synchronized boolean contains(String projectName, String tcode) {
        List<Entry> list = projectEntries.get(projectName);
        if (list == null) return false;
        for (Entry e : list) {
            if (e.tcode.equalsIgnoreCase(tcode)) return true;
        }
        return false;
    }

    /** 添加事务码（同码覆盖旧文本），并保持按事务码排序。 */
    public synchronized void add(String projectName, String tcode, String text) {
        if (projectName == null || tcode == null || tcode.isEmpty()) return;
        String normalized = tcode.trim().toUpperCase(Locale.ROOT);
        List<Entry> list = projectEntries.computeIfAbsent(
                projectName, k -> new ArrayList<>());
        list.removeIf(e -> e.tcode.equalsIgnoreCase(normalized));
        list.add(new Entry(normalized, text));
        list.sort(Comparator.comparing(e -> e.tcode));
        save();
    }

    /** 移除指定项目下的一个事务码。 */
    public synchronized void remove(String projectName, String tcode) {
        List<Entry> list = projectEntries.get(projectName);
        if (list == null) return;
        list.removeIf(e -> e.tcode.equalsIgnoreCase(tcode));
        if (list.isEmpty()) projectEntries.remove(projectName);
        save();
    }

    // ==================== 持久化 ====================

    private File getStorageFile() {
        try {
            Path metadata = ResourcesPlugin.getWorkspace().getRoot()
                    .getLocation().toPath().resolve(".metadata");
            Path dir = metadata.resolve(".plugins").resolve(Activator.PLUGIN_ID);
            Files.createDirectories(dir);
            return dir.resolve("tcodeFavorites.txt").toFile();
        } catch (IOException e) {
            return null;
        }
    }

    private synchronized void load() {
        File file = getStorageFile();
        if (file == null || !file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            Map<String, List<Entry>> loaded = new LinkedHashMap<>();
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\" + FIELD_SEP, -1);
                if (parts.length < 2) continue;
                String projectName = parts[0];
                String tcode = parts[1];
                String text = parts.length >= 3 ? parts[2] : "";
                loaded.computeIfAbsent(projectName, k -> new ArrayList<>())
                        .add(new Entry(tcode, text));
            }
            projectEntries.clear();
            projectEntries.putAll(loaded);
        } catch (IOException e) {
            logWarn("load failed: " + e.getMessage());
        }
    }

    private synchronized void save() {
        File file = getStorageFile();
        if (file == null) return;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (Map.Entry<String, List<Entry>> e : projectEntries.entrySet()) {
                for (Entry entry : e.getValue()) {
                    writer.write(e.getKey());
                    writer.write(FIELD_SEP);
                    writer.write(entry.tcode);
                    writer.write(FIELD_SEP);
                    // 事务文本不含换行（单行字段），| 替换为空格避免破坏格式
                    writer.write(entry.text.replace(FIELD_SEP, " "));
                    writer.write(LINE_SEP);
                }
            }
            writer.flush();
        } catch (IOException e) {
            logWarn("save failed: " + e.getMessage());
        }
    }

    private static void logWarn(String msg) {
        LOG.log(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                "[TcodeFavorites] " + msg));
    }
}
