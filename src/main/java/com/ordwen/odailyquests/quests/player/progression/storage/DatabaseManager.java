package com.ordwen.odailyquests.quests.player.progression.storage;

import com.ordwen.odailyquests.ODailyQuests;
import com.ordwen.odailyquests.configuration.essentials.Database;
import com.ordwen.odailyquests.configuration.essentials.Debugger;
import com.ordwen.odailyquests.quests.player.PlayerQuests;
import com.ordwen.odailyquests.quests.player.QuestsManager;
import com.ordwen.odailyquests.quests.player.progression.storage.sql.SQLManager;
import com.ordwen.odailyquests.quests.player.progression.storage.sql.sqlite.SQLiteManager;
import com.ordwen.odailyquests.quests.player.progression.storage.sql.mysql.MySQLManager;
import com.ordwen.odailyquests.quests.player.progression.storage.yaml.YamlManager;
import com.ordwen.odailyquests.tools.PluginLogger;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;

public class DatabaseManager {

    private final ODailyQuests plugin;

    private SQLManager sqlManager;
    private YamlManager yamlManager;

    public DatabaseManager(ODailyQuests plugin) {
        this.plugin = plugin;
    }

    public void load() {
        switch (Database.getMode()) {
            case MYSQL -> {
                this.sqlManager = new MySQLManager();
                this.yamlManager = null;
            }
            case SQLITE -> {
                this.sqlManager = new SQLiteManager();
                this.yamlManager = null;
            }
            case YAML -> {
                this.yamlManager = new YamlManager(plugin.getFilesManager().getProgressionFile());
                this.sqlManager = null;
            }
        }
    }

    public void close() {
        if (this.sqlManager != null) {
            this.sqlManager.close();
        }
    }

    public void loadQuestsForPlayer(String playerName) {
        if (playerName == null || playerName.isEmpty()) {
            PluginLogger.error("Cannot load quests: player name is null or empty.");
            return;
        }

        final Map<String, PlayerQuests> activeQuests = QuestsManager.getActiveQuests();
        switch (Database.getMode()) {
            case YAML -> {
                if (yamlManager != null) {
                    yamlManager.getLoadProgressionYAML().loadPlayerQuests(playerName, activeQuests);
                } else {
                    PluginLogger.error("Cannot load player quests: YAML manager is not initialized.");
                }
            }
            case MYSQL, SQLITE -> {
                if (sqlManager != null) {
                    sqlManager.getLoadProgressionSQL().loadProgression(playerName, activeQuests);
                } else {
                    PluginLogger.error("Cannot load player quests: SQL manager is not initialized.");
                }
            }
            default ->
                    PluginLogger.error("Impossible to load player quests: the selected storage mode is incorrect!");
        }
    }

    /**
     * Save progression for a player.
     *
     * @param playerName   the player's name
     * @param playerUuid   the player's UUID
     * @param playerQuests the player's quests data
     */
    public void saveProgressionForPlayer(String playerName, String playerUuid, PlayerQuests playerQuests) {
        if (playerName == null || playerName.isEmpty()) {
            Debugger.write("Skipping save: player name is null or empty.");
            return;
        }

        if (playerUuid == null || playerUuid.isEmpty()) {
            Debugger.write("Skipping save for " + playerName + ": player UUID is null or empty.");
            return;
        }

        if (playerQuests == null) {
            Debugger.write("Skipping save for " + playerName + ": playerQuests is null.");
            return;
        }

        final boolean isServerStopping = plugin.isServerStopping();

        switch (Database.getMode()) {
            case YAML -> {
                if (yamlManager != null && yamlManager.getSaveProgressionYAML() != null) {
                    yamlManager.getSaveProgressionYAML().saveProgression(playerName, playerUuid, playerQuests, isServerStopping);
                } else {
                    PluginLogger.error("Cannot save player quests: YAML manager is not properly initialized.");
                }
            }
            case MYSQL, SQLITE -> {
                if (sqlManager != null && sqlManager.getSaveProgressionSQL() != null) {
                    sqlManager.getSaveProgressionSQL().saveProgression(playerName, playerUuid, playerQuests, isServerStopping);
                } else {
                    PluginLogger.error("Cannot save player quests: SQL manager is not properly initialized.");
                }
            }
            default ->
                    PluginLogger.error("Impossible to save player quests: the selected storage mode is incorrect!");
        }
    }

    /**
     * Bulk save all players' progression data in a single optimized operation.
     * This is significantly faster than saving players one by one, especially during shutdown.
     *
     * @param playersData map of player name -> (UUID, PlayerQuests)
     */
    public void saveAllProgressionsBulk(Map<String, Map.Entry<String, PlayerQuests>> playersData) {
        if (playersData == null || playersData.isEmpty()) {
            Debugger.write("No players to bulk save.");
            return;
        }

        switch (Database.getMode()) {
            case YAML -> {
                // Use bulk YAML save - single file write for all players
                if (yamlManager != null && yamlManager.getSaveProgressionYAML() != null) {
                    yamlManager.getSaveProgressionYAML().saveAllProgressionsBulk(playersData);
                } else {
                    PluginLogger.error("Cannot bulk save: YAML manager is not properly initialized.");
                }
            }
            case MYSQL, SQLITE -> {
                if (sqlManager != null && sqlManager.getSaveProgressionSQL() != null) {
                    sqlManager.getSaveProgressionSQL().saveAllProgressionsBulk(playersData);
                } else {
                    PluginLogger.error("Cannot bulk save: SQL manager is not properly initialized.");
                }
            }
            default ->
                    PluginLogger.error("Impossible to bulk save: the selected storage mode is incorrect!");
        }
    }

    public SQLManager getSqlManager() {
        return sqlManager;
    }

    public YamlManager getYamlManager() {
        return yamlManager;
    }
}
