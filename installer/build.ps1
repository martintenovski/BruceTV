# Builds the PC installer: .\installer\build.ps1   (version is read from app/build.gradle)
$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot
$ver = [regex]::Match((Get-Content "$root\app\build.gradle" -Raw), "versionName '([^']+)'").Groups[1].Value
$iscc = @("$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe", "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe") | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $iscc) { throw "Inno Setup isn't installed. Run: winget install JRSoftware.InnoSetup" }
& $iscc "/DVersion=$ver" "$PSScriptRoot\BruceTV-PC.iss"
if ($LASTEXITCODE -ne 0) { throw "Installer build failed" }
"Built $PSScriptRoot\Output\BruceTV-PC-Setup-$ver.exe"
