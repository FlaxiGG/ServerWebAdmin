package me.flaxi.serverwebadmin;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.mindrot.jbcrypt.BCrypt;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public class ServerWebAdmin extends JavaPlugin {

    private WebServer webServer;
    private TpsMonitor tpsMonitor;
    private File usersFile;
    private YamlConfiguration usersConfig;

    @Override
    public void onEnable() {
        tpsMonitor = new TpsMonitor();
        tpsMonitor.start(this);

        getLogger().info("ServerWebAdmin Enabled!");

        saveDefaultConfig();

        int port = getConfig().getInt("web.port", 8080);

        Map<String, String> users = new HashMap<>();
        usersFile = new File(getDataFolder(), "users.yml");
        if (!usersFile.exists()) {
            saveResource("users.yml", false);
        }
        usersConfig = YamlConfiguration.loadConfiguration(usersFile);
        boolean needsSave = false;
        if (usersConfig.isConfigurationSection("users")) {
            Set<String> keys = usersConfig.getConfigurationSection("users").getKeys(false);
            for (String username : keys) {
                String hash = usersConfig.getString("users." + username + ".password");
                if (hash != null && !hash.isEmpty()) {
                    if (!hash.startsWith("$2a$")) {
                        hash = BCrypt.hashpw(hash, BCrypt.gensalt());
                        needsSave = true;
                    }
                    users.put(username, hash);
                }
            }
        }
        if (users.isEmpty()) {
            String defaultHash = BCrypt.hashpw("admin123", BCrypt.gensalt());
            users.put("admin", defaultHash);
            needsSave = true;
            getLogger().info("Default admin user created (admin / admin123)");
        }
        if (needsSave) {
            saveUsers(users);
        }

        Map<String, String> alerts = new HashMap<>();
        alerts.put("kick", getConfig().getString("alerts.kick", "You have been kicked by Web Admin."));
        alerts.put("ban_reason", getConfig().getString("alerts.ban_reason", "Banned by Web Admin"));
        alerts.put("ban_kick", getConfig().getString("alerts.ban_kick", "You have been banned by Web Admin."));

        try {
            webServer = new WebServer(this, port, users, alerts, tpsMonitor);
            getLogger().info("Web Server Started on Port " + port);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void saveUsers(Map<String, String> users) {
        usersConfig.set("users", null);
        for (Map.Entry<String, String> entry : users.entrySet()) {
            usersConfig.set("users." + entry.getKey() + ".password", entry.getValue());
        }
        try {
            usersConfig.save(usersFile);
        } catch (IOException e) {
            getLogger().warning("Failed to save users.yml: " + e.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (webServer != null) {
            webServer.stop();
        }
        if (tpsMonitor != null) {
            tpsMonitor.stop();
        }
        getLogger().info("ServerWebAdmin Disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!command.getName().equalsIgnoreCase("webadmin")) {
            return false;
        }

        if (!sender.hasPermission("serverwebadmin.use")) {
            sender.sendMessage("§cคุณไม่มีสิทธิ์ใช้คำสั่งนี้");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§a===== ServerWebAdmin =====");
            sender.sendMessage("§e/webadmin players §7- ดูผู้เล่นออนไลน์");
            sender.sendMessage("§e/webadmin kick <player> §7- เตะผู้เล่น");
            sender.sendMessage("§e/webadmin ban <player> §7- แบนผู้เล่น");
            sender.sendMessage("§eOnline: §f" + Bukkit.getOnlinePlayers().size());
            return true;
        }

        if (args[0].equalsIgnoreCase("players")) {
            sender.sendMessage("§aผู้เล่นออนไลน์: §f" + Bukkit.getOnlinePlayers().size());

            for (Player player : Bukkit.getOnlinePlayers()) {
                sender.sendMessage("§7- §f" + player.getName());
            }

            return true;
        }

        if (args[0].equalsIgnoreCase("kick")) {
            if (args.length < 2) {
                sender.sendMessage("§cใช้คำสั่ง: /webadmin kick <player>");
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);

            if (target == null) {
                sender.sendMessage("§cไม่พบผู้เล่นนี้ หรือผู้เล่นไม่ได้ออนไลน์");
                return true;
            }

            target.kickPlayer("You have been kicked by admin.");
            sender.sendMessage("§aKick ผู้เล่น §f" + target.getName() + " §aสำเร็จ");
            return true;
        }

        if (args[0].equalsIgnoreCase("ban")) {
            if (args.length < 2) {
                sender.sendMessage("§cใช้คำสั่ง: /webadmin ban <player>");
                return true;
            }

            String playerName = args[1];

            Bukkit.getBanList(BanList.Type.NAME).addBan(
                    playerName,
                    "Banned by ServerWebAdmin",
                    null,
                    sender.getName()
            );

            Player target = Bukkit.getPlayer(playerName);

            if (target != null) {
                target.kickPlayer("You have been banned by admin.");
            }

            sender.sendMessage("§aBan ผู้เล่น §f" + playerName + " §aสำเร็จ");
            return true;
        }

        sender.sendMessage("§cไม่พบคำสั่งนี้ ใช้ /webadmin");
        return true;
    }
}