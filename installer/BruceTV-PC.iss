; Windows installer for the gaming-PC side of Bruce TV (Game Mode).
; Build: .\installer\build.ps1  ->  installer\Output\BruceTV-PC-Setup-<version>.exe
#ifndef Version
  #define Version "0.0"
#endif

[Setup]
AppId={{F29F4D88-6932-48C9-A8A8-8A8F9EFDEFFE}
AppName=Bruce TV for PC
AppVersion={#Version}
AppPublisher=Bruce TV
AppPublisherURL=https://github.com/martintenovski/BruceTV
DefaultDirName={commonappdata}\BruceTV
DisableProgramGroupPage=yes
PrivilegesRequired=admin
SetupIconFile=icon.ico
UninstallDisplayIcon={app}\icon.ico
OutputDir=Output
OutputBaseFilename=BruceTV-PC-Setup-{#Version}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
; the helpers read and write their settings next to the scripts, as the normal user
[Dirs]
Name: "{app}"; Permissions: users-modify

[Files]
Source: "..\pc\*"; DestDir: "{app}"; Excludes: "__pycache__,*.json,test_games.py"; Flags: recursesubdirs createallsubdirs ignoreversion
Source: "icon.ico"; DestDir: "{app}"

[Icons]
Name: "{autoprograms}\Bruce TV PC setup"; Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\setup.ps1"""; IconFilename: "{app}\icon.ico"

[Run]
Filename: "powershell.exe"; Parameters: "-NoProfile -ExecutionPolicy Bypass -File ""{app}\setup.ps1"""; Description: "Set up Game Mode on this PC now (needs Apollo; takes about a minute)"; Flags: postinstall waituntilterminated skipifsilent

[UninstallRun]
Filename: "schtasks.exe"; Parameters: "/delete /tn ""LunaTV Last Game"" /f"; Flags: runhidden; RunOnceId: "t1"
Filename: "schtasks.exe"; Parameters: "/delete /tn ""LunaTV DS4Windows"" /f"; Flags: runhidden; RunOnceId: "t2"
Filename: "schtasks.exe"; Parameters: "/delete /tn ""LunaTV DS4 Lightbar"" /f"; Flags: runhidden; RunOnceId: "t3"
Filename: "netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Bruce TV Game Mode"""; Flags: runhidden; RunOnceId: "f1"
Filename: "netsh.exe"; Parameters: "advfirewall firewall delete rule name=""Bruce TV Helper"""; Flags: runhidden; RunOnceId: "f2"
