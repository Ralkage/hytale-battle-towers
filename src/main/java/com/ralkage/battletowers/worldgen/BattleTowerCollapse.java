package com.ralkage.battletowers.worldgen;

import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.entity.EntityRemoveEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.ParticleUtil;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.modules.entity.item.PickupItemComponent;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.DestroyableBlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.ItemContainerBlockState;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.components.SpawnMarkerReference;
import com.hypixel.hytale.server.spawning.spawnmarkers.SpawnMarkerEntity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Tower implosion logic for Battle Towers.
 *
 * When a tower boss NPC is defeated, the tower implodes shortly after.
 */
public final class BattleTowerCollapse {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Boss marker IDs baked into the BattleTower prefabs.
     * These are used as *markers* and get randomized to a real boss NPC role at spawn time.
     */
    private static final Set<String> TOWER_BOSS_MARKER_IDS = Set.of(
            "Skeleton_Archmage",
            "Outlander_Priest"
    );

    /**
     * NPC role IDs eligible to be a tower boss.
     * IMPORTANT: Keep this list limited to tower-spawned bosses only; otherwise killing that NPC elsewhere
     * could trigger collapse logic.
     */
    private static final List<String> TOWER_BOSS_POOL_TIER_1_2 = List.of(
            "Skeleton_Archmage",
            "Skeleton_Frost_Archmage",
            "Skeleton_Sand_Archmage"
    );
    private static final List<String> TOWER_BOSS_POOL_TIER_3 = List.of(
            "Outlander_Priest",
            "Golem_Firesteel",
            "Golem_Crystal_Earth",
            "Golem_Crystal_Flame",
            "Golem_Crystal_Frost",
            "Golem_Crystal_Sand",
            "Golem_Crystal_Thunder",
            "Golem_Guardian_Void"
    );

    private static final Set<String> BOSS_NPC_TYPE_IDS = Set.of(
            "Skeleton_Archmage",
            "Skeleton_Frost_Archmage",
            "Skeleton_Sand_Archmage",
            "Outlander_Priest",
            "Golem_Firesteel",
            "Golem_Crystal_Earth",
            "Golem_Crystal_Flame",
            "Golem_Crystal_Frost",
            "Golem_Crystal_Sand",
            "Golem_Crystal_Thunder",
            "Golem_Guardian_Void"
    );

    public static boolean isTowerBossMarkerId(String markerNpcTypeId) {
        return markerNpcTypeId != null && TOWER_BOSS_MARKER_IDS.contains(markerNpcTypeId);
    }

    /**
     * True for any SpawnMarker ID that represents a tower boss marker in-world.
     *
     * Important distinction:
     * - {@link #isTowerBossMarkerId(String)} is for the *placeholder* IDs baked into prefabs (used to decide whether to randomize).
     * - This method is for already-randomized/persisted markers (used to record tower anchors).
     */
    public static boolean isTowerBossSpawnMarkerId(String markerId) {
        if (markerId == null || markerId.isEmpty()) {
            return false;
        }
        return TOWER_BOSS_MARKER_IDS.contains(markerId) || BOSS_NPC_TYPE_IDS.contains(markerId);
    }

    public static List<String> getTowerBossPoolForMarker(String markerNpcTypeId) {
        if ("Outlander_Priest".equals(markerNpcTypeId)) {
            return TOWER_BOSS_POOL_TIER_3;
        }
        return TOWER_BOSS_POOL_TIER_1_2;
    }
 
    private static volatile long collapseDelaySeconds = 30;
    private static volatile long implosionTickMs = 300;
    // Tower prefab footprint is ~13x13, so radius ~9 covers corners; keep a little padding.
    private static final int IMPLOSION_RADIUS = 10;
    // Boss markers aren't perfectly centered in every prefab; allow wider scans and a larger final sweep to avoid stray pillars.
    private static final int TOP_SCAN_RADIUS = IMPLOSION_RADIUS + 12;
    private static final int FINAL_SWEEP_EXTRA_RADIUS = 12;
    private static final int MAX_SWEEP_RADIUS = 28;
    private static final int ROOF_HEADROOM = 20;     // extra range above player for roof/spire
    private static final int TOWER_CLEARANCE = 256;    // max range below player (used for base/ground scans)
    // We avoid carving terrain via shouldImplode() and stop based on computed ground surface.
    private static volatile int layersPerTick = 2;
    private static volatile int nearbyPlayerRadiusBlocks = 64;
    private static final int MOB_PURGE_RADIUS = IMPLOSION_RADIUS + 10;
    private static final int DROP_CLEAN_RADIUS = IMPLOSION_RADIUS + 6;
    // Preserve a few layers at the bottom so the tower leaves a ruin/foundation instead of a crater.
    private static final int RUIN_PRESERVE_LAYERS = 3;

    /**
     * Tracks which tower a boss belongs to at spawn time, so if the boss is knocked off and killed elsewhere,
     * the implosion still happens at the tower.
     */
    private static final ConcurrentHashMap<UUID, TowerKey> BOSS_UUID_TO_TOWER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, TowerKey> BOSS_REFHASH_TO_TOWER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, TowerKey> BOSS_REFID_TO_TOWER = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, TowerKey> BOSS_MARKER_UUID_TO_TOWER = new ConcurrentHashMap<>();
    private static final Set<UUID> DEFEATED_BOSS_UUIDS = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> DEFEATED_BOSS_REFHASHES = ConcurrentHashMap.newKeySet();
    private static final Set<Long> DEFEATED_BOSS_REFIDS = ConcurrentHashMap.newKeySet();

    /**
     * IMPORTANT: Don't use Explosion_Big as the per-layer effect.
     * Explosion_Big includes lingering fire spawners (Explosion_Big_Fires / _Fire_Ground) which look like
     * floating fires when spawned mid-air during the top-down collapse.
     */
    private static final String[] COLLAPSE_PARTICLES = {
            "Explosion_Small",
            "Block_Break_Stone",
            "Block.Stone.Block_Break_Stone"
    };
    private static final String[] FINISH_PARTICLES = {
            // Avoid Explosion_Big here too; it spawns lingering fires.
            "Impact_Explosion",
            "Explosion_Small"
    };
    private static volatile String resolvedCollapseParticleId = null;
    private static volatile String resolvedFinishParticleId = null;

    private static final String[] EXPLOSION_SOUNDS = {
            "SFX_Golem_Earth_Slam_Impact",
            "SFX_Bomb_Fire_Goblin_Death",
            "SFX_GunPvP_Grenade_Frag_Death",
            "SFX_Stone_Break"
    };
    private static final String[] RUMBLE_SOUNDS = {
            "SFX_Golem_Earth_Stomp_Impact",
            "SFX_Golem_Sand_Stomp_Impact",
            "SFX_Z3_Emit_Cave_Ice_Rumble"
    };
    private static volatile int resolvedExplosionSoundId = Integer.MIN_VALUE;
    private static volatile int resolvedRumbleSoundId = Integer.MIN_VALUE;

    private static final ConcurrentHashMap<TowerKey, TowerState> TOWERS = new ConcurrentHashMap<>();

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(
            new NamedDaemonThreadFactory("BattleTower-Implosion"));

    private BattleTowerCollapse() {
    }

