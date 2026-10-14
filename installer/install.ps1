# EShare per-user installer (no admin rights needed).
# Runs from the self-extracting Setup.exe: unpacks payload.zip, creates shortcuts, registers uninstall.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Windows.Forms

$here    = Split-Path -Parent $MyInvocation.MyCommand.Path
$target  = Join-Path $env:LOCALAPPDATA 'Programs\EShare'
$exe     = Join-Path $target 'EShare.exe'
$menuDir = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'
$ru      = (Get-UICulture).TwoLetterISOLanguageName -eq 'ru'

function Say($en, $ru_) { if ($ru) { $ru_ } else { $en } }

try {
    Get-Process -Name 'EShare','Essential Share' -ErrorAction SilentlyContinue | Stop-Process -Force
    Start-Sleep -Milliseconds 600

    # the app used to be called Essential Share: take the old install away so there is only one program
    $oldDir = Join-Path $env:LOCALAPPDATA 'Programs\Essential Share'
    if (Test-Path $oldDir) { Remove-Item $oldDir -Recurse -Force -ErrorAction SilentlyContinue }
    foreach ($old in @((Join-Path $menuDir 'Essential Share.lnk'), (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Essential Share.lnk'))) { Remove-Item $old -Force -ErrorAction SilentlyContinue }
    Remove-Item 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\Essential Share' -Recurse -Force -ErrorAction SilentlyContinue

    if (Test-Path $target) { Remove-Item $target -Recurse -Force }
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Expand-Archive -Path (Join-Path $here 'payload.zip') -DestinationPath $target -Force
    Copy-Item (Join-Path $here 'uninstall.ps1') (Join-Path $target 'uninstall.ps1') -Force

    $sh = New-Object -ComObject WScript.Shell
    foreach ($dir in @($menuDir, [Environment]::GetFolderPath('Desktop'))) {
        $lnk = $sh.CreateShortcut((Join-Path $dir 'EShare.lnk'))
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
        DisplayName     = 'EShare'
        DisplayVersion  = '1.1.0'
        Publisher       = 'EShare (unofficial fan project)'
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
        (Say "EShare is installed. It has started in the tray.`nWindows Firewall will ask once: allow private networks." "EShare установлен и запущен в трее.`nWindows спросит про брандмауэр один раз: разрешите частные сети."),
        'EShare', 'OK', 'Information')
} catch {
    [void][System.Windows.Forms.MessageBox]::Show(
        ((Say "Installation failed:" "Не удалось установить:") + "`n" + $_.Exception.Message),
        'EShare', 'OK', 'Error')
    exit 1
}
