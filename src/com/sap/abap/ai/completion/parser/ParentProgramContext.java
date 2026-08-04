package com.sap.abap.ai.completion.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 上级程序上下文: 持有反向解析出的所有上级程序(已截断)。
 *
 * 仿照 {@link AbapIncludeResolver.IncludeContext} 的风格:
 *   - 数据持有者
 *   - 提供 {@link #buildPromptContext()} 生成 prompt 片段
 *
 * 上级程序按 depth 升序排列(depth 1 = 直接调用方,depth 2 = 祖父调用方)。
 */
public class ParentProgramContext {

    private final List<ParentProgramInfo> parents = new ArrayList<>();

    public void addParent(String name, String code, String includedCode, int depth) {
        parents.add(new ParentProgramInfo(name, code, includedCode, depth));
    }

    public List<ParentProgramInfo> getParents() {
        return Collections.unmodifiableList(parents);
    }

    public boolean isEmpty() {
        return parents.isEmpty();
    }

    /**
     * 构建截断后的 prompt 片段。每个上级程序独立成段。
     *
     * 输出格式:
     *   --- Parent: Z_MAIN.abap (depth 1) ---
     *   <上级程序结构签名>
     *   --- Parent: Z_MAIN.abap - resolved INCLUDES (depth 1) ---
     *   <上级程序解析出的 INCLUDE 代码>
     */
    public String buildPromptContext() {
        if (parents.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (ParentProgramInfo p : parents) {
            sb.append("--- Parent: ").append(p.name)
              .append(" (depth ").append(p.depth).append(") ---\n");
            sb.append(p.code);
            if (!p.code.endsWith("\n")) sb.append("\n");

            if (p.includedCode != null && !p.includedCode.trim().isEmpty()) {
                sb.append("--- Parent: ").append(p.name)
                  .append(" - resolved INCLUDES (depth ").append(p.depth).append(") ---\n");
                sb.append(p.includedCode);
                if (!p.includedCode.endsWith("\n")) sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 单个上级程序信息。
     */
    public static class ParentProgramInfo {
        private final String name;
        private final String code;          // 已截断
        private final String includedCode;  // 上级解析出的 INCLUDE 代码(已截断)
        private final int depth;

        public ParentProgramInfo(String name, String code, String includedCode, int depth) {
            this.name = name;
            this.code = code;
            this.includedCode = includedCode;
            this.depth = depth;
        }

        public String getName() { return name; }
        public String getCode() { return code; }
        public String getIncludedCode() { return includedCode; }
        public int getDepth() { return depth; }
    }
}
