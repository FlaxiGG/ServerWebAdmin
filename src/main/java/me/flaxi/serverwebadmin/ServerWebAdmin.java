/*
 * MinePanel
 * Copyright (C) 2026 FlaxiLabs
 *
 * Licensed under GNU GPL v3
 * https://www.gnu.org/licenses/gpl-3.0.html
 */

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
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;

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

        String host = getConfig().getString("web.host", "0.0.0.0");
        int port = getConfig().getInt("web.port", 8080);
        boolean external = getConfig().getBoolean("web.allow-external", true);
        boolean fallbackToAny = getConfig().getBoolean("web.fallback-to-any", true);
        int sessionTimeout = getConfig().getInt("web.session-timeout", 5);
        if (sessionTimeout < 1) sessionTimeout = 5;

        if (!external) {
            host = "127.0.0.1";
        }
        if ("localhost".equalsIgnoreCase(host)) {
            host = "127.0.0.1";
        }
        if ("auto".equalsIgnoreCase(host)) {
            host = detectBestHost();
            getLogger().info("Auto-detected host: " + host);
        }

        if (isPublicIp(host)) {
            getLogger().warning("Host \"" + host + "\" is a public IP address.");
            getLogger().warning("Public IPs cannot be bound directly " +
                    "(they are not on your machine's network interface).");
            getLogger().warning("Falling back to 0.0.0.0 — use port forwarding instead.");
            host = "0.0.0.0";
        }

        Map<String, WebServer.UserInfo> users = loadUsers();

        Map<String, String> alerts = new HashMap<>();
        alerts.put("kick", getConfig().getString("alerts.kick", "You have been kicked by Web Admin."));
        alerts.put("ban_reason", getConfig().getString("alerts.ban_reason", "Banned by Web Admin"));
        alerts.put("ban_kick", getConfig().getString("alerts.ban_kick", "You have been banned by Web Admin."));

        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }

        startServer(host, port, sessionTimeout, users, alerts, fallbackToAny);
    }

    private void startServer(String host, int port, int sessionTimeout,
                             Map<String, WebServer.UserInfo> users, Map<String, String> alerts,
                             boolean fallbackToAny) {
        try {
            webServer = new WebServer(this, host, port, sessionTimeout, users, alerts, tpsMonitor);
            getLogger().info("Web server bound to " + host + ":" + port);
            return;
        } catch (java.net.BindException e) {
            getLogger().severe("Cannot bind to " + host + ":" + port + " — Port already in use");
        } catch (java.net.UnknownHostException e) {
            getLogger().severe("Invalid host address: \"" + host + "\"");
        } catch (IOException e) {
            getLogger().severe("Cannot bind to " + host + ":" + port);
            getLogger().severe("Reason: " + e.getMessage());
            getLogger().severe("Possible causes:");
            getLogger().severe("  • Docker container — process sees internal IP, not public IP");
            getLogger().severe("  • Shared hosting (Pterodactyl) — no direct interface access");
            getLogger().severe("  • Public IP not assigned to any local network interface");
            getLogger().severe("Recommended fix:");
            getLogger().severe("  • Set host: 0.0.0.0 in config.yml");
            getLogger().severe("  • Use port forwarding on your router / hosting panel");
            getLogger().severe("  • Or use a reverse proxy (Nginx, Cloudflare Tunnel)");
        }

        if (fallbackToAny && !"0.0.0.0".equals(host)) {
            getLogger().warning("fallback-to-any is enabled — trying 0.0.0.0:" + port + " ...");
            try {
                webServer = new WebServer(this, "0.0.0.0", port, sessionTimeout, users, alerts, tpsMonitor);
                getLogger().info("Web server bound to 0.0.0.0:" + port + " (fallback)");
            } catch (IOException ex) {
                getLogger().severe("Fallback also failed: " + ex.getMessage());
            }
        }
    }

    public Map<String, WebServer.UserInfo> loadUsers() {
        Map<String, WebServer.UserInfo> users = new HashMap<>();
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
                    String role = usersConfig.getString("users." + username + ".role", "viewer");
                    boolean mustChange = usersConfig.getBoolean("users." + username + ".mustChangePassword", false);
                    users.put(username, new WebServer.UserInfo(hash, role, mustChange));
                }
            }
        }
        if (users.isEmpty()) {
            String defaultHash = BCrypt.hashpw("admin123", BCrypt.gensalt());
            users.put("owner", new WebServer.UserInfo(defaultHash, "owner", true));
            needsSave = true;
            getLogger().info("Default owner account created (owner / admin123)");
        }
        if (needsSave) {
            saveUsers(users);
        }
        return users;
    }

    public void saveUsers(Map<String, WebServer.UserInfo> users) {
        usersConfig.set("users", null);
        for (Map.Entry<String, WebServer.UserInfo> entry : users.entrySet()) {
            usersConfig.set("users." + entry.getKey() + ".password", entry.getValue().password);
            usersConfig.set("users." + entry.getKey() + ".role", entry.getValue().role);
            usersConfig.set("users." + entry.getKey() + ".mustChangePassword", entry.getValue().mustChangePassword);
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
            sender.sendMessage("§cYou don't have a permission to use this.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§a===== ServerWebAdmin =====");
            sender.sendMessage("§e/webadmin players §7- Show online players");
            sender.sendMessage("§e/webadmin kick <player> §7- Kick player");
            sender.sendMessage("§e/webadmin ban <player> §7- Ban player");
            sender.sendMessage("§e/webadmin reload §7- Reload plugin config");
            sender.sendMessage("§eOnline: §f" + Bukkit.getOnlinePlayers().size());
            return true;
        }

        if (args[0].equalsIgnoreCase("players")) {
            sender.sendMessage("§aOnline Players: §f" + Bukkit.getOnlinePlayers().size());

            for (Player player : Bukkit.getOnlinePlayers()) {
                sender.sendMessage("§7- §f" + player.getName());
            }

            return true;
        }

        if (args[0].equalsIgnoreCase("kick")) {
            if (args.length < 2) {
                sender.sendMessage("§cUse command: /webadmin kick <player>");
                return true;
            }

            Player target = Bukkit.getPlayer(args[1]);

            if (target == null) {
                sender.sendMessage("§cNot found or this player is offline.");
                return true;
            }

            target.kickPlayer("You have been kicked by admin.");
            sender.sendMessage("§aKick player §f" + target.getName() + " §asuccess");
            return true;
        }

        if (args[0].equalsIgnoreCase("ban")) {
            if (args.length < 2) {
                sender.sendMessage("§cUse command: /webadmin ban <player>");
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

            sender.sendMessage("§aBan player §f" + playerName + " §asuccess");
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage("§aReloading ServerWebAdmin...");
            onDisable();
            reloadConfig();
            onEnable();
            sender.sendMessage("§aServerWebAdmin reloaded successfully!");
            return true;
        }

        sender.sendMessage("§cNot found this command, Use /webadmin for help.");
        return true;
    }

    private boolean isPublicIp(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress()) return false;
            if (addr.isSiteLocalAddress()) return false;
            if (addr.isLinkLocalAddress()) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String detectBestHost() {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface ni = nets.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> inets = ni.getInetAddresses();
                while (inets.hasMoreElements()) {
                    InetAddress addr = inets.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "0.0.0.0";
    }
}