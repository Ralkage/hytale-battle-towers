package com.ralkage.battletowers.vault;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ralkage.battletowers.worldgen.BattleTowerCollapse;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class BattleTowerVaults {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final String TOWER_KEY_ITEM_ID = "BattleTowers_Tower_Key";

    private static final int WARN_COOLDOWN_MS = 1250;
    private static final ConcurrentHashMap<Integer, Long> LAST_WARN_BY_PLAYER_REFHASH = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<String, Set<Long>> UNLOCKED_VAULT_CHESTS_BY_WORLD = new ConcurrentHashMap<>();

    private BattleTowerVaults() {
    }

    public static void register(EventRegistry eventRegistry) {
        eventRegistry.registerGlobal(UseBlockEvent.Pre.class, BattleTowerVaults::onUseBlockPre);
        LOGGER.atInfo().log("BattleTowerVaults listener registered.");
    }

    private static void onUseBlockPre(UseBlockEvent.Pre event) {
        if (event == null || event.isCancelled()) {
            return;
        }

        InteractionType interactionType = event.getInteractionType();
        if (interactionType != InteractionType.Use && interactionType != InteractionType.Secondary) {
            return;
        }

        BlockType blockType = event.getBlockType();
        if (blockType == null) {
            return;
        }

        String blockTypeId = blockType.getId();
        if (blockTypeId == null || !blockTypeId.startsWith("Furniture_Village_Chest")) {
            return;
        }

        Vector3i pos = event.getTargetBlock();
        if (pos == null) {
            return;
        }

        InteractionContext ctx = event.getContext();
        if (ctx == null) {
            return;
        }

        Ref<EntityStore> entityRef = ctx.getEntity();
        if (entityRef == null || !entityRef.isValid()) {
            return;
        }

        Store<EntityStore> store = entityRef.getStore();
        if (store == null) {
            return;
        }

        EntityStore entityStore = store.getExternalData();
        if (entityStore == null) {
            return;
        }

        World world = entityStore.getWorld();
        if (world == null) {
            return;
        }

        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();

        if (!BattleTowerCollapse.isTowerVaultChest(world, x, y, z)) {
            return;
        }

        String worldName = world.getName();
        long packedPos = packBlockPos(x, y, z);
        Set<Long> unlocked = UNLOCKED_VAULT_CHESTS_BY_WORLD.computeIfAbsent(worldName, _k -> ConcurrentHashMap.newKeySet());
        if (unlocked.contains(packedPos)) {
            return;
        }

        PlayerRef playerRef = store.getComponent(entityRef, PlayerRef.getComponentType());
        Player player = store.getComponent(entityRef, Player.getComponentType());
        if (playerRef == null || player == null) {
            return;
        }

        if (!canRemoveMaterial(player, TOWER_KEY_ITEM_ID, 1)) {
            event.setCancelled(true);
            maybeWarnNoKey(playerRef, entityRef);
            return;
        }

        if (!tryConsumeMaterial(player, TOWER_KEY_ITEM_ID, 1)) {
            event.setCancelled(true);
            maybeWarnNoKey(playerRef, entityRef);
            return;
        }

        unlocked.add(packedPos);
        playerRef.sendMessage(Message.raw("You unlocked the tower vault using a Tower Key."));
    }

    private static void maybeWarnNoKey(PlayerRef player, Ref<EntityStore> playerRef) {
        try {
            int key = playerRef != null ? playerRef.hashCode() : 0;
            long now = System.currentTimeMillis();
            Long prev = LAST_WARN_BY_PLAYER_REFHASH.get(key);
            if (prev != null && (now - prev) < WARN_COOLDOWN_MS) {
                return;
            }
            LAST_WARN_BY_PLAYER_REFHASH.put(key, now);
            player.sendMessage(Message.raw("This chest is sealed. You need a Tower Key to open it."));
        } catch (Exception ignored) {
        }
    }

    private static boolean canRemoveMaterial(Player player, String itemId, int amount) {
        try {
            ItemContainer container = player.getInventory().getCombinedEverything();
            MaterialQuantity mq = new MaterialQuantity(itemId, null, null, amount, null);
            return container != null && container.canRemoveMaterial(mq);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean tryConsumeMaterial(Player player, String itemId, int amount) {
        try {
            ItemContainer container = player.getInventory().getCombinedEverything();
            if (container == null) {
                return false;
            }
            MaterialQuantity mq = new MaterialQuantity(itemId, null, null, amount, null);
            if (!container.canRemoveMaterial(mq)) {
                return false;
            }
            var tx = container.removeMaterial(mq, true, false, true);
            player.sendInventory();
            return tx != null && tx.getRemainder() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long packBlockPos(int x, int y, int z) {
        // Minecraft-style packing (26 bits X, 12 bits Y, 26 bits Z).
        long lx = ((long) x & 0x3FFFFFFL) << 38;
        long lz = ((long) z & 0x3FFFFFFL) << 12;
        long ly = (long) y & 0xFFFL;
        return lx | lz | ly;
    }
}
