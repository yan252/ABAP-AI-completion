$proj = "C:\Users\96000217\Documents\trae_projects\com.sap.abap.ai.completion"
$src = "$proj\src"
$bin = "$proj\bin"
$dist = "$proj\dist"
$jarPath = "$dist\com.sap.abap.ai.completion_1.0.3.jar"

$ecj = "$proj\lib\ecj-4.34.jar"
$p2Pool = "C:\Users\96000217\.p2\pool\plugins"

$deps = @(
    "$p2Pool\org.eclipse.osgi_*.jar"
    "$p2Pool\org.eclipse.equinox.common_*.jar"
    "$p2Pool\org.eclipse.core.runtime_*.jar"
    "$p2Pool\org.eclipse.jface_*.jar"
    "$p2Pool\org.eclipse.jface.text_*.jar"
    "$p2Pool\org.eclipse.text_*.jar"
    "$p2Pool\org.eclipse.ui.workbench_*.jar"
    "$p2Pool\org.eclipse.ui.workbench.texteditor_*.jar"
    "$p2Pool\org.eclipse.core.resources_*.jar"
    "$p2Pool\org.eclipse.ui.ide_*.jar"
    "$p2Pool\org.eclipse.ui.views_*.jar"
    "$p2Pool\org.eclipse.ui.forms_*.jar"
    "$p2Pool\org.eclipse.swt.win32.win32.x86_64_*.jar"
    "$p2Pool\org.eclipse.equinox.preferences_*.jar"
    "$p2Pool\org.eclipse.core.commands_*.jar"
    "$p2Pool\org.eclipse.core.variables_*.jar"
    "$p2Pool\org.eclipse.core.expressions_*.jar"
    "$p2Pool\org.eclipse.core.filebuffers_*.jar"
    "$p2Pool\org.eclipse.core.jobs_*.jar"
)

Write-Host "=== Cleaning old bin ==="
Remove-Item -Recurse -Force "$bin\com" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "$bin\META-INF" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "$bin\icons" -ErrorAction SilentlyContinue
Remove-Item -Force "$bin\plugin.xml" -ErrorAction SilentlyContinue

Write-Host "=== Compiling ==="
$depPaths = ($deps | ForEach-Object { (Resolve-Path $_).Path }) -join ";"
$srcFiles = Get-ChildItem -Path $src -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }

& "C:\Users\96000217\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_23.0.2.v20250131-0604\jre\bin\javac.exe" --release 17 `
    -cp "$depPaths" `
    -d "$bin" `
    $srcFiles 2>&1

if ($LASTEXITCODE -eq 0) {
    Write-Host "=== Compilation SUCCESS ==="
} else {
    Write-Host "=== Compilation FAILED ==="
    exit 1
}

Write-Host "=== Assembling plugin resources ==="
# Copy META-INF/MANIFEST.MF
New-Item -ItemType Directory -Force -Path "$bin\META-INF" | Out-Null
Copy-Item "$proj\META-INF\MANIFEST.MF" "$bin\META-INF\" -Force

# Copy plugin.xml
Copy-Item "$proj\plugin.xml" "$bin\" -Force

# Copy icons if exists
if (Test-Path "$proj\icons") {
    Copy-Item -Recurse "$proj\icons" "$bin\" -Force
}

Write-Host "=== Creating JAR with full plugin structure ==="
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$jarExe = "C:\Users\96000217\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_23.0.2.v20250131-0604\jre\bin\jar.exe"
Push-Location $bin
if (Test-Path "$bin\icons") {
    & $jarExe cfm "$jarPath" "META-INF\MANIFEST.MF" plugin.xml com\ icons\
} else {
    & $jarExe cfm "$jarPath" "META-INF\MANIFEST.MF" plugin.xml com\
}
Pop-Location

$size = (Get-Item "$jarPath").Length / 1KB
Write-Host "`n============================================"
Write-Host "  JAR CREATED SUCCESSFULLY"
Write-Host "============================================"
Write-Host "  Location: $jarPath"
Write-Host "  Size: $size KB"
Write-Host "============================================"
Write-Host "`nJAR contents:"
& $jarExe tf "$jarPath"
Write-Host "`nDeploy to Eclipse dropins directory to install."
