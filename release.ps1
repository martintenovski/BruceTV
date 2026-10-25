# Publishes an update the TV can install from Control Center > Updates:
#   .\release.ps1 -Version 2.2 -Notes "- Thing one`n- Thing two"      (or -NotesFile notes.md)
# Bumps versionName/versionCode in app/build.gradle, builds, and creates the GitHub release
# (tag v<Version>, the APK attached, the notes shown in the TV's "What's new" modal).
# Run it from the Terminal panel like build.ps1 (Gradle can't run inside Claude's process).
param([Parameter(Mandatory)][string]$Version, [string]$Notes = "", [string]$NotesFile = "")
$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot
$repo = "martintenovski/luna-tv"   # the source repo itself; keep in sync with Updater.REPO
if ($NotesFile) { $Notes = Get-Content $NotesFile -Raw }
if (-not $Notes.Trim()) { throw "Give the release some notes (-Notes or -NotesFile): the TV shows them before updating." }

$g = Get-Content app\build.gradle -Raw
$code = [int]([regex]::Match($g, "versionCode (\d+)").Groups[1].Value)
if ($g -match "versionName '$([regex]::Escape($Version))'") { "Version $Version is already in build.gradle, rebuilding it." }
else { $code++ }
$g = $g -replace "versionCode \d+", "versionCode $code" -replace "versionName '[^']*'", "versionName '$Version'"
Set-Content app\build.gradle $g -NoNewline

& "$PSScriptRoot\build.ps1" -NoInstall
if ($LASTEXITCODE -ne 0) { exit 1 }
$apk = "$PSScriptRoot\app\build\outputs\apk\release\luna-tv-$Version.apk"
Copy-Item app\build\outputs\apk\release\app-release.apk $apk -Force
gh release create "v$Version" $apk --repo $repo --title "Luna TV $Version" --notes $Notes
if ($LASTEXITCODE -ne 0) { throw "gh release create failed" }
"RELEASED v$Version (versionCode $code). Commit app/build.gradle."
