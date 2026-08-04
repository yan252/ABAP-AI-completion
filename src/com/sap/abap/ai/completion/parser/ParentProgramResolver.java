package com.sap.abap.ai.completion.parser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;

import com.sap.abap.ai.completion.logging.AILogger;

/**
 * 反向查找 INCLUDE 上级程序。
 *
 * 当用户编辑的是一个 INCLUDE 程序时,本类在工作区中搜索所有 .abap/.abapinc/.txt 文件
 * 以及无扩展名文件(SAP ADT 环境),找到包含 `INCLUDE <当前文件名>` 语句的文件,即为上级程序。
 *
 * 算法特性:
 *   - 递归向上查找,最多 maxDepth 层(默认 2 层)
 *   - 带环检测(Set<String> visitedPaths,不移除,防跨路径循环)
 *   - 当前项目优先,工作区兜底
 *   - 每个上级程序代码独立截断(避免单层爆炸)
 *   - 复用 {@link AbapIncludeResolver#resolveAllIncludes} 解析上级的 INCLUDE
 */
public class ParentProgramResolver {

    private final IProject project;
    private final int maxDepth;
    private final int maxContextChars;

    public ParentProgramResolver(IProject project, int maxDepth, int maxContextChars) {
        this.project = project;
        this.maxDepth = Math.max(1, maxDepth);
        this.maxContextChars = Math.max(1000, maxContextChars);
    }

    /**
     * 入口: 从当前 include 文件向上找所有上级程序。
     *
     * @param includeFile 当前编辑的 INCLUDE 文件
     * @return 上级程序上下文(可能为空)
     */
    public ParentProgramContext resolveParents(IFile includeFile) {
        ParentProgramContext context = new ParentProgramContext();
        if (includeFile == null) return context;

        String fileName = includeFile.getName();
        AILogger.logError("ParentProgramResolver", "[DEBUG] resolveParents: start file=" + fileName);

        Set<String> visited = new HashSet<>();
        // 起点: 当前文件自身加入 visited,防止自引用
        String startPath = toCanonical(includeFile);
        if (startPath != null) visited.add(startPath);

        // 提取 includeName 用于搜索
        String includeName = stripExtension(fileName);
        AILogger.logError("ParentProgramResolver", "[DEBUG] searching for INCLUDE " + includeName
                + " in project=" + (project != null ? project.getName() : "null"));

        collectParentsRecursively(includeFile, 0, visited, context);

        AILogger.logError("ParentProgramResolver", "[DEBUG] found " + context.getParents().size()
                + " parent programs for " + fileName);
        return context;
    }

    // ==================== 内部递归 ====================

    private void collectParentsRecursively(IFile file, int depth,
                                            Set<String> visited,
                                            ParentProgramContext result) {
        if (depth >= maxDepth) {
            AILogger.logError("ParentProgramResolver", "[DEBUG] maxDepth " + maxDepth
                    + " reached for " + file.getName());
            return;   // 深度门控
        }

        String includeName = stripExtension(file.getName());
        if (includeName == null || includeName.isEmpty()) return;

        AILogger.logError("ParentProgramResolver", "[DEBUG] depth=" + depth
                + ", looking for parents of " + includeName);

        List<IFile> parents = findParentFilesWithFallback(includeName, file.getProject());
        AILogger.logError("ParentProgramResolver", "[DEBUG] found " + parents.size()
                + " potential parents for " + includeName);

        for (IFile parent : parents) {
            String path = toCanonical(parent);
            if (path == null) continue;
            if (visited.contains(path)) {
                AILogger.logError("ParentProgramResolver", "[DEBUG] skip visited parent "
                        + parent.getName());
                continue;   // 环检测
            }
            visited.add(path);                       // 不移除,防跨路径环

            try {
                String parentCode = AbapIncludeResolver.readFileContent(parent);
                if (parentCode == null || parentCode.isEmpty()) {
                    AILogger.logError("ParentProgramResolver", "[DEBUG] empty content for "
                            + parent.getName());
                    continue;
                }

                AILogger.logError("ParentProgramResolver", "[DEBUG] processing parent "
                        + parent.getName() + ", content length=" + parentCode.length());

                // 复用 AbapIncludeResolver 解析上级的 INCLUDE
                AbapIncludeResolver resolver = new AbapIncludeResolver(parent.getProject());
                AbapIncludeResolver.IncludeContext parentIncludes =
                        resolver.resolveAllIncludes(parentCode);

                String truncatedCode = AbapCodeTruncator.truncate(parentCode, maxContextChars);
                String truncatedIncludes = AbapCodeTruncator.truncate(
                        parentIncludes.buildPromptContext(), maxContextChars);

                result.addParent(parent.getName(), truncatedCode, truncatedIncludes, depth + 1);
                AILogger.logError("ParentProgramResolver", "[DEBUG] added parent "
                        + parent.getName() + " at depth " + (depth + 1));

                // 递归向上
                collectParentsRecursively(parent, depth + 1, visited, result);
            } catch (Exception e) {
                AILogger.logError("ParentProgramResolver", "[DEBUG] error processing "
                        + parent.getName() + ": " + e.getMessage());
                // 读取失败,跳过此 parent
            }
        }
    }

