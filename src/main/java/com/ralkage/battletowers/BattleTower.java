package com.ralkage.battletowers;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.ralkage.battletowers.worldgen.BattleTowerCollapse;
import com.ralkage.battletowers.worldgen.BattleTowerBossDeathSystem;
import com.ralkage.battletowers.worldgen.BattleTowerPrefabInstaller;
import com.ralkage.battletowers.worldgen.BattleTowerSpawnMarkerPrefabPlaceSystem;
import com.ralkage.battletowers.worldgen.BattleTowerSpawnMarkerUuidBackfill;
import com.ralkage.battletowers.worldgen.BattleTowerWorldGen;
import com.ralkage.battletowers.vault.BattleTowerVaults;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BattleTower extends JavaPlugin {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final AtomicBoolean I18N_LOGGED = new AtomicBoolean(false);

    public BattleTower(JavaPluginInit init) {
        super(init);
        LOGGER.atInfo().log("Battle Towers plugin loaded.");
    }

    @Override
    protected void setup() {
        LOGGER.atInfo().log("Initializing Battle Towers");

        BattleTowerConfig.loadAndApply();

        // Make tower prefabs available under the server prefab root (prefabs/...) so PrefabListAsset can load them.
        BattleTowerPrefabInstaller.ensureInstalled(BattleTower.class);

        // Register the /battletowers command
        getCommandRegistry().registerCommand(new BattleTowers());

        // Register worldgen event listener for battle tower placement
        BattleTowerWorldGen.register(getEventRegistry());
        BattleTowerCollapse.register(getEventRegistry());
        BattleTowerSpawnMarkerUuidBackfill.register(getEventRegistry());
        BattleTowerVaults.register(getEventRegistry());

        // Log i18n resolution after a short delay (BattleTowers is an early plugin; i18n loads later in boot).
        try {
            CompletableFuture.delayedExecutor(20, TimeUnit.SECONDS).execute(BattleTower::logI18nSanityOnce);
        } catch (Exception ignored) {
        }

        // Trigger tower collapse on actual boss death (DeathComponent), not just entity removal.
        try {
            getEntityStoreRegistry().registerSystem(new BattleTowerBossDeathSystem());
            LOGGER.atInfo().log("BattleTowerBossDeathSystem registered.");
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to register BattleTowerBossDeathSystem: %s", e.getMessage());
        }

        // Ensure tower SpawnMarker entities get UUIDComponent during prefab placement (prevents vanilla NPE in spawnNPC()).
        try {
            getEntityStoreRegistry().registerSystem(new BattleTowerSpawnMarkerPrefabPlaceSystem());
            LOGGER.atInfo().log("BattleTowerSpawnMarkerPrefabPlaceSystem registered.");
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to register BattleTowerSpawnMarkerPrefabPlaceSystem: %s", e.getMessage());
        }

        // NOTE: UUID backfill for existing SpawnMarker entities is handled by BattleTowerSpawnMarkerUuidBackfill.

        // NOTE: Prefabs are NOT loaded here. The block type registry is not yet
        // populated during setup(), so loading prefabs would resolve all block
        // names to ID 0 and crash when sending assets to connecting players.
        // Prefabs are loaded lazily by BattleTowerPrefabs.get() when first needed.
    }

    @Override
    protected void shutdown() {
        BattleTowerCollapse.shutdown();
    }

    public static HytaleLogger getPluginLogger() {
        return LOGGER;
    }

    private static void logI18nSanityOnce() {
        if (!I18N_LOGGED.compareAndSet(false, true)) {
            return;
        }
        try {
            I18nModule i18n = I18nModule.get();
            if (i18n == null) {
                return;
            }

            String lang = "en-US";
            String k1 = "server.items.BattleTowers_Tower_Core.name";
            String k2 = "items.BattleTowers_Tower_Core.name";
            String k3 = "server.items.BattleTowers_Tower_Sigil.name";
            String k4 = "items.BattleTowers_Tower_Sigil.name";

            String v1 = i18n.getMessage(lang, k1);
            String v2 = i18n.getMessage(lang, k2);
            String v3 = i18n.getMessage(lang, k3);
            String v4 = i18n.getMessage(lang, k4);

            LOGGER.atInfo().log("BattleTowers i18n sanity (%s): %s='%s', %s='%s', %s='%s', %s='%s'",
                    lang, k1, v1, k2, v2, k3, v3, k4, v4);
        } catch (Exception e) {
            LOGGER.atFine().log("BattleTowers i18n sanity check failed: %s", e.getMessage());
        }
    }
}
