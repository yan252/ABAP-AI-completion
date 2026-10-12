package com.sap.abap.ai.completion.ui;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.stream.Stream;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;

/**
 * Self-contained abap-cli runtime provisioning.
 *
 * <p>The plugin ships the full abap-cli runtime (its {@code node_modules} tree
 * plus the project-specific {@code extensions/*.mjs} commands and the
 * {@code _prefs_snapshot.mjs} / {@code _probe_direct.mjs} helper scripts)
 * inside the bundle under {@code /abap-cli/}. Customers therefore do not need
 * to install abap-cli themselves — only a Node.js runtime (18+) on PATH.</p>
 *
 * <p>Node cannot execute scripts straight out of the (packed) plugin JAR, so on
 * first use the runtime is extracted once into the bundle's writable workspace
 * state area ({@code .metadata/.plugins/<plugin>/abap-cli-stage/}). The
 * extracted directory has exactly the same layout as the old developer-only
 * {@code _abap-cli-stage} checkout; per-import data ({@code repos/},
 * {@code .abap.json}, result files) lives alongside it. A stamp file triggers
 * re-extraction after a plugin update.</p>
 *
 * <p>Two optional overrides exist for development only:</p>
 * <ul>
 *   <li>{@code abap.cli.stage.dir} system property or {@code ABAP_CLI_STAGE_DIR}
 *       env var — use an existing stage checkout as-is (no extraction);</li>
 *   <li>{@code abap.cli.node} system property or {@code ABAP_CLI_NODE} env var —
 *       explicit node executable (otherwise plain {@code node} from PATH).</li>
 * </ul>
 */
public final class AbapCliRuntime {

    /** Bundle entry folder carrying the embedded runtime (leading + trailing slash). */
    static final String BUNDLE_PREFIX = "/abap-cli/";

    /** Name of the extracted stage directory inside the bundle state area. */
    static final String STAGE_DIR_NAME = "abap-cli-stage";

    /** Stamp file written after a successful extraction. */
    static final String STAMP_FILE = ".runtime-stamp";

    /** Optional override of the stage location (development machines). */
    static final String OVERRIDE_STAGE_PROPERTY = "abap.cli.stage.dir";
    static final String OVERRIDE_STAGE_ENV = "ABAP_CLI_STAGE_DIR";

    /** Optional override of the node executable. */
    static final String OVERRIDE_NODE_PROPERTY = "abap.cli.node";
    static final String OVERRIDE_NODE_ENV = "ABAP_CLI_NODE";

    private static volatile Path cachedStage;

    private AbapCliRuntime() {
    }

    // ====================================================================
    // Stage provisioning
    // ====================================================================

    /**
     * Return the on-disk abap-cli stage root, extracting the bundled runtime on
     * first use or after a plugin update.
     *
     * @throws IOException if the state area cannot be created or the bundled
     *                     resources cannot be extracted
     */
    public static Path ensureStage() throws IOException {
        Path stage = cachedStage;
        if (stage != null && Files.isDirectory(stage)) {
            return stage;
        }
        synchronized (AbapCliRuntime.class) {
            if (cachedStage != null && Files.isDirectory(cachedStage)) {
                return cachedStage;
            }

            Path override = configuredOverrideStage();
            if (override != null) {
                cachedStage = override.toAbsolutePath().normalize();
                return cachedStage;
            }

            Path base;
            try {
                base = Activator.getDefault().getStateLocation().toFile().toPath();
            } catch (IllegalStateException e) {
                throw new IOException(
                        "Plugin state location unavailable; cannot provision abap-cli runtime.", e);
            }
            Path target = base.resolve(STAGE_DIR_NAME);
            Files.createDirectories(target);

            if (!isExtracted(target, expectedStamp())) {
                extractBundledRuntime(target);
            }
            cachedStage = target.toAbsolutePath().normalize();
            return cachedStage;
        }
    }

    /**
     * Stamp written into the extracted runtime: the plugin bundle version.
     * After every plugin update the version changes, so the runtime is
     * re-extracted automatically.
     */
    private static String expectedStamp() {
        Bundle b = Platform.getBundle(Activator.PLUGIN_ID);
        return b != null ? b.getVersion().toString() : "unknown";
    }

