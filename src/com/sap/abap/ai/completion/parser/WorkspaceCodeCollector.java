package com.sap.abap.ai.completion.parser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.widgets.Control;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.texteditor.ITextEditor;

import com.sap.abap.ai.completion.logging.AILogger;

/**
 * 工作区代码收集器。
 *
 * 当用户启用 "Use workspace ABAP code as AI reference" 时,本类收集当前在
 * Eclipse 编辑器中打开的所有 ABAP 文件,从编辑器 IDocument 直接获取内容
 * (兼容本地文件编辑器和 SAP ADT 远程编辑器),截断后作为 AI 补全的参考上下文。
 *
 * 多种文档获取策略(按优先级尝试):
 *   1. ITextEditor.getDocumentProvider().getDocument(input) — 标准方式
 *   2. 反射调用 getDocument() — 兼容 SAP ADT 自定义编辑器
 *   3. 反射调用 getText() / getContent() — 兜底
 *
 * 注意: IWorkbenchPage 必须在 UI 线程获取,然后传递到后台线程使用。
 */
public class WorkspaceCodeCollector {

    private final String currentFileName;
    private final String currentFileExtension;
    private final int maxFileLimit;
    private final int maxContextChars;
    private final IWorkbenchPage workbenchPage;

    /**
     * 构造器。
     *
     * @param currentFileName     当前正在编辑的文件名(用于排除自身)
     * @param currentFileExtension 当前文件的扩展名(用于过滤)
     * @param maxFileLimit     最多收集的文件数
     * @param maxContextChars  每个工作区文件的最大字符数(超出则用 AbapCodeTruncator 截断)
     * @param workbenchPage    当前 workbench 页面(在 UI 线程捕获,传入后台线程)
     */
    public WorkspaceCodeCollector(String currentFileName, String currentFileExtension,
                                   int maxFileLimit, int maxContextChars,
                                   IWorkbenchPage workbenchPage) {
        this.currentFileName = currentFileName;
        this.currentFileExtension = currentFileExtension != null ? currentFileExtension.toLowerCase() : "";
        this.maxFileLimit = Math.max(1, maxFileLimit);
        this.maxContextChars = maxContextChars;
        this.workbenchPage = workbenchPage;
    }

    /**
     * 收集当前 Eclipse 中打开的所有 ABAP 文件。
     * 始终返回包含分隔符的字符串,即使没有找到文件。
     */
    public String collectWorkspaceCode() {
        List<EditorContentPair> pairs = new ArrayList<>();
        int totalScanned = 0;
        StringBuilder debugSb = new StringBuilder();

        try {
            if (workbenchPage == null) {
                debugSb.append("[DEBUG] workbenchPage is null (thread issue?)");
                AILogger.logDebug("WorkspaceCodeCollector", debugSb.toString());
                return buildResult(pairs, 0);
            }

            IEditorReference[] refs = workbenchPage.getEditorReferences();
            debugSb.append("[DEBUG] total editor refs=").append(refs.length).append("\n");

            for (IEditorReference ref : refs) {
                IEditorPart editorPart = ref.getEditor(false);
                String editorTitle;
                String editorId;
                String editorClassName;
                IEditorInput input;
                String inputTypeName;

                if (editorPart == null) {
                    // 编辑器未加载(非活动标签页),尝试从 IEditorInput 直接读取文件
                    input = ref.getEditorInput();
                    if (input == null) {
                        debugSb.append("  - [SKIP] ref has no editor and no input\n");
                        continue;
                    }
                    inputTypeName = input.getClass().getName();
                    editorTitle = ref.getTitle();
                    editorId = ref.getId();
                    editorClassName = "(not loaded)";

                    // 尝试从 IEditorInput 获取 IFile 并直接读取
                    String content = readContentFromInput(input, debugSb);
                    if (content == null || content.isEmpty()) {
                        debugSb.append("  - [SKIP] ").append(editorTitle)
                                .append(": no content from input (class=").append(inputTypeName).append(")\n");
                        continue;
                    }
                    totalScanned++;
                    debugSb.append("  - [SCAN] ").append(editorTitle)
                            .append(" (not loaded, read from input, contentLen=").append(content.length()).append(")");

                    // 判断是否为当前文件
                    if (isCurrentEditorByTitle(editorTitle)) {
                        debugSb.append(" -> SKIP (current editor)\n");
                        continue;
                    }
                    if (isAbapCode(editorTitle, editorId, content)) {
                        pairs.add(new EditorContentPair(editorTitle, content));
                        debugSb.append(" -> COLLECT as ABAP\n");
                    } else {
                        debugSb.append(" -> SKIP (not ABAP)\n");
                    }
                    continue;
                }

                editorTitle = editorPart.getTitle();
                editorId = editorPart.getSite() != null ? editorPart.getSite().getId() : "unknown";
                editorClassName = editorPart.getClass().getName();
                input = editorPart.getEditorInput();
                inputTypeName = input != null ? input.getClass().getName() : "null";

                // 获取文档内容(多种策略)
                String content = getEditorContent(editorPart, input, debugSb);

                if (content == null || content.isEmpty()) {
                    debugSb.append("  - [SKIP] ").append(editorTitle)
                            .append(": no content (class=").append(editorClassName)
                            .append(", input=").append(inputTypeName)
                            .append(", editorId=").append(editorId).append(")\n");
                    continue;
                }

                totalScanned++;
                debugSb.append("  - [SCAN] ").append(editorTitle)
                        .append(" (class=").append(editorClassName)
                        .append(", editorId=").append(editorId)
                        .append(", contentLen=").append(content.length()).append(")");

                // 排除当前正在编辑的文件
                if (isCurrentEditor(editorPart)) {
                    debugSb.append(" -> SKIP (current editor)\n");
                    continue;
                }

                // 判断是否为 ABAP 代码
                if (isAbapCode(editorTitle, editorId, content)) {
                    pairs.add(new EditorContentPair(editorTitle, content));
                    debugSb.append(" -> COLLECT as ABAP\n");
                } else {
                    debugSb.append(" -> SKIP (not ABAP)\n");
                }
            }
        } catch (Exception e) {
            debugSb.append("[DEBUG] CRITICAL ERROR: ").append(e.getMessage()).append("\n");
        }

        AILogger.logDebug("WorkspaceCodeCollector", debugSb.toString());
        AILogger.logDebug("WorkspaceCodeCollector", "[DEBUG] SUMMARY: scanned=" + totalScanned
                + " editors, collected=" + pairs.size()
                + " abap files for reference (current=" + currentFileName + ")");

        return buildResult(pairs, totalScanned);
    }

