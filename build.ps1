$src = "C:\Users\96000217\Documents\trae_projects\test\com.sap.abap.ai.completion\src"
$bin = "C:\Users\96000217\Documents\trae_projects\test\com.sap.abap.ai.completion\bin"
$dist = "C:\Users\96000217\Documents\trae_projects\test\com.sap.abap.ai.completion\dist"
$jarPath = "$dist\com.sap.abap.ai.completion_1.0.0.jar"

$ecj = "C:\Users\96000217\Documents\trae_projects\test\com.sap.abap.ai.completion\lib\ecj-4.34.jar"
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

Write-Host "=== Creating temporary JAR ==="
New-Item -ItemType Directory -Force -Path $dist | Out-Null
cd "$bin"
& "C:\Users\96000217\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_23.0.2.v20250131-0604\jre\bin\jar.exe" cf "$jarPath.bak" com/
cd "C:\Users\96000217\Documents\trae_projects\test\com.sap.abap.ai.completion"
& "C:\Users\96000217\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_23.0.2.v20250131-0604\jre\bin\jar.exe" uf "$jarPath.bak" plugin.xml
Move-Item "$jarPath.bak" "$jarPath" -Force

$size = (Get-Item "$jarPath").Length / 1KB
Write-Host "`n============================================"
Write-Host "  JAR CREATED SUCCESSFULLY"
Write-Host "============================================"
Write-Host "  Location: $jarPath"
Write-Host "  Size: $size KB"
Write-Host "============================================"
Write-Host "`nEither use this JAR or run rebuild.ps1 for final packaging"
