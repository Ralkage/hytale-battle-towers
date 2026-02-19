package com.ralkage.battletowers.worldgen;

import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.events.AllWorldsLoadedEvent;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.spawning.ISpawnableWithModel;
import com.hypixel.hytale.server.spawning.SpawningContext;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Custom spawn marker processor that replaces the buggy vanilla SpawnMarkerSystems.
 * Scans for SpawnMarkerEntity components near players and spawns NPCs via NPCPlugin,
 * bypassing the vanilla SpawnMarkerEntity.spawnNPC() which has a UUIDComponent NPE.
 */
public final class BattleTowerSpawner {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final long SCAN_INTERVAL_SECONDS = 5;
    private static volatile int activationRadiusChunks = 4; // ~64 blocks

    /** Tracks markers we've already spawned NPCs for, per-world (by marker entity ref hashCode). */
    private static final Map<String, Set<Integer>> activatedMarkersByWorld = new ConcurrentHashMap<>();

    /** Prevents re-triggering tower collapse every scan once a boss is dead, per-world. */
    private static final Map<String, Set<Integer>> processedDeadBossesByWorld = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "BattleTower-Spawner");
        t.setDaemon(true);
        return t;
    });

    private static volatile ScheduledFuture<?> scanTask;

    private BattleTowerSpawner() {
    }

    public static void setActivationRadiusBlocks(int radiusBlocks) {
        int blocks = Math.max(16, radiusBlocks);
        activationRadiusChunks = Math.max(1, (blocks + (ChunkUtil.SIZE - 1)) / ChunkUtil.SIZE);
    }

    public static void register(EventRegistry eventRegistry) {
        eventRegistry.registerGlobal(AllWorldsLoadedEvent.class, BattleTowerSpawner::onWorldsLoaded);

        // If the plugin loads after worlds are already up, the event may already have fired.
        // In that case, start immediately.
        try {
            Universe universe = Universe.get();
            if (universe != null && universe.getWorlds() != null && !universe.getWorlds().isEmpty()) {
                start();
            }
        } catch (Exception ignored) {
        }

        LOGGER.atInfo().log("BattleTowerSpawner listener registered.");
    }

    private static void onWorldsLoaded(AllWorldsLoadedEvent event) {
        start();
    }

    private static void start() {
        if (scanTask != null) {
            scanTask.cancel(false);
        }
        scanTask = SCHEDULER.scheduleAtFixedRate(
                BattleTowerSpawner::tickAllWorlds, SCAN_INTERVAL_SECONDS, SCAN_INTERVAL_SECONDS, TimeUnit.SECONDS);
        LOGGER.atInfo().log("BattleTowerSpawner started (scan every %ds).", SCAN_INTERVAL_SECONDS);
    }

    public static void shutdown() {
        if (scanTask != null) {
            scanTask.cancel(false);
            scanTask = null;
        }
        try {
            SCHEDULER.shutdownNow();
        } catch (Exception ignored) {
        }
        activatedMarkersByWorld.clear();
        processedDeadBossesByWorld.clear();
        LOGGER.atInfo().log("BattleTowerSpawner stopped.");
    }

    private static void tickAllWorlds() {
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return;
            }
            for (World world : universe.getWorlds().values()) {
                if (world == null) {
                    continue;
                }
                try {
                    world.execute(() -> processMarkers(world));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            LOGGER.atFine().log("Spawner tick failed: %s", e.getMessage());
        }
    }

    private static void processMarkers(World world) {
        try {
            Set<Integer> activatedMarkers = activatedMarkersByWorld
                    .computeIfAbsent(world.getName(), _k -> ConcurrentHashMap.newKeySet());
            Set<Integer> processedDeadBosses = processedDeadBossesByWorld
                    .computeIfAbsent(world.getName(), _k -> ConcurrentHashMap.newKeySet());

            var store = world.getEntityStore().getStore();
            NPCPlugin npcPlugin = NPCPlugin.get();
            if (npcPlugin == null) {
                return;
            }

            // Collect chunk indexes near players
            Set<Long> chunksToScan = new HashSet<>();
            for (var playerRef : world.getPlayerRefs()) {
                try {
                    Vector3d pos = playerRef.getTransform().getPosition();
                    int cx = ChunkUtil.chunkCoordinate((int) Math.floor(pos.getX()));
                    int cz = ChunkUtil.chunkCoordinate((int) Math.floor(pos.getZ()));

                    int r = activationRadiusChunks;
                    for (int dx = -r; dx <= r; dx++) {
                        for (int dz = -r; dz <= r; dz++) {
                            chunksToScan.add(ChunkUtil.indexChunk(cx + dx, cz + dz));
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            if (chunksToScan.isEmpty()) {
                return;
            }

            // Phase 1: Collect pending markers (don't spawn during iteration)
            List<PendingSpawn> pendingSpawns = new ArrayList<>();
            for (long chunkIndex : chunksToScan) {
                WorldChunk chunk = world.getChunkIfLoaded(chunkIndex);
                if (chunk == null || chunk.getEntityChunk() == null) {
                    continue;
                }

                for (var ref : chunk.getEntityChunk().getEntityReferences()) {
                    try {
                        // Fallback: detect dead tower boss via DeathComponent and trigger collapse.
                        // This avoids relying on entity removal (corpse cleanup/unload) and covers cases where
                        // our ECS death system registration fails.
                        try {
                            if (!processedDeadBosses.contains(ref.hashCode())
                                    && store.getArchetype(ref).contains(DeathComponent.getComponentType())) {
                                NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
                                if (npc != null) {
                                    String npcTypeId = npc.getNPCTypeId();
                                    if (BattleTowerCollapse.isBossNpcTypeId(npcTypeId)) {
                                        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
                                        if (t != null) {
                                            processedDeadBosses.add(ref.hashCode());
                                            BattleTowerCollapse.onBossDefeated(world, store, ref, t.getPosition(), npcTypeId);
                                        }
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                        }

                        SpawnMarkerEntity marker = store.getComponent(ref, SpawnMarkerEntity.getComponentType());
                        if (marker == null) {
                            continue;
                        }

                        int markerId = ref.hashCode();
                        if (activatedMarkers.contains(markerId)) {
                            continue;
                        }

                        String markerNpcTypeId = marker.getSpawnMarkerId();
                        if (markerNpcTypeId == null || markerNpcTypeId.isEmpty()) {
                            continue;
                        }

                        String npcTypeId = markerNpcTypeId;
                        if (BattleTowerCollapse.isTowerBossMarkerId(markerNpcTypeId)) {
                            String chosen = chooseRandomTowerBoss(npcPlugin, markerNpcTypeId);
                            if (chosen != null && !chosen.isEmpty()) {
                                npcTypeId = chosen;
                                if (!markerNpcTypeId.equals(chosen)) {
                                    LOGGER.atInfo().log("Tower boss randomized: %s -> %s", markerNpcTypeId, chosen);
                                }
                            }
                        }

                        TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                        if (transform == null) {
                            continue;
                        }
                        Vector3d spawnPos = transform.getPosition();

                        int roleIndex = npcPlugin.getIndex(npcTypeId);
                        if (roleIndex < 0) {
                            LOGGER.atWarning().log("Unknown NPC role: %s", npcTypeId);
                            activatedMarkers.add(markerId);
                            continue;
                        }

                        pendingSpawns.add(new PendingSpawn(markerId, npcTypeId, roleIndex, spawnPos));
                    } catch (Exception e) {
                        LOGGER.atFine().log("Error collecting marker: %s: %s",
                                e.getClass().getSimpleName(), e.getMessage());
                    }
                }
            }

            // Phase 2: Spawn NPCs (safe - not iterating entity refs anymore)
            int spawned = 0;
            for (PendingSpawn ps : pendingSpawns) {
                try {
                    Model model = resolveModel(npcPlugin, ps.roleIndex, ps.npcTypeId);

                    var result = npcPlugin.spawnEntity(
                            store,
                            ps.roleIndex,
                            ps.position,
                            new Vector3f(0, 0, 0),
                             model,
                             (npc, npcRef, s) -> {
                                 LOGGER.atInfo().log("Spawned %s at (%.1f, %.1f, %.1f) model=%s",
                                         ps.npcTypeId, ps.position.getX(), ps.position.getY(), ps.position.getZ(),
                                         model != null ? "resolved" : "null");

                                 if (BattleTowerCollapse.isBossNpcTypeId(ps.npcTypeId)) {
                                     BattleTowerCollapse.registerBossAnchor(world, s, npcRef, ps.position);
                                 }
                             }
                     );

                    if (result != null) {
                        activatedMarkers.add(ps.markerId);
                        spawned++;
                    }
                } catch (Exception e) {
                    LOGGER.atWarning().log("Failed to spawn %s: %s: %s",
                            ps.npcTypeId, e.getClass().getSimpleName(), e.getMessage());
                }
            }

            if (spawned > 0) {
                LOGGER.atInfo().log("BattleTowerSpawner: spawned %d NPCs this tick.", spawned);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("processMarkers error: %s: %s",
                    e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Resolves the Model for an NPC role, matching what NPCSpawnCommand does internally.
     * The Model provides ModelComponent and PersistentModel - without it, NPCs are invisible.
     */
    private static Model resolveModel(NPCPlugin npcPlugin, int roleIndex, String npcTypeId) {
        try {
            var builder = npcPlugin.tryGetCachedValidRole(roleIndex);
            if (builder instanceof ISpawnableWithModel spawnable) {
                SpawningContext ctx = new SpawningContext();
                ctx.setSpawnable(spawnable);
                Model model = ctx.getModel();
                if (model != null) {
                    return model;
                }
                LOGGER.atWarning().log("Model resolved to null for %s", npcTypeId);
            } else {
                LOGGER.atWarning().log("Builder for %s does not implement ISpawnableWithModel", npcTypeId);
            }
        } catch (Exception e) {
            LOGGER.atWarning().log("Failed to resolve model for %s: %s", npcTypeId, e.getMessage());
        }
        return null;
    }

    private record PendingSpawn(int markerId, String npcTypeId, int roleIndex, Vector3d position) {
    }

    private static String chooseRandomTowerBoss(NPCPlugin npcPlugin, String markerNpcTypeId) {
        if (npcPlugin == null) {
            return markerNpcTypeId;
        }

        List<String> pool = BattleTowerCollapse.getTowerBossPoolForMarker(markerNpcTypeId);
        if (pool == null || pool.isEmpty()) {
            return markerNpcTypeId;
        }

        int start = java.util.concurrent.ThreadLocalRandom.current().nextInt(pool.size());
        for (int i = 0; i < pool.size(); i++) {
            String candidate = pool.get((start + i) % pool.size());
            if (candidate == null || candidate.isEmpty()) {
                continue;
            }
            try {
                if (npcPlugin.getIndex(candidate) >= 0) {
                    return candidate;
                }
            } catch (Exception ignored) {
            }
        }

        return markerNpcTypeId;
    }
}
