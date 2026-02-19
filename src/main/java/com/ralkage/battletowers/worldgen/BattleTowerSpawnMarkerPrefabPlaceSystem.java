package com.ralkage.battletowers.worldgen;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.prefab.event.PrefabPlaceEntityEvent;
import com.hypixel.hytale.component.system.WorldEventSystem;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.assets.spawnmarker.config.SpawnMarker;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ensures SpawnMarker entities placed from prefabs have a UUIDComponent.
 *
 * Vanilla {@link SpawnMarkerEntity#spawnNPC} asserts that the marker entity has a UUIDComponent and will crash
 * the world thread if it's missing. Our battle tower prefabs include SpawnMarker entities but no UUIDComponent.
 *
 * Running at prefab entity placement time guarantees the UUID exists before SpawnMarkerSystems ticks.
 */
public final class BattleTowerSpawnMarkerPrefabPlaceSystem
        extends WorldEventSystem<EntityStore, PrefabPlaceEntityEvent> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    public BattleTowerSpawnMarkerPrefabPlaceSystem() {
        super(PrefabPlaceEntityEvent.class);
    }

    @Override
    public void handle(Store<EntityStore> store, CommandBuffer<EntityStore> commandBuffer, PrefabPlaceEntityEvent event) {
        Holder<EntityStore> holder = event.getHolder();
        if (holder == null) {
            return;
        }

        SpawnMarkerEntity marker = holder.getComponent(SpawnMarkerEntity.getComponentType());
        if (marker == null) {
            return;
        }

        // Ensure the marker entity has a UUIDComponent (required by vanilla spawn marker systems).
        UUIDComponent uuidComponent = holder.getComponent(UUIDComponent.getComponentType());
        if (uuidComponent == null || uuidComponent.getUuid() == null) {
            UUIDComponent generated = UUIDComponent.randomUUID();
            holder.putComponent(UUIDComponent.getComponentType(), generated);
            uuidComponent = generated;
        }

        // For tower boss markers, record the tower anchor by marker UUID so boss deaths can resolve to the tower.
        UUID markerUuid = uuidComponent.getUuid();
        String markerId = safeSpawnMarkerId(marker);
        if (markerUuid != null && BattleTowerCollapse.isTowerBossMarkerId(markerId)) {
            TransformComponent t = holder.getComponent(TransformComponent.getComponentType());
            World world = null;
            try {
                world = store.getExternalData().getWorld();
            } catch (Exception ignored) {
            }
            if (world != null && t != null) {
                BattleTowerCollapse.registerBossMarkerAnchor(world, markerUuid, t.getPosition());
            }

            // Randomize the boss marker to a real boss SpawnMarker asset (vanilla systems will spawn from this).
            String chosen = chooseRandomBossMarkerId(markerId);
            if (chosen != null && !chosen.equals(markerId)) {
                try {
                    SpawnMarker chosenAsset = SpawnMarker.getAssetMap().getAsset(chosen);
                    if (chosenAsset != null) {
                        marker.setSpawnMarker(chosenAsset);
                        holder.putComponent(SpawnMarkerEntity.getComponentType(), marker);
                        LOGGER.atInfo().log("Tower boss randomized: %s -> %s", markerId, chosen);
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String safeSpawnMarkerId(SpawnMarkerEntity marker) {
        try {
            String id = marker.getSpawnMarkerId();
            return id != null ? id : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String chooseRandomBossMarkerId(String bossMarkerId) {
        List<String> pool = BattleTowerCollapse.getTowerBossPoolForMarker(bossMarkerId);
        if (pool == null || pool.isEmpty()) {
            return bossMarkerId;
        }

        int start = ThreadLocalRandom.current().nextInt(pool.size());
        for (int i = 0; i < pool.size(); i++) {
            String candidate = pool.get((start + i) % pool.size());
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                if (SpawnMarker.getAssetMap().getAsset(candidate) != null) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }
        return bossMarkerId;
    }
}

