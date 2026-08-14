package com.sap.abap.ai.completion.parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.part.FileEditorInput;
import org.eclipse.ui.texteditor.IDocumentProvider;
import org.eclipse.ui.texteditor.ITextEditor;

import com.sap.abap.ai.completion.logging.AILogger;

/**
 * Parses ABAP source code to resolve INCLUDE statements and reads
 * the referenced include programs.
 */
public class AbapIncludeResolver {

    // Pattern to match ABAP INCLUDE statements:
    //   INCLUDE <name>.        or
    //   INCLUDE <name> IF FOUND.
    private static final Pattern INCLUDE_PATTERN =
            Pattern.compile(
                    "^\\s*INCLUDE\\s+(\\w+)(?:\\s+IF\\s+FOUND)?\\s*\\.\\s*$",
                    Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private final IProject project;

    public AbapIncludeResolver(IProject project) {
        this.project = project;
    }

    /**
     * Reads the full content of the current file.
     */
    public static String readFileContent(IFile file) throws CoreException, IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getContents(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Finds all INCLUDE names in the given source code.
     */
    public List<String> findIncludes(String sourceCode) {
        List<String> includes = new ArrayList<>();
        Matcher matcher = INCLUDE_PATTERN.matcher(sourceCode);
        while (matcher.find()) {
            includes.add(matcher.group(1).toUpperCase());
        }
        return includes;
    }

    /**
     * Searches the workspace (current project) for the include file content.
     * ABAP includes are typically stored with extension .abap or can be found
     * as files matching the include name in the project structure.
     *
     * SAP ADT 注意: SAP ADT 在工作区中存储的 INCLUDE 文件可能是 XML 元数据
     * (而非 ABAP 源代码)。本方法检测到 XML 元数据后,会尝试从已打开的编辑器中
     * 获取真实的 ABAP 源代码。
     */
    public String resolveIncludeCode(String includeName) {
        AILogger.logError("AbapIncludeResolver", "[DEBUG] resolveIncludeCode: searching for INCLUDE '"
                + includeName + "' in project=" + (project != null ? project.getName() : "null"));

        List<IFile> candidates = findIncludeFiles(includeName);

        AILogger.logError("AbapIncludeResolver", "[DEBUG] resolveIncludeCode: found "
                + candidates.size() + " candidate file(s) for '" + includeName + "'");

        for (IFile file : candidates) {
            try {
                String content = readFileContent(file);
                if (content == null || content.isEmpty()) {
                    continue;
                }

                // 检测 SAP ADT XML 元数据 (不是 ABAP 源代码)
                if (isAdtMetadataXml(content)) {
                    AILogger.logError("AbapIncludeResolver", "[DEBUG] resolveIncludeCode: file '"
                            + file.getName() + "' is SAP ADT XML metadata, not ABAP source. "
                            + "Trying to read from open editor...");

                    // 尝试从已打开的编辑器中获取真实的 ABAP 源代码
                    String editorContent = readSourceFromOpenEditor(includeName);
                    if (editorContent != null && !editorContent.isEmpty()) {
                        AILogger.logError("AbapIncludeResolver", "[DEBUG] resolveIncludeCode: got ABAP source from open editor for '"
                                + includeName + "' (length=" + editorContent.length() + ")");
                        return editorContent;
                    }

                    AILogger.logError("AbapIncludeResolver", "[DEBUG] resolveIncludeCode: INCLUDE '"
                            + includeName + "' not open in any editor, cannot get ABAP source. "
                            + "Open the file in Eclipse to enable source resolution.");
                    continue;  // 跳过 XML 元数据,尝试下一个候选文件
                }

                AILogger.logError("AbapIncludeResolver", "[DEBUG] resolveIncludeCode: read ABAP source from '"
                        + file.getName() + "' (length=" + content.length() + ")");
                return content;
            } catch (Exception e) {
                AILogger.logError("AbapIncludeResolver", "[DEBUG] resolveIncludeCode: failed to read '"
                        + file.getName() + "': " + e.getMessage());
            }
        }

        // 工作区中没找到文件,也尝试从已打开的编辑器获取
        AILogger.logError("AbapIncludeResolver", "[DEBUG] resolveIncludeCode: no workspace file found for '"
                + includeName + "', trying open editors...");
        String editorContent = readSourceFromOpenEditor(includeName);
        if (editorContent != null && !editorContent.isEmpty()) {
            AILogger.logError("AbapIncludeResolver", "[DEBUG] resolveIncludeCode: got ABAP source from open editor for '"
                    + includeName + "' (length=" + editorContent.length() + ")");
            return editorContent;
        }

        AILogger.logError("AbapIncludeResolver", "[DEBUG] resolveIncludeCode: NO source found for INCLUDE '"
                + includeName + "' (not in workspace and not open in editor)");
        return null;
    }

    /**
     * 检测内容是否为 SAP ADT XML 元数据 (而非 ABAP 源代码)。
     *
     * SAP ADT 在工作区中存储的文件可能包含 XML 元数据,如:
     *   <?xml version="1.0" encoding="utf-8"?>
     *   <include:abapInclude ...> 或 <adtcore:...> 等
     */
    private static boolean isAdtMetadataXml(String content) {
        if (content == null || content.isEmpty()) return false;
        String trimmed = content.trim();
        // XML 声明开头
        if (trimmed.startsWith("<?xml")) return true;
        // SAP ADT XML 根元素
        if (trimmed.contains("<include:abapInclude")) return true;
        if (trimmed.contains("<adtcore:")) return true;
        if (trimmed.contains("<abapsource:")) return true;
        if (trimmed.contains("<program:abapProgram")) return true;
        return false;
    }

    /**
     * 尝试从已打开的 Eclipse 编辑器中获取指定 INCLUDE 名称的 ABAP 源代码。
     *
     * SAP ADT 编辑器在内存中持有真实的 ABAP 源代码 (IDocument),
     * 即使工作区文件存储的是 XML 元数据。
     *
     * 匹配策略: 编辑器标题包含 include 名称 (大小写不敏感)
     */
    private static String readSourceFromOpenEditor(String includeName) {
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) {
                IWorkbenchWindow[] windows = PlatformUI.getWorkbench().getWorkbenchWindows();
                if (windows.length > 0) window = windows[0];
            }
            if (window == null) return null;

            IWorkbenchPage page = window.getActivePage();
            if (page == null) return null;

            IEditorReference[] refs = page.getEditorReferences();
            String upperInclude = includeName.toUpperCase();

            for (IEditorReference ref : refs) {
                String title = ref.getTitle();
                if (title == null) continue;
                String upperTitle = title.toUpperCase();

                // 编辑器标题包含 include 名称 (如 "ztre08152_top.asinc" 包含 "ZTRE08152_TOP")
                if (!upperTitle.contains(upperInclude)) continue;

                // 尝试获取编辑器内容
                IEditorPart editor = ref.getEditor(false);
                if (editor == null) {
                    // 非活动编辑器,尝试从 IEditorInput 获取
                    editor = ref.getEditor(true);
                }
                if (editor == null) continue;

                String content = getEditorDocumentContent(editor);
                if (content != null && !content.isEmpty() && !isAdtMetadataXml(content)) {
                    return content;
                }
            }
        } catch (Exception e) {
            AILogger.logError("AbapIncludeResolver", "[DEBUG] readSourceFromOpenEditor error: "
                    + e.getMessage());
        }
        return null;
    }

