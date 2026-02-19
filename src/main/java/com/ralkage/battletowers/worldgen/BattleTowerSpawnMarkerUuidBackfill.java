package com.ralkage.battletowers.worldgen;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.EntityChunk;
import com.hypixel.hytale.server.core.universe.world.events.AllWorldsLoadedEvent;
import com.hypixel.hytale.server.core.universe.world.events.ChunkPreLoadProcessEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Backfills UUIDComponent on SpawnMarker entities that were loaded without it.
 *
 * Vanilla SpawnMarkerEntity.spawnNPC() asserts UUIDComponent is present; missing UUID will crash the world thread.
 * We fix existing markers at world load, and also on chunk pre-load processing for safety.
 */
public final class BattleTowerSpawnMarkerUuidBackfill {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private BattleTowerSpawnMarkerUuidBackfill() {
    }

    public static void register(EventRegistry registry) {
        registry.registerGlobal(AllWorldsLoadedEvent.class, _e -> backfillAllWorlds());
        registry.registerGlobal(ChunkPreLoadProcessEvent.class, BattleTowerSpawnMarkerUuidBackfill::onChunkPreLoad);
        LOGGER.atInfo().log("BattleTowerSpawnMarkerUuidBackfill listener registered.");
    }

    private static void backfillAllWorlds() {
        try {
            Universe universe = Universe.get();
            if (universe == null || universe.getWorlds() == null) {
                return;
            }
            for (World world : universe.getWorlds().values()) {
                if (world == null) {
                    continue;
                }
                try {
                    world.execute(() -> backfillWorld(world));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static void backfillWorld(World world) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();

            var markerType = SpawnMarkerEntity.getComponentType();
            var uuidType = UUIDComponent.getComponentType();
            var transformType = TransformComponent.getComponentType();

            Query<EntityStore> query = Query.and(markerType, Query.not(uuidType));
            AtomicInteger fixed = new AtomicInteger();

            store.forEachChunk(query, (archetypeChunk, commandBuffer) -> {
                for (int i = 0; i < archetypeChunk.size(); i++) {
                    Ref<EntityStore> ref = archetypeChunk.getReferenceTo(i);
                    UUIDComponent generated = UUIDComponent.randomUUID();
                    commandBuffer.putComponent(ref, uuidType, generated);
                    fixed.incrementAndGet();

                    try {
                        SpawnMarkerEntity marker = archetypeChunk.getComponent(i, markerType);
                        if (marker == null) {
                            continue;
                        }
                        String markerId = marker.getSpawnMarkerId();
                        if (!BattleTowerCollapse.isTowerBossSpawnMarkerId(markerId)) {
                            continue;
                        }

                        TransformComponent t = archetypeChunk.getComponent(i, transformType);
                        if (t != null && generated.getUuid() != null) {
                            BattleTowerCollapse.registerBossMarkerAnchor(world, generated.getUuid(), t.getPosition());
                        }
                    } catch (Exception ignored) {
                    }
                }
            });

            int n = fixed.get();
            if (n > 0) {
                LOGGER.atInfo().log("Backfilled UUIDComponent on %d SpawnMarker entities in world '%s'.", n, world.getName());
            }
        } catch (Exception e) {
            LOGGER.atFine().log("SpawnMarker UUID backfill skipped for world '%s': %s",
                    world != null ? world.getName() : "null", e.getMessage());
        }
    }

    private static void onChunkPreLoad(ChunkPreLoadProcessEvent event) {
        WorldChunk chunk = event.getChunk();
        if (chunk == null) {
            return;
        }

        World world = chunk.getWorld();
        if (world == null) {
            return;
        }

        try {
            var markerType = SpawnMarkerEntity.getComponentType();
            var uuidType = UUIDComponent.getComponentType();
            var transformType = TransformComponent.getComponentType();

            // 1) Fix serialized entity holders in the chunk store (most important: prevents loading UUID-less markers).
            int fixed = 0;
            try {
                var chunkHolder = event.getHolder();
                if (chunkHolder != null) {
                    EntityChunk entityChunk = chunkHolder.getComponent(EntityChunk.getComponentType());
                    if (entityChunk != null && entityChunk.getEntityHolders() != null) {
                        for (var entityHolder : entityChunk.getEntityHolders()) {
                            if (entityHolder == null) {
                                continue;
                            }
                            SpawnMarkerEntity marker = entityHolder.getComponent(markerType);
                            if (marker == null) {
                                continue;
                            }
                            UUIDComponent uuidComponent = entityHolder.getComponent(uuidType);
                            String markerId = marker.getSpawnMarkerId();
                            if (uuidComponent != null && uuidComponent.getUuid() != null) {
                                if (BattleTowerCollapse.isTowerBossSpawnMarkerId(markerId)) {
                                    TransformComponent t = entityHolder.getComponent(transformType);
                                    Vector3d pos = t != null ? t.getPosition() : null;
                                    UUID markerUuid = uuidComponent.getUuid();
                                    if (pos != null && markerUuid != null) {
                                        BattleTowerCollapse.registerBossMarkerAnchor(world, markerUuid, pos);
                                    }
                                }
                                continue;
                            }

                            UUIDComponent generated = UUIDComponent.randomUUID();
                            entityHolder.putComponent(uuidType, generated);
                            fixed++;
                            try {
                                entityChunk.markNeedsSaving();
                            } catch (Exception ignored) {
                            }

                            if (BattleTowerCollapse.isTowerBossSpawnMarkerId(markerId)) {
                                TransformComponent t = entityHolder.getComponent(transformType);
                                Vector3d pos = t != null ? t.getPosition() : null;
                                UUID markerUuid = generated.getUuid();
                                if (pos != null && markerUuid != null) {
                                    BattleTowerCollapse.registerBossMarkerAnchor(world, markerUuid, pos);
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            // 2) Fix already-loaded entity references in the entity store (safety net).
            try {
                EntityChunk entityChunk = chunk.getEntityChunk();
                if (entityChunk != null && entityChunk.getEntityReferences() != null) {
                    Store<EntityStore> store = world.getEntityStore().getStore();
                    for (var ref : entityChunk.getEntityReferences()) {
                        try {
                            SpawnMarkerEntity marker = store.getComponent(ref, markerType);
                            if (marker == null) {
                                continue;
                            }

                            UUIDComponent uuidComponent = store.getComponent(ref, uuidType);
                            String markerId = marker.getSpawnMarkerId();
                            if (uuidComponent != null && uuidComponent.getUuid() != null) {
                                if (BattleTowerCollapse.isTowerBossSpawnMarkerId(markerId)) {
                                    TransformComponent t = store.getComponent(ref, transformType);
                                    Vector3d pos = t != null ? t.getPosition() : null;
                                    UUID markerUuid = uuidComponent.getUuid();
                                    if (pos != null && markerUuid != null) {
                                        BattleTowerCollapse.registerBossMarkerAnchor(world, markerUuid, pos);
                                    }
                                }
                                continue;
                            }

                            UUIDComponent generated = UUIDComponent.randomUUID();
                            store.addComponent(ref, uuidType, generated);
                            fixed++;

                            if (BattleTowerCollapse.isTowerBossSpawnMarkerId(markerId)) {
                                TransformComponent t = store.getComponent(ref, transformType);
                                Vector3d pos = t != null ? t.getPosition() : null;
                                UUID markerUuid = generated.getUuid();
                                if (pos != null && markerUuid != null) {
                                    BattleTowerCollapse.registerBossMarkerAnchor(world, markerUuid, pos);
                                }
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            if (fixed > 0) {
                LOGGER.atInfo().log("Backfilled UUIDComponent on %d SpawnMarker entities during chunk pre-load in world '%s'.",
                        fixed, world.getName());
            }
        } catch (Exception ignored) {
        }
    }
}
