package me.flaxi.serverwebadmin;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public class ServerWebAdmin extends JavaPlugin {

    private WebServer webServer;
    private TpsMonitor tpsMonitor;

    @Override
    public void onEnable() {
        tpsMonitor = new TpsMonitor();
        tpsMonitor.start(this);

        getLogger().info("ServerWebAdmin Enabled!");

        saveDefaultConfig();

        int port = getConfig().getInt("web.port", 8080);
        String token = getConfig().getString("web.admin-token", "change-this-token");

        try {
            webServer = new WebServer(this, port, token, tpsMonitor);
            getLogger().info("Web Server Started on Port " + port);
        } catch (Exception e) {
            e.printStackTrace();
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