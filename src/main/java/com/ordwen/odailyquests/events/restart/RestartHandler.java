package com.ordwen.odailyquests.events.restart;

import com.ordwen.odailyquests.ODailyQuests;
import com.ordwen.odailyquests.configuration.essentials.Debugger;
import com.ordwen.odailyquests.tools.PluginLogger;

public class RestartHandler {

    private final ODailyQuests plugin;

    public RestartHandler(ODailyQuests oDailyQuests) {
        this.plugin = oDailyQuests;
    }

    /**
     * Mark the server as stopping to ensure data is saved synchronously.
     * This prevents async tasks from being scheduled during shutdown when
     * the scheduler may already be terminated (especially on Folia).
     */
    public void setServerStopping() {
        if (plugin.isServerStopping()) {
            // Already marked as stopping, avoid duplicate messages
            return;
        }
        Debugger.write("Server is stopping. Data will be saved synchronously to ensure integrity.");
        plugin.setServerStopping(true);
    }

    public void registerSubClasses() {
        plugin.getServer().getPluginManager().registerEvents(new RestartCommandListener(plugin), plugin);

        if (plugin.getServer().getPluginManager().getPlugin("UltimateAutoRestart") != null) {
            plugin.getServer().getPluginManager().registerEvents(new UARListener(plugin), plugin);
        }
    }
}
