# Luna TV

A plain-Java Android TV launcher (`com.bearacle.tv`, no dependencies) with selectable home layouts, plus the PC-side scripts for **Game Mode** (a console-style menu that streams your gaming PC to the TV with Moonlight + Apollo).

- `app/` – the launcher. Build and install with `build.ps1 -Tv <ip:port>`.
- `pc/` – Game Mode for the gaming PC (`pc/setup.ps1` sets it up).
- `mockups/` – the layout mockups.

## Updates

The TV updates itself from this repo's GitHub releases: Control Center › **Updates** checks, shows the release notes, then downloads and installs. It also checks every 6 hours on Home and pops the same "What's new" modal once per version.

Publish one from the Terminal panel: `.\release.ps1 -Version 2.2 -NotesFile release-notes\2.2.md`, then commit the version bump in `app/build.gradle`. Updates must be signed with the same key as the installed app (the debug keystore in `~/.android` on this PC), so don't lose it. The very first install of an updater-capable build still goes through `build.ps1`.

## First-time setup

1. **PC:** install [Apollo](https://github.com/ClassicOldSong/Apollo/releases), then right-click `pc/setup.ps1` › Run with PowerShell (one UAC prompt). It finds Python, adds "Game Mode" to Apollo, opens the firewall for the home network and starts a small helper at every logon. Safe to run again.
2. **TV:** a fresh install opens the setup by itself (or Control Center › Setup): pick a home layout, install Moonlight, let the TV find the PC, pair them, and see how many games were found.

## Game Mode

Start it from the Game Mode tile or app. Press Menu › **Settings** inside it:

- **Scan for games** – re-reads Steam, Epic, Microsoft Store, GOG and Ubisoft, and looks through Games folders on every disk. New finds come up as a list to tick.
- **Add a game** – browse the PC's disks and folders with the remote (or search by name), pick the game's folder or `.exe`, name it.
- **Manage games** – hide a game, or remove one you added.
- **Game folders** – extra folders the scan looks through.
