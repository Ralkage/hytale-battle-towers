package com.ralkage.battletowers.worldgen;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;

import java.nio.file.Path;

/**
 * Lazy-loading prefab accessor for command-based tower placement.
 *
 * Prefabs must NOT be loaded during plugin setup() because the block type
 * registry is not yet populated at that point - all block names resolve to
 * ID 0, which crashes the server when sending assets to connecting players.
 *
 * Worldgen placement uses its own prefab loading path (via zone/tile configs)
 * and does not depend on this class.
 */
public class BattleTowerPrefabs {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final String[] TIER_PREFAB_FILES = {
            "BattleTower/battletower_tier1.prefab.json",
            "BattleTower/battletower_tier2.prefab.json",
            "BattleTower/battletower_tier3.prefab.json",
            "BattleTower/battletower_shore.prefab.json"
    };

    private static BlockSelection cachedPrefab;
    private static boolean loadAttempted;

    /**
     * Returns a loaded battle tower prefab, loading lazily on first call.
     * Safe to call after the server is fully initialized (e.g., from commands).
     */
    public static BlockSelection get() {
        if (!loadAttempted) {
            loadAttempted = true;
            cachedPrefab = loadFromStore();
        }
        return cachedPrefab;
    }

    private static BlockSelection loadFromStore() {
        try {
            PrefabStore store = PrefabStore.get();
            if (store == null) {
                LOGGER.atWarning().log("PrefabStore not available.");
                return null;
            }

            Path serverPrefabsPath = store.getServerPrefabsPath();
            for (String prefabFile : TIER_PREFAB_FILES) {
                try {
                    BlockSelection prefab = store.getPrefab(serverPrefabsPath.resolve(prefabFile));
                    if (prefab != null) {
                        LOGGER.atInfo().log("Loaded battle tower prefab: %s (%d blocks)",
                                prefabFile, prefab.getBlockCount());
                        return prefab;
                    }
                } catch (Exception ignored) {
                    // Try next tier file.
                }
            }

            LOGGER.atWarning().log("No battle tower prefab found in Server/Prefabs/BattleTower.");
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to load battle tower prefab: %s", e.getMessage());
        }
        return null;
    }
}
