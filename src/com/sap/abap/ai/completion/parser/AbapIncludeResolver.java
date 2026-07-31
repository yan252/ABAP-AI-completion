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
     */
    public String resolveIncludeCode(String includeName) {
        // Try to find the include file in the current project
        // Common ABAP naming: Y*_INCL or Z*_INCL, or any file containing the include name
        List<IFile> candidates = findIncludeFiles(includeName);

        for (IFile file : candidates) {
            try {
                return readFileContent(file);
            } catch (Exception e) {
                // continue searching
            }
        }
        return null;
    }

    /**
     * Resolves ALL includes in the source and returns a map of include name -> code.
     */
    public IncludeContext resolveAllIncludes(String sourceCode) {
        IncludeContext context = new IncludeContext(sourceCode);
        List<String> includes = findIncludes(sourceCode);

        for (String includeName : includes) {
            String code = resolveIncludeCode(includeName);
            if (code != null) {
                context.addInclude(includeName, code);
            }
        }
        return context;
    }

    private List<IFile> findIncludeFiles(String includeName) {
        List<IFile> results = new ArrayList<>();

        if (project != null) {
            searchInContainer(project, includeName, results);
        }

        // Also search workspace root
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        for (IProject p : projects) {
            if (!p.equals(project) && p.isAccessible()) {
                searchInContainer(p, includeName, results);
            }
        }

        return results;
    }

    private void searchInContainer(IProject container, String includeName, List<IFile> results) {
        try {
            container.accept(resource -> {
                if (resource instanceof IFile) {
                    IFile file = (IFile) resource;
                    String fileName = file.getName().toUpperCase();
                    // Match files containing the include name (with common ABAP extensions)
                    if (fileName.contains(includeName)
                            && (fileName.endsWith(".ABAP")
                                    || fileName.endsWith(".TXT")
                                    || fileName.equals(includeName + ".ABAP")
                                    || fileName.equals(includeName + ".TXT"))) {
                        results.add(file);
                    }
                }
                return true;
            });
        } catch (CoreException e) {
            // ignore inaccessible containers
        }
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
