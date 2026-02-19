package com.ralkage.battletowers.worldgen;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.ralkage.battletowers.BattleTower;

import javax.annotation.Nonnull;

/**
 * Triggers tower collapse when a tower boss NPC actually dies (DeathComponent is added).
 *
 * This is more reliable than listening for {@code EntityRemoveEvent} because removal can be delayed
 * (corpse cleanup) or happen for reasons other than death (unload/despawn).
 */
public final class BattleTowerBossDeathSystem extends DeathSystems.OnDeathSystem {

    @Nonnull
    private final Query<EntityStore> query = Query.any();

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent component,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer
    ) {
        var npcComponentType = NPCEntity.getComponentType();
        if (npcComponentType == null) {
            return;
        }

        NPCEntity npc = commandBuffer.getComponent(ref, npcComponentType);
        if (npc == null) {
            npc = store.getComponent(ref, npcComponentType);
        }
        if (npc == null) {
            return;
        }

        String npcTypeId;
        try {
            npcTypeId = npc.getNPCTypeId();
        } catch (Exception ignored) {
            return;
        }

        if (!BattleTowerCollapse.isBossNpcTypeId(npcTypeId)) {
            return;
        }

        World world = npc.getWorld();
        if (world == null) {
            try {
                world = store.getExternalData().getWorld();
            } catch (Exception ignored) {
            }
        }
        if (world == null) {
            return;
        }

        TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
        if (transform == null) {
            transform = store.getComponent(ref, TransformComponent.getComponentType());
        }
        if (transform == null) {
            return;
        }

        Vector3d pos = transform.getPosition();
        BattleTower.getPluginLogger().atInfo().log("Boss death detected via DeathComponent: %s at (%.1f, %.1f, %.1f)",
                npcTypeId, pos.getX(), pos.getY(), pos.getZ());
        BattleTowerCollapse.onBossDefeated(world, store, ref, pos, npcTypeId);
    }
}
