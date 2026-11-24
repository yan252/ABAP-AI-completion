package com.sap.abap.ai.completion.objectsearch;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.ILog;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.osgi.framework.Bundle;

import com.sap.abap.ai.completion.Activator;

/**
 * Opens a search hit through the ADT navigation service.
 *
 * <p>The REST URI of an object type is not hard coded (and differs between
 * releases and systems): {@code TABL/DT} for instance is served under a
 * discovery-defined base URI, and on systems without an Eclipse table editor
 * ADT opens tables in SAP GUI instead. The URI is therefore resolved the same
 * way ADT resolves it — through the object type registry plus ADT Discovery
 * ({@code BlueObjectTypeUtil.getUriFromGlobalType}) — and every candidate is
 * verified with the ADT URI mapping service before navigation, because ADT
 * shows a modal "is not installed" dialog for every unmappable URI.</p>
 */
final class AbapObjectNavigator {

    private static final ILog LOG = Platform.getLog(Platform.getBundle(Activator.PLUGIN_ID));

    private static final String BUNDLE_ADT_CORE_UI = "com.sap.adt.tools.core.ui";
    private static final String CLS_NAVIGATION_FACTORY =
            "com.sap.adt.tools.core.ui.navigation.AdtNavigationServiceFactory";
    private static final String CLS_CORE_FACTORY =
            "com.sap.adt.tools.core.model.adtcore.IAdtCoreFactory";
    private static final String CLS_OBJECT_REFERENCE =
            "com.sap.adt.tools.core.model.adtcore.IAdtObjectReference";

    private static final String BUNDLE_SAPGUI_UI = "com.sap.adt.sapgui.ui";
    private static final String CLS_SAPGUI_UTILITY_FACTORY =
            "com.sap.adt.sapgui.ui.editors.AdtSapGuiEditorUtilityFactory";
    private static final String CLS_SAPGUI_UTILITY =
            "com.sap.adt.sapgui.ui.editors.IAdtSapGuiEditorUtility";

    private static final String BUNDLE_TOOLS_CORE = "com.sap.adt.tools.core";
    private static final String CLS_PROJECT_FACTORY =
            "com.sap.adt.tools.core.project.AdtProjectServiceFactory";
    private static final String CLS_PROJECT_SERVICE =
            "com.sap.adt.tools.core.project.IAbapProjectService";

    private static final String BUNDLE_BLUES_CORE = "com.sap.adt.blues.core";
    private static final String CLS_BLUE_TYPES =
            "com.sap.adt.blues.core.utils.BlueObjectTypeUtil";

    private static final String BUNDLE_TOOLS_CORE_BASE = "com.sap.adt.tools.core.base";
    private static final String CLS_URI_MAPPING_FACTORY =
            "com.sap.adt.tools.core.urimapping.AdtUriMappingServiceFactory";
    private static final String CLS_URI_MAPPING_SERVICE =
            "com.sap.adt.tools.core.urimapping.IAdtUriMappingService";
    private static final String CLS_URI_MAPPING_CONTEXT =
            "com.sap.adt.tools.core.urimapping.IUriMappingContext";
    private static final String CLS_URI_MAPPING_CONTEXT_IMPL =
            "com.sap.adt.tools.core.urimapping.UriMappingContext";

    private AbapObjectNavigator() {
    }

