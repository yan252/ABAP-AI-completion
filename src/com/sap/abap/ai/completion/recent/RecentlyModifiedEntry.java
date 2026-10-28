package com.sap.abap.ai.completion.recent;

/**
 * "最近修改" store 里的一条记录（纯数据类）。
 */
public final class RecentlyModifiedEntry {

    private final String projectName;
    private final String name;
    private final String type;
    private final long timestamp;
    private final String filePath;
    private final String uri;

    public RecentlyModifiedEntry(String projectName, String name, String type,
            long timestamp, String filePath, String uri) {
        this.projectName = projectName;
        this.name = name;
        this.type = type;
        this.timestamp = timestamp;
        this.filePath = filePath;
        this.uri = uri;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getFilePath() {
        return filePath;
    }

    public String getUri() {
        return uri;
    }

    @Override
    public String toString() {
        return name + " (" + type + ")";
    }
}
