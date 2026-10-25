# One-time setup of the gaming PC for Luna TV's Game Mode. Safe to run again any time.
# Asks once for administrator rights, then:
#   - finds Python (offers to install it), and checks for Apollo (the streaming host)
#   - adds "Game Mode" to Apollo's app list
#   - lets the TV through the firewall (ports 8765 and 8766, home network only)
#   - starts the always-on helper at every logon (the TV finds this PC through it, and reads your game list)
#   - sets up DS4Windows control if DS4Windows is installed
# Afterwards, on the TV: open Luna TV > Control Center > Setup.
param([switch]$Relaunched)
$ErrorActionPreference = "Stop"
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole("Administrators")) {
    Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Relaunched"
    exit
}

function Step($text) { Write-Host "`n== $text" -ForegroundColor Cyan }
function Ok($text) { Write-Host "   ok  $text" -ForegroundColor Green }
function Warn($text) { Write-Host "   !!  $text" -ForegroundColor Yellow }

$gm = Join-Path $PSScriptRoot "game-mode"
$user = "$env:USERDOMAIN\$env:USERNAME"

# ---------- Python ----------
Step "Python"
function Find-Python {
    $found = @()
    $found += Get-Command python.exe -All -ErrorAction SilentlyContinue | Where-Object { $_.Source -notmatch "WindowsApps" } | ForEach-Object Source
    $found += Get-ChildItem "$env:LOCALAPPDATA\Programs\Python\Python3*\python.exe", "C:\Python3*\python.exe", "C:\Program Files\Python3*\python.exe" -ErrorAction SilentlyContinue | ForEach-Object FullName
    foreach ($p in $found) {
        $v = & $p -c "import sys; print(sys.version_info >= (3, 10))" 2>$null
        if ($v -eq "True") { return $p }
    }
}
$py = Find-Python
if (-not $py) {
    if ((Read-Host "Python 3.10 or newer isn't installed. Install it now with winget? (Y/n)") -notmatch "^n") {
        winget install -e --id Python.Python.3.13 --scope machine --accept-package-agreements --accept-source-agreements
        $py = Find-Python
    }
}
if (-not $py) { Warn "Python is missing. Install it from python.org, then run this again."; if ($Relaunched) { Read-Host "Press Enter to close" }; exit 1 }
$pyw = Join-Path (Split-Path $py) "pythonw.exe"
Ok $py

# ---------- Apollo ----------
Step "Apollo (the streaming host)"
$apollo = "C:\Program Files\Apollo"
$cfg = "$apollo\config\apps.json"
if (Test-Path $cfg) {
    Ok "found at $apollo"
    $json = Get-Content $cfg -Raw | ConvertFrom-Json
    $server = Join-Path $gm "server.py"
    $cmd = "`"$pyw`" `"$server`""
    $entry = $json.apps | Where-Object { $_.name -eq "Game Mode" } | Select-Object -First 1
    if ($entry) {
        if ($entry.cmd -ne $cmd) { Copy-Item $cfg "$cfg.bak" -Force; $entry.cmd = $cmd; $changed = $true }
    } else {
        Copy-Item $cfg "$cfg.bak" -Force
        $app = [ordered]@{
            "allow-client-commands" = $false; "auto-detach" = $true; "cmd" = $cmd
            "elevated" = $false; "exclude-global-prep-cmd" = $false; "exclude-global-state-cmd" = $false
            "exit-timeout" = 5; "gamepad" = ""; "name" = "Game Mode"; "output" = ""
            "per-client-app-identity" = $false; "scale-factor" = 100; "state-cmd" = @()
            "terminate-on-pause" = $false; "use-app-identity" = $false
            "uuid" = ([guid]::NewGuid().ToString().ToUpper()); "virtual-display" = $true; "wait-all" = $true
        }
        $json.apps = @($app) + @($json.apps)
        $changed = $true
    }
    if ($changed) {
        [IO.File]::WriteAllText($cfg, ($json | ConvertTo-Json -Depth 10)) # no BOM
        Restart-Service ApolloService
        Ok "Game Mode added to Apollo's app list (backup: apps.json.bak)"
    } else { Ok "Game Mode is already in Apollo's app list" }
} else {
    Warn "Apollo isn't installed. Get it from https://github.com/ClassicOldSong/Apollo/releases, install it, then run this again."
}

# ---------- firewall ----------
Step "Firewall (home network only)"
foreach ($rule in @(@{N = "Luna TV Game Mode"; P = 8765}, @{N = "Luna TV Helper"; P = 8766})) {
    if (-not (Get-NetFirewallRule -DisplayName $rule.N -ErrorAction SilentlyContinue)) {
        New-NetFirewallRule -DisplayName $rule.N -Direction Inbound -Protocol TCP -LocalPort $rule.P -RemoteAddress LocalSubnet -Action Allow | Out-Null
    }
    Ok "port $($rule.P) open to your home network"
}

# ---------- always-on helper ----------
Step "Helper that starts at every logon"
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive
$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
# the script's path has spaces in it, so it goes inside quotes of its own
$script = Join-Path $gm "last-game.py"
$action = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -Command `"Start-Process -WindowStyle Hidden '$py' '\`"$script\`"'`""
Register-ScheduledTask -TaskName "LunaTV Last Game" -Action $action -Principal $principal -Settings $settings -Trigger (New-ScheduledTaskTrigger -AtLogOn -User $user) -Force | Out-Null
Get-NetTCPConnection -LocalPort 8766 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }  # an old copy
Start-ScheduledTask -TaskName "LunaTV Last Game"
Ok "registered and started"

# ---------- DS4Windows (optional) ----------
Step "DS4Windows"
if (Test-Path "C:\Program Files\DS4Windows\DS4Windows.exe") {
    $elevated = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest
    $helper = Join-Path $gm "ds4-helper.ps1"
    $hAction = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$helper`""
    $hSettings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -MultipleInstances Queue -ExecutionTimeLimit (New-TimeSpan -Minutes 2)
    Register-ScheduledTask -TaskName "LunaTV DS4Windows" -Action $hAction -Principal $elevated -Settings $hSettings -Force | Out-Null
    $watcher = Join-Path $gm "ds4-lightbar.ps1"   # re-sends the light bar colour after every controller reconnect
    $wAction = New-ScheduledTaskAction -Execute "powershell.exe" -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File `"$watcher`""
    $wSettings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew
    Register-ScheduledTask -TaskName "LunaTV DS4 Lightbar" -Action $wAction -Principal $elevated -Settings $wSettings -Trigger (New-ScheduledTaskTrigger -AtLogOn -User $user) -Force | Out-Null
    Start-ScheduledTask -TaskName "LunaTV DS4 Lightbar"
    Ok "controller helpers registered"
} else { Ok "not installed, skipped (Game Mode works without it)" }

# ---------- what's next ----------
Step "Done"
$ip = (Get-NetIPAddress -AddressFamily IPv4 -PrefixOrigin Dhcp, Manual -ErrorAction SilentlyContinue | Where-Object { $_.IPAddress -notmatch "^169\." } | Select-Object -First 1).IPAddress
Write-Host "   This PC: $env:COMPUTERNAME  ($ip)"
Write-Host "   Apollo's web page (for the pairing PIN): https://localhost:47990"
Write-Host "   On the TV: Luna TV > Control Center > Setup. It finds this PC by itself."
if ($Relaunched) { Read-Host "`nPress Enter to close" }
