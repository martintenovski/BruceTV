# Builds the APK and sets it up on the TV as the home screen (pass -NoInstall to only build).
param([switch]$NoInstall, [string]$Tv = "", [string]$Video = "")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$gradle = (Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-bin\*\gradle-8.14.3\bin\gradle.bat" | Select-Object -First 1).FullName
& $gradle -q assembleRelease
if ($LASTEXITCODE -ne 0) { "BUILD FAILED"; exit 1 }
$apk = "app\build\outputs\apk\release\app-release.apk"
"BUILD OK: $([int]((Get-Item $apk).Length / 1KB)) KB"
if ($NoInstall) { exit 0 }

$ErrorActionPreference = "Continue"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not $Tv) {
    # Wireless debugging changes port after every TV restart; mDNS tells us the current one.
    $line = & $adb mdns services | Select-String "_adb-tls-connect" | Select-Object -First 1
    $Tv = if ($line) { ($line -split "\s+")[-1] } else { throw "TV not found over mDNS: pass -Tv <ip:port>" }
}
& $adb connect $Tv
& $adb -s $Tv install -r $apk
$pkg = "com.bearacle.tv"
$svc = "$pkg/.KeyService"
& $adb -s $Tv shell appops set $pkg GET_USAGE_STATS allow
& $adb -s $Tv shell appops set $pkg WRITE_SETTINGS allow
& $adb -s $Tv shell appops set $pkg REQUEST_INSTALL_PACKAGES allow
& $adb -s $Tv shell pm grant $pkg android.permission.READ_MEDIA_IMAGES
& $adb -s $Tv shell pm grant $pkg com.android.providers.tv.permission.READ_EPG_DATA 2>$null
& $adb -s $Tv shell cmd package set-home-activity "$pkg/.MainActivity"
$enabled = (& $adb -s $Tv shell settings get secure enabled_accessibility_services).Trim()
if ($enabled -notmatch [regex]::Escape($svc)) {
    $new = if ($enabled -and $enabled -ne "null") { "${enabled}:$svc" } else { $svc }
    & $adb -s $Tv shell settings put secure enabled_accessibility_services $new
    & $adb -s $Tv shell settings put secure accessibility_enabled 1
}
if ($Video) {
    # The Fire pit standby loops this file; it lives in the app's own files folder.
    $dir = "/sdcard/Android/data/$pkg/files"
    & $adb -s $Tv shell mkdir -p $dir
    & $adb -s $Tv push $Video "$dir/fire-pit.mp4"
}
& $adb -s $Tv shell am start -n "$pkg/.MainActivity"
"INSTALL DONE on $Tv"