    /**
     * 从编辑器获取 IDocument 内容。
     * 支持标准 ITextEditor 和 SAP ADT 编辑器 (通过适配器)。
     */
    private static String getEditorDocumentContent(IEditorPart editor) {
        try {
            // 策略1: 标准 ITextEditor
            if (editor instanceof ITextEditor) {
                ITextEditor te = (ITextEditor) editor;
                IDocumentProvider dp = te.getDocumentProvider();
                if (dp != null) {
                    IDocument doc = dp.getDocument(te.getEditorInput());
                    if (doc != null) return doc.get();
                }
            }

            // 策略2: 通过适配器获取 ITextEditor
            ITextEditor adapted = editor.getAdapter(ITextEditor.class);
            if (adapted != null) {
                IDocumentProvider dp = adapted.getDocumentProvider();
                if (dp != null) {
                    IDocument doc = dp.getDocument(adapted.getEditorInput());
                    if (doc != null) return doc.get();
                }
            }

            // 策略3: 反射调用 getDocument() (SAP ADT 自定义编辑器)
            try {
                java.lang.reflect.Method getDoc = editor.getClass().getMethod("getDocument");
                Object result = getDoc.invoke(editor);
                if (result instanceof IDocument) {
                    return ((IDocument) result).get();
                }
            } catch (NoSuchMethodException ignored) {
            }

            // 策略4: 反射调用 getAdapter(IDocument.class)
            try {
                Object docObj = editor.getAdapter(IDocument.class);
                if (docObj instanceof IDocument) {
                    return ((IDocument) docObj).get();
                }
            } catch (Exception ignored) {
            }
        } catch (Exception e) {
            AILogger.logError("AbapIncludeResolver", "[DEBUG] getEditorDocumentContent error: "
                    + e.getMessage());
        }
        return null;
    }

    /**
     * Resolves ALL includes in the source and returns a map of include name -> code.
     */
    public IncludeContext resolveAllIncludes(String sourceCode) {
        IncludeContext context = new IncludeContext(sourceCode);
        List<String> includes = findIncludes(sourceCode);

        AILogger.logError("AbapIncludeResolver", "[DEBUG] resolveAllIncludes: extracted "
                + includes.size() + " INCLUDE statement(s): " + includes);

        int resolved = 0;
        for (String includeName : includes) {
            String code = resolveIncludeCode(includeName);
            if (code != null) {
                context.addInclude(includeName, code);
                resolved++;
            }
        }
        AILogger.logError("AbapIncludeResolver", "[DEBUG] resolveAllIncludes: resolved "
                + resolved + "/" + includes.size() + " INCLUDE(s)");
        return context;
    }

