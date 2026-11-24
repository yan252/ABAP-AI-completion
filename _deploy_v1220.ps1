# v1.2.20 部署脚本
# 用法：1. 完全关闭 Eclipse（确保 dropins jar 文件锁释放）
#       2. 在 PowerShell 里执行： powershell -ExecutionPolicy Bypass -File _deploy_v1220.ps1
#       3. 用 -clean 参数启动 Eclipse

$dropins = "D:\Users\96000217\eclipse\dropins"
$dist = "D:\Users\96000217\Documents\trae_projects\com.sap.abap.ai.completion\dist"

Write-Host "=== Step 1: 清理 dropins 里所有旧版本 ==="
$old = Get-ChildItem "$dropins\com.sap.abap.ai.completion_*.jar"
foreach ($j in $old) {
    try {
        Remove-Item $j.FullName -Force -ErrorAction Stop
        Write-Host "  DELETED: $($j.Name)"
    } catch {
        Write-Host "  FAILED to delete $($j.Name) — Eclipse still has it LOCKED!" -ForegroundColor Red
        Write-Host "  You MUST close Eclipse completely first (File > Exit, not just close window)." -ForegroundColor Red
        exit 1
    }
}

Write-Host "`n=== Step 2: 复制 v1.2.20 到 dropins ==="
$src = "$dist\com.sap.abap.ai.completion_1.2.20.jar"
if (-not (Test-Path $src)) { Write-Host "  SOURCE NOT FOUND: $src" -ForegroundColor Red; exit 1 }
Copy-Item $src $dropins -Force
$dst = "$dropins\com.sap.abap.ai.completion_1.2.20.jar"
$jar = Get-Item $dst
Write-Host "  DEPLOYED: $($jar.Name) ($([math]::Round($jar.Length/1KB, 1)) KB, $($jar.LastWriteTime))"

Write-Host "`n=== Step 3: 验证 ==="
$final = Get-ChildItem "$dropins\com.sap.abap.ai.completion_*.jar"
if ($final.Count -eq 1 -and $final.Name -eq "com.sap.abap.ai.completion_1.2.20.jar") {
    Write-Host "  PASS: dropins 里只有 v1.2.20 ✓" -ForegroundColor Green
} else {
    Write-Host "  FAIL: dropins 里还有旧版！" -ForegroundColor Red
    $final | ForEach-Object { Write-Host "    $($_.Name)" }
    exit 1
}

Write-Host "`n=== DONE ==="
Write-Host "现在用 -clean 启动 Eclipse："
Write-Host "  & 'D:\...\eclipse\eclipse.exe' -clean"
Write-Host "启动后，请："
Write-Host "  1. 打开 ProjectExplorer 视图（强制刷新 CNF）"
Write-Host "  2. 展开 Recently Modified"
Write-Host "  3. 点 PROG/I 的 > 箭头"
Write-Host "  4. 把 .metadata/.log 里所有含 '[RecentlyModified] V19' 的行贴给我"
