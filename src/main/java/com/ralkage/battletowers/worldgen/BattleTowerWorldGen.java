package com.ralkage.battletowers.worldgen;

import com.hypixel.hytale.event.EventRegistry;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.event.events.PrepareUniverseEvent;

/**
 * Handles battle tower worldgen integration via the PrepareUniverseEvent.
 *
 * The primary worldgen integration is data-driven via JSON zone configs
 * injected into Assets.zip (zones, tile configs, prefab patterns, prefab files).
 * This event listener provides logging and a hook point for future extensions.
 */
public class BattleTowerWorldGen {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /**
     * Registers the PrepareUniverseEvent listener for worldgen integration.
     */
    public static void register(EventRegistry eventRegistry) {
        eventRegistry.register(PrepareUniverseEvent.class, BattleTowerWorldGen::onPrepareUniverse);
        LOGGER.atInfo().log("Battle Tower worldgen event listener registered.");
    }

    private static void onPrepareUniverse(PrepareUniverseEvent event) {
        LOGGER.atInfo().log("PrepareUniverseEvent received - battle tower worldgen active.");
    }
}
