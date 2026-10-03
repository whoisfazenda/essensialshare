# Essential Share per-user installer (no admin rights needed).
# Runs from the self-extracting Setup.exe: unpacks payload.zip, creates shortcuts, registers uninstall.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms

$here    = Split-Path -Parent $MyInvocation.MyCommand.Path
$target  = Join-Path $env:LOCALAPPDATA 'Programs\Essential Share'
$exe     = Join-Path $target 'Essential Share.exe'
$menuDir = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'
$ru      = (Get-UICulture).TwoLetterISOLanguageName -eq 'ru'

function Say($en, $ru_) { if ($ru) { $ru_ } else { $en } }

try {
    Get-Process -Name 'Essential Share' -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Milliseconds 600

    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Expand-Archive -Path (Join-Path $here 'payload.zip') -DestinationPath $target -Force
    Copy-Item (Join-Path $here 'uninstall.ps1') (Join-Path $target 'uninstall.ps1') -Force

    $sh = New-Object -ComObject WScript.Shell
    foreach ($dir in @($menuDir, [Environment]::GetFolderPath('Desktop'))) {
        $lnk = $sh.CreateShortcut((Join-Path $dir 'Essential Share.lnk'))
        $lnk.TargetPath = $exe
        $lnk.WorkingDirectory = $target
        $lnk.IconLocation = "$exe,0"
        $lnk.Save()
    }

    $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\EssentialShare'
    New-Item -Path $key -Force | Out-Null
    $size = [int]((Get-ChildItem $target -Recurse -File | Measure-Object Length -Sum).Sum / 1KB)
    $uninst = "powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$target\uninstall.ps1`""
    @{
        DisplayName     = 'Essential Share'
        DisplayVersion  = '1.0.0'
        Publisher       = 'Essential Share (unofficial fan project)'
        DisplayIcon     = $exe
        InstallLocation = $target
        UninstallString = $uninst
        NoModify        = 1
        NoRepair        = 1
        EstimatedSize   = $size
    }.GetEnumerator() | ForEach-Object {
        $type = if ($_.Value -is [int]) { 'DWord' } else { 'String' }
        New-ItemProperty -Path $key -Name $_.Key -Value $_.Value -PropertyType $type -Force | Out-Null
    }

    Start-Process $exe
    [void][System.Windows.Forms.MessageBox]::Show(
        (Say "Essential Share is installed. It has started in the tray.`nWindows Firewall will ask once: allow private networks." "Essential Share установлен и запущен в трее.`nWindows спросит про брандмауэр один раз: разрешите частные сети."),
        'Essential Share', 'OK', 'Information')
} catch {
    [void][System.Windows.Forms.MessageBox]::Show(
        ((Say "Installation failed:" "Не удалось установить:") + "`n" + $_.Exception.Message),
        'Essential Share', 'OK', 'Error')
    exit 1
}