    /**
     * 从编辑器获取内容,按优先级尝试多种策略。
     * SAP ADT 的 ProgramEditor/IncludeEditor 不实现 ITextEditor,
     * 因此需要通过适配器、反射、StyledText 控件等多种方式获取内容。
     */
    private String getEditorContent(IEditorPart editorPart, IEditorInput input, StringBuilder debugSb) {
        String className = editorPart.getClass().getSimpleName();

        // 策略1: ITextEditor.getDocumentProvider().getDocument(input)
        if (editorPart instanceof ITextEditor) {
            try {
                ITextEditor te = (ITextEditor) editorPart;
                if (te.getDocumentProvider() != null) {
                    IDocument doc = te.getDocumentProvider().getDocument(input);
                    if (doc != null) {
                        String text = doc.get();
                        if (text != null && !text.isEmpty()) {
                            debugSb.append("    [via ITextEditor.getDocumentProvider()] len=").append(text.length()).append("\n");
                            return text;
                        }
                    }
                }
            } catch (Exception e) {
                debugSb.append("    [ITextEditor.getDocumentProvider FAILED: ").append(e.getMessage()).append("]\n");
            }
        }

        // 策略2: 通过 getAdapter(ITextEditor.class) 获取适配器
        try {
            Object adapted = editorPart.getAdapter(ITextEditor.class);
            if (adapted instanceof ITextEditor) {
                ITextEditor te = (ITextEditor) adapted;
                if (te.getDocumentProvider() != null) {
                    IDocument doc = te.getDocumentProvider().getDocument(input);
                    if (doc != null) {
                        String text = doc.get();
                        if (text != null && !text.isEmpty()) {
                            debugSb.append("    [via getAdapter(ITextEditor)] len=").append(text.length()).append("\n");
                            return text;
                        }
                    }
                }
            }
        } catch (Exception e) {
            debugSb.append("    [getAdapter(ITextEditor) FAILED: ").append(e.getMessage()).append("]\n");
        }

        // 策略3: 通过 getAdapter(IDocument.class) 直接获取文档
        try {
            Object adapted = editorPart.getAdapter(IDocument.class);
            if (adapted instanceof IDocument) {
                String text = ((IDocument) adapted).get();
                if (text != null && !text.isEmpty()) {
                    debugSb.append("    [via getAdapter(IDocument)] len=").append(text.length()).append("\n");
                    return text;
                }
            }
        } catch (Exception e) {
            debugSb.append("    [getAdapter(IDocument) FAILED: ").append(e.getMessage()).append("]\n");
        }

        // 策略4: 反射调用 getDocument() 或 getText()
        for (String methodName : new String[]{"getDocument", "getText", "getContent", "getEditorText"}) {
            try {
                Method m = editorPart.getClass().getMethod(methodName);
                Object result = m.invoke(editorPart);
                if (result instanceof IDocument) {
                    String text = ((IDocument) result).get();
                    if (text != null && !text.isEmpty()) {
                        debugSb.append("    [via reflective ").append(methodName).append("()] len=").append(text.length()).append("\n");
                        return text;
                    }
                } else if (result instanceof String) {
                    String text = (String) result;
                    if (!text.isEmpty()) {
                        debugSb.append("    [via reflective ").append(methodName).append("()] len=").append(text.length()).append("\n");
                        return text;
                    }
                }
            } catch (NoSuchMethodException e) {
                // 跳过,尝试下一个
            } catch (Exception e) {
                debugSb.append("    [reflective ").append(methodName).append(" FAILED: ").append(e.getMessage()).append("]\n");
            }
        }

        // 策略5: SAP ADT 专用: 通过 programSp/sourcePage 字段获取源代码
        String adtText = getTextFromAdtSourcePage(editorPart, debugSb);
        if (adtText != null) {
            return adtText;
        }

        // 策略6: 反射查找 IDocument 类型的字段
        String fieldResult = findDocumentInFields(editorPart, debugSb);
        if (fieldResult != null) {
            return fieldResult;
        }

        // 策略7: 从 StyledText 控件获取内容
        String styledTextResult = getTextFromStyledText(editorPart, debugSb);
        if (styledTextResult != null) {
            return styledTextResult;
        }

        // 策略8: 转储可用方法和字段用于调试
        dumpMethodsAndFields(editorPart, debugSb);

        return null;
    }

