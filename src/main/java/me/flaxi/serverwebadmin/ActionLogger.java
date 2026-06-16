/*
 * MinePanel
 * Copyright (C) 2026 FlaxiLabs
 *
 * Licensed under GNU GPL v3
 * https://www.gnu.org/licenses/gpl-3.0.html
 */

package me.flaxi.serverwebadmin;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class ActionLogger {

    private final JavaPlugin plugin;
    private final File logFile;

    public ActionLogger(JavaPlugin plugin) {
        this.plugin = plugin;

        File logsFolder = new File(plugin.getDataFolder(), "logs");

        if (!logsFolder.exists()) {
            logsFolder.mkdirs();
        }

        this.logFile = new File(logsFolder, "actions.log");
    }

    public void write(String action, String playerName, String ip) {
        String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());

        String line = "[" + time + "] "
                + "ACTION=" + action
                + " PLAYER=" + playerName
                + " IP=" + ip
                + System.lineSeparator();

        try {
            FileWriter writer = new FileWriter(logFile, true);
            writer.write(line);
            writer.close();
        } catch (IOException e) {
            plugin.getLogger().warning("Cannot write action log: " + e.getMessage());
        }
    }
}