    /** Opens the object in its ADT editor; returns false when that failed. */
    static boolean open(AbapObjectItem item) {
        if (item == null || item.getProject() == null) {
            return false;
        }
        try {
            Class<?> factoryClass = loadClass(BUNDLE_ADT_CORE_UI, CLS_NAVIGATION_FACTORY);
            if (factoryClass == null) {
                log("open(" + item.getName() + "): ADT navigation service not available");
                return false;
            }
            Object service = factoryClass.getMethod("createNavigationService").invoke(null);
            if (service == null) {
                return false;
            }
            Method navigate = findNavigateMethod(service.getClass());
            if (navigate == null) {
                log("open(" + item.getName() + "): navigate method not found");
                return false;
            }

            String type = item.getType() == null ? "" : item.getType().toUpperCase();
            if (!type.startsWith("VIEW/")) {
                for (String uri : candidateUris(item)) {
                    if (!isMappable(item.getProject(), uri)) {
                        log("open(" + item.getName() + "): uri not mappable, skipped: " + uri);
                        continue;
                    }
                    IEditorPart editor = navigate(service, navigate, item.getProject(),
                            item.getName(), item.getType(), uri);
                    if (editor != null) {
                        IWorkbenchPage page = editor.getSite() == null
                                ? null : editor.getSite().getPage();
                        if (page != null) {
                            page.activate(editor);
                        }
                        editor.setFocus();
                        return true;
                    }
                    log("open(" + item.getName() + "): no editor for uri=" + uri);
                }
            }
            // Views are opened in SAP GUI on every system; nothing could be
            // opened in an Eclipse editor (typical on systems that have no
            // Eclipse editor for the object type, e.g. tables on ECC). Open it
            // in SAP GUI, the same way ADT's own "Open in SAP GUI" does.
            IEditorPart gui = openInSapGui(item);
            if (gui != null) {
                IWorkbenchPage page = gui.getSite() == null ? null : gui.getSite().getPage();
                if (page != null) {
                    page.activate(gui);
                }
                gui.setFocus();
                return true;
            }
            log("open(" + item.getName() + ") failed (type=" + item.getType() + ")");
            return false;
        } catch (Throwable t) {
            log("open(" + item.getName() + ") failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * ADT URIs to try for one hit, in order:
     * <ol>
     *   <li>URI resolved by ADT itself (object type registry + discovery),</li>
     *   <li>URI delivered by the search client (or reconstructed),</li>
     *   <li>for database tables the SAP GUI (VIT) URI, which is how ADT opens
     *       tables on systems without an Eclipse table editor.</li>
     * </ol>
     */
    private static List<String> candidateUris(AbapObjectItem item) {
        LinkedHashSet<String> uris = new LinkedHashSet<>();
        String type = item.getType() == null ? "" : item.getType().toUpperCase();
        String name = item.getName() == null ? "" : item.getName();

        String resolved = resolveCanonicalUri(item.getProject(), type, name);
        if (resolved != null && !resolved.isEmpty()) {
            uris.add(resolved);
            uris.add(resolved + "/source/main");
        }

        // DDIC types that are not database tables have no stable REST URI we
        // could construct: the back end hands them to its classical (SAP GUI)
        // handler, which dumps with ASSERTION_FAILED when the URI is not one it
        // knows. Only the URI ADT itself resolved (through the object type
        // registry / discovery) is used for them.
        boolean discoveryOnly = type.startsWith("VIEW/") || type.startsWith("TABL/DS")
                || type.startsWith("DTEL/") || type.startsWith("DOMA/");
        if (!discoveryOnly) {
            String stored = item.getUri();
            if (stored != null && !stored.trim().isEmpty()) {
                uris.add(stored);
            } else {
                String fallback = fallbackUri(item);
                if (fallback != null && !fallback.trim().isEmpty()) {
                    uris.add(fallback);
                }
            }
        }

        if (type.startsWith("TABL/DT")) {
            // /sap/bc/adt/vit/wb/object_type/tabldt/object_name/{name}
            uris.add("/sap/bc/adt/vit/wb/object_type/"
                    + type.replace("/", "").toLowerCase() + "/object_name/" + name);
        }
        uris.remove("");
        return new ArrayList<>(uris);
    }

    /**
     * ADT's own URI for a global workbench type and object name. Uses the
     * discovery based helper of the ADT Blue objects SDK, so the result matches
     * the URI ADT would compute itself; returns {@code null} when the type is
     * not a Blue object type or discovery is unavailable.
     */
    private static String resolveCanonicalUri(IProject project, String type, String name) {
        try {
            Class<?> projectFactory = loadClass(BUNDLE_TOOLS_CORE, CLS_PROJECT_FACTORY);
            Class<?> blueTypes = loadClass(BUNDLE_BLUES_CORE, CLS_BLUE_TYPES);
            if (projectFactory == null || blueTypes == null) {
                return null;
            }
            Object projectService = projectFactory.getMethod("createProjectService").invoke(null);
            if (projectService == null) {
                return null;
            }
            Class<?> serviceClass = Class.forName(CLS_PROJECT_SERVICE, true,
                    projectService.getClass().getClassLoader());
            Object destinationId = serviceClass.getMethod("getDestinationId", IProject.class)
                    .invoke(projectService, project);
            if (!(destinationId instanceof String) || ((String) destinationId).isEmpty()) {
                return null;
            }
            Object uri = blueTypes
                    .getMethod("getUriFromGlobalType", String.class, String.class, String.class)
                    .invoke(null, type, name, destinationId);
            return uri == null ? null : uri.toString();
        } catch (Throwable t) {
            log("resolveCanonicalUri(" + type + ", " + name + ") unavailable: " + t);
            return null;
        }
    }

    /**
     * true when the ADT URI mapping service can map {@code uri} to a workspace
     * resource. ADT shows a modal "is not installed" dialog for URIs it cannot
     * map, so unmappable candidates must be filtered out up front. When the
     * mapping service itself is unavailable, navigation is attempted anyway.
     */
    private static boolean isMappable(IProject project, String uri) {
        try {
            Class<?> factory = loadClass(BUNDLE_TOOLS_CORE_BASE, CLS_URI_MAPPING_FACTORY);
            if (factory == null) {
                return true;
            }
            Object service = factory.getMethod("createUriMappingService").invoke(null);
            if (service == null) {
                return true;
            }
            ClassLoader loader = service.getClass().getClassLoader();
            Class<?> serviceClass = Class.forName(CLS_URI_MAPPING_SERVICE, true, loader);
            Class<?> contextClass = Class.forName(CLS_URI_MAPPING_CONTEXT, true, loader);
            Class<?> contextImpl = Class.forName(CLS_URI_MAPPING_CONTEXT_IMPL, true, loader);
            Object context = contextImpl.getConstructor(IProject.class).newInstance(project);
            Object resource = serviceClass
                    .getMethod("getPlatformResource", contextClass, URI.class)
                    .invoke(service, context, URI.create(uri));
            return resource != null;
        } catch (InvocationTargetException e) {
            // The URI mapping handlers reject foreign URIs with an argument
            // exception; such a URI belongs to a different object type.
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    private static IEditorPart navigate(Object service, Method navigate, IProject project,
            String name, String type, String uri) {
        try {
            Class<?> referenceClass = navigate.getParameterTypes()[1];
            Class<?> coreFactoryClass = Class.forName(CLS_CORE_FACTORY, true,
                    referenceClass.getClassLoader());
            Object coreFactory = coreFactoryClass.getField("eINSTANCE").get(null);
            Object reference = coreFactoryClass.getMethod("createAdtObjectReference")
                    .invoke(coreFactory);
            referenceClass.getMethod("setName", String.class).invoke(reference, name);
            referenceClass.getMethod("setType", String.class).invoke(reference, type);
            referenceClass.getMethod("setUri", String.class).invoke(reference, uri);
            Object result = navigate.invoke(service, project, reference, Boolean.FALSE);
            return result instanceof IEditorPart ? (IEditorPart) result : null;
        } catch (Throwable t) {
            log("navigate(" + name + ", " + type + ", " + uri + ") failed: " + t.getMessage());
            return null;
        }
    }

    private static Method findNavigateMethod(Class<?> serviceClass) {
        for (Method method : serviceClass.getMethods()) {
            if ("navigate".equals(method.getName()) && method.getParameterCount() == 3
                    && IProject.class.equals(method.getParameterTypes()[0])) {
                return method;
            }
        }
        return null;
    }

    /**
     * Opens the object in SAP GUI through ADT's own SAP GUI editor utility
     * ({@code IAdtSapGuiEditorUtility.openEditorForObject} with
     * {@code WB:DISPLAY}) — the same entry point ADT uses for "Open in SAP
     * GUI". This is the fallback for object types that have no Eclipse editor
     * on the current system (database tables on ECC, for instance).
     */
    private static IEditorPart openInSapGui(AbapObjectItem item) {
        try {
            String type = item.getType() == null ? "" : item.getType().toUpperCase();
            Class<?> factoryClass = loadClass(BUNDLE_SAPGUI_UI, CLS_SAPGUI_UTILITY_FACTORY);
            if (factoryClass == null) {
                log("openInSapGui(" + item.getName() + "): SAP GUI integration not available");
                return null;
            }
            Object utility = factoryClass.getMethod("createSapGuiEditorUtility").invoke(null);
            if (utility == null) {
                return null;
            }
            ClassLoader loader = utility.getClass().getClassLoader();
            Class<?> utilityClass = Class.forName(CLS_SAPGUI_UTILITY, true, loader);
            Class<?> referenceClass = Class.forName(CLS_OBJECT_REFERENCE, true, loader);

            Class<?> coreFactoryClass = Class.forName(CLS_CORE_FACTORY, true,
                    referenceClass.getClassLoader());
            Object coreFactory = coreFactoryClass.getField("eINSTANCE").get(null);
            Object reference = coreFactoryClass.getMethod("createAdtObjectReference")
                    .invoke(coreFactory);
            referenceClass.getMethod("setName", String.class).invoke(reference, item.getName());
            referenceClass.getMethod("setType", String.class).invoke(reference, item.getType());
            // The SAP GUI integration of the back end maps the object URI to a
            // transaction, and the accepted URI form depends on the object
            // type: database tables need the VIT URI (a REST URI dumps with
            // "No URI-Mapping defined"), while views are mapped from their ADT
            // REST URI (a VIT URI asserts in the back end GUI handler).
            String uri;
            if (type.startsWith("TABL/DT")) {
                uri = buildVitUri(utility, item);
            } else {
                uri = item.getUri();
            }
            if (uri == null || uri.trim().isEmpty()) {
                uri = fallbackUri(item);
            }
            if (uri != null && !uri.trim().isEmpty()) {
                referenceClass.getMethod("setUri", String.class).invoke(reference, uri);
            }
            log("openInSapGui(" + item.getName() + ", " + item.getType() + "): uri=" + uri);

            Object editor = utilityClass
                    .getMethod("openEditorForObject", IProject.class, referenceClass,
                            boolean.class, String.class)
                    .invoke(utility, item.getProject(), reference, Boolean.FALSE, "WB:DISPLAY");
            log("openInSapGui(" + item.getName() + ", " + item.getType() + ") -> " + editor);
            return editor instanceof IEditorPart ? (IEditorPart) editor : null;
        } catch (Throwable t) {
            log("openInSapGui(" + item.getName() + ") failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Builds the VIT URI ({@code /sap/bc/adt/vit/wb/object_type/...}) of an
     * object with ADT's own URI builder, so the back end can map it to the
     * SAP GUI transaction of that object type.
     */
    private static String buildVitUri(Object sapGuiPlugin, AbapObjectItem item) {
        try {
            ClassLoader loader = sapGuiPlugin.getClass().getClassLoader();
            Class<?> pluginClass = Class.forName("com.sap.adt.sapgui.ui.ISapGuiPlugin", true, loader);
            Object builder = pluginClass.getMethod("getSapGuiUriBuilder").invoke(sapGuiPlugin);
            if (builder == null) {
                return null;
            }
            Class<?> builderClass = Class.forName(
                    "com.sap.adt.sapgui.ui.util.ISapGuiUriBuilder", true, loader);
            Object uri = builderClass
                    .getMethod("createVitUriForWbType", String.class, String.class)
                    .invoke(builder, item.getType(), item.getName());
            return uri == null ? null : uri.toString();
        } catch (Throwable t) {
            log("buildVitUri(" + item.getName() + ", " + item.getType() + ") failed: "
                    + t.getMessage());
            return null;
        }
    }

    /** URI reconstruction for hits that unexpectedly carry no URI. */
    private static String fallbackUri(AbapObjectItem item) {
        String type = item.getType() == null ? "" : item.getType().toUpperCase();
        String name = item.getName() == null ? "" : item.getName().toLowerCase();
        String encoded = name.replace("/", "%2F");
        if (type.startsWith("PROG/I")) {
            return "/sap/bc/adt/programs/includes/" + encoded;
        }
        if (type.startsWith("PROG/")) {
            return "/sap/bc/adt/programs/programs/" + encoded;
        }
        if (type.startsWith("CLAS/OM") || type.startsWith("METH/")) {
            // A method has no standalone object URI; the search normally
            // provides the semantic class URI for it.
            return null;
        }
        if (type.startsWith("CLAS/")) {
            return "/sap/bc/adt/oo/classes/" + encoded;
        }
        if (type.startsWith("INTF/")) {
            return "/sap/bc/adt/oo/interfaces/" + encoded;
        }
        if (type.startsWith("TABL/DT")) {
            return "/sap/bc/adt/ddic/tables/" + encoded;
        }
        if (type.startsWith("TABL/DS") || type.startsWith("STRU/")) {
            return "/sap/bc/adt/ddic/structures/" + encoded;
        }
        if (type.startsWith("VIEW/")) {
            return "/sap/bc/adt/ddic/views/" + encoded;
        }
        if (type.startsWith("DDLS/")) {
            return "/sap/bc/adt/ddic/ddl/sources/" + encoded;
        }
        if (type.startsWith("DTEL/")) {
            return "/sap/bc/adt/ddic/datatypes/" + encoded;
        }
        if (type.startsWith("DOMA/")) {
            return "/sap/bc/adt/ddic/domains/" + encoded;
        }
        if (type.startsWith("MSAG/")) {
            return "/sap/bc/adt/messageclass/" + encoded;
        }
        return null;
    }

    private static Class<?> loadClass(String bundleId, String className) {
        try {
            Bundle bundle = Platform.getBundle(bundleId);
            return bundle == null ? null : bundle.loadClass(className);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void log(String message) {
        try {
            LOG.log(new Status(IStatus.WARNING, Activator.PLUGIN_ID, "[ObjectSearch] " + message));
        } catch (Throwable ignored) {
            // logging must never break navigation
        }
    }
}