    public static long getCollapseDelaySeconds() {
        return collapseDelaySeconds;
    }

    public static void setCollapseDelaySeconds(long seconds) {
        collapseDelaySeconds = Math.max(5, seconds);
    }

    public static long getImplosionTickMs() {
        return implosionTickMs;
    }

    public static void setImplosionTickMs(long tickMs) {
        // Keep within reasonable bounds to avoid runaway scheduling or ultra-slow collapse.
        implosionTickMs = Math.max(50, Math.min(2000, tickMs));
    }

    public static int getLayersPerTick() {
        return layersPerTick;
    }

    public static void setLayersPerTick(int layers) {
        layersPerTick = Math.max(1, Math.min(10, layers));
    }

    public static void setAnnounceRadiusBlocks(int radiusBlocks) {
        nearbyPlayerRadiusBlocks = Math.max(16, radiusBlocks);
    }

    public static void register(EventRegistry eventRegistry) {
        eventRegistry.registerGlobal(EntityRemoveEvent.class, BattleTowerCollapse::onEntityRemove);
        LOGGER.atInfo().log("BattleTowerCollapse listener registered.");
    }

    /**
     * Manually trigger an implosion at the given coordinates (for testing via command).
     * The baseY is the starting Y for the implosion - a few bottom layers will be preserved as a ruin.
     */
    public static void triggerImplosion(World world, int x, int y, int z) {
        triggerImplosion(world, x, y, z, y);
    }

    public static void triggerImplosion(World world, int x, int y, int z, int baseY) {
        TowerKey key = new TowerKey(world.getName(), x, y, z);
        TowerState state = TOWERS.computeIfAbsent(key, _k -> new TowerState());
        if (state.collapseScheduled) {
            return;
        }
        state.collapseScheduled = true;
        state.baseY = baseY;

        long delaySeconds = getCollapseDelaySeconds();
        LOGGER.atInfo().log("Manual implosion triggered at (%d, %d, %d) baseY=%d - in %d seconds.",
                x, y, z, baseY, delaySeconds);
        scheduleImplosionStart(world, key, state, delaySeconds);
    }

    public static void shutdown() {
        try {
            SCHEDULER.shutdownNow();
        } catch (Exception ignored) {
        }
        TOWERS.clear();
        BOSS_UUID_TO_TOWER.clear();
        BOSS_REFHASH_TO_TOWER.clear();
        BOSS_REFID_TO_TOWER.clear();
        BOSS_MARKER_UUID_TO_TOWER.clear();
        DEFEATED_BOSS_UUIDS.clear();
        DEFEATED_BOSS_REFHASHES.clear();
        DEFEATED_BOSS_REFIDS.clear();
    }

    public static boolean isBossNpcTypeId(String npcTypeId) {
        return BOSS_NPC_TYPE_IDS.contains(npcTypeId);
    }

