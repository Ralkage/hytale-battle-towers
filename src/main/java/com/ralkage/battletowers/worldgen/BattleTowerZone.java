package com.ralkage.battletowers.worldgen;

import com.hypixel.hytale.logger.HytaleLogger;

/**
 * Constants and configuration for battle tower zone placement.
 *
 * Battle towers spawn via pattern-based placement configured in Tile JSON
 * configs, using the shared PrefabPattern at PrefabPatterns_Shared/BattleTower.json.
 * Each zone tier gets a different tower prefab variant with tier-appropriate
 * materials, mobs, and loot.
 *
 * Zone configs are provided for:
 * - Zone1_Tier1 (Emerald Wilds - easy, stone towers with Trork/Skeleton)
 * - Zone1_Tier2 (Emerald Wilds - medium, shale towers with Skeleton/Goblin)
 * - Zone1_Tier3 (Emerald Wilds - hard, basalt towers with Outlander/Skeleton)
 * - Zone1_Shore (beach, sandstone towers with Skeleton)
 *
 * Towers spawn sporadically with ~1000 block grid spacing (Scale 0.001)
 * and 500 block exclusion radius.
 */
public class BattleTowerZone {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Minimum distance between battle towers (pattern-based ExclusionRadius) */
    public static final double EXCLUSION_RADIUS = 500.0;

    /** Distance from zone border where towers cannot spawn */
    public static final double BORDER_EXCLUSION = 50.0;
}
