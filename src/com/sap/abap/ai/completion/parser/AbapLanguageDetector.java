package com.sap.abap.ai.completion.parser;

import java.util.HashSet;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension3;
import org.eclipse.jface.text.IDocumentPartitioner;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.part.FileEditorInput;

/**
 * 组合判断当前编辑上下文是否为 ABAP 代码。
 *
 * 五策略短路 OR(任一命中即视为 ABAP):
 *   1. 编辑器 ID 包含 "abap"  (SAP ADT 场景)
 *   2. 文件扩展名 .abap / .abapinc / .asinc / .txt
 *      或文件名以 Y/Z/SAP/R 开头 (无扩展名)
 *   3. 文档分区类型名包含 "abap"
 *   4. 内容启发式: 存在 ABAP 标志语句 (REPORT/PROGRAM/FUNCTION-POOL/CLASS-METHODS)
 *      或 INCLUDE + REPORT 组合
 *   5. 内容启发式: ABAP 关键字命中数 >= 2
 *
 * 性能考虑: 开销最大的内容启发式放在最后。
 */
public final class AbapLanguageDetector {

    /** 内容启发式(兜底)需要的最小关键字命中数 */
    private static final int MIN_KEYWORD_HITS = 2;

    /** ABAP 控制流关键字集合(大写,用于启发式判断) */
    private static final Set<String> ABAP_KEYWORDS = new HashSet<>();
    static {
        ABAP_KEYWORDS.add("DATA:");
        ABAP_KEYWORDS.add("DATA ");
        ABAP_KEYWORDS.add("TYPES:");
        ABAP_KEYWORDS.add("TYPES ");
        ABAP_KEYWORDS.add("CONSTANTS:");
        ABAP_KEYWORDS.add("CONSTANTS ");
        ABAP_KEYWORDS.add("TABLES:");
        ABAP_KEYWORDS.add("TABLES ");
        ABAP_KEYWORDS.add("PARAMETERS");
        ABAP_KEYWORDS.add("SELECT-OPTIONS");
        ABAP_KEYWORDS.add("START-OF-SELECTION");
        ABAP_KEYWORDS.add("INITIALIZATION");
        ABAP_KEYWORDS.add("AT SELECTION-SCREEN");
        ABAP_KEYWORDS.add("END-OF-SELECTION");
        ABAP_KEYWORDS.add("LOAD-OF-PROGRAM");
        ABAP_KEYWORDS.add("SELECT ");
        ABAP_KEYWORDS.add("LOOP AT ");
        ABAP_KEYWORDS.add("WRITE:");
        ABAP_KEYWORDS.add("WRITE ");
        ABAP_KEYWORDS.add("INCLUDE ");
        ABAP_KEYWORDS.add("CALL FUNCTION ");
        ABAP_KEYWORDS.add("PERFORM ");
        ABAP_KEYWORDS.add("IF ");
        ABAP_KEYWORDS.add("ELSEIF ");
        ABAP_KEYWORDS.add("ENDIF.");
        ABAP_KEYWORDS.add("CASE ");
        ABAP_KEYWORDS.add("ENDCASE.");
        ABAP_KEYWORDS.add("DO ");
        ABAP_KEYWORDS.add("ENDDO.");
        ABAP_KEYWORDS.add("WHILE ");
        ABAP_KEYWORDS.add("ENDWHILE.");
        ABAP_KEYWORDS.add("FIELD-SYMBOLS:");
        ABAP_KEYWORDS.add("TYPE-POOLS:");
        ABAP_KEYWORDS.add("FORM ");
        ABAP_KEYWORDS.add("ENDFORM.");
        ABAP_KEYWORDS.add("METHOD ");
        ABAP_KEYWORDS.add("ENDMETHOD.");
        ABAP_KEYWORDS.add("CLASS ");
        ABAP_KEYWORDS.add("ENDCLASS.");
    }

    private AbapLanguageDetector() {
    }