    /**
     * 反射查找 IDocument 类型的字段(包括父类字段)。
     */
    private String findDocumentInFields(Object obj, StringBuilder debugSb) {
        Class<?> cls = obj.getClass();
        int depth = 0;
        while (cls != null && depth < 5) {
            Field[] fields = cls.getDeclaredFields();
            for (Field f : fields) {
                if (IDocument.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        if (val instanceof IDocument) {
                            String text = ((IDocument) val).get();
                            if (text != null && !text.isEmpty()) {
                                debugSb.append("    [via field ").append(cls.getSimpleName())
                                        .append(".").append(f.getName())
                                        .append("] len=").append(text.length()).append("\n");
                                return text;
                            }
                        }
                    } catch (Exception e) {
                        // 不可访问,跳过
                    }
                }
            }
            cls = cls.getSuperclass();
            depth++;
        }
        return null;
    }

    /**
     * SAP ADT 专用: 通过 programSp / sourcePage / file / model 获取源代码。
     */
    private String getTextFromAdtSourcePage(Object editorPart, StringBuilder debugSb) {
        try {
            // === 1. 从 file: IFile 直接读取 ===
            String text = readTextFromIFile(editorPart, debugSb);
            if (text != null) return text;

            // === 2. 从 programSp/sourcePage 页面对象提取 ===
            String[] fieldNames = {"programSp", "sourcePage", "source", "page"};
            for (String fieldName : fieldNames) {
                Object page = findFieldValue(editorPart, fieldName);
                if (page == null) continue;

                String pageClassName = page.getClass().getSimpleName();
                debugSb.append("    [ADT] Found ").append(fieldName)
                        .append(" : ").append(pageClassName).append("\n");

                // 页面的调试转储
                dumpObjectSummary(page, debugSb);

                // 从页面尝试多种提取方式
                text = getTextFromPageObject(page, debugSb);
                if (text != null) return text;
            }

            // === 3. 从 getModel() 提取 ===
            try {
                Method m = editorPart.getClass().getMethod("getModel");
                Object model = m.invoke(editorPart);
                if (model != null) {
                    String modelClassName = model.getClass().getSimpleName();
                    debugSb.append("    [ADT] getModel() -> ").append(modelClassName).append("\n");
                    dumpObjectSummary(model, debugSb);

                    // 从 model 查找文本
                    text = getTextFromPageObject(model, debugSb);
                    if (text != null) return text;

                    // 尝试 model 的特定方法
                    for (String methodName : new String[]{"getSource", "getCode", "getText", "getContent", "getProgramText"}) {
                        try {
                            Method mm = model.getClass().getMethod(methodName);
                            Object result = mm.invoke(model);
                            if (result instanceof String) {
                                String str = (String) result;
                                if (!str.isEmpty()) {
                                    debugSb.append("    [ADT] via model.").append(methodName).append("()] len=").append(str.length()).append("\n");
                                    return str;
                                }
                            }
                        } catch (NoSuchMethodException e) {
                            // 跳过
                        } catch (Exception e) {
                            debugSb.append("    [ADT] model.").append(methodName).append(" FAILED: ").append(e.getMessage()).append("\n");
                        }
                    }
                }
            } catch (NoSuchMethodException e) {
                // 跳过
            } catch (Exception e) {
                debugSb.append("    [ADT] getModel() FAILED: ").append(e.getMessage()).append("\n");
            }

            // === 4. 从 getSourceFile() 获取 IFile 再读取 ===
            try {
                Method m = editorPart.getClass().getMethod("getSourceFile");
                Object fileObj = m.invoke(editorPart);
                if (fileObj instanceof IFile) {
                    text = readIFileContent((IFile) fileObj, debugSb);
                    if (text != null) return text;
                }
            } catch (NoSuchMethodException e) {
                // 跳过
            } catch (Exception e) {
                debugSb.append("    [ADT] getSourceFile() FAILED: ").append(e.getMessage()).append("\n");
            }

            // === 5. 从 getFile() 获取 IFile 再读取 ===
            try {
                Method m = editorPart.getClass().getMethod("getFile");
                Object fileObj = m.invoke(editorPart);
                if (fileObj instanceof IFile) {
                    text = readIFileContent((IFile) fileObj, debugSb);
                    if (text != null) return text;
                }
            } catch (NoSuchMethodException e) {
                // 跳过
            } catch (Exception e) {
                debugSb.append("    [ADT] getFile() FAILED: ").append(e.getMessage()).append("\n");
            }

        } catch (Exception e) {
            debugSb.append("    [ADT source page FAILED: ").append(e.getMessage()).append("]\n");
        }
        return null;
    }

    /**
     * 从页面/模型对象提取文本(多种策略)。
     */
    private String getTextFromPageObject(Object obj, StringBuilder debugSb) {
        // 1. StyledText 控件
        String text = getTextFromStyledTextInObject(obj, debugSb);
        if (text != null) return text;

        // 2. IDocument 字段
        text = findDocumentInFields(obj, debugSb);
        if (text != null) return text;

        // 3. 公共方法
        for (String methodName : new String[]{"getText", "getEditorText", "getContent", "getStyledText", "getTextWidget", "getDocument"}) {
            try {
                Method m = obj.getClass().getMethod(methodName);
                Object result = m.invoke(obj);
                if (result instanceof String) {
                    text = (String) result;
                    if (!text.isEmpty()) {
                        debugSb.append("    [ADT] via ").append(obj.getClass().getSimpleName())
                                .append(".").append(methodName).append("()] len=").append(text.length()).append("\n");
                        return text;
                    }
                }
                if (result instanceof IDocument) {
                    text = ((IDocument) result).get();
                    if (text != null && !text.isEmpty()) {
                        debugSb.append("    [ADT] via ").append(obj.getClass().getSimpleName())
                                .append(".").append(methodName).append("()] len=").append(text.length()).append("\n");
                        return text;
                    }
                }
                if (result instanceof StyledText) {
                    text = ((StyledText) result).getText();
                    if (text != null && !text.isEmpty()) {
                        debugSb.append("    [ADT] via ").append(obj.getClass().getSimpleName())
                                .append(".").append(methodName).append("() -> StyledText] len=").append(text.length()).append("\n");
                        return text;
                    }
                }
            } catch (NoSuchMethodException e) {
                // 跳过
            } catch (Exception e) {
                debugSb.append("    [ADT] ").append(methodName).append(" FAILED: ").append(e.getMessage()).append("\n");
            }
        }
        return null;
    }

    /**
     * 从编辑器的 file 字段读取 IFile 内容。
     */
    private String readTextFromIFile(Object editorPart, StringBuilder debugSb) {
        // 查找 file 字段
        Object fileObj = findFieldValue(editorPart, "file");
        if (fileObj instanceof IFile) {
            debugSb.append("    [ADT] Found file : IFile\n");
            String text = readIFileContent((IFile) fileObj, debugSb);
            if (text != null) return text;
        }

        // 通过反射获取 getFile() 方法
        try {
            Method m = editorPart.getClass().getMethod("getFile");
            Object result = m.invoke(editorPart);
            if (result instanceof IFile) {
                debugSb.append("    [ADT] getFile() -> IFile\n");
                String text = readIFileContent((IFile) result, debugSb);
                if (text != null) return text;
            }
        } catch (NoSuchMethodException e) {
            // 跳过
        } catch (Exception e) {
            debugSb.append("    [ADT] getFile() FAILED: ").append(e.getMessage()).append("\n");
        }

        return null;
    }

    /**
     * 读取 IFile 内容(尝试多种方式)。
     */
    private String readIFileContent(IFile file, StringBuilder debugSb) {
        try {
            if (!file.exists()) {
                debugSb.append("    [ADT] IFile does not exist: ").append(file.getFullPath()).append("\n");
                return null;
            }
            // 方式1: IFile.getContents()
            java.io.InputStream is = file.getContents();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(line);
                }
            }
            String text = sb.toString();
            if (!text.isEmpty()) {
                debugSb.append("    [ADT] read IFile.getContents()] len=").append(text.length()).append("\n");
                return text;
            }
        } catch (Exception e) {
            debugSb.append("    [ADT] IFile.getContents() FAILED: ").append(e.getMessage()).append("\n");
        }

        try {
            // 方式2: IFile.getLocation() 读取本地文件
            java.net.URI location = file.getLocationURI();
            if (location != null) {
                java.io.File localFile = new java.io.File(location);
                if (localFile.exists()) {
                    byte[] bytes = java.nio.file.Files.readAllBytes(localFile.toPath());
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    if (!text.isEmpty()) {
                        debugSb.append("    [ADT] read IFile via local path] len=").append(text.length()).append("\n");
                        return text;
                    }
                }
            }
        } catch (Exception e) {
            debugSb.append("    [ADT] IFile.getLocation() FAILED: ").append(e.getMessage()).append("\n");
        }

        return null;
    }

    /**
     * 转储对象的类层次、字段和方法摘要。
     */
    private void dumpObjectSummary(Object obj, StringBuilder debugSb) {
        Class<?> cls = obj.getClass();
        debugSb.append("    [ADT] ").append(cls.getSimpleName()).append(" hierarchy: ");
        Class<?> c = cls;
        while (c != null) {
            debugSb.append(c.getSimpleName()).append(" -> ");
            c = c.getSuperclass();
        }
        debugSb.append("Object\n");

        // 转储关键字段
        debugSb.append("    [ADT] Fields:\n");
        try {
            Class<?> c2 = cls;
            int d = 0;
            while (c2 != null && d < 5) {
                for (Field f : c2.getDeclaredFields()) {
                    debugSb.append("      - ").append(c2.getSimpleName())
                            .append(".").append(f.getName())
                            .append(" : ").append(f.getType().getSimpleName()).append("\n");
                }
                c2 = c2.getSuperclass();
                d++;
            }
        } catch (Exception e) {
            debugSb.append("      (error listing fields)\n");
        }

        // 转储关键字方法(文本相关)
        debugSb.append("    [ADT] Text-related methods:\n");
        try {
            java.util.Set<String> methodNames = new java.util.HashSet<>();
            for (Method m : cls.getMethods()) {
                String name = m.getName().toLowerCase();
                if (name.contains("text") || name.contains("document") || name.contains("content")
                        || name.contains("source") || name.contains("code") || name.contains("editor")
                        || name.contains("styled") || name.contains("gettext") || name.contains("getcode")
                        || name.contains("getsource")) {
                    methodNames.add(m.getName());
                }
            }
            for (String name : methodNames) {
                debugSb.append("      - ").append(name).append("()\n");
            }
            if (methodNames.isEmpty()) {
                debugSb.append("      (none found)\n");
            }
        } catch (Exception e) {
            debugSb.append("      (error listing methods)\n");
        }
    }

    /**
     * 在对象中查找指定名称的字段值。
     */
    private Object findFieldValue(Object obj, String fieldName) {
        Class<?> cls = obj.getClass();
        int depth = 0;
        while (cls != null && depth < 5) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
                depth++;
            } catch (Exception e) {
                cls = cls.getSuperclass();
                depth++;
            }
        }
        return null;
    }

    /**
     * 从任意对象中查找 StyledText 控件并获取文本。
     */
    private String getTextFromStyledTextInObject(Object obj, StringBuilder debugSb) {
        // 查找 StyledText 类型的字段
        String text = findStyledTextField(obj, debugSb);
        if (text != null) return text;

        // 查找 Control/Composite 类型的字段，递归搜索
        Class<?> cls = obj.getClass();
        int depth = 0;
        while (cls != null && depth < 5) {
            for (Field f : cls.getDeclaredFields()) {
                if (Control.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        if (val instanceof Control) {
                            text = findStyledTextInControl((Control) val, debugSb);
                            if (text != null) return text;
                        }
                    } catch (Exception e) {
                        // 跳过
                    }
                }
            }
            cls = cls.getSuperclass();
            depth++;
        }
        return null;
    }

    /**
     * 从 StyledText 控件获取内容。
     * SAP ADT 编辑器使用 StyledText 作为底层文本控件。
     */
    private String getTextFromStyledText(IEditorPart editorPart, StringBuilder debugSb) {
        try {
            // 尝试通过 getAdapter(Control.class) 获取底层控件
            Object ctrl = editorPart.getAdapter(Control.class);
            if (ctrl instanceof StyledText) {
                String text = ((StyledText) ctrl).getText();
                if (text != null && !text.isEmpty()) {
                    debugSb.append("    [via getAdapter(StyledText)] len=").append(text.length()).append("\n");
                    return text;
                }
            }

            // 尝试通过反射查找 getStyledText() 或 getTextWidget() 方法
            for (String methodName : new String[]{"getStyledText", "getTextWidget", "getControl", "textWidget", "control"}) {
                try {
                    Method m = editorPart.getClass().getMethod(methodName);
                    Object result = m.invoke(editorPart);
                    if (result instanceof StyledText) {
                        String text = ((StyledText) result).getText();
                        if (text != null && !text.isEmpty()) {
                            debugSb.append("    [via reflective ").append(methodName).append("() -> StyledText] len=").append(text.length()).append("\n");
                            return text;
                        }
                    }
                    if (result instanceof Control) {
                        // 尝试递归查找子控件中的 StyledText
                        String text = findStyledTextInControl((Control) result, debugSb);
                        if (text != null) return text;
                    }
                } catch (NoSuchMethodException e) {
                    // 跳过
                } catch (Exception e) {
                    debugSb.append("    [reflective ").append(methodName).append(" FAILED: ").append(e.getMessage()).append("]\n");
                }
            }

            // 递归查找 StyledText 类型的字段
            String fieldText = findStyledTextField(editorPart, debugSb);
            if (fieldText != null) return fieldText;

        } catch (Exception e) {
            debugSb.append("    [StyledText approach FAILED: ").append(e.getMessage()).append("]\n");
        }
        return null;
    }

    /**
     * 在控件树中递归查找 StyledText。
     */
    private String findStyledTextInControl(Control ctrl, StringBuilder debugSb) {
        if (ctrl instanceof StyledText) {
            String text = ((StyledText) ctrl).getText();
            if (text != null && !text.isEmpty()) {
                debugSb.append("    [via StyledText control tree] len=").append(text.length()).append("\n");
                return text;
            }
        }
        if (ctrl instanceof org.eclipse.swt.widgets.Composite) {
            for (Control child : ((org.eclipse.swt.widgets.Composite) ctrl).getChildren()) {
                String result = findStyledTextInControl(child, debugSb);
                if (result != null) return result;
            }
        }
        return null;
    }

    /**
     * 反射查找 StyledText 类型的字段。
     */
    private String findStyledTextField(Object obj, StringBuilder debugSb) {
        Class<?> cls = obj.getClass();
        int depth = 0;
        while (cls != null && depth < 5) {
            Field[] fields = cls.getDeclaredFields();
            for (Field f : fields) {
                if (StyledText.class.isAssignableFrom(f.getType())) {
                    try {
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        if (val instanceof StyledText) {
                            String text = ((StyledText) val).getText();
                            if (text != null && !text.isEmpty()) {
                                debugSb.append("    [via field ").append(cls.getSimpleName())
                                        .append(".").append(f.getName())
                                        .append(" -> StyledText] len=").append(text.length()).append("\n");
                                return text;
                            }
                        }
                    } catch (Exception e) {
                        // 不可访问,跳过
                    }
                }
            }
            cls = cls.getSuperclass();
            depth++;
        }
        return null;
    }

    /**
     * 转储编辑器的可用方法和字段,用于调试。
     */
    private void dumpMethodsAndFields(Object obj, StringBuilder debugSb) {
        Class<?> cls = obj.getClass();
        debugSb.append("    [DEBUG] Class hierarchy: ");
        Class<?> c = cls;
        while (c != null) {
            debugSb.append(c.getSimpleName()).append(" -> ");
            c = c.getSuperclass();
        }
        debugSb.append("Object\n");

        // 转储公共方法
        debugSb.append("    [DEBUG] Public methods:\n");
        try {
            java.util.Set<String> methodNames = new java.util.HashSet<>();
            for (Method m : cls.getMethods()) {
                methodNames.add(m.getName());
            }
            for (String name : methodNames) {
                debugSb.append("      - ").append(name).append("()\n");
            }
        } catch (Exception e) {
            debugSb.append("      (error listing methods)\n");
        }

        // 转储声明字段
        debugSb.append("    [DEBUG] All fields (including superclasses):\n");
        try {
            Class<?> c2 = cls;
            int d = 0;
            while (c2 != null && d < 5) {
                for (Field f : c2.getDeclaredFields()) {
                    debugSb.append("      - ").append(c2.getSimpleName())
                            .append(".").append(f.getName())
                            .append(" : ").append(f.getType().getSimpleName()).append("\n");
                }
                c2 = c2.getSuperclass();
                d++;
            }
        } catch (Exception e) {
            debugSb.append("      (error listing fields)\n");
        }
    }

    /**
     * 构建最终结果(始终包含分隔符,即使没有找到文件)。
     */
    private String buildResult(List<EditorContentPair> pairs, int totalScanned) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=====================================================\n");
        sb.append("===     Workspace Reference Code (open files)     ===\n");
        sb.append("=====================================================\n\n");

        if (pairs.isEmpty()) {
            sb.append("(No other ABAP files found among open editors)\n");
            sb.append("Scanned ").append(totalScanned).append(" editors, none matched ABAP criteria.\n");
            sb.append("Debug: check [DEBUG] log lines above for details.\n");
        } else {
            // 按内容长度排序(短文件优先)
            pairs.sort((a, b) -> Integer.compare(a.content.length(), b.content.length()));

            int limit = Math.min(maxFileLimit, pairs.size());
            for (int i = 0; i < limit; i++) {
                EditorContentPair pair = pairs.get(i);
                // 与节点2（父程序上下文）保持一致：上游先通过 AbapCodeTruncator
                // 按结构保留式策略截断到 maxContextChars，保证工作区文件同样被压缩。
                String truncated = AbapCodeTruncator.truncate(pair.content, maxContextChars);
                sb.append("--- File: ").append(pair.displayName).append(" ---\n");
                sb.append(truncated).append("\n");
            }
        }

        sb.append("=====================================================\n");
        return sb.toString();
    }

    /**
     * 判断编辑器是否为当前正在编辑的文件(通过编辑器标题)。
     */
    private boolean isCurrentEditor(IEditorPart editorPart) {
        if (currentFileName == null || currentFileName.isEmpty()) return false;
        String title = editorPart.getTitle();
        if (title.equalsIgnoreCase(currentFileName)) return true;
        return isCurrentEditorByTitle(title);
    }

    /**
     * 通过标题判断是否为当前文件(通用方法)。
     * SAP ADT 编辑器标题(如 ZTRE08152_F02)与 IFile 文件名(如 ztre08152_f02.asinc)格式不同,
     * 需要多重比较策略。
     */
    private boolean isCurrentEditorByTitle(String title) {
        if (currentFileName == null || currentFileName.isEmpty()) return false;

        // 0. 直接比较
        if (title.equalsIgnoreCase(currentFileName)) return true;

        // 1. 去扩展名后比较
        String baseTitle = title.contains(".") ? title.substring(0, title.lastIndexOf('.')) : title;
        String baseCurrent = currentFileName.contains(".") ? currentFileName.substring(0, currentFileName.lastIndexOf('.')) : currentFileName;
        if (baseTitle.equalsIgnoreCase(baseCurrent)) return true;

        // 2. 标题+扩展名 与 原文件名比较
        if (!currentFileExtension.isEmpty()) {
            String titleWithExt = title + currentFileExtension;
            if (titleWithExt.equalsIgnoreCase(currentFileName)) return true;
        }

        // 3. 包含检测: 标题包含在文件名中 或 文件名包含在标题中
        // 例如 title="ZTRE08152_F02", currentFileName="ztre08152_f02.asinc"
        if (currentFileName.toLowerCase().contains(title.toLowerCase())) return true;
        if (title.toLowerCase().contains(baseCurrent.toLowerCase())) return true;

        return false;
    }

    /**
     * 从 IEditorInput 直接读取内容(用于未加载的编辑器)。
     */
    private String readContentFromInput(IEditorInput input, StringBuilder debugSb) {
        // 1. 尝试 FileEditorInput
        if (input instanceof org.eclipse.ui.part.FileEditorInput) {
            IFile file = ((org.eclipse.ui.part.FileEditorInput) input).getFile();
            String text = readIFileContent(file, debugSb);
            if (text != null) return text;
        }

        // 2. 尝试 getAdapter(IFile.class)
        try {
            Object adapted = input.getAdapter(IFile.class);
            if (adapted instanceof IFile) {
                String text = readIFileContent((IFile) adapted, debugSb);
                if (text != null) return text;
            }
        } catch (Exception e) {
            debugSb.append("    [input.getAdapter(IFile) FAILED: ").append(e.getMessage()).append("\n");
        }

        // 3. 反射查找 getFile() 方法
        try {
            Method m = input.getClass().getMethod("getFile");
            Object result = m.invoke(input);
            if (result instanceof IFile) {
                debugSb.append("    [input.getFile() -> IFile]\n");
                String text = readIFileContent((IFile) result, debugSb);
                if (text != null) return text;
            }
        } catch (NoSuchMethodException e) {
            // 跳过
        } catch (Exception e) {
            debugSb.append("    [input.getFile() FAILED: ").append(e.getMessage()).append("\n");
        }

        // 4. 反射查找 getContents() 方法
        try {
            Method m = input.getClass().getMethod("getContents");
            Object result = m.invoke(input);
            if (result instanceof java.io.InputStream) {
                debugSb.append("    [input.getContents() -> InputStream]\n");
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader((java.io.InputStream) result, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(line);
                    }
                }
                String text = sb.toString();
                if (!text.isEmpty()) {
                    debugSb.append("    [input.getContents()] len=").append(text.length()).append("\n");
                    return text;
                }
            }
        } catch (NoSuchMethodException e) {
            // 跳过
        } catch (Exception e) {
            debugSb.append("    [input.getContents() FAILED: ").append(e.getMessage()).append("\n");
        }

        // 5. 反射查找 URI 并读取
        try {
            Method m = input.getClass().getMethod("getURI");
            Object result = m.invoke(input);
            if (result instanceof java.net.URI) {
                java.io.File localFile = new java.io.File((java.net.URI) result);
                if (localFile.exists()) {
                    byte[] bytes = java.nio.file.Files.readAllBytes(localFile.toPath());
                    String text = new String(bytes, StandardCharsets.UTF_8);
                    if (!text.isEmpty()) {
                        debugSb.append("    [input.getURI() -> local file] len=").append(text.length()).append("\n");
                        return text;
                    }
                }
            }
        } catch (NoSuchMethodException e) {
            // 跳过
        } catch (Exception e) {
            debugSb.append("    [input.getURI() FAILED: ").append(e.getMessage()).append("\n");
        }

        return null;
    }

    /**
     * 判断编辑器内容是否为 ABAP 代码。
     */
    private static boolean isAbapCode(String displayName, String editorId, String content) {
        // 1. 编辑器 ID 包含 abap — 直接通过(SAP ADT 场景)
        if (editorId != null && editorId.toLowerCase().contains("abap")) {
            return true;
        }

        // 2. 文件名特征
        if (displayName != null) {
            String upper = displayName.toUpperCase();
            if (upper.endsWith(".ABAP") || upper.endsWith(".ABAPINC")) return true;
            int dot = upper.lastIndexOf('.');
            String base = dot > 0 ? upper.substring(0, dot) : upper;
            if (base.startsWith("Y") || base.startsWith("Z")
                    || base.startsWith("SAP") || base.startsWith("R")) {
                return true;
            }
        }

        // 3. 内容启发式判断
        if (content != null && content.length() > 20) {
            String upper = content.toUpperCase();

            // 3a. 先检查 ABAP 标志语句(REPORT/PROGRAM/FUNCTION-POOL/CLASS-METHODS 等)
            // 只要存在任一标志即判定为 ABAP
            if (upper.contains("REPORT ")
                    || upper.contains("PROGRAM ")
                    || upper.contains("FUNCTION-POOL ")
                    || upper.contains("CLASS-METHODS ")
                    || upper.contains("INCLUDE ") && upper.contains("REPORT")) {
                return true;
            }

            // 3b. 关键字命中数判断(至少3个)
            int hits = 0;
            if (upper.contains("REPORT ")) hits++;
            if (upper.contains("DATA:")) hits++;
            if (upper.contains("TYPES:")) hits++;
            if (upper.contains("START-OF-SELECTION")) hits++;
            if (upper.contains("METHOD ")) hits++;
            if (upper.contains("CLASS ")) hits++;
            if (upper.contains("ENDCLASS.")) hits++;
            if (upper.contains("SELECT ")) hits++;
            if (upper.contains("LOOP AT ")) hits++;
            if (upper.contains("WRITE:")) hits++;
            if (upper.contains("INCLUDE ")) hits++;
            if (upper.contains("FUNCTION ")) hits++;
            if (upper.contains("TABLES:")) hits++;
            if (upper.contains("CONSTANTS:")) hits++;
            if (upper.contains("FORM ")) hits++;
            if (upper.contains("ENDMETHOD.")) hits++;
            if (upper.contains("IF ")) hits++;
            if (upper.contains("ELSEIF ")) hits++;
            if (upper.contains("ENDIF.")) hits++;
            if (upper.contains("CASE ")) hits++;
            if (upper.contains("ENDCASE.")) hits++;
            if (upper.contains("DO ")) hits++;
            if (upper.contains("ENDDO.")) hits++;
            if (upper.contains("WHILE ")) hits++;
            if (upper.contains("ENDWHILE.")) hits++;
            return hits >= 2;
        }

        return false;
    }

    private static class EditorContentPair {
        final String displayName;
        final String content;

        EditorContentPair(String displayName, String content) {
            this.displayName = displayName;
            this.content = content;
        }
    }
}
