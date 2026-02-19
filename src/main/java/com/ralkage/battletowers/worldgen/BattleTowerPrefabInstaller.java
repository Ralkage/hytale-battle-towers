package com.ralkage.battletowers.worldgen;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Ensures battle tower prefabs are available under the server prefab root directory.
 *
 * PrefabListAsset supports loading prefabs from {@code RootDirectory: "Server"} which resolves to the
 * runtime {@code prefabs/} folder (relative to the server working directory). We install our prefab
 * JSON files there so worldgen can resolve {@code BattleTower.*} prefab ids.
 */
public final class BattleTowerPrefabInstaller {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String RESOURCE_DIR = "Server/Prefabs/BattleTower/";
    private static final String[] PREFAB_FILES = {
            "battletower_tier1.prefab.json",
            "battletower_tier2.prefab.json",
            "battletower_tier3.prefab.json",
            "battletower_shore.prefab.json"
    };

    private BattleTowerPrefabInstaller() {
    }

    public static void ensureInstalled(Class<?> resourceAnchor) {
        Path destDir = Path.of("prefabs").resolve("BattleTower");
        try {
            Files.createDirectories(destDir);
        } catch (IOException e) {
            LOGGER.atWarning().log("Failed to create prefab directory '%s': %s", destDir.toAbsolutePath(), e.getMessage());
            return;
        }

        ClassLoader cl = resourceAnchor.getClassLoader();
        int installed = 0;
        for (String file : PREFAB_FILES) {
            String resourcePath = RESOURCE_DIR + file;
            Path destFile = destDir.resolve(file);

            try (InputStream in = cl.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    LOGGER.atWarning().log("Missing prefab resource: %s", resourcePath);
                    continue;
                }
                Files.copy(in, destFile, StandardCopyOption.REPLACE_EXISTING);
                installed++;
            } catch (IOException e) {
                LOGGER.atWarning().log("Failed to install prefab '%s' -> '%s': %s",
                        resourcePath, destFile.toAbsolutePath(), e.getMessage());
            }
        }

        if (installed > 0) {
            LOGGER.atInfo().log("Installed %d battle tower prefabs into '%s'.", installed, destDir.toAbsolutePath());
        } else {
            LOGGER.atWarning().log("No battle tower prefabs were installed (check resources and file permissions).");
        }
    }
}

