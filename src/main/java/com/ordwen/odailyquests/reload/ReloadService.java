package com.ordwen.odailyquests.reload;

import com.ordwen.odailyquests.ODailyQuests;
import com.ordwen.odailyquests.configuration.ConfigFactory;
import com.ordwen.odailyquests.configuration.essentials.Debugger;
import com.ordwen.odailyquests.configuration.integrations.ItemsAdderEnabled;
import com.ordwen.odailyquests.configuration.integrations.NexoEnabled;
import com.ordwen.odailyquests.configuration.integrations.OraxenEnabled;
import com.ordwen.odailyquests.quests.categories.CategoriesLoader;
import com.ordwen.odailyquests.quests.player.PlayerQuests;
import com.ordwen.odailyquests.quests.player.QuestsManager;
import com.ordwen.odailyquests.tools.PluginLogger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;

public class ReloadService {

    private final ODailyQuests plugin;
    private final CategoriesLoader categoriesLoader;

    /**
     * Constructor.
     *
     * @param plugin main class instance.
     */
    public ReloadService(ODailyQuests plugin) {
        this.plugin = plugin;
        this.categoriesLoader = plugin.getCategoriesLoader();
    }

    /**
     * Load all quests from connected players, to avoid errors on reload.
     */
    public void loadConnectedPlayerQuests() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!QuestsManager.getActiveQuests().containsKey(player.getName())) {
                plugin.getDatabaseManager().loadQuestsForPlayer(player.getName());
            }
        }
    }

    /**
     * Save all quests from connected players using bulk operation for maximum performance.
     * This method is optimized for server shutdown/reload scenarios.
     */
    public void saveConnectedPlayerQuests() {
        final Map<String, PlayerQuests> activeQuestsMap = QuestsManager.getActiveQuests();
        if (activeQuestsMap == null || activeQuestsMap.isEmpty()) {
            Debugger.write("No active quests to save - map is null or empty.");
            return;
        }

        // Create snapshot to avoid ConcurrentModificationException
        final Map<String, PlayerQuests> activeQuests = new HashMap<>(activeQuestsMap);
        final int playerCount = activeQuests.size();
        Debugger.write("Preparing bulk save for " + playerCount + " player(s)...");

        // Prepare data for bulk save: Map<playerName, Entry<playerUUID, PlayerQuests>>
        final Map<String, Map.Entry<String, PlayerQuests>> bulkSaveData = new HashMap<>();

        for (Map.Entry<String, PlayerQuests> entry : activeQuests.entrySet()) {
            final String playerName = entry.getKey();
            final PlayerQuests playerQuests = entry.getValue();

            if (playerName == null || playerQuests == null) {
                Debugger.write("Skipping null entry in activeQuests.");
                continue;
            }

            final Player player = Bukkit.getPlayer(playerName);
            if (player == null) {
                Debugger.write("Cannot save progression for player " + playerName + " - player is offline.");
                // Still remove from active quests to clean up
                QuestsManager.getActiveQuests().remove(playerName);
                continue;
            }

            bulkSaveData.put(playerName, new AbstractMap.SimpleEntry<>(player.getUniqueId().toString(), playerQuests));
        }

        if (bulkSaveData.isEmpty()) {
            Debugger.write("No valid player data to save.");
            return;
        }

        // Use bulk save for maximum performance
        plugin.getDatabaseManager().saveAllProgressionsBulk(bulkSaveData);

        // Clear all saved players from active quests
        for (String playerName : bulkSaveData.keySet()) {
            QuestsManager.getActiveQuests().remove(playerName);
        }
    }

    /**
     * Execute all required actions when the command /qadmin reload is performed.
     */
    public void reload() {
        try {
            /* load files */
            plugin.getFilesManager().load();
            ODQReloadEvent.call(plugin, ReloadPhase.FILES_LOADED);

            /* load configurations */
            ConfigFactory.registerConfigs(plugin.getFilesManager());
            ODQReloadEvent.call(plugin, ReloadPhase.CONFIGS_LOADED);

            /* load database */
            plugin.getDatabaseManager().load();
            ODQReloadEvent.call(plugin, ReloadPhase.DATABASE_LOADED);

            /* load quests & interface */
            if ((!ItemsAdderEnabled.isEnabled() || ItemsAdderEnabled.isLoaded())
                    && (!OraxenEnabled.isEnabled() || OraxenEnabled.isLoaded())
                    && (!NexoEnabled.isEnabled() || NexoEnabled.isLoaded())) {

                categoriesLoader.loadCategories();
                plugin.getInterfacesManager().initAllObjects();
                ODQReloadEvent.call(plugin, ReloadPhase.CONTENT_LOADED);
            }

            saveConnectedPlayerQuests();
            ODQReloadEvent.call(plugin, ReloadPhase.PLAYERS_SAVED);

            ODailyQuests.morePaperLib.scheduling().globalRegionalScheduler().runDelayed(() -> {
                loadConnectedPlayerQuests();
                ODQReloadEvent.call(plugin, ReloadPhase.PLAYERS_LOADED);
            }, 20L);

            ODQReloadEvent.call(plugin, ReloadPhase.RELOAD_COMPLETE);
        } catch (Exception e) {
            PluginLogger.error("An error occurred while reloading the plugin. Please check the logs for details.");
        }
    }
}
