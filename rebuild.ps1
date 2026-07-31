# Rebuild the plugin JAR with correct META-INF/MANIFEST.MF inclusion

$JDK23 = "C:\Users\96000217\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_23.0.2.v20250131-0604\jre"
$PROJ = "C:\Users\96000217\Documents\trae_projects\test\com.sap.abap.ai.completion"
$jar = "$JDK23\bin\jar"

Write-Host "=== Rebuilding plugin JAR ==="

# Create a temporary working directory
$tmpDir = "$PROJ\build_tmp"
Remove-Item -Recurse -Force $tmpDir -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$tmpDir\META-INF" | Out-Null
New-Item -ItemType Directory -Force -Path "$tmpDir\com" | Out-Null

# Copy all class files (preserving directory structure)
Copy-Item -Recurse "$PROJ\bin\com" "$tmpDir\" -Force

# Copy plugin.xml
Copy-Item "$PROJ\plugin.xml" "$tmpDir\" -Force

# Create a proper MANIFEST.MF (must have trailing newline, no extra spaces)
@"
Manifest-Version: 1.0
Bundle-ManifestVersion: 2
Bundle-Name: ABAP AI Completion
Bundle-SymbolicName: com.sap.abap.ai.completion; singleton:=true
Bundle-Version: 1.0.0
Bundle-Activator: com.sap.abap.ai.completion.Activator
Bundle-Vendor: SAP ABAP AI Tools
Bundle-ActivationPolicy: lazy
Require-Bundle: org.eclipse.ui,
 org.eclipse.core.runtime,
 org.eclipse.jface,
 org.eclipse.jface.text,
 org.eclipse.ui.workbench.texteditor,
 org.eclipse.core.resources,
 org.eclipse.ui.ide,
 org.eclipse.ui.views,
 org.eclipse.ui.forms,
 org.eclipse.swt,
 org.eclipse.equinox.preferences,
 org.eclipse.core.commands,
 org.eclipse.core.variables,
 org.eclipse.core.expressions,
 org.eclipse.core.filebuffers,
 org.eclipse.osgi
Bundle-RequiredExecutionEnvironment: JavaSE-17
Automatic-Module-Permissions: true
Export-Package: com.sap.abap.ai.completion,
 com.sap.abap.ai.completion.client,
 com.sap.abap.ai.completion.editor,
 com.sap.abap.ai.completion.parser,
 com.sap.abap.ai.completion.preferences

"@ | Set-Content -Path "$tmpDir\META-INF\MANIFEST.MF" -Encoding ASCII -NoNewline

Write-Host "MANIFEST.MF content:"
Get-Content "$tmpDir\META-INF\MANIFEST.MF"
Write-Host "---"

# Build the JAR - explicitly include META-INF/MANIFEST.MF
Push-Location $tmpDir
& $jar cfm "$PROJ\dist\com.sap.abap.ai.completion_1.0.0.jar" "META-INF\MANIFEST.MF" plugin.xml com\
Pop-Location

Write-Host ""
Write-Host "=== Verifying JAR contents ==="
& $jar tf "$PROJ\dist\com.sap.abap.ai.completion_1.0.0.jar"

Write-Host ""
Write-Host "File size: $((Get-Item "$PROJ\dist\com.sap.abap.ai.completion_1.0.0.jar").Length / 1KB) KB"

# Clean up temp
Remove-Item -Recurse -Force $tmpDir -ErrorAction SilentlyContinue
