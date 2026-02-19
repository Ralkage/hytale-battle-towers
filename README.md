# BattleTowers (Hytale Plugin)

Adds Battle Towers worldgen with randomized bosses and a top-down tower implosion when the boss is defeated.

## Build (jar for distribution)

From the repo root:

```powershell
.\gradlew.bat --no-daemon clean jar
```

Output:

- `build/libs/BattleTowers-0.1.0.jar`

## Install

- Copy `build/libs/BattleTowers-0.1.0.jar` into your server's plugins directory.
- Restart the server (ensure your server launch enables plugins, e.g. `--accept-early-plugins` if needed).

## Config

On Windows the plugin reads (and will create on first run):

- `%APPDATA%\\Hytale\\BattleTowers.properties`

### Configuring the Template
If you for example installed the game in a non-standard location, you will need to tell the project about that.
The recommended way is to create a file at `%USERPROFILE%/.gradle/gradle.properties` to set these properties globally.

```properties
# Set a custom game install location
hytale.install_dir=path/to/Hytale

# Speed up the decompilation process significantly, by only including the core hytale packages.
# Recommended if decompiling the game takes a very long time on your PC.
hytale.decompile_partial=true
```
