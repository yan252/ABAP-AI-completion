# ABAP 上下文解析与日志功能实现计划

## Context

当前插件 [com.sap.abap.ai.completion](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion) 存在三个问题:

1. **无 ABAP 门控**: [AICompletionHandler](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/editor/AICompletionHandler.java#L47-L52) 和 [AICompletionListener](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/editor/AICompletionListener.java#L78-L82) 仅判断 `instanceof ITextEditor`,会在 Java/JS/XML 等所有文本编辑器中触发,浪费 API 调用。

2. **无上级程序反向解析**: [AbapIncludeResolver](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/parser/AbapIncludeResolver.java#L88-L99) 只能向下解析当前文件中的 INCLUDE 语句。当用户编辑的是一个 INCLUDE 程序时,AI 无法获知调用方(上级主程序)的上下文,导致补全质量下降。

3. **无接口日志**: 调试时无法查看实际传给 AI 的 prompt 和返回结果。

本方案通过组合判断、反向递归查找、智能截断和日志机制解决以上问题。

---

## 一、新增文件

### 1. [AbapLanguageDetector.java](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/parser/AbapLanguageDetector.java)
**路径**: `src/com/sap/abap/ai/completion/parser/AbapLanguageDetector.java`
**职责**: ABAP 代码组合判断(四策略短路 OR)

```java
public final class AbapLanguageDetector {
    public static boolean isAbapContext(IEditorPart editor, IFile file, IDocument doc);
    public static boolean isAbapContent(String content);
    // 四个独立策略(包级可见,便于测试)
    static boolean matchEditorId(IEditorPart editor);        // editor id 含 "abap"
    static boolean matchFileExtension(IFile file);           // .abap / .abapinc
    static boolean matchPartitionType(IDocument doc);        // 分区类型含 "abap"
    static boolean matchContentHeuristic(String content);    // 关键字命中数 >= 3
}
```

**判断顺序**(短路 OR,任一命中即返回 true):
1. 编辑器 ID 包含 "abap" (SAP ADT 场景)
2. 文件扩展名 `.abap` 或 `.abapinc`
3. 文档分区类型名包含 "abap" (通过 `IDocumentExtension3`)
4. 内容启发式: 关键字集合 `{REPORT, DATA:, TYPES:, START-OF-SELECTION, METHOD, CLASS, ENDCLASS., SELECT, LOOP AT, WRITE:, FORM, ENDFORM, PARAMETERS, TABLES, CONSTANTS:}` 命中数 ≥ 3

### 2. [ParentProgramResolver.java](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/parser/ParentProgramResolver.java)
**路径**: `src/com/sap/abap/ai/completion/parser/ParentProgramResolver.java`
**职责**: 反向查找 INCLUDE 上级程序,递归(深度可配),带环检测

```java
public class ParentProgramResolver {
    public ParentProgramResolver(IProject project, int maxDepth, int maxContextChars);
    public ParentProgramContext resolveParents(IFile includeFile);
    // 内部递归
    private void collectParentsRecursively(IFile file, int depth,
        Set<String> visitedPaths, List<ParentProgramInfo> result);
    private List<IFile> findParentFilesByContent(IProject container, String includeName);
    private List<IFile> findParentFilesWithFallback(String includeName, IProject preferred);
}
```

**关键算法**: 见下方"关键算法"节。

### 3. [ParentProgramContext.java](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/parser/ParentProgramContext.java)
**路径**: `src/com/sap/abap/ai/completion/src/com/sap/abap/ai/completion/parser/ParentProgramContext.java`
**职责**: 上级程序上下文数据持有者,仿照现有 `IncludeContext` 风格

```java
public class ParentProgramContext {
    private final List<ParentProgramInfo> parents;  // 按 depth 排序
    public void addParent(String name, String code, String includedCode, int depth);
    public List<ParentProgramInfo> getParents();
    public boolean isEmpty();
    public String buildPromptContext();   // 构建截断后的 prompt 片段

    public static class ParentProgramInfo {
        private final String name;
        private final String code;          // 已截断
        private final String includedCode;  // 上级解析出的 INCLUDE 代码(截断后)
        private final int depth;
    }
}
```

### 4. [AbapCodeTruncator.java](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/parser/AbapCodeTruncator.java)
**路径**: `src/com/sap/abap/ai/completion/src/com/sap/abap/ai/completion/parser/AbapCodeTruncator.java`
**职责**: Token 控制 - 保留结构行,截断实现细节

```java
public final class AbapCodeTruncator {
    public static final int DEFAULT_MAX_CHARS = 8000;
    public static String truncate(String source, int maxChars);
    static boolean isStructuralLine(String strippedUpperLine);
}
```

### 5. [AILogger.java](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/logging/AILogger.java)
**路径**: `src/com/sap/abap/ai/completion/src/com/sap/abap/ai/completion/logging/AILogger.java`
**职责**: 接口日志记录,默认关闭,输出到插件 state area

```java
public final class AILogger {
    public static void logRequest(String fileName, String systemPrompt, String userPrompt);
    public static void logResponse(String fileName, String completion, long durationMs);
    public static void logError(String fileName, String errorMsg);
    private static boolean isEnabled();
    private static java.nio.file.Path getLogPath();
    private static void append(String content);
}
```

**输出位置**: `<workspace>/.metadata/.plugins/com.sap.abap.ai.completion/ai-interface.log`

**日志格式**:
```
[2026-08-04 10:23:15.123] [REQUEST] [ZMY_PROG.abap]
--- SYSTEM PROMPT ---
<system prompt 全文>
--- USER PROMPT ---
<user prompt 全文>
[2026-08-04 10:23:17.456] [RESPONSE] [ZMY_PROG.abap] (2333ms)
<completion 全文>
```

---

## 二、修改文件

### 1. [AICompletionHandler.java](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/editor/AICompletionHandler.java)
**修改点**: `doExecute` 方法,在获取 `editor`/`file`/`doc` 之后(约 line 59),`requestCompletion` 之前插入 ABAP 检测:
```java
if (!AbapLanguageDetector.isAbapContext(editor, file, doc)) {
    showStatus(event, "Not an ABAP source.");
    return null;
}
```

### 2. [AICompletionListener.java](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/editor/AICompletionListener.java)
**修改点 A**: `attachToEditorPart` (line 78-82) 和 `partActivated` (line 346-350),在 `instanceof ITextEditor` 判断之后追加 ABAP 检测。非 ABAP 编辑器不附加监听器。

**修改点 B**: `triggerPollingCompletion` (line 319) 开头加同一道防线。

### 3. [AbapIncludeResolver.java](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/parser/AbapIncludeResolver.java)
**修改点**: `searchInContainer` (line 119-139) 文件扩展名匹配条件,在 `.ABAP`/`.TXT` 之外加 `.ABAPINC`。

### 4. [AICompletionService.java](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/editor/AICompletionService.java)
**修改点 A**: `requestCompletion` (line 41-83),在 `resolver.resolveAllIncludes(currentCode)` 之后插入上级解析和日志埋点:
```java
IncludeContext context = resolver.resolveAllIncludes(currentCode);

// 新增:上级程序反向解析
ParentProgramContext parentCtx = null;
if (AIConfiguration.isParentProgramResolutionEnabled()) {
    ParentProgramResolver parentResolver = new ParentProgramResolver(
        project, AIConfiguration.getAbapSearchDepth(), AIConfiguration.getMaxContextChars());
    parentCtx = parentResolver.resolveParents(file);
}

String systemPrompt = getEffectiveSystemPrompt();
String userPrompt = buildUserPrompt(
    context.buildPromptContext(),
    parentCtx != null ? parentCtx.buildPromptContext() : "",
    codeBefore, codeAfter);

// 新增:日志
final long startTs = System.currentTimeMillis();
AILogger.logRequest(file.getName(), systemPrompt, userPrompt);
String result = AIClient.complete(systemPrompt, userPrompt);
AILogger.logResponse(file.getName(), result, System.currentTimeMillis() - startTs);
return result;
```

**修改点 B**: `buildUserPrompt` (line 148-184) 签名扩展,新增 `parentProgramContext` 参数。在 `=== Current ABAP Program ===` 段之后、`=== Available Skill Files ===` 段之前插入:
```
=== Parent Programs (calling context, truncated) ===
<上级程序片段>
```

**修改点 C**: 错误路径(`future.whenComplete` 的 `ex != null` 分支,line 64-74)追加 `AILogger.logError(file.getName(), msg)`。

### 5. [PreferenceConstants.java](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/preferences/PreferenceConstants.java)
**新增常量**:
```java
// === Parent Program Resolution ===
public static final String PARENT_PROGRAM_RESOLUTION_ENABLED = "parentProgramResolutionEnabled";
public static final String ABAP_SEARCH_DEPTH = "abapSearchDepth";
public static final String MAX_CONTEXT_CHARS = "maxContextChars";

// === Interface Logging ===
public static final String INTERFACE_LOGGING_ENABLED = "interfaceLoggingEnabled";

// === Defaults ===
public static final boolean DEFAULT_PARENT_PROGRAM_RESOLUTION_ENABLED = true;
public static final String DEFAULT_ABAP_SEARCH_DEPTH = "2";
public static final String DEFAULT_MAX_CONTEXT_CHARS = "8000";
public static final boolean DEFAULT_INTERFACE_LOGGING_ENABLED = false;
```

### 6. [AIConfiguration.java](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/preferences/AIConfiguration.java)
**新增 getter**: `isParentProgramResolutionEnabled()`, `getAbapSearchDepth()`, `getMaxContextChars()`, `isInterfaceLoggingEnabled()`。遵循现有 `getStore().getBoolean/getString` + try/parse 模式。

### 7. [PreferenceInitializer.java](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/preferences/PreferenceInitializer.java)
**修改点**: `initializeDefaultPreferences` 末尾追加 4 个 `store.setDefault(...)` 调用。

### 8. [AICompletionPreferencePage.java](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/src/com/sap/abap/ai/completion/preferences/AICompletionPreferencePage.java)
**修改点 A**: 新增字段 `chkParentResolution`, `txtSearchDepth`, `txtMaxContextChars`, `chkInterfaceLogging`。

**修改点 B**: `createContents` 中插入 `createContextGroup(main)` 调用(放在 `createAutoCompletionGroup` 之后)。

**修改点 C**: 新增方法 `createContextGroup(Composite parent)`,包含:
- "Enable parent program reverse lookup" 复选框
- "ABAP search depth (levels)" 文本框
- "Max context chars per parent" 文本框
- "Enable interface logging" 复选框

**修改点 D**: `loadValues`/`saveValues`/`performDefaults` 三处同步追加对应读写逻辑。

---

## 三、关键算法

### 算法1: ABAP 组合判断(短路 OR)
```
isAbapContext(editor, file, doc):
    if matchEditorId(editor)         → return true   // ADT 编辑器 id 含 "abap"
    if matchFileExtension(file)      → return true   // .abap 或 .abapinc
    if matchPartitionType(doc)      → return true   // 分区类型名含 "abap"
    if doc != null && matchContentHeuristic(doc.get()) → return true
    return false
```
`matchContentHeuristic` 放最后(需读全文,开销最大)。

### 算法2: 反向查找上级程序(防循环 + 深度控制)
```
resolveParents(includeFile):
    visited = { canonicalPath(includeFile) }    // 起点
    result = []
    collectParentsRecursively(includeFile, depth=0, visited, result)
    return new ParentProgramContext(result)

collectParentsRecursively(file, depth, visited, result):
    if depth >= maxDepth: return               // 深度门控(默认 2)
    includeName = stripExtension(file.getName())
    parents = findParentFilesWithFallback(includeName, file.getProject())
    for parent in parents:
        path = canonicalPath(parent)
        if visited.contains(path): continue   // 环检测
        visited.add(path)                     // 不移除,防跨路径环
        code = readFileContent(parent)
        // 复用 AbapIncludeResolver 解析上级的 INCLUDE
        IncludeContext parentIncludes = new AbapIncludeResolver(project)
                                                .resolveAllIncludes(code)
        truncatedCode = AbapCodeTruncator.truncate(code, maxContextChars)
        truncatedIncludes = AbapCodeTruncator.truncate(
                                parentIncludes.buildPromptContext(), maxContextChars)
        result.add(new ParentProgramInfo(parent.getName(), truncatedCode,
                                         truncatedIncludes, depth+1))
        collectParentsRecursively(parent, depth+1, visited, result)   // 递归向上

findParentFilesWithFallback(name, preferred):
    results = findParentFilesByContent(preferred, name)
    if !results.isEmpty(): return results      // 当前项目优先
    for p in workspace.projects:                // 工作区兜底
        if p != preferred && p.isAccessible():
            results.addAll(findParentFilesByContent(p, name))
    return results
```
**关键点**:
- `visited` 不在递归返回时移除元素,防止 A→B→A 跨路径循环
- `maxDepth=2` 含义: 从当前 include 起最多向上找 2 层(父亲、祖父)
- 复用 `AbapIncludeResolver.resolveAllIncludes` 解析上级的 INCLUDE

### 算法3: 智能截断(保留关键部分)
```
truncate(source, maxChars):
    if source.length() <= maxChars: return source
    kept = StringBuilder()
    for line in source.split("\n"):
        if isStructuralLine(line.trim().toUpperCase()):
            kept.append(line).append("\n")
            if kept.length() >= maxChars: break
    if kept.length() > maxChars:
        kept = kept.substring(0, maxChars) + "\n... [truncated]\n"
    if kept.isEmpty():
        kept = source.substring(0, maxChars) + "\n... [truncated]\n"
    return kept.toString()

isStructuralLine(strippedUpper):
    // 声明
    if startsWith("DATA ") || startsWith("DATA:") → true
    if startsWith("TYPES ") || startsWith("TYPES:") → true
    if startsWith("CONSTANTS ") || startsWith("CONSTANTS:") → true
    if startsWith("TABLES:") || startsWith("TABLES ") → true
    if startsWith("PARAMETERS") || startsWith("SELECT-OPTIONS") → true
    // 类/方法/FORM 签名
    if startsWith("CLASS ") && (contains "DEFINITION" || contains "SECTION") → true
    if equals "PUBLIC SECTION" || "PROTECTED SECTION" || "PRIVATE SECTION" → true
    if startsWith("METHOD ") && !endsWith "." → true
    if startsWith("FORM ") && !endsWith "." → true
    // 段落标记
    if startsWith "REPORT " || startsWith "PROGRAM " → true
    if startsWith "START-OF-SELECTION" || startsWith "INITIALIZATION" → true
    if startsWith "ENDCLASS" || "ENDMETHOD" || "ENDFORM" → true
    if startsWith "INCLUDE " → true
    return false
```
保留声明/签名/段落结构,丢弃 `LOOP/SELECT/WRITE/IF` 等实现细节。

---

## 四、配置项汇总

| PreferenceConstants 键 | 默认值 | 类型 | 说明 |
|---|---|---|---|
| `parentProgramResolutionEnabled` | `true` | boolean | 启用上级程序反向解析 |
| `abapSearchDepth` | `"2"` | String→int | ABAP 代码搜索深度(层) |
| `maxContextChars` | `"8000"` | String→int | 每个上级程序最大字符数 |
| `interfaceLoggingEnabled` | `false` | boolean | 启用接口日志记录 |

---

## 五、Prompt 结构(修改后)

```
=== Current ABAP Program (with INCLUDES) ===
<当前文件全文 + 当前文件向下解析的 INCLUDE 代码>

=== Parent Programs (calling context, truncated) ===
--- Parent: Z_MAIN_PROGRAM.abap (depth 1) ---
<上级程序结构签名(截断后)>

--- Parent: Z_MAIN_PROGRAM.abap - resolved INCLUDES (depth 1) ---
<上级程序解析出的 INCLUDE 代码(截断后)>

--- Parent: Z_TOPLEVEL.abap (depth 2) ---
<祖父程序结构签名(截断后)>

=== Available Skill Files (reference patterns) ===
<skill 目录文件内容>

=== Cursor Context ===
Before cursor:
<最后 15 行>

>>> CURSOR <<<

After cursor:
<前 5 行>

Generate only the ABAP code to insert at the cursor position.
```
新增 `=== Parent Programs ===` 段插在 `Current` 与 `Skill` 之间,不破坏其余逻辑。

---

## 六、日志机制

- **不使用 Eclipse Platform.getLog()**: 会与 Eclipse 自身错误混淆
- **使用 `Platform.getStateLocation(bundle)`**: 写到 `<workspace>/.metadata/.plugins/com.sap.abap.ai.completion/ai-interface.log`,与插件状态隔离
- **写入方式**: `Files.write(path, bytes, CREATE, APPEND)` + `synchronized (WRITE_LOCK)` 串行化
- **开关门控**: `AILogger` 内部首行 `if (!isEnabled()) return;`,无性能开销
- **敏感信息**: API Key 永不记录(不在 prompt 中);模型名、URL 不记录
- **记录时机**: `AICompletionService.requestCompletion` 中,`AIClient.complete` 调用前后各记一次。`requestQuickCompletion` 默认不记录(频率高)。

---

## 七、实施顺序

1. **第一步(配置链路)**: `PreferenceConstants` + `AIConfiguration` + `PreferenceInitializer` + `AICompletionPreferencePage` - 先打通配置,后续功能可读开关
2. **第二步(ABAP 检测)**: `AbapLanguageDetector` + 改 `AICompletionHandler`/`AICompletionListener` 门控 - 独立可测
3. **第三步(截断)**: `AbapCodeTruncator` - 纯函数,易单测
4. **第四步(上级解析)**: `ParentProgramResolver` + `ParentProgramContext` + 改 `AbapIncludeResolver` 扩展名 + 改 `AICompletionService.requestCompletion`/`buildUserPrompt` - 核心功能
5. **第五步(日志)**: `AILogger` + 在 `AICompletionService` 埋点

---

## 八、验证方法

### 8.1 ABAP 组合判断验证
- Eclipse 中分别打开 `.abap`、`.txt`、`.java`、`.xml` 文件,按 Ctrl+Shift+.,确认仅 `.abap`/`.abapinc` 触发补全,其余提示 "Not an ABAP source."

### 8.2 反向查找上级程序验证
**测试场景搭建**: 创建测试项目:
- `Z_MAIN.abap`: 含 `INCLUDE Z_INCL1.`
- `Z_INCL1.abap`: 含 `INCLUDE Z_INCL2.`
- `Z_INCL2.abap`: 纯代码

**场景 A(单层)**: 打开 `Z_INCL1.abap`,触发补全,查日志确认 user prompt 含 `--- Parent: Z_MAIN.abap (depth 1) ---`

**场景 B(多层,depth=2)**: 打开 `Z_INCL2.abap`,确认 prompt 含 depth 1(Z_INCL1)和 depth 2(Z_MAIN)

**场景 C(深度限制)**: 将 `abapSearchDepth` 改为 1,打开 `Z_INCL2.abap`,确认只出现 depth 1

**场景 D(环检测)**: 创建 `Z_A.abap` 含 `INCLUDE Z_B.`、`Z_B.abap` 含 `INCLUDE Z_A.`,打开任一文件,确认不卡死、每个 parent 仅出现一次

### 8.3 智能截断验证
- 用大 ABAP 文件触发补全,查日志 user prompt 段,确认 `[truncated]` 出现且结构行(DATA/TYPES/METHOD 签名)保留

### 8.4 偏好设置 UI 验证
- 打开 Window > Preferences > ABAP AI Completion,确认新增 "ABAP Context Resolution" 组,4 个控件齐全
- 修改值,Apply + OK,重启 Eclipse,重新打开偏好页确认值持久化
- 点 "Restore Defaults",确认 4 个值回到默认(true / 2 / 8000 / false)

### 8.5 日志记录验证
- 偏好页勾选 "Enable interface logging",保存
- 打开任一 `.abap` 文件触发 Ctrl+Shift+.
- 导航到 `<workspace>/.metadata/.plugins/com.sap.abap.ai.completion/ai-interface.log`,确认文件含 `[REQUEST]`、system prompt 全文、user prompt 全文、`[RESPONSE]`、completion、耗时
- 反向验证: 取消勾选日志开关,再次触发补全,确认日志文件不再增长

### 8.6 端到端集成验证
- 配置真实可用的 OpenAI(或兼容)API Key
- 打开含 INCLUDE 链的 ABAP 程序,触发补全,确认补全结果考虑了上下文
- 打开一个 INCLUDE 文件,触发补全,确认补全结果考虑了上级程序的声明(说明 parent context 生效)

### 8.7 构建验证
- 运行 `build.ps1` + `rebuild.ps1` 重新打包,确认编译无错误
- 将新 JAR 部署到 Eclipse dropins,重启验证

---

## 关键文件清单

**新增**:
- `src/com/sap/abap/ai/completion/parser/AbapLanguageDetector.java`
- `src/com/sap/abap/ai/completion/parser/ParentProgramResolver.java`
- `src/com/sap/abap/ai/completion/parser/ParentProgramContext.java`
- `src/com/sap/abap/ai/completion/parser/AbapCodeTruncator.java`
- `src/com/sap/abap/ai/completion/logging/AILogger.java`

**修改**:
- `src/com/sap/abap/ai/completion/editor/AICompletionHandler.java`
- `src/com/sap/abap/ai/completion/editor/AICompletionListener.java`
- `src/com/sap/abap/ai/completion/editor/AICompletionService.java`
- `src/com/sap/abap/ai/completion/parser/AbapIncludeResolver.java`
- `src/com/sap/abap/ai/completion/preferences/PreferenceConstants.java`
- `src/com/sap/abap/ai/completion/preferences/AIConfiguration.java`
- `src/com/sap/abap/ai/completion/preferences/PreferenceInitializer.java`
- `src/com/sap/abap/ai/completion/preferences/AICompletionPreferencePage.java`