    // ==================== 文件搜索 ====================

    /**
     * 当前项目优先,空则工作区兜底。
     */
    private List<IFile> findParentFilesWithFallback(String includeName, IProject preferred) {
        List<IFile> results = new ArrayList<>();

        // 1. 当前项目优先
        if (preferred != null && preferred.isAccessible()) {
            searchIncludeCallers(preferred, includeName, results);
        }
        if (!results.isEmpty()) return results;

        // 2. 工作区兜底
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IProject[] all = root.getProjects();
        for (IProject p : all) {
            if (p.equals(preferred)) continue;
            if (!p.isAccessible()) continue;
            searchIncludeCallers(p, includeName, results);
        }
        return results;
    }

    /**
     * 在指定 project 内搜索 content 包含 `INCLUDE <includeName>` 的文件。
     * 支持 .abap/.abapinc/.txt 扩展名以及无扩展名的文件(SAP ADT 环境)。
     */
    private void searchIncludeCallers(IProject container, String includeName,
                                       List<IFile> results) {
        // 构造精确匹配的正则: INCLUDE <includeName>. 或 INCLUDE <includeName> IF FOUND.
        Pattern exact = Pattern.compile(
                "^\\s*INCLUDE\\s+" + Pattern.quote(includeName)
                        + "(?:\\s+IF\\s+FOUND)?\\s*\\.\\s*$",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

        final int[] scannedCount = {0};
        final int[] matchedCount = {0};

        try {
            container.accept(resource -> {
                if (resource instanceof IFile) {
                    IFile f = (IFile) resource;
                    String name = f.getName().toUpperCase();
                    // 支持 .ABAP, .ABAPINC, .TXT 以及无扩展名文件(SAP ADT 环境)
                    if (isAbapSourceFile(name)) {
                        scannedCount[0]++;
                        try {
                            String content = AbapIncludeResolver.readFileContent(f);
                            if (content != null && exact.matcher(content).find()) {
                                results.add(f);
                                matchedCount[0]++;
                            }
                        } catch (Exception e) {
                            // 读取失败,跳过
                        }
                    }
                }
                return true;
            });
        } catch (CoreException e) {
            // 容器不可访问,忽略
        }

        AILogger.logError("ParentProgramResolver", "[DEBUG] searchIncludeCallers: scanned=" + scannedCount[0]
                + " abap files, matched=" + matchedCount[0]
                + " for INCLUDE " + includeName + " in " + container.getName());
    }

    // ==================== 辅助方法 ====================

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
        // 排除常见的非 ABAP 扩展名
        int dot = upperName.lastIndexOf('.');
        if (dot < 0) return true;  // 无扩展名,视为 ABAP
        // 排除明显不是 ABAP 的扩展名
        String ext = upperName.substring(dot);
        if (ext.equals(".JAVA") || ext.equals(".XML") || ext.equals(".JSON")
                || ext.equals(".PROPERTIES") || ext.equals(".HTML")
                || ext.equals(".CSS") || ext.equals(".JS")) {
            return false;
        }
        // 其他扩展名也检查一下,如果文件名中包含 ABAP 关键字模式
        return isLikelyAbapName(upperName);
    }

    /**
     * 通过文件名模式判断是否可能为 ABAP 文件。
     * ABAP 程序通常以 Y/Z 开头,或包含特定命名模式。
     */
    private static boolean isLikelyAbapName(String upperName) {
        // 去掉扩展名
        int dot = upperName.lastIndexOf('.');
        String base = dot > 0 ? upperName.substring(0, dot) : upperName;
        // ABAP 程序命名模式: Y/Z 开头,或包含 SAP 特定模式
        if (base.startsWith("Y") || base.startsWith("Z")) return true;
        if (base.startsWith("SAP") || base.startsWith("R")) return true;
        // 如果文件名较长,可能是 ABAP 程序
        return base.length() >= 8;
    }

    /**
     * 去掉文件扩展名,返回纯名称(大写)。
     * 例如: Z_INCL1.abap -> Z_INCL1
     */
    static String stripExtension(String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        int dot = fileName.lastIndexOf('.');
        String base = (dot > 0) ? fileName.substring(0, dot) : fileName;
        return base.toUpperCase();
    }

    /**
     * 获取文件的规范路径(用于环检测)。
     * 使用 IResource.getRawLocationURI() 而非 getLocation(),兼容链接资源。
     */
    private static String toCanonical(IFile file) {
        try {
            java.net.URI uri = file.getRawLocationURI();
            if (uri != null) return uri.toString();
        } catch (Exception e) {
            // fall through
        }
        try {
            return file.getFullPath().toString();
        } catch (Exception e) {
            return null;
        }
    }
}
