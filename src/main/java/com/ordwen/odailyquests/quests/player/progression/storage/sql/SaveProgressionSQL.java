package com.ordwen.odailyquests.quests.player.progression.storage.sql;

import com.ordwen.odailyquests.ODailyQuests;
import com.ordwen.odailyquests.configuration.essentials.Database;
import com.ordwen.odailyquests.configuration.essentials.Debugger;
import com.ordwen.odailyquests.configuration.essentials.Logs;
import com.ordwen.odailyquests.enums.SQLQuery;
import com.ordwen.odailyquests.enums.StorageMode;
import com.ordwen.odailyquests.quests.types.AbstractQuest;
import com.ordwen.odailyquests.quests.player.PlayerQuests;
import com.ordwen.odailyquests.quests.player.progression.Progression;
import com.ordwen.odailyquests.tools.PluginLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SaveProgressionSQL {

    /* instance of SQLManager */
    private final SQLManager sqlManager;

    /**
     * Constructor.
     *
     * @param sqlManager instance of MySQLManager.
     */
    public SaveProgressionSQL(SQLManager sqlManager) {
        this.sqlManager = sqlManager;
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
     * Bulk save all players' progression in a single optimized transaction.
     * This is MUCH faster than saving each player individually.
     *
     * @param playersData map of player name to their PlayerQuests data
     */
    public void saveAllProgressionsBulk(Map<String, Map.Entry<String, PlayerQuests>> playersData) {
        if (playersData == null || playersData.isEmpty()) {
            Debugger.write("No players to save in bulk.");
            return;
        }

        if (sqlManager == null) {
            PluginLogger.error("Cannot bulk save: SQL manager is not initialized.");
            return;
        }

        final long startTime = System.currentTimeMillis();
        final int playerCount = playersData.size();
        Debugger.write("Starting bulk save for " + playerCount + " player(s)...");

        // Step 1: Prepare all data snapshots in parallel (CPU-bound work)
        List<PlayerSaveData> saveDataList = new ArrayList<>();
        
        // Use parallel stream for faster snapshot creation on large player counts
        if (playerCount > 10) {
            playersData.entrySet().parallelStream().forEach(entry -> {
                PlayerSaveData data = createSaveDataSnapshot(entry.getKey(), entry.getValue().getKey(), entry.getValue().getValue());
                if (data != null) {
                    synchronized (saveDataList) {
                        saveDataList.add(data);
                    }
                }
            });
        } else {
            // Sequential for small counts (less overhead)
            for (Map.Entry<String, Map.Entry<String, PlayerQuests>> entry : playersData.entrySet()) {
                PlayerSaveData data = createSaveDataSnapshot(entry.getKey(), entry.getValue().getKey(), entry.getValue().getValue());
                if (data != null) {
                    saveDataList.add(data);
                }
            }
        }

        if (saveDataList.isEmpty()) {
            Debugger.write("No valid player data to save after snapshot creation.");
            return;
        }

        // Step 2: Save all data in a single transaction
        saveBulkToDatabase(saveDataList);

        final long duration = System.currentTimeMillis() - startTime;
        PluginLogger.info("Bulk saved " + saveDataList.size() + " player(s) in " + duration + "ms.");
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
     * Save all player data to database in a single transaction with batched statements.
     */
    private void saveBulkToDatabase(List<PlayerSaveData> saveDataList) {
        try (Connection conn = sqlManager.getConnection()) {
            if (conn == null) {
                PluginLogger.error("Database connection unavailable for bulk save.");
                return;
            }

            // Disable auto-commit for transaction batching
            final boolean originalAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);

            try {
                final boolean isMySQL = Database.getMode() == StorageMode.MYSQL;

                // Batch all player data saves
                final String playerQuery = isMySQL
                        ? SQLQuery.MYSQL_SAVE_PLAYER.getQuery()
                        : SQLQuery.SQLITE_SAVE_PLAYER.getQuery();

                try (PreparedStatement playerStatement = conn.prepareStatement(playerQuery)) {
                    for (PlayerSaveData data : saveDataList) {
                        playerStatement.setString(1, data.playerUuid());
                        playerStatement.setLong(2, data.timestamp());
                        playerStatement.setInt(3, data.achievedQuests());
                        playerStatement.setInt(4, data.totalAchievedQuests());
                        playerStatement.addBatch();
                    }
                    playerStatement.executeBatch();
                }

                // Batch all quest progression saves
                final String progressQuery = isMySQL
                        ? SQLQuery.MYSQL_SAVE_PROGRESS.getQuery()
                        : SQLQuery.SQLITE_SAVE_PROGRESS.getQuery();

                try (PreparedStatement progressStatement = conn.prepareStatement(progressQuery)) {
                    for (PlayerSaveData data : saveDataList) {
                        int index = 0;
                        for (Map.Entry<AbstractQuest, Progression> entry : data.quests().entrySet()) {
                            final AbstractQuest quest = entry.getKey();
                            final Progression progression = entry.getValue();

                            if (quest == null || progression == null) continue;

                            progressStatement.setString(1, data.playerUuid());
                            progressStatement.setInt(2, index);
                            progressStatement.setInt(3, quest.getQuestIndex());
                            progressStatement.setInt(4, progression.getAdvancement());
                            progressStatement.setInt(5, progression.getRequiredAmount());
                            progressStatement.setBoolean(6, progression.isAchieved());
                            progressStatement.setInt(7, progression.getSelectedRequiredIndex());
                            progressStatement.addBatch();
                            index++;
                        }
                    }
                    progressStatement.executeBatch();
                }

                // Batch all category stats saves
                final String categoryQuery = isMySQL
                        ? SQLQuery.MYSQL_SAVE_PLAYER_CATEGORY_STATS.getQuery()
                        : SQLQuery.SQLITE_SAVE_PLAYER_CATEGORY_STATS.getQuery();

                try (PreparedStatement categoryStatement = conn.prepareStatement(categoryQuery)) {
                    for (PlayerSaveData data : saveDataList) {
                        for (Map.Entry<String, Integer> entry : data.categoryStats().entrySet()) {
                            if (entry.getKey() == null) continue;
                            
                            categoryStatement.setString(1, data.playerUuid());
                            categoryStatement.setString(2, entry.getKey());
                            categoryStatement.setInt(3, entry.getValue());
                            categoryStatement.addBatch();
                        }
                    }
                    categoryStatement.executeBatch();
                }

                // Commit the transaction
                conn.commit();
                Debugger.write("Bulk transaction committed successfully.");

            } catch (SQLException e) {
                // Rollback on error
                try {
                    conn.rollback();
                    PluginLogger.error("Bulk save failed, transaction rolled back: " + e.getMessage());
                } catch (SQLException rollbackEx) {
                    PluginLogger.error("Failed to rollback transaction: " + rollbackEx.getMessage());
                }
            } finally {
                // Restore auto-commit
                try {
                    conn.setAutoCommit(originalAutoCommit);
                } catch (SQLException e) {
                    Debugger.write("Failed to restore auto-commit: " + e.getMessage());
                }
            }

        } catch (SQLException e) {
            PluginLogger.error("Failed to get connection for bulk save: " + e.getMessage());
        }
    }

    /**
     * Save player quests progression.
     *
     * @param playerName   name of the player.
     * @param playerUuid   player uuid.
     * @param playerQuests player quests.
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

        // Edge case: null SQL manager
        if (sqlManager == null) {
            PluginLogger.error("Cannot save progression for player " + playerName + ": SQL manager is not initialized.");
            return;
        }

        Debugger.write("Entering saveProgression method for player " + playerName);

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
            saveDatas(playerName, playerUuid, timestamp, achievedQuests, totalAchievedQuests, questsSnapshot, categoryStatsSnapshot);
        } else {
            try {
                ODailyQuests.morePaperLib.scheduling().asyncScheduler().run(() -> {
                    Debugger.write("Saving player " + playerName + " progression asynchronously.");
                    saveDatas(playerName, playerUuid, timestamp, achievedQuests, totalAchievedQuests, questsSnapshot, categoryStatsSnapshot);
                });
            } catch (Exception e) {
                // Fallback to synchronous save if async scheduling fails (e.g., during shutdown)
                Debugger.write("Async scheduling failed for player " + playerName + ", falling back to synchronous save: " + e.getMessage());
                saveDatas(playerName, playerUuid, timestamp, achievedQuests, totalAchievedQuests, questsSnapshot, categoryStatsSnapshot);
            }
        }
    }

    /**
     * Save player quests progression.
     *
     * @param playerName          name of the player.
     * @param playerUuid          player uuid.
     * @param timestamp           timestamp.
     * @param achievedQuests      achieved quests.
     * @param totalAchievedQuests total achieved quests.
     * @param quests              quests.
     */
    private void saveDatas(String playerName, String playerUuid, long timestamp, int achievedQuests, int totalAchievedQuests, Map<AbstractQuest, Progression> quests, Map<String, Integer> totalAchievedByCategory) {
        try (Connection conn = sqlManager.getConnection()) {
            if (conn == null) {
                PluginLogger.error("Database connection unavailable");
                return;
            }

            final String playerQuery = (Database.getMode() == StorageMode.MYSQL)
                    ? SQLQuery.MYSQL_SAVE_PLAYER.getQuery()
                    : SQLQuery.SQLITE_SAVE_PLAYER.getQuery();

            try (PreparedStatement playerStatement = conn.prepareStatement(playerQuery)) {
                playerStatement.setString(1, playerUuid);
                playerStatement.setLong(2, timestamp);
                playerStatement.setInt(3, achievedQuests);
                playerStatement.setInt(4, totalAchievedQuests);
                playerStatement.executeUpdate();

                Debugger.write("Player " + playerName + " data saved");
            }

            final String progressQuery = (Database.getMode() == StorageMode.MYSQL)
                    ? SQLQuery.MYSQL_SAVE_PROGRESS.getQuery()
                    : SQLQuery.SQLITE_SAVE_PROGRESS.getQuery();

            try (PreparedStatement progressionStatement = conn.prepareStatement(progressQuery)) {
                progressionStatement.setString(1, playerUuid);

                int index = 0;
                for (Map.Entry<AbstractQuest, Progression> entry : quests.entrySet()) {
                    final AbstractQuest quest = entry.getKey();
                    final Progression progression = entry.getValue();

                    progressionStatement.setInt(2, index);
                    progressionStatement.setInt(3, quest.getQuestIndex());
                    progressionStatement.setInt(4, progression.getAdvancement());
                    progressionStatement.setInt(5, progression.getRequiredAmount());
                    progressionStatement.setBoolean(6, progression.isAchieved());
                    progressionStatement.setInt(7, progression.getSelectedRequiredIndex());
                    progressionStatement.addBatch();

                    Debugger.write("Quest number " + index + " saved for player " + playerName);
                    index++;
                }

                progressionStatement.executeBatch();
                Debugger.write(playerName + " quests progression saved");
            }

            final String categoryQuery = (Database.getMode() == StorageMode.MYSQL)
                    ? SQLQuery.MYSQL_SAVE_PLAYER_CATEGORY_STATS.getQuery()
                    : SQLQuery.SQLITE_SAVE_PLAYER_CATEGORY_STATS.getQuery();

            try (PreparedStatement categoryStatement = conn.prepareStatement(categoryQuery)) {
                categoryStatement.setString(1, playerUuid);

                for (Map.Entry<String, Integer> entry : totalAchievedByCategory.entrySet()) {
                    categoryStatement.setString(2, entry.getKey());
                    categoryStatement.setInt(3, entry.getValue());
                    categoryStatement.addBatch();
                }

                categoryStatement.executeBatch();
                Debugger.write(playerName + "'s category stats saved.");
            }

            if (Logs.isEnabled()) {
                PluginLogger.info(playerName + "'s data saved.");
            }
        } catch (SQLException e) {
            Debugger.write("An error occurred while saving player " + playerName + " data.");
            Debugger.write(e.getMessage());
            PluginLogger.error("An error occurred while saving player " + playerName + " data.");
            PluginLogger.error(e.getMessage());
        }
    }
}
