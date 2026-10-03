# Builds EssentialShare-Setup.exe (self-extracting installer) from the app image.
# Needs only what ships with Windows (IExpress). Run after `gradlew :desktop:createDistributable`.
param(
    [string]$AppDir = (Join-Path $PSScriptRoot '..\desktop\build\compose\binaries\main\app\Essential Share'),
    [string]$Out    = (Join-Path $PSScriptRoot '..\dist\EssentialShare-Setup.exe')
)
$ErrorActionPreference = 'Stop'
$AppDir = (Resolve-Path $AppDir).Path
$work = Join-Path $env:TEMP 'essential-share-setup'
if (Test-Path $work) { Remove-Item $work -Recurse -Force }
New-Item -ItemType Directory $work | Out-Null

Write-Host 'Packing app image...'
Compress-Archive -Path (Join-Path $AppDir '*') -DestinationPath (Join-Path $work 'payload.zip') -CompressionLevel Optimal
Copy-Item (Join-Path $PSScriptRoot 'install.ps1') $work
Copy-Item (Join-Path $PSScriptRoot 'uninstall.ps1') $work

$outDir = Split-Path -Parent ([IO.Path]::GetFullPath($Out))
New-Item -ItemType Directory $outDir -Force | Out-Null
$outFull = Join-Path $outDir (Split-Path -Leaf $Out)
if (Test-Path $outFull) { Remove-Item $outFull -Force }

$sed = @"
[Version]
Class=IEXPRESS
SEDVersion=3
[Options]
PackagePurpose=InstallApp
ShowInstallProgramWindow=0
HideExtractAnimation=1
UseLongFileName=1
InsideCompressed=0
CAB_FixedSize=0
CAB_ResvCodeSigning=0
RebootMode=N
InstallPrompt=
DisplayLicense=
FinishMessage=
TargetName=$outFull
FriendlyName=Essential Share Setup
AppLaunched=powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File install.ps1
PostInstallCmd=<None>
AdminQuietInstCmd=
UserQuietInstCmd=
SourceFiles=SourceFiles
[SourceFiles]
SourceFiles0=$work\
[SourceFiles0]
%FILE0%=
%FILE1%=
%FILE2%=
[Strings]
FILE0=payload.zip
FILE1=install.ps1
FILE2=uninstall.ps1
"@
$sedPath = Join-Path $work 'setup.sed'
Set-Content -Path $sedPath -Value $sed -Encoding ASCII

Write-Host 'Building installer...'
$p = Start-Process iexpress.exe -ArgumentList '/N', '/Q', $sedPath -Wait -PassThru -WindowStyle Hidden
if (-not (Test-Path $outFull)) { throw "IExpress failed (exit $($p.ExitCode))" }
Write-Host ("Done: {0} ({1:N1} MB)" -f $outFull, ((Get-Item $outFull).Length / 1MB))
