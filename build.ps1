$proj = "D:\Users\96000217\Documents\trae_projects\com.sap.abap.ai.completion"
$src = "$proj\src"
$bin = "$proj\bin"
$dist = "$proj\dist"
$jarPath = "$dist\com.sap.abap.ai.completion_1.1.39.jar"

$ecj = "$proj\lib\ecj-4.34.jar"
$p2Pool = "D:\Users\96000217\eclipse\plugins"
$jdkHome = "D:\jdk\jdk-21.0.12.1"

# Source of the third-party abap-cli node_modules tree to embed. The project's
# own scripts live under $proj\abap-cli; node_modules is assembled from the
# maintained abap-cli stage checkout (override with $env:ABAP_CLI_STAGE_DIR).
$abapStage = if ($env:ABAP_CLI_STAGE_DIR) { $env:ABAP_CLI_STAGE_DIR }
             else { "D:\Users\96000217\Documents\trae_projects\_abap-cli-stage" }

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
    "$p2Pool\org.eclipse.core.filesystem_*.jar"
    "$p2Pool\org.eclipse.core.jobs_*.jar"
    "$p2Pool\com.sap.conn.jco_*.jar"
)

Write-Host "=== Cleaning old bin ==="
Remove-Item -Recurse -Force "$bin\com" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "$bin\META-INF" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "$bin\icons" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "$bin\references" -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force "$bin\abap-cli" -ErrorAction SilentlyContinue
Remove-Item -Force "$bin\plugin.xml" -ErrorAction SilentlyContinue
Remove-Item -Force "$bin\templates.properties" -ErrorAction SilentlyContinue

Write-Host "=== Compiling ==="
$depPaths = ($deps | ForEach-Object { (Resolve-Path $_).Path }) -join ";"
$srcFiles = Get-ChildItem -Path $src -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }

& "$jdkHome\bin\javac.exe" --release 17 `
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

# Copy references (模板参考目录) if exists
if (Test-Path "$proj\references") {
    Copy-Item -Recurse "$proj\references" "$bin\" -Force
}

# Copy templates.properties (模板菜单与 zip 文件名映射配置) if exists
if (Test-Path "$proj\templates.properties") {
    Copy-Item "$proj\templates.properties" "$bin\" -Force
}

Write-Host "=== Embedding abap-cli runtime ==="
# The runtime shipped inside the plugin and extracted on first use (see
# AbapCliRuntime.java): our own scripts/extensions (versioned with the project)
# plus the third-party abap-cli node_modules tree from the stage checkout.
$runtimeBin = "$bin\abap-cli"
New-Item -ItemType Directory -Force -Path "$runtimeBin\extensions" | Out-Null
Copy-Item "$proj\abap-cli\*.mjs" "$runtimeBin\" -Force
Copy-Item "$proj\abap-cli\extensions\*.mjs" "$runtimeBin\extensions\" -Force

$stageModules = "$abapStage\node_modules"
if (-not (Test-Path $stageModules)) {
    Write-Host "=== BUILD FAILED: node_modules not found at $stageModules ==="
    Write-Host "    Set `$env:ABAP_CLI_STAGE_DIR to a prepared abap-cli stage checkout."
    exit 1
}
Write-Host "Copying node_modules from $abapStage (this takes a moment)..."
New-Item -ItemType Directory -Force -Path "$runtimeBin\node_modules" | Out-Null
Get-ChildItem -Path $stageModules -Force |
    Where-Object { $_.Name -ne ".bin" } |
    ForEach-Object { Copy-Item $_.FullName "$runtimeBin\node_modules\" -Recurse -Force }
$fileCount = (Get-ChildItem -Path "$runtimeBin" -Recurse -File).Count
Write-Host "Embedded runtime files: $fileCount"

Write-Host "=== Creating JAR with full plugin structure ==="
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$jarExe = "$jdkHome\bin\jar.exe"
Push-Location $bin
$jarArgs = @("cfm", "$jarPath", "META-INF\MANIFEST.MF", "plugin.xml", "com\")
if (Test-Path "$bin\icons") { $jarArgs += "icons\" }
if (Test-Path "$bin\references") { $jarArgs += "references\" }
if (Test-Path "$bin\templates.properties") { $jarArgs += "templates.properties" }
if (Test-Path "$bin\abap-cli") { $jarArgs += "abap-cli\" }
& $jarExe $jarArgs
Pop-Location

$size = (Get-Item "$jarPath").Length / 1KB
Write-Host "`n============================================"
Write-Host "  JAR CREATED SUCCESSFULLY"
Write-Host "============================================"
Write-Host "  Location: $jarPath"
Write-Host "  Size: $([math]::Round($size / 1024, 2)) MB"
Write-Host "============================================"
Write-Host "`nJAR entry summary:"
$entries = & $jarExe tf "$jarPath"
Write-Host ("  total entries: {0}" -f $entries.Count)
Write-Host ("  abap-cli entries: {0}" -f ($entries | Where-Object { $_ -like "abap-cli/*" }).Count)
$entries | Where-Object { $_ -notlike "abap-cli/node_modules/*" } | ForEach-Object { Write-Host "  $_" }
Write-Host "  ... (abap-cli/node_modules/* entries omitted)"
Write-Host "`nDeploy to Eclipse dropins directory or the update site to install."
