package com.ralkage.battletowers;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.prefab.selection.buffer.PrefabLoader;
import com.hypixel.hytale.server.core.prefab.selection.standard.BlockSelection;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.spawning.ISpawnableWithModel;
import com.hypixel.hytale.server.spawning.SpawningContext;
import com.ralkage.battletowers.worldgen.BattleTowerCollapse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class BattleTowers extends CommandBase {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final OptionalArg<String> subcommandArg;
    private final OptionalArg<String> npcArg;
    private final OptionalArg<Integer> amountArg;

    public BattleTowers() {
        super("battletowers", "Battle Towers command");
        this.subcommandArg = withOptionalArg("action", "Subcommand: implode, spawn", ArgTypes.STRING);
        this.npcArg = withOptionalArg("npc", "NPC type ID (e.g. Skeleton_Soldier)", ArgTypes.STRING);
        this.amountArg = withOptionalArg("amount", "Optional amount (e.g. sigils to spend)", ArgTypes.INTEGER);
    }

    @Override
    protected void executeSync(CommandContext context) {
        try {
            String sub = subcommandArg.get(context);

            if ("implode".equalsIgnoreCase(sub)) {
                handleImplode(context);
                return;
            }
            if ("spawn".equalsIgnoreCase(sub)) {
                handleSpawn(context);
                return;
            }
            if ("delay".equalsIgnoreCase(sub)) {
                handleDelay(context);
                return;
            }
            if ("forgekey".equalsIgnoreCase(sub)) {
                handleForgeKey(context);
                return;
            }

            runDiagnostics(context);
        } catch (Throwable t) {
            LOGGER.atSevere().log("executeSync CRASHED: %s", t);
            try {
                context.sendMessage(Message.raw("CRASH: " + t));
            } catch (Throwable ignored) {
            }
        }
    }

    private void handleImplode(CommandContext context) {
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("This command must be run by a player."));
            return;
        }

        try {
            var ref = context.senderAsPlayerRef();
            if (ref == null || !ref.isValid()) {
                context.sendMessage(Message.raw("Could not resolve player reference."));
                return;
            }

            var store = ref.getStore();
            World world = store.getExternalData().getWorld();
            if (world == null) {
                context.sendMessage(Message.raw("Could not get world."));
                return;
            }

            // All component/entity access must happen on the world thread
            world.execute(() -> {
                try {
                    PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
                    if (player == null) {
                        context.sendMessage(Message.raw("Could not get player component."));
                        return;
                    }

                    Vector3d pos = player.getTransform().getPosition();
                    int x = (int) Math.floor(pos.getX());
                    int y = (int) Math.floor(pos.getY());
                    int z = (int) Math.floor(pos.getZ());

                    context.sendMessage(Message.raw("Triggering tower implosion at (" + x + ", " + y + ", " + z + ") in "
                            + BattleTowerCollapse.getCollapseDelaySeconds() + " seconds..."));
                    BattleTowerCollapse.triggerImplosion(world, x, y, z);
                } catch (Exception e) {
                    context.sendMessage(Message.raw("Error: " + e.getClass().getSimpleName() + " - " + e.getMessage()));
                    LOGGER.atWarning().log("Implode command failed: %s", e);
                }
            });
        } catch (Exception e) {
            context.sendMessage(Message.raw("Error: " + e.getClass().getSimpleName() + " - " + e.getMessage()));
            LOGGER.atWarning().log("Implode command failed: %s", e);
        }
    }

    private void handleSpawn(CommandContext context) {
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("This command must be run by a player."));
            return;
        }

        String npcType = npcArg.get(context);
        if (npcType == null || npcType.isEmpty()) {
            npcType = "Skeleton_Soldier"; // default
        }
        final String finalNpcType = npcType;

        try {
            var ref = context.senderAsPlayerRef();
            if (ref == null || !ref.isValid()) {
                context.sendMessage(Message.raw("Could not resolve player reference."));
                return;
            }

            var refStore = ref.getStore();
            World world = refStore.getExternalData().getWorld();
            if (world == null) {
                context.sendMessage(Message.raw("Could not get world."));
                return;
            }

            world.execute(() -> {
                try {
                    PlayerRef player = refStore.getComponent(ref, PlayerRef.getComponentType());
                    if (player == null) {
                        context.sendMessage(Message.raw("Could not get player component."));
                        return;
                    }

                    Vector3d pos = player.getTransform().getPosition();
                    // Spawn 3 blocks in front of player
                    Vector3d spawnPos = new Vector3d(pos.getX() + 3, pos.getY(), pos.getZ());

                    NPCPlugin npcPlugin = NPCPlugin.get();
                    int roleIndex = npcPlugin.getIndex(finalNpcType);
                    if (roleIndex < 0) {
                        context.sendMessage(Message.raw("Unknown NPC type: " + finalNpcType));
                        return;
                    }

                    // Resolve Model from role builder (required for NPC visibility)
                    Model model = null;
                    try {
                        var builder = npcPlugin.tryGetCachedValidRole(roleIndex);
                        if (builder instanceof ISpawnableWithModel spawnable) {
                            SpawningContext ctx = new SpawningContext();
                            ctx.setSpawnable(spawnable);
                            model = ctx.getModel();
                        }
                    } catch (Exception e) {
                        LOGGER.atWarning().log("Could not resolve model for %s: %s", finalNpcType, e.getMessage());
                    }

                    var store = world.getEntityStore().getStore();
                    final Model finalModel = model;
                    var result = npcPlugin.spawnEntity(
                            store,
                            roleIndex,
                            spawnPos,
                            new Vector3f(0, 0, 0),
                            model,
                            (npc, npcRef, s) -> {
                                LOGGER.atInfo().log("Spawned %s at (%.1f, %.1f, %.1f) model=%s",
                                        finalNpcType, spawnPos.getX(), spawnPos.getY(), spawnPos.getZ(),
                                        finalModel != null ? "resolved" : "null");
                            }
                    );

                    if (result != null) {
                        context.sendMessage(Message.raw("Spawned " + finalNpcType + " at ("
                                + (int) spawnPos.getX() + ", " + (int) spawnPos.getY() + ", " + (int) spawnPos.getZ() + ")"));
                    } else {
                        context.sendMessage(Message.raw("Failed to spawn " + finalNpcType + " (returned null)"));
                    }
                } catch (Exception e) {
                    context.sendMessage(Message.raw("Spawn error: " + e.getClass().getSimpleName() + " - " + e.getMessage()));
                    LOGGER.atWarning().log("Spawn command failed: %s", e);
                }
            });
        } catch (Exception e) {
            context.sendMessage(Message.raw("Error: " + e.getClass().getSimpleName() + " - " + e.getMessage()));
            LOGGER.atWarning().log("Spawn command failed: %s", e);
        }
    }

    private void handleDelay(CommandContext context) {
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("This command must be run by a player."));
            return;
        }

        Integer raw = amountArg.get(context);
        int sigils = raw == null ? 1 : raw;
        sigils = Math.max(1, Math.min(5, sigils));
        final int sigilsFinal = sigils;
        final long extraSecondsFinal = sigilsFinal * 10L;

        try {
            var ref = context.senderAsPlayerRef();
            if (ref == null || !ref.isValid()) {
                context.sendMessage(Message.raw("Could not resolve player reference."));
                return;
            }

            var refStore = ref.getStore();
            World world = refStore.getExternalData().getWorld();
            if (world == null) {
                context.sendMessage(Message.raw("Could not get world."));
                return;
            }

            world.execute(() -> {
                try {
                    PlayerRef playerRef = refStore.getComponent(ref, PlayerRef.getComponentType());
                    Player player = refStore.getComponent(ref, Player.getComponentType());
                    if (playerRef == null || player == null) {
                        context.sendMessage(Message.raw("Could not get player."));
                        return;
                    }

                    Vector3d pos = playerRef.getTransform().getPosition();
                    int x = (int) Math.floor(pos.getX());
                    int z = (int) Math.floor(pos.getZ());

                    int searchRadius = 128;
                    long remaining = BattleTowerCollapse.getNearestPendingCollapseSeconds(world, x, z, searchRadius);
                    if (remaining <= 0) {
                        context.sendMessage(Message.raw("No pending tower implosion nearby to delay."));
                        return;
                    }

                    if (!tryConsumeMaterial(player, ref, refStore, "BattleTowers_Tower_Sigil", sigilsFinal)) {
                        context.sendMessage(Message.raw("You need " + sigilsFinal + " Tower Sigil(s) to do that."));
                        return;
                    }

                    long newRemaining = BattleTowerCollapse.delayNearestPendingCollapse(world, x, z, searchRadius, extraSecondsFinal);
                    if (newRemaining <= 0) {
                        // Tower started imploding between checks; refund.
                        tryGiveItem(player, ref, refStore, "BattleTowers_Tower_Sigil", sigilsFinal);
                        context.sendMessage(Message.raw("Could not delay (implosion already started). Refunded sigils."));
                        return;
                    }

                    context.sendMessage(Message.raw("Spent " + sigilsFinal + " Tower Sigil(s). Tower implosion delayed; now ~"
                            + newRemaining + " seconds remaining."));
                } catch (Exception e) {
                    context.sendMessage(Message.raw("Error: " + e.getClass().getSimpleName() + " - " + e.getMessage()));
                    LOGGER.atWarning().log("Delay command failed: %s", e);
                }
            });
        } catch (Exception e) {
            context.sendMessage(Message.raw("Error: " + e.getClass().getSimpleName() + " - " + e.getMessage()));
            LOGGER.atWarning().log("Delay command failed: %s", e);
        }
    }

    private void handleForgeKey(CommandContext context) {
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("This command must be run by a player."));
            return;
        }

        try {
            var ref = context.senderAsPlayerRef();
            if (ref == null || !ref.isValid()) {
                context.sendMessage(Message.raw("Could not resolve player reference."));
                return;
            }

            var refStore = ref.getStore();
            World world = refStore.getExternalData().getWorld();
            if (world == null) {
                context.sendMessage(Message.raw("Could not get world."));
                return;
            }

            world.execute(() -> {
                try {
                    Player player = refStore.getComponent(ref, Player.getComponentType());
                    if (player == null) {
                        context.sendMessage(Message.raw("Could not get player."));
                        return;
                    }

                    // Costs (hybrid): 1 Core + 5 Sigils => 1 Tower Key
                    int sigilsCost = 5;
                    int coresCost = 1;

                    if (!canRemoveMaterial(player, "BattleTowers_Tower_Sigil", sigilsCost)
                            || !canRemoveMaterial(player, "BattleTowers_Tower_Core", coresCost)) {
                        context.sendMessage(Message.raw("Cost: 5 Tower Sigils + 1 Tower Core."));
                        return;
                    }

                    if (!tryConsumeMaterial(player, ref, refStore, "BattleTowers_Tower_Sigil", sigilsCost)) {
                        context.sendMessage(Message.raw("Cost: 5 Tower Sigils + 1 Tower Core."));
                        return;
                    }
                    if (!tryConsumeMaterial(player, ref, refStore, "BattleTowers_Tower_Core", coresCost)) {
                        tryGiveItem(player, ref, refStore, "BattleTowers_Tower_Sigil", sigilsCost);
                        context.sendMessage(Message.raw("Cost: 5 Tower Sigils + 1 Tower Core."));
                        return;
                    }

                    if (!tryGiveItem(player, ref, refStore, "BattleTowers_Tower_Key", 1)) {
                        // Inventory full; refund costs.
                        tryGiveItem(player, ref, refStore, "BattleTowers_Tower_Sigil", sigilsCost);
                        tryGiveItem(player, ref, refStore, "BattleTowers_Tower_Core", coresCost);
                        context.sendMessage(Message.raw("Inventory full. Refunded materials."));
                        return;
                    }

                    context.sendMessage(Message.raw("Forged 1 Tower Key (spent 5 Sigils + 1 Core)."));
                } catch (Exception e) {
                    context.sendMessage(Message.raw("Error: " + e.getClass().getSimpleName() + " - " + e.getMessage()));
                    LOGGER.atWarning().log("ForgeKey command failed: %s", e);
                }
            });
        } catch (Exception e) {
            context.sendMessage(Message.raw("Error: " + e.getClass().getSimpleName() + " - " + e.getMessage()));
            LOGGER.atWarning().log("ForgeKey command failed: %s", e);
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

    private static boolean tryConsumeMaterial(Player player, com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> playerRef,
                                              com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store,
                                              String itemId, int amount) {
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

    private static boolean tryGiveItem(Player player,
                                       com.hypixel.hytale.component.Ref<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> playerRef,
                                       com.hypixel.hytale.component.Store<com.hypixel.hytale.server.core.universe.world.storage.EntityStore> store,
                                       String itemId,
                                       int amount) {
        try {
            ItemStack stack = new ItemStack(itemId, amount, null);
            var tx = player.giveItem(stack, playerRef, store);
            player.sendInventory();
            ItemStack remainder = tx != null ? tx.getRemainder() : null;
            return remainder == null || remainder.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void runDiagnostics(CommandContext context) {
        context.sendMessage(Message.raw("Battle Towers - Diagnostic Report:"));

        try {
            PrefabStore store = PrefabStore.get();
            if (store == null) {
                context.sendMessage(Message.raw("  PrefabStore: NULL"));
                return;
            }
            Path assetPrefabsPath = store.getAssetPrefabsPath();
            Path serverPrefabsPath = store.getServerPrefabsPath();
            context.sendMessage(Message.raw("  Asset prefabs path: " + assetPrefabsPath));
            context.sendMessage(Message.raw("  Server prefabs path: " + serverPrefabsPath));

            Path btDir = serverPrefabsPath.resolve("BattleTower");
            boolean dirExists = java.nio.file.Files.isDirectory(btDir);
            context.sendMessage(Message.raw("  BattleTower dir exists (server): " + dirExists));

            List<String> resolved = new ArrayList<>();
            PrefabLoader.resolvePrefabs(serverPrefabsPath, "BattleTower.*", path -> {
                resolved.add(serverPrefabsPath.relativize(path).toString());
            });
            context.sendMessage(Message.raw("  Resolved prefabs: " + resolved.size()));
            for (String p : resolved) {
                context.sendMessage(Message.raw("    - " + p));
            }

            String[] prefabFiles = {
                "BattleTower/battletower_tier1.prefab.json",
                "BattleTower/battletower_tier2.prefab.json",
                "BattleTower/battletower_tier3.prefab.json",
                "BattleTower/battletower_shore.prefab.json"
            };
            for (String f : prefabFiles) {
                try {
                    BlockSelection bs = store.getPrefab(serverPrefabsPath.resolve(f));
                    if (bs != null) {
                        context.sendMessage(Message.raw("  Load " + f + ": OK (" + bs.getBlockCount() + " blocks)"));
                    } else {
                        context.sendMessage(Message.raw("  Load " + f + ": null"));
                    }
                } catch (Exception ex) {
                    context.sendMessage(Message.raw("  Load " + f + ": FAILED - " + ex.getMessage()));
                }
            }
        } catch (Exception e) {
            context.sendMessage(Message.raw("  Error: " + e.getMessage()));
            LOGGER.atWarning().log("BattleTowers diagnostic failed: %s", e.getMessage());
        }
    }
}