    /**
     * Explicit external stage override (checkout used during development), or
     * {@code null} when the bundled runtime should be used.
     */
    private static Path configuredOverrideStage() {
        String v = System.getProperty(OVERRIDE_STAGE_PROPERTY);
        if (v == null || v.trim().isEmpty()) {
            v = System.getenv(OVERRIDE_STAGE_ENV);
        }
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        Path p = Paths.get(v.trim());
        return Files.isDirectory(p) ? p.toAbsolutePath().normalize() : null;
    }

    /** {@code true} when {@code target} holds a runtime matching {@code stamp}. */
    private static boolean isExtracted(Path target, String stamp) {
        Path stampFile = target.resolve(STAMP_FILE);
        if (!Files.isRegularFile(stampFile)) {
            return false;
        }
        try {
            String v = new String(Files.readAllBytes(stampFile), StandardCharsets.UTF_8).trim();
            if (!stamp.equals(v)) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
        // Guard against a partial extraction or manual deletion.
        return Files.isRegularFile(target.resolve(
                AbapGitImportSupport.ABAP_CLI_INDEX_REL));
    }

    /**
     * Copy every {@code /abap-cli/**} bundle entry to {@code target}. The two
     * managed directories ({@code node_modules}, {@code extensions}) are wiped
     * first so removed/renamed files never linger; per-import data
     * ({@code repos/}, {@code .abap.json}, {@code _*_result.txt}) is kept.
     */
    private static void extractBundledRuntime(Path target) throws IOException {
        Bundle bundle = Platform.getBundle(Activator.PLUGIN_ID);
        if (bundle == null) {
            throw new IOException("Plugin bundle " + Activator.PLUGIN_ID + " is not active.");
        }

        System.out.println("[AbapCliRuntime] extracting bundled abap-cli runtime -> " + target);
        deleteManaged(target.resolve("node_modules"));
        deleteManaged(target.resolve("extensions"));
        Files.createDirectories(target);

        Enumeration<URL> entries = bundle.findEntries("/abap-cli", "*", true);
        if (entries == null || !entries.hasMoreElements()) {
            throw new IOException(
                    "Bundled abap-cli runtime not found at " + BUNDLE_PREFIX
                            + " — the plugin was built without it (run build.ps1).");
        }

        int copied = 0;
        while (entries.hasMoreElements()) {
            URL url = entries.nextElement();
            String path = url.getPath();
            if (path == null || !path.startsWith(BUNDLE_PREFIX)) {
                continue;
            }
            String rel = URLDecoder.decode(path.substring(BUNDLE_PREFIX.length()),
                    StandardCharsets.UTF_8);
            if (rel.isEmpty() || STAMP_FILE.equals(rel)) {
                continue;
            }
            Path out = target.resolve(rel);
            // Directory entries arrive with a trailing slash.
            if (path.endsWith("/")) {
                Files.createDirectories(out);
                continue;
            }
            Files.createDirectories(out.getParent());
            try (InputStream in = url.openStream()) {
                if (in == null) {
                    throw new IOException("Cannot read bundled resource: " + path);
                }
                Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            }
            copied++;
        }

        String stamp = expectedStamp();
        Files.write(target.resolve(STAMP_FILE),
                stamp.getBytes(StandardCharsets.UTF_8));
        System.out.println("[AbapCliRuntime] extraction complete, " + copied + " files, stamp="
                + stamp);
    }

    /** Delete a managed runtime subdirectory (best effort, never fatal). */
    private static void deleteManaged(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // A locked file (e.g. antivirus scan) is retried on the next
                    // start; extraction overwrites regular files anyway.
                }
            });
        } catch (IOException e) {
            System.err.println("[AbapCliRuntime] could not clean " + dir + ": " + e.getMessage());
        }
    }

    // ====================================================================
    // Node executable
    // ====================================================================

    /**
     * Node executable used for every abap-cli process: the explicit override if
     * configured, otherwise plain {@code node} resolved from PATH.
     */
    public static String nodeCommand() {
        String v = System.getProperty(OVERRIDE_NODE_PROPERTY);
        if (v == null || v.trim().isEmpty()) {
            v = System.getenv(OVERRIDE_NODE_ENV);
        }
        return (v == null || v.trim().isEmpty()) ? "node" : v.trim();
    }
}
