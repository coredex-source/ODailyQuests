package com.ordwen.odailyquests.quests.player.progression.storage.yaml;

import com.ordwen.odailyquests.ODailyQuests;
import com.ordwen.odailyquests.configuration.essentials.Debugger;
import com.ordwen.odailyquests.configuration.essentials.Logs;
import com.ordwen.odailyquests.files.implementations.ProgressionFile;
import com.ordwen.odailyquests.quests.types.AbstractQuest;
import com.ordwen.odailyquests.quests.player.PlayerQuests;
import com.ordwen.odailyquests.quests.player.progression.Progression;
import com.ordwen.odailyquests.tools.PluginLogger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SaveProgressionYAML {

    private final ProgressionFile progressionFile;

    public SaveProgressionYAML(ProgressionFile progressionFile) {
        this.progressionFile = progressionFile;
    }

    /**
     * Data class to hold a player's save data snapshot.
     */
    private record PlayerSaveData(
            String playerName,
            String playerUuid,
            long timestamp,
            int achievedQuests,
            int totalAchievedQuests,
            Map<AbstractQuest, Progression> quests,
            Map<String, Integer> categoryStats
    ) {}

    /**
     * Bulk save all players' progression to YAML file in a single write operation.
     * This is much faster than saving each player individually.
     *
     * @param playersData map of player name -> (UUID, PlayerQuests)
     */
    public void saveAllProgressionsBulk(Map<String, Map.Entry<String, PlayerQuests>> playersData) {
        if (playersData == null || playersData.isEmpty()) {
            Debugger.write("No players to bulk save to YAML.");
            return;
        }

        if (progressionFile == null) {
            PluginLogger.error("Cannot bulk save: progression file is not initialized.");
            return;
        }

        final long startTime = System.currentTimeMillis();
        final int playerCount = playersData.size();
        Debugger.write("Starting YAML bulk save for " + playerCount + " player(s)...");

        // Step 1: Create snapshots of all player data
        List<PlayerSaveData> saveDataList = new ArrayList<>();
        for (Map.Entry<String, Map.Entry<String, PlayerQuests>> entry : playersData.entrySet()) {
            PlayerSaveData data = createSaveDataSnapshot(entry.getKey(), entry.getValue().getKey(), entry.getValue().getValue());
            if (data != null) {
                saveDataList.add(data);
            }
        }

        if (saveDataList.isEmpty()) {
            Debugger.write("No valid player data to save after snapshot creation.");
            return;
        }

        // Step 2: Write all data to config (in memory)
        try {
            final FileConfiguration config = progressionFile.getConfig();
            if (config == null) {
                PluginLogger.error("Cannot bulk save: config is null.");
                return;
            }

            for (PlayerSaveData data : saveDataList) {
                writePlayerDataToConfig(config, data);
            }

            // Step 3: Single file write for all players
            config.save(progressionFile.getFile());

            final long duration = System.currentTimeMillis() - startTime;
            PluginLogger.info("Bulk saved " + saveDataList.size() + " player(s) to YAML in " + duration + "ms.");

        } catch (IOException e) {
            PluginLogger.error("Failed to save YAML progression file: " + e.getMessage());
        } catch (Exception e) {
            PluginLogger.error("Unexpected error during YAML bulk save: " + e.getMessage());
        }
    }

    /**
     * Create a snapshot of player data for saving.
     */
    private PlayerSaveData createSaveDataSnapshot(String playerName, String playerUuid, PlayerQuests playerQuests) {
        if (playerQuests == null || playerName == null || playerUuid == null) {
            return null;
        }

        try {
            final Map<AbstractQuest, Progression> questsSnapshot = new LinkedHashMap<>(playerQuests.getQuests());
            final Map<String, Integer> categoryStatsSnapshot = new LinkedHashMap<>(playerQuests.getTotalAchievedQuestsByCategory());

            if (questsSnapshot.isEmpty()) {
                Debugger.write("Skipping " + playerName + " - no quests to save.");
                return null;
            }

            return new PlayerSaveData(
                    playerName,
                    playerUuid,
                    playerQuests.getTimestamp(),
                    playerQuests.getAchievedQuests(),
                    playerQuests.getTotalAchievedQuests(),
                    questsSnapshot,
                    categoryStatsSnapshot
            );
        } catch (Exception e) {
            Debugger.write("Failed to snapshot data for " + playerName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Write a player's data to the config (without saving to disk).
     */
    private void writePlayerDataToConfig(FileConfiguration config, PlayerSaveData data) {
        final String playerUuid = data.playerUuid();

        config.set(playerUuid + ".timestamp", data.timestamp());
        config.set(playerUuid + ".achievedQuests", data.achievedQuests());
        config.set(playerUuid + ".totalAchievedQuests", data.totalAchievedQuests());

        int index = 1;
        for (Map.Entry<AbstractQuest, Progression> entry : data.quests().entrySet()) {
            final AbstractQuest quest = entry.getKey();
            final Progression progression = entry.getValue();

            if (quest == null || progression == null) continue;

            final ConfigurationSection questSection = config.createSection(playerUuid + ".quests." + index);
            questSection.set("index", quest.getQuestIndex());
            questSection.set("progression", progression.getAdvancement());
            questSection.set("requiredAmount", progression.getRequiredAmount());
            questSection.set("selectedRequired", progression.getSelectedRequiredIndex());
            questSection.set("isAchieved", progression.isAchieved());
            index++;
        }

        final ConfigurationSection statsSection = config.createSection(playerUuid + ".totalAchievedQuestsByCategory");
        for (Map.Entry<String, Integer> entry : data.categoryStats().entrySet()) {
            if (entry.getKey() != null) {
                statsSection.set(entry.getKey(), entry.getValue());
            }
        }

        Debugger.write("Added " + data.playerName() + "'s data to YAML config.");
    }

    /**
     * Save player quests progression to YAML file.
     *
     * @param playerName       name of the player.
     * @param playerUuid       player uuid.
     * @param playerQuests     player quests.
     * @param isServerStopping whether the server is stopping (forces synchronous save).
     */
    public void saveProgression(String playerName, String playerUuid, PlayerQuests playerQuests, boolean isServerStopping) {
        // Edge case: null playerQuests
        if (playerQuests == null) {
            Debugger.write("Skipping save for player " + playerName + " - playerQuests is null (player may not be fully loaded yet).");
            return;
        }

        // Edge case: null or empty player identifiers
        if (playerName == null || playerName.isEmpty() || playerUuid == null || playerUuid.isEmpty()) {
            PluginLogger.error("Cannot save progression: invalid player name or UUID.");
            return;
        }

        // Edge case: progression file not initialized
        if (progressionFile == null) {
            PluginLogger.error("Cannot save progression for player " + playerName + ": progression file is not initialized.");
            return;
        }

        // Capture snapshot of data to avoid concurrent modification issues during async save
        final long timestamp = playerQuests.getTimestamp();
        final int achievedQuests = playerQuests.getAchievedQuests();
        final int totalAchievedQuests = playerQuests.getTotalAchievedQuests();

        // Create defensive copies of the maps to avoid ConcurrentModificationException
        final Map<AbstractQuest, Progression> questsSnapshot;
        final Map<String, Integer> categoryStatsSnapshot;

        try {
            questsSnapshot = new LinkedHashMap<>(playerQuests.getQuests());
            categoryStatsSnapshot = new LinkedHashMap<>(playerQuests.getTotalAchievedQuestsByCategory());
        } catch (Exception e) {
            PluginLogger.error("Failed to snapshot quest data for player " + playerName + ": " + e.getMessage());
            return;
        }

        // Edge case: empty quests map (nothing to save)
        if (questsSnapshot.isEmpty()) {
            Debugger.write("No quests to save for player " + playerName + " - quests map is empty.");
            return;
        }

        if (isServerStopping) {
            Debugger.write("Saving player " + playerName + " progression synchronously (server is stopping).");
            updateFile(playerName, playerUuid, timestamp, achievedQuests, totalAchievedQuests, questsSnapshot, categoryStatsSnapshot);
        } else {
            try {
                ODailyQuests.morePaperLib.scheduling().asyncScheduler().run(() -> {
                    Debugger.write("Saving player " + playerName + " progression asynchronously.");
                    updateFile(playerName, playerUuid, timestamp, achievedQuests, totalAchievedQuests, questsSnapshot, categoryStatsSnapshot);
                });
            } catch (Exception e) {
                // Fallback to synchronous save if async scheduling fails (e.g., during shutdown)
                Debugger.write("Async scheduling failed for player " + playerName + ", falling back to synchronous save: " + e.getMessage());
                updateFile(playerName, playerUuid, timestamp, achievedQuests, totalAchievedQuests, questsSnapshot, categoryStatsSnapshot);
            }
        }
    }

    /**
     * Update the YAML file with player progression data.
     *
     * @param playerName            name of the player.
     * @param playerUuid            player uuid.
     * @param timestamp             timestamp.
     * @param achievedQuests        achieved quests.
     * @param totalAchievedQuests   total achieved quests.
     * @param quests                quests snapshot.
     * @param categoryStats         category stats snapshot.
     */
    private void updateFile(String playerName, String playerUuid, long timestamp, int achievedQuests, int totalAchievedQuests,
                            Map<AbstractQuest, Progression> quests, Map<String, Integer> categoryStats) {
        try {
            final FileConfiguration config = progressionFile.getConfig();
            if (config == null) {
                PluginLogger.error("Cannot save progression for player " + playerName + ": config is null.");
                return;
            }

            config.set(playerUuid + ".timestamp", timestamp);
            config.set(playerUuid + ".achievedQuests", achievedQuests);
            config.set(playerUuid + ".totalAchievedQuests", totalAchievedQuests);

            int index = 1;
            for (Map.Entry<AbstractQuest, Progression> entry : quests.entrySet()) {
                final AbstractQuest quest = entry.getKey();
                final Progression progression = entry.getValue();

                if (quest == null || progression == null) {
                    Debugger.write("Skipping null quest or progression at index " + index + " for player " + playerName);
                    continue;
                }

                final ConfigurationSection questSection = config.createSection(playerUuid + ".quests." + index);
                questSection.set("index", quest.getQuestIndex());
                questSection.set("progression", progression.getAdvancement());
                questSection.set("requiredAmount", progression.getRequiredAmount());
                questSection.set("selectedRequired", progression.getSelectedRequiredIndex());
                questSection.set("isAchieved", progression.isAchieved());

                index++;
            }

            final ConfigurationSection statsSection = config.createSection(playerUuid + ".totalAchievedQuestsByCategory");
            for (Map.Entry<String, Integer> entry : categoryStats.entrySet()) {
                final String category = entry.getKey();
                final int amount = entry.getValue();
                if (category != null) {
                    statsSection.set(category, amount);
                }
            }

            if (Logs.isEnabled()) {
                PluginLogger.info(playerName + "'s data saved.");
            }

            config.save(progressionFile.getFile());
        } catch (IOException e) {
            PluginLogger.error("An error happened while saving the progression file for player " + playerName + ".");
            PluginLogger.error(e.getMessage());
        } catch (Exception e) {
            PluginLogger.error("Unexpected error while saving progression for player " + playerName + ": " + e.getMessage());
        }
    }
}