    private List<IFile> findIncludeFiles(String includeName) {
        List<IFile> results = new ArrayList<>();

        if (project != null) {
            // 只在当前项目中搜索 (SAP ADT 场景: 当前系统的项目)
            searchInContainer(project, includeName, results);
            return results;
        }

        // project 为 null 时,回退到搜索整个工作区
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (IProject p : projects) {
            if (p.isAccessible()) {
                searchInContainer(p, includeName, results);
            }
        }

        return results;
    }

    private void searchInContainer(IProject container, String includeName, List<IFile> results) {
        final int[] scannedCount = {0};
        final int[] matchedCount = {0};
        try {
            container.accept(resource -> {
                if (resource instanceof IFile) {
                    IFile file = (IFile) resource;
                    String fileName = file.getName().toUpperCase();
                    scannedCount[0]++;
                    // Match files containing the include name (with common ABAP extensions or no extension)
                    if (fileName.contains(includeName) && isAbapSourceFile(fileName)) {
                        results.add(file);
                        matchedCount[0]++;
                    }
                }
                return true;
            });
        } catch (CoreException e) {
            // ignore inaccessible containers
        }
        AILogger.logError("AbapIncludeResolver", "[DEBUG] searchInContainer: scanned=" + scannedCount[0]
                + " files, matched=" + matchedCount[0]
                + " for INCLUDE '" + includeName + "' in project '" + container.getName() + "'");
    }

    /**
     * 判断文件名是否为 ABAP 源文件。
     * 支持: .ABAP, .ABAPINC, .TXT, 以及无扩展名(SAP ADT 环境)。
     */
    private static boolean isAbapSourceFile(String upperName) {
        if (upperName == null || upperName.isEmpty()) return false;
        if (upperName.endsWith(".ABAP")) return true;
        if (upperName.endsWith(".ABAPINC")) return true;
        if (upperName.endsWith(".TXT")) return true;
        // SAP ADT 环境中, ABAP 文件可能没有扩展名
        int dot = upperName.lastIndexOf('.');
        if (dot < 0) return true;  // 无扩展名,视为 ABAP
        // 排除明显不是 ABAP 的扩展名
        String ext = upperName.substring(dot);
        if (ext.equals(".JAVA") || ext.equals(".XML") || ext.equals(".JSON")
                || ext.equals(".PROPERTIES") || ext.equals(".HTML")
                || ext.equals(".CSS") || ext.equals(".JS")) {
            return false;
        }
        // ABAP 程序命名模式: Y/Z 开头
        String base = upperName.substring(0, dot);
        return base.startsWith("Y") || base.startsWith("Z")
                || base.startsWith("SAP") || base.startsWith("R");
    }

    /**
     * Context holding the main source code along with resolved include sources.
     */
    public static class IncludeContext {
        private final String mainSource;
        private final List<IncludeInfo> includes;

        public IncludeContext(String mainSource) {
            this.mainSource = mainSource;
            this.includes = new ArrayList<>();
        }

        public void addInclude(String name, String code) {
            includes.add(new IncludeInfo(name, code));
        }

        public String getMainSource() {
            return mainSource;
        }

        public List<IncludeInfo> getIncludes() {
            return includes;
        }

        /**
         * Builds a combined prompt context with the main code and all included references.
         */
        public String buildPromptContext() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== Current ABAP Program ===\n");
            sb.append(mainSource);
            sb.append("\n");

            if (!includes.isEmpty()) {
                sb.append("\n=== Referenced INCLUDES (for context) ===\n");
                for (IncludeInfo inc : includes) {
                    sb.append("--- INCLUDE ").append(inc.name).append(" ---\n");
                    sb.append(inc.code);
                    sb.append("\n");
                }
            }

            return sb.toString();
        }

        /**
         * 仅构建已解析的 INCLUDE 代码上下文(不含主源码)。
         * 用于上级程序场景: 上级程序自身代码已单独传入,
         * 这里只需附加其 INCLUDE 的代码。
         */
        public String buildIncludesOnlyContext() {
            if (includes.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            sb.append("=== Resolved INCLUDES of parent program ===\n");
            for (IncludeInfo inc : includes) {
                sb.append("--- INCLUDE ").append(inc.name).append(" ---\n");
                sb.append(inc.code);
                sb.append("\n");
            }
            return sb.toString();
        }

        /**
         * 返回已解析的 INCLUDE 数量。
         */
        public int getResolvedCount() {
            return includes.size();
        }

        public static class IncludeInfo {
            private final String name;
            private final String code;

            public IncludeInfo(String name, String code) {
                this.name = name;
                this.code = code;
            }

            public String getName() {
                return name;
            }

            public String getCode() {
                return code;
            }
        }
    }
}
