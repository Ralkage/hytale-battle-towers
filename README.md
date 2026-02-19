# BattleTowers (Hytale Mod / Plugin)

BattleTowers adds Battle Tower worldgen, randomized tower bosses, and a dramatic tower implosion sequence once the boss is defeated.
It also adds RPG progression items (Sigil/Core/Key) and a sealed “vault” chest at the top that requires a Tower Key to open.

## Features

- **Worldgen battle towers** (tiered prefabs and zone integration).
- **Randomized tower bosses** (e.g., Skeleton Archmage variants, golems, etc. depending on tier).
- **Boss defeat → countdown → top-down implosion**
  - Announces time remaining to nearby players.
  - Collapses from the **tower top down** and preserves a small base “ruin” instead of digging a crater.
  - Cleans up **remaining tower mobs**, **spawn markers**, and **dropped items** after implosion.
- **Tower vaults**
  - The top chest(s) are **sealed**.
  - **Tower Key** is consumed on first open to unlock the vault chest (unlock is per-session).
- **Progression items**
  - `BattleTowers_Tower_Sigil` (currency): spend to delay an imminent tower implosion.
  - `BattleTowers_Tower_Core` (milestone): rare boss trophy used for key forging.
  - `BattleTowers_Tower_Key` (gate): consumed to unlock tower vault chest(s).

## Commands

- `/battletowers` → diagnostics (prefab resolution + basic checks)
- `/battletowers implode` → manually trigger an implosion at your position (debug)
- `/battletowers spawn [NpcTypeId]` → spawn an NPC near you (debug)
- `/battletowers delay [1-5]` → spend Sigils to delay the nearest pending implosion (**10s per sigil**)
- `/battletowers forgekey` → spend **5 Sigils + 1 Core** to get **1 Tower Key**

## Config

On Windows the mod/plugin reads (and creates on first run):

- `%APPDATA%\\Hytale\\BattleTowers.properties`

Keys:

```properties
implosion_delay_seconds=30
implosion_announce_radius_blocks=64
implosion_tick_ms=300
implosion_layers_per_tick=2
spawner_activation_radius_blocks=64
```

## Install

### Singleplayer / local “Mods” folder

- Copy the jar into:
  - `%APPDATA%\\Hytale\\UserData\\Mods\\`
- Ensure **only one** `BattleTowers-*.jar` is present at a time (don’t keep old versions alongside new).

### Dedicated server

- Copy the jar into your server’s plugin/mods folder (depends on your server wrapper).
- Restart the server.

## Version compatibility (“Outdated mod detected”)

Hytale will warn if a mod doesn’t declare a target server version (or targets a different build).
This project writes the `manifest.json` `ServerVersion` to match the **local Hytale build** it was compiled against.

If your Hytale build changes, rebuild the jar (or set `server_version` in `gradle.properties` to the exact build string).

## Build (jar for distribution)

From the repo root:

```powershell
.\gradlew --no-daemon clean jar
```

Output:

- `build/libs/BattleTowers-<version>.jar`

## Dev notes (Hytale install location)

If you installed Hytale in a non-standard location, set these in `%USERPROFILE%/.gradle/gradle.properties`:

```properties
hytale.install_dir=path/to/Hytale
hytale.decompile_partial=true
```
