# Installs Bruce TV on your Google TV from your PC. Safe to run again (it updates in place).
#   .\install-tv.ps1 -Tv 192.168.1.50:41234        (the address shown under Wireless debugging on the TV)
#   .\install-tv.ps1 -Tv <ip:port> -Apk .\bruce-tv.apk   (use a file you already have)
# Downloads the latest release, installs it, grants the permissions it needs, makes it the home screen and starts it.
param([Parameter(Mandatory)][string]$Tv, [string]$Apk = "")
$ErrorActionPreference = "Stop"
$adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
if (-not $adb) { throw "adb isn't installed. Run: winget install Google.PlatformTools   (then open a new PowerShell window)" }

if (-not $Apk) {
    $rel = Invoke-RestMethod "https://api.github.com/repos/martintenovski/BruceTV/releases/latest"
    $asset = $rel.assets | Where-Object name -like "*.apk" | Select-Object -First 1
    if (-not $asset) { throw "No APK found on the latest release." }
    $Apk = Join-Path $env:TEMP $asset.name
    "Downloading $($asset.name) ..."
    Invoke-WebRequest $asset.browser_download_url -OutFile $Apk
}

& $adb connect $Tv
if ((& $adb -s $Tv get-state 2>&1) -notmatch "device") { throw "Can't reach the TV at $Tv. Is Wireless debugging on, and is that the address shown there? (If it's the first time, pair first: adb pair <ip:pairing-port> <code>)" }
& $adb -s $Tv install -r $Apk
if ($LASTEXITCODE -ne 0) { throw "Install failed." }

$pkg = "com.bearacle.tv"
$svc = "$pkg/.KeyService"
& $adb -s $Tv shell appops set $pkg GET_USAGE_STATS allow
& $adb -s $Tv shell appops set $pkg WRITE_SETTINGS allow
& $adb -s $Tv shell appops set $pkg REQUEST_INSTALL_PACKAGES allow
& $adb -s $Tv shell pm grant $pkg android.permission.READ_MEDIA_IMAGES
& $adb -s $Tv shell pm grant $pkg com.android.providers.tv.permission.READ_EPG_DATA 2>$null
& $adb -s $Tv shell cmd package set-home-activity "$pkg/.MainActivity"
$enabled = (& $adb -s $Tv shell settings get secure enabled_accessibility_services).Trim()   # the Home button service
if ($enabled -notmatch [regex]::Escape($svc)) {
    $new = if ($enabled -and $enabled -ne "null") { "${enabled}:$svc" } else { $svc }
    & $adb -s $Tv shell settings put secure enabled_accessibility_services $new
    & $adb -s $Tv shell settings put secure accessibility_enabled 1
}
& $adb -s $Tv shell am start -n "$pkg/.MainActivity"
"Done. Bruce TV is on your TV; the setup wizard opens the first time."
