# PowerShell script to create Desktop Shortcut for IGIRS AI 0.2
$WshShell = New-Object -ComObject WScript.Shell
$projectDir = Split-Path -Parent $PSScriptRoot
$desktopPaths = @(
    [Environment]::GetFolderPath('Desktop'),
    "$HOME\Desktop"
) | Select-Object -Unique

foreach ($desktop in $desktopPaths) {
    if (Test-Path $desktop) {
        $shortcutPath = Join-Path $desktop "IGIRS AI 0.2.lnk"
        $shortcut = $WshShell.CreateShortcut($shortcutPath)
        $shortcut.TargetPath = "$projectDir\.venv\Scripts\pythonw.exe"
        $shortcut.Arguments = "`"$projectDir\run_desktop.py`""
        $shortcut.WorkingDirectory = $projectDir
        $shortcut.Description = "IGIRS AI 0.2 — 3D Cyber Command Center"
        $shortcut.IconLocation = "$projectDir\assets\igirs_orb.ico,0"
        $shortcut.WindowStyle = 1
        $shortcut.Save()
        Write-Host "[OK] Created shortcut: $shortcutPath" -ForegroundColor Green
    }
}