    /**
     * 组合判断入口: 任一策略命中即视为 ABAP。
     */
    public static boolean isAbapContext(IEditorPart editor, IFile file, IDocument doc) {
        // 策略 1: 编辑器 ID 包含 "abap"
        if (matchEditorId(editor)) return true;

        // 策略 2: 文件扩展名或文件名特征
        if (matchFileExtension(file)) return true;
        if (matchFileNamePattern(file)) return true;
        if (matchFileExtensionFromEditor(editor)) return true;

        // 策略 3: 文档分区类型
        if (matchPartitionType(doc)) return true;

        // 策略 4+5: 内容启发式
        if (doc != null) {
            try {
                String content = doc.get();
                if (matchFlagStatements(content)) return true;
                if (matchContentHeuristic(content)) return true;
            } catch (Exception e) {
                // 读文档失败,忽略
            }
        }
        return false;
    }

    /**
     * 仅基于内容判断(供无 editor 场景使用)。
     */
    public static boolean isAbapContent(String content) {
        if (content == null || content.isEmpty()) return false;
        if (matchFlagStatements(content)) return true;
        return matchContentHeuristic(content);
    }

    /**
     * 基于文件名判断(供外部调用)。
     */
    public static boolean isAbapFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) return false;
        String upper = fileName.toUpperCase();
        // 已知 ABAP 扩展名
        if (upper.endsWith(".ABAP") || upper.endsWith(".ABAPINC")
                || upper.endsWith(".ASINC") || upper.endsWith(".TXT")) {
            return true;
        }
        // 文件名以 Y/Z/SAP/R 开头(可能无扩展名)
        int dot = upper.lastIndexOf('.');
        String base = dot > 0 ? upper.substring(0, dot) : upper;
        return base.startsWith("Y") || base.startsWith("Z")
                || base.startsWith("SAP") || base.startsWith("R");
    }

    // ==================== 策略 1: 编辑器 ID ====================

    /**
     * SAP ADT 的 ABAP 源码编辑器 ID 通常包含 "abap" 关键字。
     * 例如: com.sap.adt.tools.abapsource.ui.sources.editors.AbapSource
     */
    static boolean matchEditorId(IEditorPart editor) {
        if (editor == null) return false;
        try {
            String id = editor.getSite().getId();
            if (id == null) return false;
            return id.toLowerCase().contains("abap");
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 策略 2: 文件扩展名/文件名 ====================

    /**
     * 文件扩展名为 .abap / .abapinc / .asinc / .txt 视为 ABAP。
     */
    static boolean matchFileExtension(IFile file) {
        if (file == null) return false;
        String name = file.getName().toLowerCase();
        return name.endsWith(".abap") || name.endsWith(".abapinc")
                || name.endsWith(".asinc") || name.endsWith(".txt");
    }

    /**
     * 文件名无扩展名但以 Y/Z/SAP/R 开头(ABAP 命名约定)。
     */
    static boolean matchFileNamePattern(IFile file) {
        if (file == null) return false;
        String name = file.getName().toUpperCase();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return (base.startsWith("Y") || base.startsWith("Z")
                || base.startsWith("SAP") || base.startsWith("R"));
    }

    /**
     * 从编辑器输入推断文件扩展名(供无 IFile 场景兜底)。
     */
    static boolean matchFileExtensionFromEditor(IEditorPart editor) {
        if (editor == null) return false;
        try {
            IEditorInput input = editor.getEditorInput();
            if (input == null) return false;
            // 尝试通过 FileEditorInput 获取
            if (input instanceof FileEditorInput) {
                IFile file = ((FileEditorInput) input).getFile();
                if (file != null) {
                    if (matchFileExtension(file)) return true;
                    if (matchFileNamePattern(file)) return true;
                }
            }
            // 从 input.getName() 推断
            String name = input.getName();
            if (name != null) {
                String upper = name.toUpperCase();
                if (upper.endsWith(".ABAP") || upper.endsWith(".ABAPINC")
                        || upper.endsWith(".ASINC") || upper.endsWith(".TXT")) {
                    return true;
                }
                int dot = upper.lastIndexOf('.');
                String base = dot > 0 ? upper.substring(0, dot) : upper;
                if (base.startsWith("Y") || base.startsWith("Z")
                        || base.startsWith("SAP") || base.startsWith("R")) {
                    return true;
                }
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /**
     * 从 IEditorInput 推断文件扩展名(供外部调用)。
     */
    public static boolean matchFileExtension(IEditorInput input) {
        if (input == null) return false;
        if (input instanceof FileEditorInput) {
            IFile file = ((FileEditorInput) input).getFile();
            if (file != null) {
                if (matchFileExtension(file)) return true;
                if (matchFileNamePattern(file)) return true;
            }
        }
        String name = input.getName();
        if (name == null) return false;
        String upper = name.toUpperCase();
        if (upper.endsWith(".ABAP") || upper.endsWith(".ABAPINC")
                || upper.endsWith(".ASINC") || upper.endsWith(".TXT")) {
            return true;
        }
        int dot = upper.lastIndexOf('.');
        String base = dot > 0 ? upper.substring(0, dot) : upper;
        return base.startsWith("Y") || base.startsWith("Z")
                || base.startsWith("SAP") || base.startsWith("R");
    }

    // ==================== 策略 3: 文档分区类型 ====================

    /**
     * 文档分区类型名包含 "abap" 视为 ABAP。
     * 不依赖 ADT 插件常量,避免新增依赖。
     */
    static boolean matchPartitionType(IDocument doc) {
        if (doc == null) return false;
        IDocumentPartitioner partitioner = doc.getDocumentPartitioner();
        if (partitioner == null && doc instanceof IDocumentExtension3) {
            try {
                partitioner = ((IDocumentExtension3) doc)
                        .getDocumentPartitioner(IDocumentExtension3.DEFAULT_PARTITIONING);
            } catch (Exception e) {
                return false;
            }
        }
        if (partitioner == null) return false;
        String[] types = partitioner.getLegalContentTypes();
        if (types == null) return false;
        for (String t : types) {
            if (t != null && t.toLowerCase().contains("abap")) {
                return true;
            }
        }
        return false;
    }

    // ==================== 策略 4: 标志语句检测 ====================

    /**
     * ABAP 标志语句 —— 只要存在任一即判定为 ABAP。
     * 这是最可靠的判断方式,因为 REPORT/PROGRAM/FUNCTION-POOL/CLASS-METHODS
     * 是 ABAP 程序的标志性开头。
     */
    static boolean matchFlagStatements(String content) {
        if (content == null || content.length() < 10) return false;
        String upper = content.toUpperCase();

        // 单独的标志语句
        if (upper.contains("REPORT ")
                || upper.contains("PROGRAM ")
                || upper.contains("FUNCTION-POOL ")
                || upper.contains("CLASS-METHODS ")) {
            return true;
        }

        // INCLUDE + REPORT 组合 (INCLUDE 程序)
        if (upper.contains("INCLUDE ") && upper.contains("REPORT")) {
            return true;
        }

        return false;
    }

    // ==================== 策略 5: 内容关键字启发式 ====================

    /**
     * 统计 ABAP 关键字命中数,达到 {@link #MIN_KEYWORD_HITS} 即视为 ABAP。
     * 放在最后(需读全文,开销最大)。
     */
    static boolean matchContentHeuristic(String content) {
        if (content == null || content.length() < 10) return false;
        String upper = content.toUpperCase();
        int hits = 0;
        for (String kw : ABAP_KEYWORDS) {
            if (upper.contains(kw)) {
                hits++;
                if (hits >= MIN_KEYWORD_HITS) return true;
            }
        }
        return hits >= MIN_KEYWORD_HITS;
    }
}
