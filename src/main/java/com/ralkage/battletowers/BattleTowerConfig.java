package com.ralkage.battletowers;

import com.hypixel.hytale.logger.HytaleLogger;
import com.ralkage.battletowers.worldgen.BattleTowerCollapse;
import com.ralkage.battletowers.worldgen.BattleTowerSpawner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

public final class BattleTowerConfig {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String KEY_IMPLOSION_DELAY_SECONDS = "implosion_delay_seconds";
    private static final String KEY_IMPLOSION_ANNOUNCE_RADIUS_BLOCKS = "implosion_announce_radius_blocks";
    private static final String KEY_IMPLOSION_TICK_MS = "implosion_tick_ms";
    private static final String KEY_IMPLOSION_LAYERS_PER_TICK = "implosion_layers_per_tick";
    private static final String KEY_SPAWNER_ACTIVATION_RADIUS_BLOCKS = "spawner_activation_radius_blocks";

    private static final long DEFAULT_IMPLOSION_DELAY_SECONDS = 30;
    private static final int DEFAULT_IMPLOSION_ANNOUNCE_RADIUS_BLOCKS = 64;
    private static final long DEFAULT_IMPLOSION_TICK_MS = 300;
    private static final int DEFAULT_IMPLOSION_LAYERS_PER_TICK = 2;
    private static final int DEFAULT_SPAWNER_ACTIVATION_RADIUS_BLOCKS = 64;

    private BattleTowerConfig() {
    }

    public static void loadAndApply() {
        Path configPath = getDefaultConfigPath();
        Properties props = new Properties();

        try {
            if (Files.notExists(configPath)) {
                writeDefaultConfig(configPath);
            }

            try (InputStream in = Files.newInputStream(configPath)) {
                props.load(in);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("BattleTowers config load failed (%s): %s",
                    configPath, e.getMessage());
        }

        // Ensure new keys are present when upgrading from older configs.
        boolean changed = ensureDefaults(configPath, props);
        if (changed) {
            try (OutputStream out = Files.newOutputStream(configPath)) {
                props.store(out, "BattleTowers settings");
            } catch (Exception ignored) {
            }
        }

        long delaySeconds = getLong(props, KEY_IMPLOSION_DELAY_SECONDS, DEFAULT_IMPLOSION_DELAY_SECONDS, 5, 600);
        int announceRadius = getInt(props, KEY_IMPLOSION_ANNOUNCE_RADIUS_BLOCKS, DEFAULT_IMPLOSION_ANNOUNCE_RADIUS_BLOCKS, 16, 512);
        long tickMs = getLong(props, KEY_IMPLOSION_TICK_MS, DEFAULT_IMPLOSION_TICK_MS, 50, 2000);
        int layersPerTick = getInt(props, KEY_IMPLOSION_LAYERS_PER_TICK, DEFAULT_IMPLOSION_LAYERS_PER_TICK, 1, 10);
        int activationRadiusBlocks = getInt(props, KEY_SPAWNER_ACTIVATION_RADIUS_BLOCKS, DEFAULT_SPAWNER_ACTIVATION_RADIUS_BLOCKS, 16, 512);

        BattleTowerCollapse.setCollapseDelaySeconds(delaySeconds);
        BattleTowerCollapse.setAnnounceRadiusBlocks(announceRadius);
        BattleTowerCollapse.setImplosionTickMs(tickMs);
        BattleTowerCollapse.setLayersPerTick(layersPerTick);
        BattleTowerSpawner.setActivationRadiusBlocks(activationRadiusBlocks);

        LOGGER.atInfo().log("BattleTowers config: delay=%ds announceRadius=%d tickMs=%d layersPerTick=%d activationRadius=%d (file=%s)",
                delaySeconds, announceRadius, tickMs, layersPerTick, activationRadiusBlocks, configPath);
    }

    private static Path getDefaultConfigPath() {
        String appData = System.getenv("APPDATA");
        Path base;
        if (appData != null && !appData.isBlank()) {
            base = Paths.get(appData).resolve("Hytale");
        } else {
            base = Paths.get(System.getProperty("user.home", ".")).resolve("AppData").resolve("Roaming").resolve("Hytale");
        }
        return base.resolve("BattleTowers.properties");
    }

    private static void writeDefaultConfig(Path configPath) throws IOException {
        Files.createDirectories(configPath.getParent());

        Properties defaults = new Properties();
        defaults.setProperty(KEY_IMPLOSION_DELAY_SECONDS, Long.toString(DEFAULT_IMPLOSION_DELAY_SECONDS));
        defaults.setProperty(KEY_IMPLOSION_ANNOUNCE_RADIUS_BLOCKS, Integer.toString(DEFAULT_IMPLOSION_ANNOUNCE_RADIUS_BLOCKS));
        defaults.setProperty(KEY_IMPLOSION_TICK_MS, Long.toString(DEFAULT_IMPLOSION_TICK_MS));
        defaults.setProperty(KEY_IMPLOSION_LAYERS_PER_TICK, Integer.toString(DEFAULT_IMPLOSION_LAYERS_PER_TICK));
        defaults.setProperty(KEY_SPAWNER_ACTIVATION_RADIUS_BLOCKS, Integer.toString(DEFAULT_SPAWNER_ACTIVATION_RADIUS_BLOCKS));

        try (OutputStream out = Files.newOutputStream(configPath)) {
            defaults.store(out, "BattleTowers settings");
        }
    }

    private static boolean ensureDefaults(Path configPath, Properties props) {
        boolean changed = false;
        changed |= putIfMissing(props, KEY_IMPLOSION_DELAY_SECONDS, Long.toString(DEFAULT_IMPLOSION_DELAY_SECONDS));
        changed |= putIfMissing(props, KEY_IMPLOSION_ANNOUNCE_RADIUS_BLOCKS, Integer.toString(DEFAULT_IMPLOSION_ANNOUNCE_RADIUS_BLOCKS));
        changed |= putIfMissing(props, KEY_IMPLOSION_TICK_MS, Long.toString(DEFAULT_IMPLOSION_TICK_MS));
        changed |= putIfMissing(props, KEY_IMPLOSION_LAYERS_PER_TICK, Integer.toString(DEFAULT_IMPLOSION_LAYERS_PER_TICK));
        changed |= putIfMissing(props, KEY_SPAWNER_ACTIVATION_RADIUS_BLOCKS, Integer.toString(DEFAULT_SPAWNER_ACTIVATION_RADIUS_BLOCKS));

        if (changed) {
            LOGGER.atInfo().log("BattleTowers config upgraded with missing defaults (file=%s).", configPath);
        }
        return changed;
    }

    private static boolean putIfMissing(Properties props, String key, String value) {
        String existing = props.getProperty(key);
        if (existing == null || existing.isBlank()) {
            props.setProperty(key, value);
            return true;
        }
        return false;
    }

    private static int getInt(Properties props, String key, int def, int min, int max) {
        try {
            String raw = props.getProperty(key);
            if (raw == null || raw.isBlank()) {
                return def;
            }
            int v = Integer.parseInt(raw.trim());
            if (v < min) {
                return min;
            }
            if (v > max) {
                return max;
            }
            return v;
        } catch (Exception ignored) {
            return def;
        }
    }

    private static long getLong(Properties props, String key, long def, long min, long max) {
        try {
            String raw = props.getProperty(key);
            if (raw == null || raw.isBlank()) {
                return def;
            }
            long v = Long.parseLong(raw.trim());
            if (v < min) {
                return min;
            }
            if (v > max) {
                return max;
            }
            return v;
        } catch (Exception ignored) {
            return def;
        }
    }
}
