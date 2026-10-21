package com.sap.abap.ai.completion.ui;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
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
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;

/**
 * abap-cli runtime provisioning.
 *
 * <p>Since v1.1.49 the plugin no longer ships the abap-cli runtime inside the
 * bundle. Instead the full runtime (its {@code node_modules} tree plus the
 * project-specific {@code extensions/*.mjs} commands and the
 * {@code _prefs_snapshot.mjs} / {@code _probe_direct.mjs} helper scripts) is
 * published with every plugin release as a GitHub Release asset
 * ({@code abap-cli-runtime-<version>.zip} on tag {@code v<version>} of
 * {@link #GITHUB_REPO_URL}; see {@link #stageZipUrl()}). On first use the
 * plugin offers a one-click install ({@link #installFromGitHub(IProgressMonitor)})
 * that downloads exactly that asset into the
 * bundle's writable workspace state area
 * ({@code .metadata/.plugins/<plugin>/abap-cli-stage/}). Only a Node.js
 * runtime (18+) on PATH is additionally required.</p>
 *
 * <p>Node cannot execute scripts straight out of a packed archive, so the
 * extracted directory has exactly the same layout as the old developer-only
 * {@code _abap-cli-stage} checkout; per-import data ({@code repos/},
 * {@code .abap.json}, result files) lives alongside it. A stamp file triggers
 * re-installation after a plugin update (the stamp holds the plugin version,
 * keeping the downloaded runtime aligned with the plugin release).</p>
 *
 * <p>Two optional overrides exist for development only:</p>
 * <ul>
 *   <li>{@code abap.cli.stage.dir} system property or {@code ABAP_CLI_STAGE_DIR}
 *       env var — use an existing stage checkout as-is (no download);</li>
 *   <li>{@code abap.cli.node} system property or {@code ABAP_CLI_NODE} env var —
 *       explicit node executable (otherwise plain {@code node} from PATH).</li>
 * </ul>
 *
 * <p>For backward compatibility a runtime embedded under {@code /abap-cli/}
 * in the bundle (old build style) is still extracted when present.</p>
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

    /** GitHub repository hosting the downloadable abap-cli runtime. */
    public static final String GITHUB_REPO_URL = "https://github.com/yan252/ABAP-AI-completion";

    /**
     * GitHub Releases page listing the {@code abap-cli-runtime-&lt;version&gt;.zip}
     * assets (one per plugin release, tag {@code v<plugin version>}).
     */
    public static final String GITHUB_RELEASES_URL = GITHUB_REPO_URL + "/releases";

    /**
     * Build the download URL of the runtime zip: a GitHub Release asset named
     * {@code abap-cli-runtime-<plugin version>.zip} attached to the release tag
     * {@code v<plugin version>}. Deriving the URL from the bundle version keeps
     * the downloaded runtime (especially the {@code extensions/*.mjs} commands)
     * exactly in sync with the running plugin.
     */
    public static String stageZipUrl() {
        return GITHUB_REPO_URL + "/releases/download/v" + expectedStamp()
                + "/abap-cli-runtime-" + expectedStamp() + ".zip";
    }

    /** Message shown (and logged) when the runtime is not installed yet. */
    static final String NOT_INSTALLED_MESSAGE =
            "The abap-cli runtime is not installed yet. It is required for the SAP"
                    + "\nconnection test and the template import and is no longer bundled"
                    + "\nwith the plugin."
                    + "\n\nIt is installed with one click from the plugin (SAP Connection"
                    + "\nConfig 'Test Connection' or a Templates menu import), or manually:"
                    + "\ndownload 'abap-cli-runtime-" + expectedStamp() + ".zip' from\n  "
                    + GITHUB_RELEASES_URL
                    + "\nextract it and point the system property 'abap.cli.stage.dir'"
                    + "\n(or environment variable ABAP_CLI_STAGE_DIR) at the extracted"
                    + "\nfolder, then restart Eclipse.";

    private static volatile Path cachedStage;

    /**
     * Thrown by {@link #ensureStage()} when the runtime is neither installed in
     * the workspace state area nor embedded in the plugin bundle — the caller
     * should offer the one-click install ({@link #installFromGitHub}).
     */
    public static final class NotInstalledException extends IOException {
        private static final long serialVersionUID = 1L;

        NotInstalledException(String message) {
            super(message);
        }
    }

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
                if (!hasBundledRuntime()) {
                    // No embedded runtime (standard build since v1.1.49): the
                    // caller should offer the one-click GitHub install.
                    throw new NotInstalledException(NOT_INSTALLED_MESSAGE);
                }
                extractBundledRuntime(target);
            }
            cachedStage = target.toAbsolutePath().normalize();
            return cachedStage;
        }
    }

    /**
     * {@code true} when the bundle carries an embedded runtime under
     * {@code /abap-cli/} (old build style, still supported for development).
     */
    private static boolean hasBundledRuntime() {
        Bundle bundle = Platform.getBundle(Activator.PLUGIN_ID);
        if (bundle == null) {
            return false;
        }
        Enumeration<URL> entries = bundle.findEntries("/abap-cli", "*", true);
        return entries != null && entries.hasMoreElements();
    }

    /**
     * Non-invasive installation check: {@code true} when a usable runtime is
     * present (external stage override, embedded bundle runtime, or an
     * extracted stage matching the current plugin version in the workspace
     * state area). Never downloads or extracts anything — used by the
     * "Download abap-cli" button to decide between "already installed" and
     * offering the one-click install.
     */
    public static boolean isInstalled() {
        if (configuredOverrideStage() != null) {
            return true;
        }
        if (hasBundledRuntime()) {
            return true;
        }
        try {
            Path base = Activator.getDefault().getStateLocation().toFile().toPath();
            return isExtracted(base.resolve(STAGE_DIR_NAME), expectedStamp());
        } catch (Exception e) {
            return false;
        }
    }

    // ====================================================================
    // One-click install from GitHub
    // ====================================================================

    /**
     * Download the runtime zip from {@link #stageZipUrl()} (GitHub Release
     * asset matching the plugin version) and
     * install it into the workspace state area.
     *
     * <p>The managed directories ({@code node_modules}, {@code extensions}) are
     * wiped first so removed/renamed files never linger; per-import data
     * ({@code repos/}, {@code .abap.json}, {@code _*_result.txt}) is kept. On
     * success the stamp file is written with the current plugin version and the
     * internal stage cache is refreshed.</p>
     *
     * @param monitor progress monitor (may be {@code null}); cancelling raises
     *                an {@code IOException} with a "cancelled" message
     * @throws IOException on download/extraction failure or cancellation
     */
    public static void installFromGitHub(IProgressMonitor monitor) throws IOException {
        IProgressMonitor pm = monitor != null ? monitor : new NullProgressMonitor();
        Path base;
        try {
            base = Activator.getDefault().getStateLocation().toFile().toPath();
        } catch (IllegalStateException e) {
            throw new IOException(
                    "Plugin state location unavailable; cannot install abap-cli runtime.", e);
        }
        Path target = base.resolve(STAGE_DIR_NAME);

        synchronized (AbapCliRuntime.class) {
            Path tmpZip;
            try {
                tmpZip = Files.createTempFile("abap-cli-stage-", ".zip");
            } catch (IOException e) {
                throw new IOException("Cannot create a temporary download file.", e);
            }
            try {
                downloadStageZip(tmpZip, pm);
                pm.subTask("Extracting abap-cli runtime...");
                deleteManaged(target.resolve("node_modules"));
                deleteManaged(target.resolve("extensions"));
                Files.createDirectories(target);
                extractStageZip(tmpZip, target, pm);

                String stamp = expectedStamp();
                Files.write(target.resolve(STAMP_FILE),
                        stamp.getBytes(StandardCharsets.UTF_8));
                cachedStage = target.toAbsolutePath().normalize();
                System.out.println("[AbapCliRuntime] runtime installed from GitHub, stamp="
                        + stamp);
            } catch (IOException e) {
                cachedStage = null;
                throw e;
            } finally {
                try {
                    Files.deleteIfExists(tmpZip);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /** Ensures the OS proxy configuration is used at most once per JVM. */
    private static volatile boolean systemProxiesEnabled;

    /**
     * Ask the JDK to read the Windows proxy configuration (registry /
     * auto-config). Must happen before the first use of the default proxy
     * selector, hence the reset via {@code setDefault(null)}.
     */
    private static void enableSystemProxies() {
        if (systemProxiesEnabled) {
            return;
        }
        synchronized (AbapCliRuntime.class) {
            if (systemProxiesEnabled) {
                return;
            }
            try {
                if (System.getProperty("java.net.useSystemProxies") == null) {
                    System.setProperty("java.net.useSystemProxies", "true");
                }
                java.net.ProxySelector.setDefault(null); // force re-initialization
                java.net.ProxySelector.getDefault();
            } catch (Throwable t) {
                System.out.println("[AbapCliRuntime] could not enable system proxies: " + t);
            }
            systemProxiesEnabled = true;
        }
    }

    /**
     * Download the runtime zip with progress reporting. Strategy:
     * <ol>
     *   <li>plain Java HTTPS (system proxies enabled);</li>
     *   <li>on any failure — typically HTTP 407 from a corporate proxy that
     *       demands the Windows login credentials, which the JDK cannot supply
     *       transparently — retry via PowerShell WebClient using the system
     *       proxy with the current user's default credentials (same behavior
     *       as the web browser);</li>
     *   <li>finally, retry via Windows BITS
     *       ({@code Start-BitsTransfer}).</li>
     * </ol>
     * Only when all three fail the combined error is thrown.
     */
    private static void downloadStageZip(Path dest, IProgressMonitor pm) throws IOException {
        String urlSpec = stageZipUrl();

        IOException javaFailure;
        try {
            downloadViaJava(urlSpec, dest, pm);
            return;
        } catch (IOException e) {
            javaFailure = e;
        }
        System.out.println("[AbapCliRuntime] direct Java download failed ("
                + javaFailure.getMessage().replace('\n', ' ')
                + ") - trying Windows fallback with system proxy/login credentials");
        pm.subTask("Direct download blocked (corporate proxy?) -"
                + " retrying via Windows system proxy...");

        IOException psFailure;
        try {
            downloadViaPowerShell(urlSpec, dest, 300);
            System.out.println("[AbapCliRuntime] downloaded via PowerShell WebClient"
                    + " (system proxy + default credentials)");
            return;
        } catch (IOException e) {
            psFailure = e;
        }
        System.out.println("[AbapCliRuntime] PowerShell download failed ("
                + psFailure.getMessage().replace('\n', ' ') + ") - trying BITS");

        IOException bitsFailure;
        try {
            downloadViaPowerShell(urlSpec, dest, 600, true);
            System.out.println("[AbapCliRuntime] downloaded via BITS");
            return;
        } catch (IOException e) {
            bitsFailure = e;
        }

        throw new IOException("Download failed from " + urlSpec
                + "\n\nDirect (Java): " + firstLine(javaFailure.getMessage())
                + "\nPowerShell (system proxy): " + firstLine(psFailure.getMessage())
                + "\nBITS: " + firstLine(bitsFailure.getMessage())
                + "\n\nHint: HTTP 407 means a corporate proxy requires your Windows"
                + " login credentials. The fallbacks above use them - if all failed,"
                + "\nyour proxy may block non-browser downloads entirely. In that case"
                + " download the zip manually in the browser from\n  "
                + GITHUB_RELEASES_URL, javaFailure);
    }

    /** First line of a (possibly multi-line) exception message. */
    private static String firstLine(String msg) {
        if (msg == null) {
            return "(no message)";
        }
        int i = msg.indexOf('\n');
        return i >= 0 ? msg.substring(0, i) : msg;
    }

    /** Plain Java HTTPS download (also handles the 404 asset hint). */
    private static void downloadViaJava(String urlSpec, Path dest, IProgressMonitor pm)
            throws IOException {
        enableSystemProxies();
        pm.subTask("Downloading abap-cli runtime from GitHub...");
        HttpURLConnection conn;
        try {
            URL url = URI.create(urlSpec).toURL();
            conn = (HttpURLConnection) url.openConnection();
        } catch (IOException e) {
            throw new IOException("Cannot open " + urlSpec + ": " + e.getMessage(), e);
        }
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(60_000);
        conn.setInstanceFollowRedirects(true);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                String hint = "";
                if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                    hint = "\n(The runtime asset for version " + expectedStamp()
                            + " is not published yet. Please upload"
                            + "\n 'abap-cli-runtime-" + expectedStamp() + ".zip'"
                            + " as an asset of the release tag 'v" + expectedStamp()
                            + "' at\n " + GITHUB_RELEASES_URL + ")";
                } else if (code == HttpURLConnection.HTTP_PROXY_AUTH) {
                    hint = "\n(HTTP 407 Proxy Authentication Required - corporate"
                            + " proxy detected)";
                }
                throw new IOException("Download failed: HTTP " + code + " from "
                        + urlSpec + hint);
            }
            long total = conn.getContentLengthLong();
            pm.beginTask("Downloading abap-cli runtime from GitHub...",
                    total > 0 ? (int) Math.min(total / 1024, Integer.MAX_VALUE)
                            : IProgressMonitor.UNKNOWN);
            try (InputStream in = conn.getInputStream();
                    java.io.OutputStream out = Files.newOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (pm.isCanceled()) {
                        throw new IOException("Installation cancelled by user.");
                    }
                    out.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        pm.worked((int) (n / 1024));
                    }
                }
                System.out.println("[AbapCliRuntime] downloaded " + (done / 1024)
                        + " KB from " + urlSpec);
            }
        } finally {
            conn.disconnect();
        }
        verifyZipFile(dest);
    }

    /** PowerShell WebClient download (system proxy + default credentials). */
    private static void downloadViaPowerShell(String urlSpec, Path dest, int timeoutSec)
            throws IOException {
        downloadViaPowerShell(urlSpec, dest, timeoutSec, false);
    }

    /**
     * Windows-native download via PowerShell. Uses {@code System.Net.WebClient}
     * which reads the Windows proxy configuration (including auto-config) and,
     * with {@code CredentialCache.DefaultCredentials}, authenticates with the
     * logged-in domain user - the same way the web browser does. With
     * {@code useBits} the newer {@code Start-BitsTransfer} cmdlet is used
     * instead (robust, resumable, proxy-SSO aware).
     */
    private static void downloadViaPowerShell(String urlSpec, Path dest, int timeoutSec,
            boolean useBits) throws IOException {
        try {
            Files.deleteIfExists(dest);
        } catch (IOException ignored) {
        }
        String psScript = useBits
                ? "Start-BitsTransfer -Source '" + urlSpec + "' -Destination '"
                        + dest.toString().replace("'", "''") + "'"
                : "$ProgressPreference='SilentlyContinue';"
                        + "$wc=New-Object System.Net.WebClient;"
                        + "$wc.Proxy.Credentials=[System.Net.CredentialCache]::DefaultCredentials;"
                        + "$wc.DownloadFile('" + urlSpec + "','"
                        + dest.toString().replace("'", "''") + "')";
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
                "Bypass", "-Command", psScript);
        pb.redirectErrorStream(true);
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException("Cannot start powershell: " + e.getMessage(), e);
        }
        StringBuilder output = new StringBuilder();
        try (InputStream in = proc.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) >= 0) {
                output.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
        }
        try {
            if (!proc.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                throw new IOException("powershell timed out after " + timeoutSec + " s");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted.", e);
        }
        if (proc.exitValue() != 0) {
            String out = output.toString().trim();
            throw new IOException("powershell exit code " + proc.exitValue()
                    + (out.isEmpty() ? "" : ": " + firstLine(out)));
        }
        verifyZipFile(dest);
    }

    /** {@code true} when the file exists, is not trivially small and starts with the ZIP magic. */
    private static void verifyZipFile(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("Downloaded file is missing: " + file);
        }
        long size = Files.size(file);
        if (size < 1024) {
            throw new IOException("Downloaded file is too small (" + size + " bytes).");
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = new byte[2];
            if (in.read(magic) != 2 || magic[0] != 'P' || magic[1] != 'K') {
                throw new IOException("Downloaded file is not a zip archive"
                        + " (a proxy error page?).");
            }
        }
    }

    /**
     * Extract the runtime zip (built by build.ps1; its root already contains
     * {@code node_modules/}, {@code extensions/} and the {@code *.mjs} scripts)
     * directly into {@code target}. Guards against path traversal entries.
     */
    private static void extractStageZip(Path zip, Path target, IProgressMonitor pm)
            throws IOException {
        Path targetNorm = target.toAbsolutePath().normalize();
        try (ZipInputStream zin = new ZipInputStream(
                new BufferedInputStream(Files.newInputStream(zip), 128 * 1024),
                StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (pm.isCanceled()) {
                    throw new IOException("Installation cancelled by user.");
                }
                String name = e.getName();
                if (name == null || name.isEmpty()) {
                    continue;
                }
                // Normalize zip separators (some zip tools write backslashes).
                name = name.replace('\\', '/');
                Path out = targetNorm.resolve(name).normalize();
                if (!out.startsWith(targetNorm)) {
                    continue; // zip-slip guard
                }
                if (name.endsWith("/")) {
                    Files.createDirectories(out);
                    continue;
                }
                Files.createDirectories(out.getParent());
                Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!Files.isRegularFile(targetNorm.resolve(
                AbapGitImportSupport.ABAP_CLI_INDEX_REL))) {
            throw new IOException(
                    "The downloaded archive does not contain a valid abap-cli runtime ('"
                            + AbapGitImportSupport.ABAP_CLI_INDEX_REL + "' missing)."
                            + "\nPlease verify that the asset 'abap-cli-runtime-"
                            + expectedStamp() + ".zip' of release 'v" + expectedStamp()
                            + "' at\n  " + GITHUB_RELEASES_URL
                            + "\nis complete.");
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
