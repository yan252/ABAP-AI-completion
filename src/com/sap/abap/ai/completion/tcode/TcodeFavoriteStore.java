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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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

    /**
     * 首次为某个 ABAP 项目展示"事务码收藏"时预置的默认事务码。
     * 入库后按事务码字母序排列显示。
     */
    public static final String[] DEFAULT_TCODES = {
            "SE09", "SE38", "SE24", "SE37", "SE93", "SE54", "SE55"
    };

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    private static volatile TcodeFavoriteStore INSTANCE;

    /** 按项目名分组的事务码条目。 */
    private final Map<String, List<Entry>> projectEntries = new HashMap<>();

    /** 已写入过默认事务码的项目（防止用户清空后被反复回填）。 */
    private final Set<String> seededProjects = new HashSet<>();

    private TcodeFavoriteStore() {
        load();
        loadSeeded();
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

    /**
     * 为指定项目预置默认事务码（首次展示收藏时调用）。
     *
     * <p>仅当该项目<b>尚未种子化</b>且<b>当前没有任何收藏条目</b>时才写入，
     * 既不会覆盖用户已有的自定义收藏，也不会在用户清空后反复回填。
     * 写入后立即持久化并标记该项目为已种子化。</p>
     *
     * @return true 表示本次新写入了默认事务码（调用方可据此异步回填事务文本）
     */
    public synchronized boolean ensureDefaults(String projectName) {
        if (projectName == null || projectName.isEmpty()) return false;
        if (seededProjects.contains(projectName)) return false;
        if (hasEntries(projectName)) {
            // 已有自定义收藏：标记为已种子化，避免后续再判定为空时回填。
            seededProjects.add(projectName);
            saveSeeded();
            return false;
        }
        for (String tcode : DEFAULT_TCODES) {
            add(projectName, tcode, "");
        }
        seededProjects.add(projectName);
        saveSeeded();
        return true;
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

    private File getSeededFile() {
        try {
            Path metadata = ResourcesPlugin.getWorkspace().getRoot()
                    .getLocation().toPath().resolve(".metadata");
            Path dir = metadata.resolve(".plugins").resolve(Activator.PLUGIN_ID);
            Files.createDirectories(dir);
            return dir.resolve("tcodeFavorites.seeded.txt").toFile();
        } catch (IOException e) {
            return null;
        }
    }

    private synchronized void loadSeeded() {
        File file = getSeededFile();
        if (file == null || !file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    seededProjects.add(line);
                }
            }
        } catch (IOException e) {
            logWarn("loadSeeded failed: " + e.getMessage());
        }
    }

    private synchronized void saveSeeded() {
        File file = getSeededFile();
        if (file == null) return;
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
            for (String projectName : seededProjects) {
                writer.write(projectName);
                writer.write(LINE_SEP);
            }
            writer.flush();
        } catch (IOException e) {
            logWarn("saveSeeded failed: " + e.getMessage());
        }
    }

    private static void logWarn(String msg) {
        LOG.log(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                "[TcodeFavorites] " + msg));
    }
}
