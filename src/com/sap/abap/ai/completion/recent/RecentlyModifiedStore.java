package com.sap.abap.ai.completion.recent;

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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;
import com.sap.abap.ai.completion.preferences.AIConfiguration;

/**
 * "最近修改" 本地存储：按项目名分组，持久化到工作区 .metadata 下。
 *
 * <p>格式（一行一条，竖线分隔）：</p>
 * <pre>projectName|objectName|type|timestamp|filePath|uri</pre>
 */
public final class RecentlyModifiedStore {

    private static final int MAX_ENTRIES_PER_PROJECT = 30;
    private static final String LINE_SEP = "\n";
    private static final String FIELD_SEP = "|";

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    private static volatile RecentlyModifiedStore INSTANCE;

    /** 按项目名分组的条目列表（队首 = 最近修改）。 */
    private final Map<String, List<RecentlyModifiedEntry>> projectEntries = new HashMap<>();

    private RecentlyModifiedStore() {
        load();
    }

    public static synchronized RecentlyModifiedStore getInstance() {
        if (INSTANCE == null) INSTANCE = new RecentlyModifiedStore();
        return INSTANCE;
    }

    // ==================== API ====================

    public synchronized boolean hasEntries(String projectName) {
        return !getEntries(projectName).isEmpty();
    }

    /** 返回该项目下的条目（队首 = 最近修改）。只读。 */
    public synchronized List<RecentlyModifiedEntry> getEntries(String projectName) {
        List<RecentlyModifiedEntry> list = projectEntries.get(projectName);
        return list == null ? Collections.emptyList() : new ArrayList<>(list);
    }

    /**
     * 记录一次保存：存在则移除旧条目再插到队首（"移动到最前"）。
     * 类型过滤由调用方负责（这里只做存储 + 去重 + 截断）。
     */
    public synchronized void add(IProject project, String name, String type,
            String filePath, String uri) {
        if (project == null) return;
        add(project.getName(), name, type, filePath, uri);
    }

    /** 从指定项目下移除匹配条目（按 name + type）。 */
    public synchronized void remove(String projectName, String name, String type) {
        List<RecentlyModifiedEntry> list = projectEntries.get(projectName);
        if (list == null) return;
        list.removeIf(e -> e.getName().equals(name)
                && (type == null || type.isEmpty() || type.equals(e.getType())));
        if (list.isEmpty()) projectEntries.remove(projectName);
        save();
    }

    /** 按项目清空全部条目。 */
    public synchronized void clearProject(String projectName) {
        projectEntries.remove(projectName);
        save();
    }

    /** 按项目名添加（内部使用）。 */
    private synchronized void add(String projectName, String name, String type,
            String filePath, String uri) {
        List<RecentlyModifiedEntry> list = projectEntries.computeIfAbsent(
                projectName, k -> new ArrayList<>());

        // 去重：同 name+type+filePath 视为同一个对象
        list.removeIf(e -> e.getName().equals(name)
                && (type == null || type.equals(e.getType())));

        RecentlyModifiedEntry entry = new RecentlyModifiedEntry(
                projectName, name, type, System.currentTimeMillis(), filePath, uri);
        list.add(0, entry);

        if (list.size() > MAX_ENTRIES_PER_PROJECT) {
            list = new ArrayList<>(list.subList(0, MAX_ENTRIES_PER_PROJECT));
            projectEntries.put(projectName, list);
        }

        save();
    }

    // ==================== 持久化 ====================

    private File getStorageFile() {
        try {
            Path metadata = ResourcesPlugin.getWorkspace().getRoot()
                    .getLocation().toPath().resolve(".metadata");
            Path dir = metadata.resolve(".plugins").resolve(Activator.PLUGIN_ID);
            Files.createDirectories(dir);
            return dir.resolve("recentlyModified.txt").toFile();
        } catch (IOException e) {
            return null;
        }
    }

    private synchronized void load() {
        File file = getStorageFile();
        if (file == null || !file.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            Map<String, List<RecentlyModifiedEntry>> loaded = new LinkedHashMap<>();
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\" + FIELD_SEP, -1);
                if (parts.length < 5) continue;
                String projectName = parts[0];
                String name = parts[1];
                String type = parts[2];
                long timestamp;
                try { timestamp = Long.parseLong(parts[3]); }
                catch (NumberFormatException ignored) { continue; }
                String filePath = parts[4];
                String uri = parts.length >= 6 ? parts[5] : null;

                List<RecentlyModifiedEntry> list = loaded.computeIfAbsent(
                        projectName, k -> new ArrayList<>());
                list.add(new RecentlyModifiedEntry(
                        projectName, name, type, timestamp, filePath, uri));
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
            for (Map.Entry<String, List<RecentlyModifiedEntry>> e : projectEntries.entrySet()) {
                for (RecentlyModifiedEntry entry : e.getValue()) {
                    writer.write(entry.getProjectName());
                    writer.write(FIELD_SEP);
                    writer.write(entry.getName());
                    writer.write(FIELD_SEP);
                    writer.write(nullSafe(entry.getType()));
                    writer.write(FIELD_SEP);
                    writer.write(String.valueOf(entry.getTimestamp()));
                    writer.write(FIELD_SEP);
                    writer.write(nullSafe(entry.getFilePath()));
                    writer.write(FIELD_SEP);
                    writer.write(nullSafe(entry.getUri()));
                    writer.write(LINE_SEP);
                }
            }
            writer.flush();
        } catch (IOException e) {
            logWarn("save failed: " + e.getMessage());
        }
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static void logWarn(String msg) {
        LOG.log(new Status(IStatus.WARNING, Activator.PLUGIN_ID,
                "[RecentlyModified] " + msg));
    }
}
