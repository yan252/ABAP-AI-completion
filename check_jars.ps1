$ErrorActionPreference = 'Continue'
$jar = "C:\Users\96000217\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_23.0.2.v20250131-0604\jre\bin\jar.exe"
$targets = @(
  "D:\Users\96000217\eclipse\plugins\com.sap.abap.ai.completion_1.0.5.jar",
  "D:\Users\96000217\eclipse\plugins\com.sap.abap.ai.completion_1.0.6.jar",
  "D:\Users\96000217\Documents\trae_projects\com.sap.abap.ai.completion\dist\com.sap.abap.ai.completion_1.0.8.jar"
)
foreach ($j in $targets) {
  Write-Host "=== $j ==="
  if (-not (Test-Path $j)) { Write-Host "  (not found)"; continue }
  $d = Split-Path $j
  $f = Split-Path $j -Leaf
  Push-Location $d
  & $jar xf $f plugin.xml
  Write-Host "  main.menu locationURI:"
  Select-String -Path "$d\plugin.xml" -Pattern 'locationURI=.*main.menu'
  Write-Host "  popup.any locationURI:"
  Select-String -Path "$d\plugin.xml" -Pattern 'locationURI=.*popup.any'
  Remove-Item "$d\plugin.xml" -Force -ErrorAction SilentlyContinue
  Pop-Location
}