    public static void registerBossAnchor(World world, Store<EntityStore> store, Ref<EntityStore> bossRef, Vector3d towerAnchorPos) {
        if (world == null || store == null || bossRef == null || towerAnchorPos == null) {
            return;
        }

        // Only track anchors for known tower bosses.
        try {
            NPCEntity npc = store.getComponent(bossRef, NPCEntity.getComponentType());
            if (npc != null && !isBossNpcTypeId(npc.getNPCTypeId())) {
                return;
            }
        } catch (Exception ignored) {
        }

        int x = (int) Math.floor(towerAnchorPos.getX());
        int y = (int) Math.floor(towerAnchorPos.getY());
        int z = (int) Math.floor(towerAnchorPos.getZ());
        TowerKey key = new TowerKey(world.getName(), x, y, z);

        // Track this tower anchor even before collapse is scheduled (used for vault detection).
        TowerState state = TOWERS.computeIfAbsent(key, _k -> new TowerState());
        state.baseY = y;

        BOSS_REFHASH_TO_TOWER.put(bossRef.hashCode(), key);
        BOSS_REFID_TO_TOWER.put(refId(store, bossRef), key);

        try {
            UUIDComponent uuidComponent = store.getComponent(bossRef, UUIDComponent.getComponentType());
            if (uuidComponent != null && uuidComponent.getUuid() != null) {
                BOSS_UUID_TO_TOWER.put(uuidComponent.getUuid(), key);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Records a tower anchor against a boss spawn-marker UUID.
     *
     * This allows resolving the tower location later via {@link SpawnMarkerReference} on the spawned boss NPC
     * (even if the boss is moved away from the tower before dying).
     */
    public static void registerBossMarkerAnchor(World world, UUID markerUuid, Vector3d towerAnchorPos) {
        if (world == null || markerUuid == null || towerAnchorPos == null) {
            return;
        }

        int x = (int) Math.floor(towerAnchorPos.getX());
        int y = (int) Math.floor(towerAnchorPos.getY());
        int z = (int) Math.floor(towerAnchorPos.getZ());
        TowerKey key = new TowerKey(world.getName(), x, y, z);

        // Track this tower anchor even before collapse is scheduled (used for vault detection).
        TowerState state = TOWERS.computeIfAbsent(key, _k -> new TowerState());
        state.baseY = y;

        BOSS_MARKER_UUID_TO_TOWER.put(markerUuid, key);
    }

    public static boolean isTowerVaultChest(World world, int blockX, int blockY, int blockZ) {
        if (world == null) {
            return false;
        }

        // Restrict to chests near the tower-top boss anchor so mid-tower chests remain free loot.
        final int radius = 28;
        final int radiusSq = radius * radius;
        final int maxBelowTop = 20;
        final int maxAboveTop = 8;

        String worldName = world.getName();
        for (var entry : TOWERS.entrySet()) {
            TowerKey key = entry.getKey();
            if (key == null || !worldName.equals(key.worldName())) {
                continue;
            }

            int dx = blockX - key.x();
            int dz = blockZ - key.z();
            if (dx * dx + dz * dz > radiusSq) {
                continue;
            }

            if (blockY < (key.y() - maxBelowTop) || blockY > (key.y() + maxAboveTop)) {
                continue;
            }

            return true;
        }

        return false;
    }

    private static void cleanupBossAnchor(Store<EntityStore> store, Ref<EntityStore> bossRef) {
        if (bossRef == null) {
            return;
        }

        BOSS_REFHASH_TO_TOWER.remove(bossRef.hashCode());
        if (store != null) {
            try {
                BOSS_REFID_TO_TOWER.remove(refId(store, bossRef));
            } catch (Exception ignored) {
            }
        }

        if (store == null) {
            return;
        }
        try {
            UUIDComponent uuidComponent = store.getComponent(bossRef, UUIDComponent.getComponentType());
            if (uuidComponent != null && uuidComponent.getUuid() != null) {
                BOSS_UUID_TO_TOWER.remove(uuidComponent.getUuid());
            }
        } catch (Exception ignored) {
        }
    }

    private static void markBossDefeated(Store<EntityStore> store, Ref<EntityStore> bossRef) {
        if (bossRef == null) {
            return;
        }
        DEFEATED_BOSS_REFHASHES.add(bossRef.hashCode());
        if (store != null) {
            try {
                DEFEATED_BOSS_REFIDS.add(refId(store, bossRef));
            } catch (Exception ignored) {
            }
        }

        if (store == null) {
            return;
        }
        try {
            UUIDComponent uuidComponent = store.getComponent(bossRef, UUIDComponent.getComponentType());
            if (uuidComponent != null && uuidComponent.getUuid() != null) {
                DEFEATED_BOSS_UUIDS.add(uuidComponent.getUuid());
            }
        } catch (Exception ignored) {
        }
    }

    private static long refId(Store<EntityStore> store, Ref<EntityStore> ref) {
        // Ref.hashCode() is not guaranteed to be stable. Use storeIndex+refIndex for a stable runtime identifier.
        int storeIndex = store != null ? store.getStoreIndex() : 0;
        int refIndex = ref != null ? ref.getIndex() : 0;
        return (((long) storeIndex) << 32) | (refIndex & 0xFFFFFFFFL);
    }

    private static TowerKey resolveBossTowerKey(World world, Store<EntityStore> store, Ref<EntityStore> bossRef) {
        if (world == null || bossRef == null) {
            return null;
        }

        if (store != null) {
            try {
                UUIDComponent uuidComponent = store.getComponent(bossRef, UUIDComponent.getComponentType());
                if (uuidComponent != null && uuidComponent.getUuid() != null) {
                    TowerKey key = BOSS_UUID_TO_TOWER.get(uuidComponent.getUuid());
                    if (key != null && world.getName().equals(key.worldName())) {
                        return key;
                    }
                }
            } catch (Exception ignored) {
            }
        }

        if (store != null) {
            try {
                TowerKey byId = BOSS_REFID_TO_TOWER.get(refId(store, bossRef));
                if (byId != null && world.getName().equals(byId.worldName())) {
                    return byId;
                }
            } catch (Exception ignored) {
            }
        }

        TowerKey byRef = BOSS_REFHASH_TO_TOWER.get(bossRef.hashCode());
        if (byRef != null && world.getName().equals(byRef.worldName())) {
            return byRef;
        }

        // Fallback: if the boss was spawned by a SpawnMarker, resolve the marker UUID to a tower anchor.
        if (store != null) {
            try {
                SpawnMarkerReference spawnMarkerRef = store.getComponent(bossRef, SpawnMarkerReference.getComponentType());
                if (spawnMarkerRef != null && spawnMarkerRef.getReference() != null) {
                    UUID markerUuid = spawnMarkerRef.getReference().getUuid();
                    if (markerUuid != null) {
                        TowerKey key = BOSS_MARKER_UUID_TO_TOWER.get(markerUuid);
                        if (key != null && world.getName().equals(key.worldName())) {
                            // Cache for this boss entity as well (helps if multiple systems query it).
                            try {
                                UUIDComponent uuidComponent = store.getComponent(bossRef, UUIDComponent.getComponentType());
                                if (uuidComponent != null && uuidComponent.getUuid() != null) {
                                    BOSS_UUID_TO_TOWER.put(uuidComponent.getUuid(), key);
                                }
                            } catch (Exception ignored) {
                            }
                            try {
                                BOSS_REFID_TO_TOWER.put(refId(store, bossRef), key);
                            } catch (Exception ignored) {
                            }
                            BOSS_REFHASH_TO_TOWER.put(bossRef.hashCode(), key);
                            return key;
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return null;
    }

    public static void onBossDefeated(World world, Store<EntityStore> store, Ref<EntityStore> bossRef, Vector3d bossPos, String npcTypeId) {
        if (world == null || bossPos == null) {
            return;
        }

        TowerKey key = resolveBossTowerKey(world, store, bossRef);
        if (key == null) {
            // Without a recorded tower anchor, don't implode anything.
            // This prevents non-tower NPC deaths (e.g. world-spawned golems) from collapsing terrain.
            try {
                LOGGER.atWarning().log("Boss defeated but no tower anchor found: %s at (%.1f, %.1f, %.1f)",
                        npcTypeId, bossPos.getX(), bossPos.getY(), bossPos.getZ());
            } catch (Exception ignored) {
            }
            return;
        }
        markBossDefeated(store, bossRef);
        final TowerKey collapseKey = key;

        TowerState state = TOWERS.computeIfAbsent(key, _k -> new TowerState());
        if (state.collapseScheduled) {
            return;
        }
        state.collapseScheduled = true;

        // Collapse from the tower anchor downwards.
        state.baseY = key.y();

        long delaySeconds = getCollapseDelaySeconds();
        LOGGER.atInfo().log("Tower boss %s defeated - imploding tower at (%d, %d, %d) in %d seconds.",
                npcTypeId, key.x(), key.y(), key.z(), delaySeconds);

        scheduleImplosionStart(world, collapseKey, state, delaySeconds);

        // Boss is dead; we don't need to retain the mapping anymore.
        cleanupBossAnchor(store, bossRef);
    }

    public static void onBossDefeated(World world, Vector3d pos, String npcTypeId) {
        onBossDefeated(world, null, null, pos, npcTypeId);
    }

    private static void onEntityRemove(EntityRemoveEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof NPCEntity npc)) {
            return;
        }

        String npcTypeId;
        try {
            npcTypeId = npc.getNPCTypeId();
        } catch (Exception e) {
            return;
        }

        if (!isBossNpcTypeId(npcTypeId)) {
            return;
        }

        World world = npc.getWorld();
        if (world == null) {
            return;
        }

        Ref<EntityStore> ref = null;
        try {
            ref = npc.getReference();
        } catch (Exception ignored) {
        }
        if (ref != null && DEFEATED_BOSS_REFHASHES.contains(ref.hashCode())) {
            return;
        }
        Store<EntityStore> store = null;
        try {
            store = world.getEntityStore().getStore();
        } catch (Exception ignored) {
        }
        if (ref != null && store != null) {
            try {
                if (DEFEATED_BOSS_REFIDS.contains(refId(store, ref))) {
                    return;
                }
            } catch (Exception ignored) {
            }
        }

        Vector3d pos = null;
        try {
            if (npc.getReference() != null && npc.getReference().isValid()) {
                TransformComponent transform = world.getEntityStore().getStore()
                        .getComponent(npc.getReference(), TransformComponent.getComponentType());
                if (transform != null) {
                    pos = transform.getPosition();
                }
            }
        } catch (Exception ignored) {
        }
        if (pos == null) {
            try {
                @SuppressWarnings("removal")
                TransformComponent transform = npc.getTransformComponent();
                if (transform != null) {
                    pos = transform.getPosition();
                }
            } catch (Exception ignored) {
            }
        }

        if (pos == null) {
            return;
        }

        TowerKey anchorKey = resolveBossTowerKey(world, store, ref);
        if (anchorKey == null) {
            // No anchor => not a tower boss we spawned.
            return;
        }

        // Ensure death-based collapse logic is also triggered if the boss is removed later (corpse cleanup, unload).
        onBossDefeated(world, store, ref, pos, npcTypeId);
    }

    public static long getNearestPendingCollapseSeconds(World world, int x, int z, int radiusBlocks) {
        if (world == null) {
            return -1;
        }

        long now = System.currentTimeMillis();
        String worldName = world.getName();
        int r = Math.max(1, radiusBlocks);
        int radiusSq = r * r;

        TowerKey bestKey = null;
        double bestDistSq = Double.MAX_VALUE;

        for (var entry : TOWERS.entrySet()) {
            TowerKey key = entry.getKey();
            TowerState state = entry.getValue();
            if (key == null || state == null) {
                continue;
            }
            if (!worldName.equals(key.worldName())) {
                continue;
            }
            if (!state.collapseScheduled || state.imploding) {
                continue;
            }
            long startAt = state.collapseStartAtEpochMs;
            if (startAt <= 0) {
                continue;
            }
            long remaining = Math.max(0, (startAt - now + 999) / 1000);
            if (remaining <= 0) {
                continue;
            }

            double dx = key.x() - x;
            double dz = key.z() - z;
            double distSq = dx * dx + dz * dz;
            if (distSq > radiusSq) {
                continue;
            }
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestKey = key;
            }
        }

        if (bestKey == null) {
            return -1;
        }
        TowerState state = TOWERS.get(bestKey);
        if (state == null || state.collapseStartAtEpochMs <= 0) {
            return -1;
        }
        return Math.max(0, (state.collapseStartAtEpochMs - now + 999) / 1000);
    }

    /**
     * Adds delay to the nearest tower that has a scheduled (but not yet started) collapse.
     *
     * @return new remaining seconds until implosion starts, or -1 if no pending collapse found.
     */
    public static long delayNearestPendingCollapse(World world, int x, int z, int radiusBlocks, long extraSeconds) {
        if (world == null || extraSeconds <= 0) {
            return -1;
        }

        long now = System.currentTimeMillis();
        String worldName = world.getName();
        int r = Math.max(1, radiusBlocks);
        int radiusSq = r * r;

        TowerKey bestKey = null;
        TowerState bestState = null;
        double bestDistSq = Double.MAX_VALUE;

        for (var entry : TOWERS.entrySet()) {
            TowerKey key = entry.getKey();
            TowerState state = entry.getValue();
            if (key == null || state == null) {
                continue;
            }
            if (!worldName.equals(key.worldName())) {
                continue;
            }
            if (!state.collapseScheduled || state.imploding) {
                continue;
            }
            long startAt = state.collapseStartAtEpochMs;
            if (startAt <= 0) {
                continue;
            }
            long remaining = Math.max(0, (startAt - now + 999) / 1000);
            if (remaining <= 0) {
                continue;
            }

            double dx = key.x() - x;
            double dz = key.z() - z;
            double distSq = dx * dx + dz * dz;
            if (distSq > radiusSq) {
                continue;
            }
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestKey = key;
                bestState = state;
            }
        }

        if (bestKey == null || bestState == null) {
            return -1;
        }

        long remaining = Math.max(0, (bestState.collapseStartAtEpochMs - now + 999) / 1000);
        if (remaining <= 0) {
            return -1;
        }

        // Cap to prevent griefing / infinite delay.
        long newDelay = Math.min(remaining + extraSeconds, 180);
        scheduleImplosionStart(world, bestKey, bestState, newDelay);
        return newDelay;
    }

    private static void scheduleImplosionStart(World world, TowerKey key, TowerState state, long delaySeconds) {
        if (world == null || key == null || state == null) {
            return;
        }

        long safeDelay = Math.max(1, delaySeconds);
        synchronized (state) {
            // Cancel previous scheduled start (if any).
            ScheduledFuture<?> prevStart = state.collapseStartFuture;
            if (prevStart != null) {
                try {
                    prevStart.cancel(false);
                } catch (Exception ignored) {
                }
            }

            // Cancel previous countdown messages.
            for (ScheduledFuture<?> f : state.countdownFutures) {
                try {
                    f.cancel(false);
                } catch (Exception ignored) {
                }
            }
            state.countdownFutures.clear();

            state.collapseStartAtEpochMs = System.currentTimeMillis() + safeDelay * 1000L;

            announceImplosionCountdown(world, key.x(), key.z(), safeDelay, (afterSeconds, message) -> {
                ScheduledFuture<?> f = SCHEDULER.schedule(
                        () -> sendMessageToNearbyPlayers(world, key.x(), key.z(), message),
                        afterSeconds,
                        TimeUnit.SECONDS
                );
                synchronized (state) {
                    state.countdownFutures.add(f);
                }
            });

            state.collapseStartFuture = SCHEDULER.schedule(() -> scheduleImplosion(world, key), safeDelay, TimeUnit.SECONDS);
        }
    }

    private static void announceImplosionCountdown(
            World world,
            int centerX,
            int centerZ,
            long delaySeconds,
            BiConsumer<Long, String> scheduleMessageAfterSeconds
    ) {
        sendMessageToNearbyPlayers(world, centerX, centerZ,
                "The tower shudders... it will implode in " + delaySeconds + " seconds!");

        long[] checkpoints = {30, 10, 5, 4, 3, 2, 1};
        for (long secondsLeft : checkpoints) {
            long after = delaySeconds - secondsLeft;
            if (after <= 0) {
                continue;
            }
            scheduleMessageAfterSeconds.accept(after, "Tower implodes in " + secondsLeft + " seconds!");
        }
    }

    private static void sendMessageToNearbyPlayers(World world, int centerX, int centerZ, String message) {
        try {
            world.execute(() -> {
                int radius = nearbyPlayerRadiusBlocks;
                int radiusSq = radius * radius;

                for (PlayerRef player : world.getPlayerRefs()) {
                    try {
                        Vector3d pos = player.getTransform().getPosition();
                        double dx = pos.getX() - centerX;
                        double dz = pos.getZ() - centerZ;
                        if (dx * dx + dz * dz > radiusSq) {
                            continue;
                        }
                        player.sendMessage(Message.raw(message));
                    } catch (Exception ignored) {
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static void scheduleImplosion(World world, TowerKey key) {
        TowerState state = TOWERS.get(key);
        if (state == null || state.imploding) {
            return;
        }
        state.imploding = true;

        AtomicInteger centerXRef = new AtomicInteger(key.x());
        AtomicInteger centerZRef = new AtomicInteger(key.z());
        AtomicInteger radiusRef = new AtomicInteger(IMPLOSION_RADIUS);
        int baseY = state.baseY;

        // Y increases upward. Tower: high Y = top, low Y = base.
        // Start at the first actual tower block from the top (not empty air above the tower) and collapse downward,
        // preserving the base.
        int plannedMaxY = baseY + ROOF_HEADROOM;                    // above player for roof/spire
        int fallbackMinY = baseY - TOWER_CLEARANCE;          // fallback if we can't detect ground
        var minYRef = new AtomicInteger(fallbackMinY);

        AtomicInteger maxYRef = new AtomicInteger(plannedMaxY);
        AtomicInteger currentY = new AtomicInteger(plannedMaxY);
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        ScheduledFuture<?> future = SCHEDULER.scheduleAtFixedRate(() -> {
            if (state.topY == Integer.MIN_VALUE) {
                try {
                    world.execute(() -> {
                        int anchorX = key.x();
                        int anchorZ = key.z();
                        int topY = findTowerTopY(world, anchorX, anchorZ, plannedMaxY, fallbackMinY, TOP_SCAN_RADIUS);
                        state.topY = topY;
                        maxYRef.set(topY);
                        currentY.set(topY);

                        CollapseGeometry geo = computeCollapseGeometry(world, anchorX, anchorZ, topY, Math.max(fallbackMinY, topY - 64), 24);
                        centerXRef.set(geo.centerX);
                        centerZRef.set(geo.centerZ);
                        radiusRef.set(geo.radius);

                        LOGGER.atInfo().log("Starting top-down implosion maxY=%d to minY=%d (baseY=%d) at anchor (%d, %d) -> center (%d, %d) radius=%d",
                                topY, fallbackMinY, baseY, anchorX, anchorZ, geo.centerX, geo.centerZ, geo.radius);
                    });
                } catch (Exception ignored) {
                }
                return;
            }

            int centerX = centerXRef.get();
            int centerZ = centerZRef.get();
            int towerRadius = radiusRef.get();
            int mobRadius = Math.min(MAX_SWEEP_RADIUS, towerRadius + 10);
            int dropRadius = Math.min(MAX_SWEEP_RADIUS, towerRadius + 6);
            int finalSweepRadius = Math.min(MAX_SWEEP_RADIUS, towerRadius + FINAL_SWEEP_EXTRA_RADIUS);

            int maxY = maxYRef.get();
            int y = currentY.get();
            int effectiveMinY = minYRef.get();
            if (y < effectiveMinY) {
                ScheduledFuture<?> f = futureRef.get();
                if (f != null) {
                    f.cancel(false);
                }
                try {
                    int cleanupMinY = state.cleanupMinY != Integer.MIN_VALUE
                            ? state.cleanupMinY
                            : Math.max(fallbackMinY, effectiveMinY - Math.max(12, RUIN_PRESERVE_LAYERS + 4));
                    world.execute(() -> {
                        int swept = sweepTowerBlocks(world, centerX, centerZ, effectiveMinY, maxY, finalSweepRadius);
                        if (swept > 0) {
                            LOGGER.atInfo().log("Implosion cleanup removed %d remaining blocks at (%d, %d)",
                                    swept, centerX, centerZ);
                        }

                        int drops = removeDroppedItems(world, centerX, centerZ, cleanupMinY, maxY, dropRadius);
                        if (drops > 0) {
                            LOGGER.atInfo().log("Implosion cleanup removed %d dropped items at (%d, %d)",
                                    drops, centerX, centerZ);
                        }

                        // One final, big explosion near the preserved base (not mid-air layers).
                        spawnCollapseEffects(world, centerX, Math.min(maxY, effectiveMinY + 2), centerZ, true);
                    });
                } catch (Exception ignored) {
                }
                TOWERS.remove(key);
                // Drops can be spawned a tick or two after blocks/NPCs are removed; do a couple delayed sweeps.
                int cleanupMinY = state.cleanupMinY != Integer.MIN_VALUE
                        ? state.cleanupMinY
                        : Math.max(fallbackMinY, effectiveMinY - Math.max(12, RUIN_PRESERVE_LAYERS + 4));
                SCHEDULER.schedule(() -> {
                    try {
                        world.execute(() -> {
                            sweepTowerBlocks(world, centerX, centerZ, effectiveMinY, maxY, finalSweepRadius);
                            removeDroppedItems(world, centerX, centerZ, cleanupMinY, maxY, dropRadius);
                        });
                    } catch (Exception ignored) {
                    }
                }, 2, TimeUnit.SECONDS);
                SCHEDULER.schedule(() -> {
                    try {
                        world.execute(() -> {
                            sweepTowerBlocks(world, centerX, centerZ, effectiveMinY, maxY, finalSweepRadius);
                            removeDroppedItems(world, centerX, centerZ, cleanupMinY, maxY, dropRadius);
                        });
                    } catch (Exception ignored) {
                    }
                }, 6, TimeUnit.SECONDS);
                LOGGER.atInfo().log("Implosion complete at (%d, %d)", centerX, centerZ);
                return;
            }

            int startY = y;
            int endY = Math.max(y - layersPerTick + 1, effectiveMinY);
            currentY.set(endY - 1);

            world.execute(() -> {
                if (state.ruinMinY == Integer.MIN_VALUE) {
                    int ruinMinY = computeRuinMinY(world, centerX, centerZ, maxY, fallbackMinY, towerRadius);
                    state.ruinMinY = ruinMinY;
                    if (ruinMinY > minYRef.get()) {
                        minYRef.set(ruinMinY);
                        LOGGER.atInfo().log("Ruin base preserved: stopping implosion at minY=%d (base+%d) at (%d, %d)",
                                ruinMinY, RUIN_PRESERVE_LAYERS, centerX, centerZ);
                    }
                }

                int minY = minYRef.get();
                int cleanupMinY = Math.max(fallbackMinY, minY - Math.max(12, RUIN_PRESERVE_LAYERS + 4));
                state.cleanupMinY = cleanupMinY;

                if (!state.mobsPurged) {
                    state.mobsPurged = true;
                    int purged = purgeTowerMobs(world, centerX, centerZ, cleanupMinY, maxY, mobRadius);
                    if (purged > 0) {
                        LOGGER.atInfo().log("Purged %d NPCs from tower during implosion at (%d, %d)",
                                purged, centerX, centerZ);
                    }
                }

                if (!state.spawnMarkersRemoved) {
                    state.spawnMarkersRemoved = true;
                    int removed = removeTowerSpawnMarkers(world, centerX, centerZ, cleanupMinY, maxY, mobRadius);
                    if (removed > 0) {
                        LOGGER.atInfo().log("Removed %d spawn markers from tower during implosion at (%d, %d)",
                                removed, centerX, centerZ);
                    }
                }

                for (int ly = startY; ly >= endY; ly--) {
                    int removed = implodeLayer(world, centerX, centerZ, ly, towerRadius);
                    // No terrain-count heuristic; we stop at the computed ruin minY.
                }
                spawnCollapseEffects(world, centerX, startY, centerZ, false);
            });
        }, 0, implosionTickMs, TimeUnit.MILLISECONDS);

        futureRef.set(future);
        state.implosionFuture = future;
    }

    private static int computeRuinMinY(World world, int centerX, int centerZ, int scanStartY, int fallbackMinY, int towerRadius) {
        try {
            int groundY = findLocalGroundSurfaceY(world, centerX, centerZ, scanStartY, fallbackMinY, towerRadius);
            if (groundY == Integer.MIN_VALUE) {
                // Fail-safe: prefer leaving a stump over digging into terrain if we can't detect ground.
                return Math.max(fallbackMinY, scanStartY - 60);
            }

            int stopY = groundY + RUIN_PRESERVE_LAYERS;
            // Sanity clamps: never above scanStartY and never below the hard fallback.
            stopY = Math.min(stopY, scanStartY);
            stopY = Math.max(stopY, fallbackMinY);
            return stopY;
        } catch (Exception ignored) {
            return Math.max(fallbackMinY, scanStartY - 60);
        }
    }

    private static int findLocalGroundSurfaceY(World world, int centerX, int centerZ, int startY, int minY, int towerRadius) {
        try {
            // Sample just outside the tower footprint to avoid reading the tower itself.
            int r0 = Math.max(IMPLOSION_RADIUS, towerRadius);
            int[] radii = {r0 + 3, r0 + 6};
            List<Integer> samples = new ArrayList<>();

            for (int r : radii) {
                int half = Math.max(1, r / 2);
                int[][] offsets = {
                        {r, 0}, {-r, 0}, {0, r}, {0, -r},
                        {r, r}, {-r, r}, {r, -r}, {-r, -r},
                        {r, half}, {r, -half}, {-r, half}, {-r, -half},
                        {half, r}, {-half, r}, {half, -r}, {-half, -r},
                };

                for (int[] o : offsets) {
                    int y = findGroundSurfaceY(world, centerX + o[0], centerZ + o[1], startY, minY);
                    if (y != Integer.MIN_VALUE) {
                        samples.add(y);
                    }
                }
            }

            if (samples.size() < 5) {
                return Integer.MIN_VALUE;
            }

            samples.sort(Integer::compareTo);
            return samples.get(samples.size() / 2); // median
        } catch (Exception ignored) {
            return Integer.MIN_VALUE;
        }
    }

    private static int findTowerTopY(World world, int centerX, int centerZ, int startY, int minY, int radius) {
        try {
            int rSq = radius * radius;
            for (int y = startY; y >= minY; y--) {
                for (int x = centerX - radius; x <= centerX + radius; x++) {
                    for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                        int dx = x - centerX;
                        int dz = z - centerZ;
                        if (dx * dx + dz * dz > rSq) {
                            continue;
                        }

                        WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
                        if (chunk == null) {
                            continue;
                        }
                        int localX = ChunkUtil.localCoordinate(x);
                        int localZ = ChunkUtil.localCoordinate(z);

                        BlockType type;
                        try {
                            type = chunk.getBlockType(localX, y, localZ);
                        } catch (Exception ignored) {
                            continue;
                        }
                        if (type == null || type == BlockType.EMPTY) {
                            continue;
                        }

                        String id = type.getId();
                        if (shouldImplode(id)) {
                            return y;
                        }

                        try {
                            BlockState state = chunk.getState(localX, y, localZ);
                            if (state instanceof ItemContainerBlockState) {
                                return y;
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return startY;
    }

    private record CollapseGeometry(int centerX, int centerZ, int radius) {
    }

    private static CollapseGeometry computeCollapseGeometry(World world, int anchorX, int anchorZ, int topY, int minY, int scanRadius) {
        int r = Math.max(IMPLOSION_RADIUS, Math.min(64, scanRadius));
        int bestMinX = Integer.MAX_VALUE;
        int bestMaxX = Integer.MIN_VALUE;
        int bestMinZ = Integer.MAX_VALUE;
        int bestMaxZ = Integer.MIN_VALUE;
        int bestCount = 0;

        int startY = topY;
        int stopY = Math.max(minY, topY - 48);
        for (int y = startY; y >= stopY; y--) {
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            int count = 0;

            for (int x = anchorX - r; x <= anchorX + r; x++) {
                for (int z = anchorZ - r; z <= anchorZ + r; z++) {
                    WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
                    if (chunk == null) {
                        continue;
                    }
                    int localX = ChunkUtil.localCoordinate(x);
                    int localZ = ChunkUtil.localCoordinate(z);

                    BlockType type;
                    try {
                        type = chunk.getBlockType(localX, y, localZ);
                    } catch (Exception ignored) {
                        continue;
                    }
                    if (type == null || type == BlockType.EMPTY) {
                        continue;
                    }

                    String id = type.getId();
                    if (!isLikelyTowerBlockId(id)) {
                        // Still treat containers as tower geometry (top vaults).
                        try {
                            BlockState state = chunk.getState(localX, y, localZ);
                            if (!(state instanceof ItemContainerBlockState)) {
                                continue;
                            }
                        } catch (Exception ignored) {
                            continue;
                        }
                    }

                    count++;
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (z < minZ) minZ = z;
                    if (z > maxZ) maxZ = z;
                }
            }

            if (count > bestCount) {
                bestCount = count;
                bestMinX = minX;
                bestMaxX = maxX;
                bestMinZ = minZ;
                bestMaxZ = maxZ;
            }

            if (count >= 24) {
                break;
            }
        }

        if (bestCount <= 0 || bestMinX == Integer.MAX_VALUE || bestMinZ == Integer.MAX_VALUE) {
            return new CollapseGeometry(anchorX, anchorZ, IMPLOSION_RADIUS);
        }

        int centerX = (bestMinX + bestMaxX) / 2;
        int centerZ = (bestMinZ + bestMaxZ) / 2;
        int rx = Math.max(Math.abs(bestMaxX - centerX), Math.abs(bestMinX - centerX));
        int rz = Math.max(Math.abs(bestMaxZ - centerZ), Math.abs(bestMinZ - centerZ));
        int radius = Math.max(IMPLOSION_RADIUS, Math.max(rx, rz) + 3);
        radius = Math.min(MAX_SWEEP_RADIUS, radius);
        return new CollapseGeometry(centerX, centerZ, radius);
    }

    private static boolean isLikelyTowerBlockId(String blockTypeId) {
        if (blockTypeId == null || blockTypeId.isEmpty()) {
            return false;
        }
        // Tower prefabs use Rock_*_(Brick|Cobble) variants across tiers.
        if (blockTypeId.startsWith("Rock_")) {
            return blockTypeId.contains("_Cobble") || blockTypeId.contains("_Brick");
        }
        return blockTypeId.startsWith("Wood_")
                || blockTypeId.startsWith("Furniture_")
                || blockTypeId.startsWith("Deco_");
    }

    private static int findGroundSurfaceY(World world, int x, int z, int startY, int minY) {
        int yMax = Math.max(startY, minY);
        int yMin = Math.min(startY, minY);

        WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null) {
            return Integer.MIN_VALUE;
        }

        int localX = ChunkUtil.localCoordinate(x);
        int localZ = ChunkUtil.localCoordinate(z);

        for (int y = yMax; y >= yMin; y--) {
            BlockType type;
            try {
                type = chunk.getBlockType(localX, y, localZ);
            } catch (Exception ignored) {
                continue;
            }

            if (type == null || type == BlockType.EMPTY) {
                continue;
            }

            String id = type.getId();
            if (isSurfaceNoiseBlock(id)) {
                continue;
            }
            return y;
        }

        return Integer.MIN_VALUE;
    }

    private static boolean isSurfaceNoiseBlock(String blockTypeId) {
        if (blockTypeId == null || blockTypeId.isEmpty()) {
            return true;
        }

        String id = blockTypeId.toLowerCase(Locale.ROOT);

        // Treat fluids as a "surface" to avoid scanning down through deep rivers/lakes.
        if (id.contains("water") || id.contains("lava") || id.contains("liquid")) {
            return false;
        }

        // Skip canopy/foliage/decor so ground detection doesn't lock onto trees or tall grass.
        return id.startsWith("foliage_")
                || id.startsWith("plant_")
                || id.startsWith("flower_")
                || id.contains("leaf")
                || id.contains("leaves")
                || id.contains("vine")
                || id.contains("mushroom")
                || id.contains("sapling")
                || id.contains("bush")
                || id.startsWith("deco_")
                || id.startsWith("furniture_")
                || id.startsWith("wood_")
                || id.contains("log")
                || id.contains("branch");
    }

    private static int sweepTowerBlocks(World world, int centerX, int centerZ, int minY, int maxY, int radius) {
        try {
            int removed = 0;
            for (int y = maxY; y >= minY; y--) {
                removed += implodeLayer(world, centerX, centerZ, y, radius, false);
            }
            return removed;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int purgeTowerMobs(World world, int centerX, int centerZ, int minY, int maxY, int radius) {
        try {
            var store = world.getEntityStore().getStore();
            int chunkX = ChunkUtil.chunkCoordinate(centerX);
            int chunkZ = ChunkUtil.chunkCoordinate(centerZ);
            int radiusChunks = (radius / ChunkUtil.SIZE) + 1;
            int radiusSq = radius * radius;

            List<NPCEntity> toRemove = new ArrayList<>();
            Set<Integer> visited = new HashSet<>();

            for (int cx = chunkX - radiusChunks; cx <= chunkX + radiusChunks; cx++) {
                for (int cz = chunkZ - radiusChunks; cz <= chunkZ + radiusChunks; cz++) {
                    WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunk(cx, cz));
                    if (chunk == null || chunk.getEntityChunk() == null) {
                        continue;
                    }

                    for (var ref : chunk.getEntityChunk().getEntityReferences()) {
                        try {
                            int hash = ref.hashCode();
                            if (!visited.add(hash)) {
                                continue;
                            }

                            Player player = store.getComponent(ref, Player.getComponentType());
                            if (player != null) {
                                continue;
                            }

                            NPCEntity npc = store.getComponent(ref, NPCEntity.getComponentType());
                            if (npc == null) {
                                continue;
                            }

                            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                            if (transform == null) {
                                continue;
                            }

                            Vector3d pos = transform.getPosition();
                            int y = (int) Math.floor(pos.getY());
                            if (y < minY || y > maxY) {
                                continue;
                            }

                            double dx = pos.getX() - centerX;
                            double dz = pos.getZ() - centerZ;
                            if (dx * dx + dz * dz > radiusSq) {
                                continue;
                            }

                            toRemove.add(npc);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            int removed = 0;
            for (NPCEntity npc : toRemove) {
                try {
                    if (npc.remove()) {
                        removed++;
                    }
                } catch (Exception ignored) {
                }
            }
            return removed;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int removeTowerSpawnMarkers(World world, int centerX, int centerZ, int minY, int maxY, int radius) {
        try {
            var store = world.getEntityStore().getStore();
            int chunkX = ChunkUtil.chunkCoordinate(centerX);
            int chunkZ = ChunkUtil.chunkCoordinate(centerZ);
            int radiusChunks = (radius / ChunkUtil.SIZE) + 1;
            int radiusSq = radius * radius;

            List<Ref<EntityStore>> toRemove = new ArrayList<>();
            Set<Integer> visited = new HashSet<>();

            for (int cx = chunkX - radiusChunks; cx <= chunkX + radiusChunks; cx++) {
                for (int cz = chunkZ - radiusChunks; cz <= chunkZ + radiusChunks; cz++) {
                    WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunk(cx, cz));
                    if (chunk == null || chunk.getEntityChunk() == null) {
                        continue;
                    }

                    for (var ref : chunk.getEntityChunk().getEntityReferences()) {
                        try {
                            int hash = ref.hashCode();
                            if (!visited.add(hash)) {
                                continue;
                            }

                            SpawnMarkerEntity marker = store.getComponent(ref, SpawnMarkerEntity.getComponentType());
                            if (marker == null) {
                                continue;
                            }

                            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                            if (transform == null) {
                                continue;
                            }

                            Vector3d pos = transform.getPosition();
                            int y = (int) Math.floor(pos.getY());
                            if (y < minY || y > maxY) {
                                continue;
                            }

                            double dx = pos.getX() - centerX;
                            double dz = pos.getZ() - centerZ;
                            if (dx * dx + dz * dz > radiusSq) {
                                continue;
                            }

                            toRemove.add(ref);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            int removed = 0;
            for (Ref<EntityStore> ref : toRemove) {
                try {
                    store.removeEntity(ref, RemoveReason.REMOVE);
                    removed++;
                } catch (Exception ignored) {
                }
            }
            return removed;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int removeDroppedItems(World world, int centerX, int centerZ, int minY, int maxY, int radius) {
        try {
            var store = world.getEntityStore().getStore();
            int chunkX = ChunkUtil.chunkCoordinate(centerX);
            int chunkZ = ChunkUtil.chunkCoordinate(centerZ);
            int radiusChunks = (radius / ChunkUtil.SIZE) + 1;
            int radiusSq = radius * radius;

            List<Ref<EntityStore>> toRemove = new ArrayList<>();
            Set<Integer> visited = new HashSet<>();

            for (int cx = chunkX - radiusChunks; cx <= chunkX + radiusChunks; cx++) {
                for (int cz = chunkZ - radiusChunks; cz <= chunkZ + radiusChunks; cz++) {
                    WorldChunk chunk = world.getChunkIfLoaded(ChunkUtil.indexChunk(cx, cz));
                    if (chunk == null || chunk.getEntityChunk() == null) {
                        continue;
                    }

                    for (var ref : chunk.getEntityChunk().getEntityReferences()) {
                        try {
                            int hash = ref.hashCode();
                            if (!visited.add(hash)) {
                                continue;
                            }

                            // Skip players.
                            Player player = store.getComponent(ref, Player.getComponentType());
                            if (player != null) {
                                continue;
                            }

                            // Dropped items are ECS entities with ItemComponent / PickupItemComponent.
                            ItemComponent itemComponent = store.getComponent(ref, ItemComponent.getComponentType());
                            PickupItemComponent pickupItem = store.getComponent(ref, PickupItemComponent.getComponentType());
                            if (itemComponent == null && pickupItem == null) {
                                continue;
                            }

                            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
                            if (transform == null) {
                                continue;
                            }

                            Vector3d pos = transform.getPosition();
                            int y = (int) Math.floor(pos.getY());
                            if (y < minY || y > maxY) {
                                continue;
                            }

                            double dx = pos.getX() - centerX;
                            double dz = pos.getZ() - centerZ;
                            if (dx * dx + dz * dz > radiusSq) {
                                continue;
                            }

                            toRemove.add(ref);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            int removed = 0;
            for (Ref<EntityStore> ref : toRemove) {
                try {
                    store.removeEntity(ref, RemoveReason.REMOVE);
                    removed++;
                } catch (Exception ignored) {
                }
            }
            return removed;
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static int implodeLayer(World world, int centerX, int centerZ, int y, int radius) {
        return implodeLayer(world, centerX, centerZ, y, radius, true);
    }

    private static int implodeLayer(World world, int centerX, int centerZ, int y, int radius, boolean log) {
        int removed = 0;
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                int dx = x - centerX;
                int dz = z - centerZ;
                if (dx * dx + dz * dz > radius * radius) {
                    continue;
                }

                long chunkIndex = ChunkUtil.indexChunkFromBlock(x, z);
                WorldChunk chunk = world.getChunkIfLoaded(chunkIndex);
                if (chunk == null) {
                    continue;
                }

                int localX = ChunkUtil.localCoordinate(x);
                int localZ = ChunkUtil.localCoordinate(z);

                BlockType type = chunk.getBlockType(localX, y, localZ);
                if (type == null) {
                    continue;
                }
                String id = type.getId();
                BlockState state = null;
                try {
                    state = chunk.getState(localX, y, localZ);
                } catch (Exception ignored) {
                }
                boolean hasContainerState = state instanceof ItemContainerBlockState;

                if (!hasContainerState && !shouldImplode(id)) {
                    continue;
                }

                removeBlockAndState(chunk, localX, y, localZ, state);
                removed++;
            }
        }
        if (log && removed > 0) {
            LOGGER.atInfo().log("Imploded Y=%d: removed %d blocks", y, removed);
        }
        return removed;
    }

    private static void removeBlockAndState(WorldChunk chunk, int localX, int y, int localZ, BlockState state) {
        try {
            if (state != null) {
                try {
                    if (state instanceof DestroyableBlockState destroyable) {
                        destroyable.onDestroy();
                    }
                } catch (Exception ignored) {
                }
                try {
                    state.invalidate();
                } catch (Exception ignored) {
                }
                try {
                    chunk.setState(localX, y, localZ, (BlockState) null, true);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }

        try {
            chunk.setBlock(localX, y, localZ, BlockType.EMPTY);
        } catch (Exception ignored) {
        }
    }

    private static void spawnCollapseEffects(World world, int centerX, int y, int centerZ, boolean big) {
        try {
            var accessor = world.getEntityStore().getStore();

            int[][] offsets = big
                    ? new int[][]{{0, 0}}
                    : new int[][]{
                    {0, 0}, {5, 5}, {-5, 5}, {5, -5}, {-5, -5},
                    {8, 0}, {-8, 0}, {0, 8}, {0, -8}
            };

            // --- Particles ---
            for (int[] off : offsets) {
                Vector3d pos = new Vector3d(centerX + off[0], y, centerZ + off[1]);

                String cached = big ? resolvedFinishParticleId : resolvedCollapseParticleId;
                if (cached != null) {
                    try {
                        ParticleUtil.spawnParticleEffect(cached, pos, accessor);
                        continue;
                    } catch (Exception ignored) {
                        if (big) {
                            resolvedFinishParticleId = null;
                        } else {
                            resolvedCollapseParticleId = null;
                        }
                    }
                }

                String[] candidates = big ? FINISH_PARTICLES : COLLAPSE_PARTICLES;
                for (String id : candidates) {
                    try {
                        ParticleUtil.spawnParticleEffect(id, pos, accessor);
                        if (big) {
                            resolvedFinishParticleId = id;
                        } else {
                            resolvedCollapseParticleId = id;
                        }
                        LOGGER.atInfo().log("Resolved %s particle system ID: %s", big ? "finish" : "collapse", id);
                        break;
                    } catch (Exception ignored) {
                    }
                }
            }

            // --- Sounds ---
            Vector3d soundPos = new Vector3d(centerX, y, centerZ);

            // Only play the big explosion sound once (finish), otherwise it spams during the 200ms implosion ticks.
            if (big) {
                int explosionId = resolvedExplosionSoundId;
                if (explosionId == Integer.MIN_VALUE) {
                    explosionId = resolveSoundId(EXPLOSION_SOUNDS, "explosion");
                    if (explosionId != Integer.MIN_VALUE) {
                        resolvedExplosionSoundId = explosionId;
                    }
                }
                if (explosionId != Integer.MIN_VALUE) {
                    try {
                        SoundUtil.playSoundEvent3d(explosionId, SoundCategory.SFX, soundPos, accessor);
                        LOGGER.atInfo().log("Played explosion sound id=%d at (%d, %d, %d)", explosionId, centerX, y, centerZ);
                    } catch (Throwable t) {
                        LOGGER.atWarning().log("Explosion sound FAILED: %s: %s", t.getClass().getSimpleName(), t.getMessage());
                        resolvedExplosionSoundId = Integer.MIN_VALUE;
                    }
                }
            }

            // Rumble sound
            int rumbleId = resolvedRumbleSoundId;
            if (rumbleId == Integer.MIN_VALUE) {
                rumbleId = resolveSoundId(RUMBLE_SOUNDS, "rumble");
                if (rumbleId != Integer.MIN_VALUE) {
                    resolvedRumbleSoundId = rumbleId;
                }
            }
            if (rumbleId != Integer.MIN_VALUE) {
                try {
                    SoundUtil.playSoundEvent3d(rumbleId, SoundCategory.SFX, soundPos, accessor);
                    LOGGER.atInfo().log("Played rumble sound id=%d at (%d, %d, %d)", rumbleId, centerX, y, centerZ);
                } catch (Throwable t) {
                    LOGGER.atWarning().log("Rumble sound FAILED: %s: %s", t.getClass().getSimpleName(), t.getMessage());
                    resolvedRumbleSoundId = Integer.MIN_VALUE;
                }
            }
        } catch (Exception e) {
            LOGGER.atFine().log("Could not spawn collapse effects: %s", e.getMessage());
        }
    }

    private static int resolveSoundId(String[] candidates, String label) {
        try {
            var assetMap = SoundEvent.getAssetMap();
            for (String name : candidates) {
                int id = assetMap.getIndex(name);
                if (id != Integer.MIN_VALUE) {
                    LOGGER.atInfo().log("Resolved %s sound event: %s (id=%d)", label, name, id);
                    return id;
                }
            }
        } catch (Exception e) {
            LOGGER.atFine().log("Could not resolve %s sound: %s", label, e.getMessage());
        }
        return Integer.MIN_VALUE;
    }

    private static boolean shouldImplode(String blockTypeId) {
        if (blockTypeId == null || blockTypeId.isEmpty()) {
            return false;
        }
        if (blockTypeId.startsWith("Rock_")) {
            // Avoid carving natural terrain by only removing tower-like "brick/cobble" blocks.
            return blockTypeId.contains("_Cobble") || blockTypeId.contains("_Brick");
        }
        return blockTypeId.startsWith("Wood_")
                || blockTypeId.startsWith("Furniture_")
                || blockTypeId.startsWith("Deco_");
    }

    private record TowerKey(String worldName, int x, int y, int z) {
    }

    private static final class TowerState {
        volatile boolean collapseScheduled;
        volatile boolean imploding;
        volatile int baseY;
        volatile int topY = Integer.MIN_VALUE;
        volatile ScheduledFuture<?> collapseStartFuture;
        volatile long collapseStartAtEpochMs;
        final List<ScheduledFuture<?>> countdownFutures = new ArrayList<>();
        volatile ScheduledFuture<?> implosionFuture;
        volatile boolean mobsPurged;
        volatile boolean spawnMarkersRemoved;
        volatile int cleanupMinY = Integer.MIN_VALUE;
        volatile int ruinMinY = Integer.MIN_VALUE;
    }

    private static final class NamedDaemonThreadFactory implements ThreadFactory {
        private final String baseName;
        private final AtomicInteger idx = new AtomicInteger();

        private NamedDaemonThreadFactory(String baseName) {
            this.baseName = baseName;
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, baseName + "-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
