# EShare uninstaller (per user). Settings in %APPDATA%\EShare are kept.
$ErrorActionPreference = 'SilentlyContinue'
$target = Split-Path -Parent $MyInvocation.MyCommand.Path

Get-Process -Name 'EShare','Essential Share' | Stop-Process -Force
Start-Sleep -Milliseconds 600

Remove-Item (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\EShare.lnk') -Force
Remove-Item (Join-Path ([Environment]::GetFolderPath('Desktop')) 'EShare.lnk') -Force
Remove-Item (Join-Path $env:APPDATA 'Microsoft\Windows\SendTo\EShare.lnk') -Force

# entries the app writes itself (autostart, right-click menu)
Remove-ItemProperty 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'EssentialShare'
Remove-Item 'HKCU:\Software\Classes\*\shell\EssentialShare' -Recurse -Force
Remove-Item 'HKCU:\Software\Classes\Directory\shell\EssentialShare' -Recurse -Force
Remove-Item 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\EssentialShare' -Recurse -Force

# this script lives inside the folder it deletes, so finish from a detached cmd
Start-Process cmd.exe -ArgumentList '/c', "ping -n 3 127.0.0.1 >nul & rmdir /s /q `"$target`"" -WindowStyle Hidden
