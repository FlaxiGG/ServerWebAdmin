package me.flaxi.serverwebadmin;

import fi.iki.elonen.NanoHTTPD;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.*;
import java.util.*;

import java.lang.management.ManagementFactory;

public class WebServer extends NanoHTTPD {

    private final ServerWebAdmin plugin;
    private final Map<String, String> users;
    private final Map<String, String> alerts;
    private final ActionLogger actionLogger;
    private final TpsMonitor tpsMonitor;

    public WebServer(ServerWebAdmin plugin, int port, Map<String, String> users, Map<String, String> alerts, TpsMonitor tpsMonitor) throws IOException {
        super(port);
        this.plugin = plugin;
        this.users = users;
        this.alerts = alerts;
        this.tpsMonitor = tpsMonitor;
        this.actionLogger = new ActionLogger(plugin);
        start(SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        if (uri.equals("/api/login") && method == Method.POST) {
            Map<String, String> params = session.getParms();
            String username = params.get("username");
            String password = params.get("password");
            if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing username or password\"}");
            }
            String storedPassword = users.get(username);
            if (storedPassword != null && storedPassword.equals(password)) {
                final String ip = getClientIp(session);
                actionLogger.write("LOGIN", username, ip);
                return json("{\"success\":true,\"token\":\"" + password + "\",\"message\":\"Login successful\"}");
            }
            return json("{\"success\":false,\"message\":\"Invalid username or password\"}");
        }

        if (uri.equals("/api/users") && method == Method.GET) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            StringBuilder sb = new StringBuilder("[");
            int idx = 0;
            for (String username : users.keySet()) {
                if (idx > 0) sb.append(",");
                sb.append("{\"username\":\"").append(escapeJson(username)).append("\"}");
                idx++;
            }
            sb.append("]");
            return json(sb.toString());
        }

        if (uri.equals("/api/users/add") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            Map<String, String> params = session.getParms();
            final String username = params.get("username");
            final String password = params.get("password");
            if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing username or password\"}");
            }
            if (users.containsKey(username)) {
                return json("{\"success\":false,\"message\":\"User already exists\"}");
            }
            users.put(username, password);
            plugin.saveUsers(users);
            final String ip = getClientIp(session);
            actionLogger.write("USER_ADD", username, ip);
            return json("{\"success\":true,\"message\":\"User added\"}");
        }

        if (uri.equals("/api/users/remove") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            Map<String, String> params = session.getParms();
            final String username = params.get("username");
            if (username == null || username.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing username parameter\"}");
            }
            if (!users.containsKey(username)) {
                return json("{\"success\":false,\"message\":\"User not found\"}");
            }
            if (users.size() <= 1) {
                return json("{\"success\":false,\"message\":\"Cannot remove the last user\"}");
            }
            users.remove(username);
            plugin.saveUsers(users);
            final String ip = getClientIp(session);
            actionLogger.write("USER_REMOVE", username, ip);
            return json("{\"success\":true,\"message\":\"User removed\"}");
        }

        if (uri.equals("/api/users/change-password") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            Map<String, String> params = session.getParms();
            final String username = params.get("username");
            final String newPassword = params.get("password");
            if (username == null || newPassword == null || username.isEmpty() || newPassword.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing username or password\"}");
            }
            if (!users.containsKey(username)) {
                return json("{\"success\":false,\"message\":\"User not found\"}");
            }
            users.put(username, newPassword);
            plugin.saveUsers(users);
            final String ip = getClientIp(session);
            actionLogger.write("USER_PASSWORD", username, ip);
            return json("{\"success\":true,\"message\":\"Password changed\"}");
        }

        if (uri.equals("/api/status") && method == Method.GET) {
            return json("{\"status\":\"online\",\"players\":" + Bukkit.getOnlinePlayers().size() + "}");
        }

        if (uri.equals("/api/players") && method == Method.GET) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            StringBuilder result = new StringBuilder();
            result.append("{\"online\":").append(Bukkit.getOnlinePlayers().size()).append(",");
            result.append("\"players\":[");

            int index = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (index > 0) result.append(",");
                String gm = player.getGameMode().name().toLowerCase();
                int health = (int) player.getHealth();
                int food = player.getFoodLevel();
                result.append("{")
                        .append("\"name\":\"").append(player.getName()).append("\",")
                        .append("\"x\":").append(player.getLocation().getBlockX()).append(",")
                        .append("\"y\":").append(player.getLocation().getBlockY()).append(",")
                        .append("\"z\":").append(player.getLocation().getBlockZ()).append(",")
                        .append("\"gamemode\":\"").append(gm).append("\",")
                        .append("\"health\":").append(health).append(",")
                        .append("\"food\":").append(food)
                        .append("}");
                index++;
            }

            result.append("]}");
            return json(result.toString());
        }

        if (uri.equals("/api/kick") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            Map<String, String> params = session.getParms();
            final String playerName = params.get("player");

            if (playerName == null || playerName.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing player parameter\"}");
            }

            final String ip = getClientIp(session);
            actionLogger.write("KICK", playerName, ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Player target = Bukkit.getPlayer(playerName);

                    if (target != null) {
                        target.kickPlayer(alerts.get("kick"));
                    }
                }
            });

            return json("{\"success\":true,\"message\":\"Kick request sent\"}");
        }

        if (uri.equals("/api/ban") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            Map<String, String> params = session.getParms();
            final String playerName = params.get("player");

            if (playerName == null || playerName.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing player parameter\"}");
            }

            final String ip = getClientIp(session);
            actionLogger.write("BAN", playerName, ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.getBanList(BanList.Type.NAME).addBan(
                            playerName,
                            alerts.get("ban_reason"),
                            null,
                            "WebAdmin"
                    );

                    Player target = Bukkit.getPlayer(playerName);

                    if (target != null) {
                        target.kickPlayer(alerts.get("ban_kick"));
                    }
                }
            });

            return json("{\"success\":true,\"message\":\"Ban request sent\"}");
        }

        if (uri.equals("/api/server") && method == Method.GET) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }

            Runtime runtime = Runtime.getRuntime();

            long maxMemory = runtime.maxMemory() / 1024 / 1024;
            long totalMemory = runtime.totalMemory() / 1024 / 1024;
            long freeMemory = runtime.freeMemory() / 1024 / 1024;
            long usedMemory = totalMemory - freeMemory;

            long uptimeMillis = ManagementFactory.getRuntimeMXBean().getUptime();
            long uptimeSeconds = uptimeMillis / 1000;

            String motd = Bukkit.getMotd();
            if (motd == null || motd.isEmpty()) {
                motd = "Server Web Admin";
            }
            motd = motd.replaceAll("§[0-9a-fk-or]", "");
            motd = motd.replaceAll("&[0-9a-fk-or]", "");
            motd = motd.replace("\\", "\\\\").replace("\"", "\\\"");

            String data = "{"
                    + "\"status\":\"online\","
                    + "\"players\":" + Bukkit.getOnlinePlayers().size() + ","
                    + "\"ramUsed\":" + usedMemory + ","
                    + "\"ramMax\":" + maxMemory + ","
                    + "\"uptimeSeconds\":" + uptimeSeconds + ","
                    + "\"tps\":" + tpsMonitor.getTps() + ","
                    + "\"motd\":\"" + motd + "\""
                    + "}";

            return json(data);
        }

        //Server maintain command

        if (uri.equals("/api/reload") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            final String ip = getClientIp(session);
            actionLogger.write("RELOAD", "SERVER", ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "reload confirm");
                }
            });

            return json("{\"success\":true,\"message\":\"Server reloading\u2026\"}");
        }

        if (uri.equals("/api/save") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            final String ip = getClientIp(session);
            actionLogger.write("SAVE", "SERVER", ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
                }
            });

            return json("{\"success\":true,\"message\":\"World saved\"}");
        }

        if (uri.equals("/api/stop") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            final String ip = getClientIp(session);
            actionLogger.write("STOP", "SERVER", ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
                }
            });
            Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.shutdown();
                }
            }, 100L);

            return json("{\"success\":true,\"message\":\"Saving world then stopping\u2026\"}");
        }

        //Weather command

        if (uri.equals("/api/clear") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            final String ip = getClientIp(session);
            actionLogger.write("WEATHER", "CLEAR", ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "weather clear");
                }
            });

            return json("{\"success\":true,\"message\":\"Weather has been clear.\"}");
        }

        if (uri.equals("/api/rain") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            final String ip = getClientIp(session);
            actionLogger.write("WEATHER", "RAIN", ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "weather rain");
                }
            });

            return json("{\"success\":true,\"message\":\"Weather has been raining.\"}");
        }

        if (uri.equals("/api/thunder") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            final String ip = getClientIp(session);
            actionLogger.write("WEATHER", "THUNDER", ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "weather thunder");
                }
            });

            return json("{\"success\":true,\"message\":\"Weather has been thundering.\"}");
        }

        //Time set command
        if (uri.equals("/api/day") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            final String ip = getClientIp(session);
            actionLogger.write("TIME SET", "DAY", ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "time set day");
                }
            });

            return json("{\"success\":true,\"message\":\"Time has been set to day.\"}");
        }
        if (uri.equals("/api/night") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            final String ip = getClientIp(session);
            actionLogger.write("TIME SET", "NIGHT", ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "time set night");
                }
            });

            return json("{\"success\":true,\"message\":\"Time has been set to night.\"}");
        }
        if (uri.equals("/api/noon") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            final String ip = getClientIp(session);
            actionLogger.write("TIME SET", "NOON", ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "time set noon");
                }
            });

            return json("{\"success\":true,\"message\":\"Time has been set to noon.\"}");
        }
        if (uri.equals("/api/midnight") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            final String ip = getClientIp(session);
            actionLogger.write("TIME SET", "MIDNIGHT", ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "time set midnight");
                }
            });

            return json("{\"success\":true,\"message\":\"Time has been set to midnight.\"}");
        }

        //Players teleport command

        if (uri.equals("/api/teleport") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            Map<String, String> params = session.getParms();
            final String fromName = params.get("from");
            final String toName = params.get("to");

            if (fromName == null || fromName.isEmpty() || toName == null || toName.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing from/to player parameter\"}");
            }

            final String ip = getClientIp(session);
            actionLogger.write("TELEPORT", fromName + " \u2192 " + toName, ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Player fromPlayer = Bukkit.getPlayer(fromName);
                    Player toPlayer = Bukkit.getPlayer(toName);

                    if (fromPlayer != null && toPlayer != null) {
                        fromPlayer.teleport(toPlayer.getLocation());
                    }
                }
            });

            return json("{\"success\":true,\"message\":\"Teleported " + fromName + " to " + toName + "\"}");
        }

        if (uri.equals("/api/player-action") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            Map<String, String> params = session.getParms();
            final String playerName = params.get("player");
            final String action = params.get("action");

            if (playerName == null || playerName.isEmpty() || action == null) {
                return json("{\"success\":false,\"message\":\"Missing player or action parameter\"}");
            }

            final String ip = getClientIp(session);
            actionLogger.write("ACTION", playerName + " \u2192 " + action, ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Player target = Bukkit.getPlayer(playerName);
                    if (target == null) return;

                    if (action.startsWith("gamemode")) {
                        String gm = action.substring(9);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "gamemode " + gm + " " + playerName);
                    } else if (action.equals("heal")) {
                        target.setHealth(20);
                        target.setFoodLevel(20);
                    } else if (action.equals("feed")) {
                        target.setFoodLevel(20);
                        target.setSaturation(10);
                    } else if (action.equals("kill")) {
                        target.setHealth(0);
                    }
                }
            });

            return json("{\"success\":true,\"message\":\"Action '" + action + "' executed on " + playerName + "\"}");
        }

        if (uri.equals("/api/command") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            Map<String, String> params = session.getParms();
            final String command = params.get("command");

            if (command == null || command.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing command parameter\"}");
            }

            final String ip = getClientIp(session);
            actionLogger.write("COMMAND", command, ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
                }
            });

            return json("{\"success\":true,\"message\":\"Command executed: " + command + "\"}");
        }

        if (uri.equals("/api/console") && method == Method.GET) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }

            int lines = 50;
            String linesParam = session.getParms().get("lines");
            if (linesParam != null) {
                try {
                    lines = Integer.parseInt(linesParam);
                } catch (NumberFormatException ignored) {}
            }

            StringBuilder sb = new StringBuilder();
            try {
                File logFile = new File("logs/latest.log");
                if (logFile.exists()) {
                    List<String> allLines = new ArrayList<>();
                    BufferedReader reader = new BufferedReader(new FileReader(logFile));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        allLines.add(line);
                    }
                    reader.close();

                    int start = Math.max(0, allLines.size() - lines);
                    sb.append("{\"totalLines\":").append(allLines.size()).append(",\"lines\":[");
                    for (int i = start; i < allLines.size(); i++) {
                        if (i > start) sb.append(",");
                        sb.append("\"").append(escapeJson(allLines.get(i))).append("\"");
                    }
                    sb.append("]}");
                }
            } catch (IOException e) {
                sb.append("{\"lines\":[]}");
            }

            return json(sb.toString());
        }

        if (uri.equals("/api/bans") && method == Method.GET) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }

            StringBuilder sb = new StringBuilder();
            Set<BanEntry> bans = Bukkit.getBanList(BanList.Type.NAME).getBanEntries();
            sb.append("[");
            int idx = 0;
            for (BanEntry ban : bans) {
                if (idx > 0) sb.append(",");
                sb.append("{\"name\":\"").append(ban.getTarget())
                        .append("\",\"reason\":\"").append(escapeJson(ban.getReason() != null ? ban.getReason() : ""))
                        .append("\",\"source\":\"").append(escapeJson(ban.getSource() != null ? ban.getSource() : ""))
                        .append("\"}");
                idx++;
            }
            sb.append("]");

            return json(sb.toString());
        }

        if (uri.equals("/api/unban") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            Map<String, String> params = session.getParms();
            final String playerName = params.get("player");

            if (playerName == null || playerName.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing player parameter\"}");
            }

            final String ip = getClientIp(session);
            actionLogger.write("UNBAN", playerName, ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.getBanList(BanList.Type.NAME).pardon(playerName);
                }
            });

            return json("{\"success\":true,\"message\":\"Unbanned " + playerName + "\"}");
        }

        if (uri.equals("/api/whitelist") && method == Method.GET) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }

            boolean enabled = Bukkit.hasWhitelist();
            Set<OfflinePlayer> whitelisted = Bukkit.getWhitelistedPlayers();

            StringBuilder sb = new StringBuilder();
            sb.append("{\"enabled\":").append(enabled).append(",\"players\":[");
            int idx = 0;
            for (OfflinePlayer p : whitelisted) {
                if (idx > 0) sb.append(",");
                sb.append("{\"name\":\"").append(p.getName())
                        .append("\",\"uuid\":\"").append(p.getUniqueId().toString())
                        .append("\"}");
                idx++;
            }
            sb.append("]}");

            return json(sb.toString());
        }

        if (uri.equals("/api/whitelist/toggle") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            final String ip = getClientIp(session);
            boolean current = Bukkit.hasWhitelist();
            final boolean newState = !current;

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.setWhitelist(newState);
                }
            });

            actionLogger.write("WHITELIST_TOGGLE", String.valueOf(newState), ip);
            return json("{\"success\":true,\"enabled\":" + newState + ",\"message\":\"Whitelist " + (newState ? "enabled" : "disabled") + "\"}");
        }

        if (uri.equals("/api/whitelist/add") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            Map<String, String> params = session.getParms();
            final String playerName = params.get("player");

            if (playerName == null || playerName.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing player parameter\"}");
            }

            final String ip = getClientIp(session);
            actionLogger.write("WHITELIST_ADD", playerName, ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
                    if (target != null) {
                        target.setWhitelisted(true);
                    }
                }
            });

            return json("{\"success\":true,\"message\":\"Added " + playerName + " to whitelist\"}");
        }

        if (uri.equals("/api/whitelist/remove") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            Map<String, String> params = session.getParms();
            final String playerName = params.get("player");

            if (playerName == null || playerName.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing player parameter\"}");
            }

            final String ip = getClientIp(session);
            actionLogger.write("WHITELIST_REMOVE", playerName, ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    OfflinePlayer target = Bukkit.getOfflinePlayer(playerName);
                    if (target != null) {
                        target.setWhitelisted(false);
                    }
                }
            });

            return json("{\"success\":true,\"message\":\"Removed " + playerName + " from whitelist\"}");
        }

        String html =
                "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head>" +
                "<meta charset=\"UTF-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<meta name=\"theme-color\" content=\"#f4efe9\">" +
                "<meta name=\"color-scheme\" content=\"light\">" +
                "<title>Server Web Admin</title>" +
                "<style>" +
                "*{box-sizing:border-box;margin:0;padding:0}" +
                "body{" +
                "font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;" +
                "background:#f4efe9;color:#4a4a4a;min-height:100vh;line-height:1.5;" +
                "font-size:0.9375rem" +
                "}" +
                /* Header */
                ".header{" +
                "text-align:center;padding:2.5rem 1.5rem 2rem;" +
                "background:linear-gradient(135deg,#ece3d7 0%,#f4efe9 100%);" +
                "border-bottom:1px solid #e5ded5" +
                "}" +
                ".header h1{" +
                "font-size:1.625rem;font-weight:700;color:#3d3d3d;" +
                "letter-spacing:-0.02em;text-wrap:balance" +
                "}" +
                ".header .sub{font-size:0.8rem;color:#8a847c;margin-top:0.25rem;letter-spacing:0.02em}" +
                /* Grid */
                ".cards{" +
                "display:grid;grid-template-columns:repeat(auto-fill,minmax(290px,1fr));" +
                "gap:1rem;padding:1.5rem;max-width:1200px;margin:0 auto" +
                "}" +
                ".card-full{grid-column:1/-1}" +
                /* Card */
                ".card{" +
                "background:#fff;border-radius:16px;padding:1.5rem;" +
                "box-shadow:0 1px 3px rgba(0,0,0,.04),0 4px 12px rgba(0,0,0,.03);" +
                "border:1px solid #ece7df;" +
                "transition:box-shadow .2s ease,transform .2s ease" +
                "}" +
                ".card:hover{" +
                "box-shadow:0 2px 6px rgba(0,0,0,.06),0 8px 20px rgba(0,0,0,.04);" +
                "transform:translateY(-1px)" +
                "}" +
                ".card-header{" +
                "display:flex;align-items:center;gap:0.625rem;" +
                "margin-bottom:1rem;padding-bottom:0.875rem;" +
                "border-bottom:1px solid #f0ebe2" +
                "}" +
                ".card-header h2{font-size:1rem;font-weight:600;color:#3d3d3d;flex:1}" +
                ".dot{width:10px;height:10px;border-radius:50%;flex-shrink:0}" +
                ".dot-green{background:#8cbfa8}" +
                ".dot-blue{background:#8baec2}" +
                ".dot-purple{background:#a89ec4}" +
                ".dot-amber{background:#dbbc7c}" +
                /* Stat */
                ".stat-value{" +
                "font-size:1.75rem;font-weight:700;color:#3d3d3d;" +
                "font-variant-numeric:tabular-nums" +
                "}" +
                ".stat-label{" +
                "font-size:0.6875rem;color:#8a847c;text-transform:uppercase;" +
                "letter-spacing:0.06em;margin-bottom:0.25rem" +
                "}" +
                ".stat-row{display:flex;gap:1.5rem;flex-wrap:wrap}" +
                ".stat-item{min-width:0}" +
                /* Button */
                ".btn{" +
                "display:inline-flex;align-items:center;gap:0.375rem;" +
                "padding:0.5rem 1rem;border:none;border-radius:10px;" +
                "font-size:0.75rem;font-weight:600;cursor:pointer;" +
                "transition:background .15s ease,transform .1s ease;" +
                "touch-action:manipulation;-webkit-tap-highlight-color:transparent" +
                "}" +
                ".btn:active{transform:scale(.97)}" +
                ".btn:focus-visible{outline:2px solid #8baec2;outline-offset:2px}" +
                ".btn-primary{background:#8baec2;color:#fff}" +
                ".btn-primary:hover{background:#7a9db1}" +
                ".btn-kick{background:#efdba8;color:#6b5730}" +
                ".btn-kick:hover{background:#e5cf94}" +
                ".btn-ban{background:#ecc8c8;color:#6b3a3a}" +
                ".btn-ban:hover{background:#e0b4b4}" +
                ".btn-restart{background:#efdba8;color:#6b5730;font-size:0.8125rem;padding:0.625rem 1.25rem}" +
                ".btn-restart:hover{background:#e5cf94}" +
                ".btn-save{background:#a8d4b8;color:#3a5c40;font-size:0.8125rem;padding:0.625rem 1.25rem}" +
                ".btn-save:hover{background:#93c4a4}" +
                ".btn-tp{background:#c4b8df;color:#4a3e5c;font-size:0.75rem;padding:0.375rem 0.75rem}" +
                ".btn-tp:hover{background:#b4a6d3}" +
                ".btn-goto{background:#b8d4c8;color:#3a5c4a;font-size:0.75rem;padding:0.375rem 0.75rem}" +
                ".btn-goto:hover{background:#a3c4b5}" +
                /* Player info */
                ".player-info{flex:1;min-width:0}" +
                ".player-meta{font-size:0.6875rem;color:#8a847c;margin-top:0.125rem;display:flex;gap:0.75rem;flex-wrap:wrap}" +
                ".player-coords{font-variant-numeric:tabular-nums}" +
                ".player-gm{text-transform:capitalize}" +
                /* Modal overlay */
                ".modal-overlay{" +
                "position:fixed;inset:0;background:rgba(0,0,0,.25);" +
                "display:flex;align-items:center;justify-content:center;" +
                "z-index:200;opacity:0;pointer-events:none;" +
                "overscroll-behavior:contain;" +
                "transition:opacity .2s ease" +
                "}" +
                ".modal-overlay.show{opacity:1;pointer-events:auto}" +
                ".modal{" +
                "background:#fff;border-radius:16px;padding:1.5rem;" +
                "max-width:400px;width:90%;box-shadow:0 8px 32px rgba(0,0,0,.12);" +
                "transform:translateY(.5rem);transition:transform .2s ease" +
                "}" +
                ".modal-overlay.show .modal{transform:translateY(0)}" +
                ".modal h3{font-size:1.05rem;font-weight:600;color:#3d3d3d;margin-bottom:0.75rem}" +
                ".modal p{font-size:0.8125rem;color:#8a847c;margin-bottom:1rem}" +
                ".modal-actions{display:flex;gap:0.5rem;justify-content:flex-end;margin-top:1rem}" +
                /* Responsive modal */
                "@media(max-width:480px){" +
                ".modal{padding:1.25rem}" +
                ".player-meta{font-size:0.625rem;gap:0.5rem}" +
                "}" +
                /* Scroll margin for heading anchors */
                ".card-header h2{scroll-margin-top:1rem}" +
                ".btn-stop{background:#e49494;color:#fff;font-size:0.8125rem;padding:0.625rem 1.25rem}" +
                ".btn-stop:hover{background:#d67c7c}" +
                /* Input */
                ".input{" +
                "padding:0.625rem 0.875rem;border:1.5px solid #e5ded5;border-radius:10px;" +
                "font-size:0.875rem;color:#4a4a4a;background:#faf8f5;" +
                "transition:border-color .15s ease,box-shadow .15s ease;outline:none" +
                "}" +
                ".input::placeholder{color:#b0a99f}" +
                ".input:focus{border-color:#8baec2;box-shadow:0 0 0 3px rgba(139,174,194,.15)}" +
                /* Token row */
                ".token-row{display:flex;gap:0.5rem;align-items:center;flex-wrap:wrap}" +
                ".token-row .input{flex:1;min-width:180px}" +
                /* Player row */
                ".player-row{" +
                "display:flex;align-items:center;justify-content:space-between;" +
                "padding:0.625rem 0.75rem;border-radius:10px;margin-bottom:0.375rem;" +
                "background:#faf8f5;border:1px solid #f0ebe2;" +
                "transition:background .15s ease" +
                "}" +
                ".player-row:hover{background:#f4efe9}" +
                ".player-name{font-weight:500;color:#4a4a4a}" +
                ".player-actions{display:flex;gap:0.375rem;flex-shrink:0}" +
                /* Empty state */
                ".empty{" +
                "text-align:center;color:#b0a99f;padding:2rem 1rem;" +
                "font-size:0.875rem" +
                "}" +
                /* Footer */
                ".footer{" +
                "text-align:center;padding:1.5rem;color:#b0a99f;font-size:0.75rem" +
                "}" +
                /* Toast */
                ".toast{" +
                "position:fixed;bottom:1.5rem;right:1.5rem;left:1.5rem;" +
                "max-width:380px;margin-left:auto;background:#3d3d3d;color:#fff;" +
                "padding:0.75rem 1.25rem;border-radius:10px;font-size:0.8125rem;" +
                "font-weight:500;box-shadow:0 4px 16px rgba(0,0,0,.12);" +
                "opacity:0;transform:translateY(.5rem);pointer-events:none;" +
                "transition:opacity .2s ease,transform .2s ease;z-index:100" +
                "}" +
                ".toast.show{opacity:1;transform:translateY(0)}" +
                /* Loading pulse */
                "@keyframes pulse{0%,100%{opacity:1}50%{opacity:.5}}" +
                /* SR-only */
                ".sr-only{position:absolute;width:1px;height:1px;padding:0;" +
                "margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}" +
                /* Animations - respect reduced motion */
                "@media(prefers-reduced-motion:reduce){" +
                ".card{transition:none}" +
                ".btn{transition:none}" +
                ".toast{transition:none}" +
                "}" +
                /* Responsive */
                "@media(max-width:480px){" +
                ".cards{grid-template-columns:1fr;padding:1rem;gap:0.75rem}" +
                ".card{padding:1.25rem}" +
                ".header{padding:1.5rem 1rem 1.25rem}" +
                ".header h1{font-size:1.375rem}" +
                ".stat-value{font-size:1.5rem}" +
                "}" +
                /* Sidebar layout */
                ".app{display:flex;min-height:100vh}" +
                ".sidebar{" +
                "width:240px;background:linear-gradient(180deg,#e3ddd2 0%,#ded7cb 100%);" +
                "border-right:1px solid #d3ccc0;display:flex;flex-direction:column;" +
                "flex-shrink:0;position:sticky;top:0;height:100vh;overflow-y:auto;padding:1.75rem 1.25rem" +
                "}" +
                ".sidebar-brand{" +
                "font-size:1.05rem;font-weight:700;color:#3d3d3d;margin-bottom:1.75rem;" +
                "padding-bottom:1.25rem;border-bottom:1px solid #d3ccc0;letter-spacing:-0.02em;" +
                "display:flex;align-items:center;gap:0.625rem" +
                "}" +
                ".sidebar-brand-icon{" +
                "width:28px;height:28px;border-radius:8px;background:linear-gradient(135deg,#8cbfa8,#8baec2);" +
                "display:flex;align-items:center;justify-content:center;flex-shrink:0" +
                "}" +
                ".nav-btn{" +
                "display:flex;align-items:center;gap:0.625rem;width:100%;" +
                "padding:0.75rem 0.875rem;border:none;border-radius:10px;" +
                "background:none;color:#5a5a5a;font-size:0.85rem;font-weight:500;" +
                "cursor:pointer;text-align:left;margin-bottom:0.25rem;" +
                "transition:background .15s ease,color .15s ease;position:relative;overflow:hidden" +
                "}" +
                ".nav-btn:hover{background:#d5cdc0;color:#3d3d3d}" +
                ".nav-btn.active{background:#cbc1b2;color:#3d3d3d;font-weight:600}" +
                ".nav-btn.active::before{content:'';position:absolute;left:0;top:50%;transform:translateY(-50%);" +
                "width:3px;height:24px;border-radius:0 3px 3px 0;background:var(--nav-accent)}" +
                ".nav-dot{width:9px;height:9px;border-radius:50%;flex-shrink:0;transition:transform .15s ease}" +
                ".nav-btn.active .nav-dot{transform:scale(1.3)}" +
                ".nav-label{flex:1}" +
                ".main-area{flex:1;min-width:0;overflow-y:auto;width:0}" +
                ".page{display:none}" +
                ".page.active{display:block}" +
                ".page-content{padding:1.5rem;max-width:1200px;margin:0 auto}" +
                /* Console output */
                ".console{" +
                "background:#faf8f5;color:#4a4a4a;font-family:'SF Mono',Consolas,'Liberation Mono',monospace;" +
                "font-size:0.72rem;padding:0.75rem;border-radius:10px;max-height:320px;overflow-y:auto;" +
                "white-space:pre-wrap;word-break:break-all;line-height:1.6;border:1px solid #ece7df" +
                "}" +
                /* Table */
                ".tbl{width:100%;border-collapse:collapse;font-size:0.8125rem}" +
                ".tbl th{text-align:left;padding:0.5rem 0.75rem;font-size:0.6875rem;font-weight:600;color:#8a847c;text-transform:uppercase;letter-spacing:0.05em;border-bottom:1px solid #f0ebe2}" +
                ".tbl td{padding:0.625rem 0.75rem;border-bottom:1px solid #f0ebe2;color:#4a4a4a}" +
                ".tbl tr:hover td{background:#faf8f5}" +
                /* Health bar */
                ".hp-bar{width:80px;height:6px;border-radius:3px;background:#ece7df;overflow:hidden;display:inline-block;vertical-align:middle;margin-left:0.5rem}" +
                ".hp-fill{height:100%;border-radius:3px;transition:width .3s ease}" +
                /* Gamemode select */
                ".gm-select{font-size:0.6875rem;padding:0.25rem 0.5rem;border:1px solid #e5ded5;border-radius:6px;background:#fff;color:#4a4a4a;cursor:pointer}" +
                ".gm-select:focus-visible{outline:2px solid #8baec2;outline-offset:2px}" +
                /* Toggle switch */
                ".toggle-sw{position:relative;width:44px;height:24px;background:#d5cec4;border-radius:12px;cursor:pointer;transition:background .2s ease;border:none;flex-shrink:0}" +
                ".toggle-sw.on{background:#8cbfa8}" +
                ".toggle-sw::after{content:'';position:absolute;top:2px;left:2px;width:20px;height:20px;border-radius:50%;background:#fff;transition:transform .2s ease}" +
                ".toggle-sw.on::after{transform:translateX(20px)}" +
                /* Server Control card */
                ".ctrl-row{display:flex;gap:0.75rem;flex-wrap:wrap;align-items:center;margin-top:30px}" +
                /* Responsive sidebar */
                "@media(max-width:768px){" +
                ".app{flex-direction:column}" +
                ".sidebar{width:100%;height:auto;position:relative;flex-direction:row;flex-wrap:wrap;padding:0.875rem 1rem;gap:0.5rem;align-items:center;border-right:none;border-bottom:1px solid #d3ccc0}" +
                ".sidebar-brand{width:100%;margin-bottom:0;padding-bottom:0.625rem;border-bottom:1px solid #d3ccc0;font-size:0.9rem}" +
                ".nav-btn{width:auto;margin-bottom:0;font-size:0.75rem;padding:0.375rem 0.75rem}" +
                ".nav-btn.active::before{display:none}" +
                ".page-content{padding:1rem}" +
                ".tbl{font-size:0.75rem}" +
                ".tbl th,.tbl td{padding:0.375rem 0.5rem}" +
                "}" +
                "</style>" +
                "</head>" +
                "<body>" +
                /* Login page */
                "<div id=\"login-page\" style=\"display:flex;align-items:center;justify-content:center;min-height:100vh;background:linear-gradient(135deg,#ece3d7 0%,#f4efe9 100%);\">" +
                "<div style=\"background:#fff;border-radius:16px;padding:2.5rem;box-shadow:0 4px 24px rgba(0,0,0,.08);border:1px solid #ece7df;max-width:380px;width:90%;\">" +
                "<div style=\"text-align:center;margin-bottom:1.75rem;\">" +
                "<span style=\"display:inline-block;width:40px;height:40px;border-radius:10px;background:linear-gradient(135deg,#8cbfa8,#8baec2);margin-bottom:0.75rem;\"></span>" +
                "<h1 style=\"font-size:1.25rem;font-weight:700;color:#3d3d3d;\">Server Web Admin</h1>" +
                "<p style=\"font-size:0.75rem;color:#8a847c;margin-top:0.25rem;\">Sign in to continue</p>" +
                "</div>" +
                "<div id=\"login-error\" style=\"background:#fde8e8;color:#6b3a3a;font-size:0.75rem;padding:0.5rem 0.75rem;border-radius:8px;margin-bottom:1rem;display:none;\"></div>" +
                "<input id=\"login-username\" class=\"input\" type=\"text\" autocomplete=\"username\" placeholder=\"Username\" style=\"width:100%;margin-bottom:0.625rem;\">" +
                "<input id=\"login-password\" class=\"input\" type=\"password\" autocomplete=\"current-password\" placeholder=\"Password\" style=\"width:100%;margin-bottom:1rem;\" onkeydown=\"if(event.key==='Enter')doLogin()\">" +
                "<button class=\"btn btn-primary\" onclick=\"doLogin()\" style=\"width:100%;justify-content:center;font-size:0.8125rem;padding:0.625rem;\">Sign In</button>" +
                "</div>" +
                "</div>" +
                /* App container */
                "<div id=\"app-container\" style=\"display:none;\">" +
                /* App layout */
                "<div class=\"app\">" +
                /* Sidebar */
                "<nav class=\"sidebar\">" +
                "<div class=\"sidebar-brand\">" +
                "<span class=\"sidebar-brand-icon\" aria-hidden=\"true\"></span>" +
                "<span>Server Web Admin</span>" +
                "</div>" +
                "<button class=\"nav-btn active\" data-page=\"dashboard\" style=\"--nav-accent:#8cbfa8\" onclick=\"switchPage('dashboard')\"><span class=\"nav-dot\" style=\"background:#8cbfa8\"></span><span class=\"nav-label\">Dashboard</span></button>" +
                "<button class=\"nav-btn\" data-page=\"console\" style=\"--nav-accent:#8baec2\" onclick=\"switchPage('console')\"><span class=\"nav-dot\" style=\"background:#8baec2\"></span><span class=\"nav-label\">Console</span></button>" +
                "<button class=\"nav-btn\" data-page=\"bans\" style=\"--nav-accent:#e49494\" onclick=\"switchPage('bans')\"><span class=\"nav-dot\" style=\"background:#e49494\"></span><span class=\"nav-label\">Bans</span></button>" +
                "<button class=\"nav-btn\" data-page=\"whitelist\" style=\"--nav-accent:#dbbc7c\" onclick=\"switchPage('whitelist')\"><span class=\"nav-dot\" style=\"background:#dbbc7c\"></span><span class=\"nav-label\">Whitelist</span></button>" +
                "<button class=\"nav-btn\" data-page=\"users\" style=\"--nav-accent:#a89ec4\" onclick=\"switchPage('users')\"><span class=\"nav-dot\" style=\"background:#a89ec4\"></span><span class=\"nav-label\">User Management</span></button>" +
                "<button class=\"nav-btn\" onclick=\"doLogout()\" style=\"margin-top:auto;color:#8a847c;\"><span class=\"nav-label\" style=\"font-size:0.75rem;\">Logout</span></button>" +
                "</nav>" +
                /* Main area */
                "<div class=\"main-area\">" +
                /* === DASHBOARD === */
                "<section id=\"page-dashboard\" class=\"page active\">" +
                "<header class=\"header\">" +
                "<h1 id=\"server-motd\">Server Web Admin</h1>" +
                "<p class=\"sub\">Minecraft Server Control Panel</p>" +
                "</header>" +
                "<main class=\"cards\">" +
                /* Status card */
                "<div class=\"card\">" +
                "<div class=\"card-header\">" +
                "<span class=\"dot dot-green\" aria-hidden=\"true\"></span>" +
                "<h2>Server Status</h2>" +
                "</div>" +
                "<div class=\"stat-row\">" +
                "<div class=\"stat-item\">" +
                "<div class=\"stat-label\">Status</div>" +
                "<div class=\"stat-value\" style=\"font-size:1.125rem;color:#8cbfa8;\">Online</div>" +
                "</div>" +
                "<div class=\"stat-item\">" +
                "<div class=\"stat-label\">Players</div>" +
                "<div class=\"stat-value\" id=\"online\">0</div>" +
                "</div>" +
                "</div>" +
                "</div>" +
                /* Performance card */
                "<div class=\"card\">" +
                "<div class=\"card-header\">" +
                "<span class=\"dot dot-blue\" aria-hidden=\"true\"></span>" +
                "<h2>Server Performance</h2>" +
                "</div>" +
                "<div class=\"stat-label\">RAM Usage</div>" +
                "<div class=\"stat-value\" style=\"font-size:1.125rem;\"><span id=\"ram\">0 / 0 MB</span></div>" +
                "<div style=\"margin-top:0.75rem;\">" +
                "<div class=\"stat-label\">Uptime</div>" +
                "<div class=\"stat-value\" style=\"font-size:1.125rem;\" id=\"uptime\">0s</div>" +
                "</div>" +
                "</div>" +
                /* TPS card */
                "<div class=\"card\">" +
                "<div class=\"card-header\">" +
                "<span class=\"dot dot-purple\" aria-hidden=\"true\"></span>" +
                "<h2>Server TPS</h2>" +
                "</div>" +
                "<div class=\"stat-label\">Ticks Per Second</div>" +
                "<div class=\"stat-value\" id=\"tps\">20.0</div>" +
                "</div>" +
                /* Server Control card */
                "<div class=\"card card-full\">" +
                "<div class=\"card-header\">" +
                "<span class=\"dot dot-amber\" aria-hidden=\"true\"></span>" +
                "<h2>Server Control</h2>" +
                "</div>" +
                "<div class=\"ctrl-row\">" +
                "<span style=\"font-size:0.8125rem;color:#8a847c;flex:1;min-width:140px;\">Save, reload or stop the server</span>" +
                "<button class=\"btn btn-save\" onclick=\"saveServer()\">Save Server</button>" +
                "<button class=\"btn btn-restart\" onclick=\"reloadServer()\">Reload Server</button>" +
                "<button class=\"btn btn-stop\" onclick=\"stopServer()\">Stop Server</button>" +
                "</div>" +
                /* Weather section */
                "<div style=\"margin-top:1.25rem;display:flex;align-items:center;gap:0.625rem;\">" +
                "<span style=\"font-size:0.85rem;font-weight:600;color:#4a4a4a;\">Weather</span>" +
                "<span style=\"font-size:0.75rem;color:#8a847c;\">Clear, rain or thunder</span>" +
                "</div>" +
                "<div class=\"ctrl-row\">" +
                "<button class=\"btn btn-save\" onclick=\"weatherClear()\">Clear</button>" +
                "<button class=\"btn btn-restart\" onclick=\"weatherRain()\">Rain</button>" +
                "<button class=\"btn btn-stop\" onclick=\"weatherThunder()\">Thunder</button>" +
                "</div>" +
                /* Times section */
                "<div style=\"margin-top:1.25rem;display:flex;align-items:center;gap:0.625rem;\">" +
                "<span style=\"font-size:0.85rem;font-weight:600;color:#4a4a4a;\">Times</span>" +
                "<span style=\"font-size:0.75rem;color:#8a847c;\">Day, night, noon or midnight</span>" +
                "</div>" +
                "<div class=\"ctrl-row\">" +
                "<button class=\"btn btn-save\" onclick=\"timeDay()\">Day</button>" +
                "<button class=\"btn btn-restart\" onclick=\"timeNight()\">Night</button>" +
                "<button class=\"btn btn-stop\" onclick=\"timeNoon()\">Noon</button>" +
                "<button class=\"btn btn-stop\" onclick=\"timeMidnight()\">Midnight</button>" +
                "</div>" +
                "</div>" +
                /* Players card */
                "<div class=\"card card-full\">" +
                "<div class=\"card-header\">" +
                "<span class=\"dot dot-green\" aria-hidden=\"true\"></span>" +
                "<h2>Online Players</h2>" +
                "<span id=\"player-count\" style=\"font-size:0.8rem;color:#8a847c;\"></span>" +
                "</div>" +
                "<div id=\"players\"></div>" +
                "</div>" +
                /* Console Output card (dashboard) */
                "<div class=\"card card-full\">" +
                "<div class=\"card-header\">" +
                "<span class=\"dot dot-blue\" aria-hidden=\"true\"></span>" +
                "<h2>Console Output</h2>" +
                "<span style=\"font-size:0.7rem;color:#8a847c;\">last 15 lines</span>" +
                "</div>" +
                "<pre id=\"console-dash\" class=\"console\" style=\"max-height:200px;\">Loading\u2026</pre>" +
                "</div>" +
                "</main>" +
                "</section>" +
                /* === CONSOLE PAGE === */
                "<section id=\"page-console\" class=\"page\">" +
                "<div class=\"page-content\">" +
                "<h1 style=\"font-size:1.5rem;font-weight:700;color:#3d3d3d;margin-bottom:1.25rem;\">Console Commands</h1>" +
                "<div class=\"card\">" +
                "<div class=\"card-header\"><span class=\"dot dot-blue\" aria-hidden=\"true\"></span><h2>Run Command</h2></div>" +
                "<div class=\"token-row\">" +
                "<input id=\"console-cmd\" class=\"input\" type=\"text\" autocomplete=\"off\" spellcheck=\"false\" placeholder=\"Enter command (e.g. say Hello)\u2026\" style=\"flex:1\" onkeydown=\"if(event.key==='Enter')sendCommand()\">" +
                "<button class=\"btn btn-primary\" onclick=\"sendCommand()\">Send</button>" +
                "</div>" +
                "</div>" +
                "<div class=\"card card-full\" style=\"margin-top:1rem;\">" +
                "<div class=\"card-header\"><span class=\"dot dot-blue\" aria-hidden=\"true\"></span><h2>Console Output</h2><span style=\"font-size:0.7rem;color:#8a847c;\" id=\"console-line-count\"></span></div>" +
                "<pre id=\"console-full\" class=\"console\" style=\"max-height:500px;\">Loading\u2026</pre>" +
                "</div>" +
                "</div>" +
                "</section>" +
                /* === BANS PAGE === */
                "<section id=\"page-bans\" class=\"page\">" +
                "<div class=\"page-content\">" +
                "<h1 style=\"font-size:1.5rem;font-weight:700;color:#3d3d3d;margin-bottom:1.25rem;\">Ban Management</h1>" +
                "<div class=\"card card-full\">" +
                "<div class=\"card-header\"><span class=\"dot\" style=\"background:#e49494\" aria-hidden=\"true\"></span><h2>Banned Players</h2></div>" +
                "<div id=\"ban-list\"><div class=\"empty\">Loading\u2026</div></div>" +
                "</div>" +
                "</div>" +
                "</section>" +
                /* === WHITELIST PAGE === */
                "<section id=\"page-whitelist\" class=\"page\">" +
                "<div class=\"page-content\">" +
                "<h1 style=\"font-size:1.5rem;font-weight:700;color:#3d3d3d;margin-bottom:1.25rem;\">Whitelist Management</h1>" +
                "<div class=\"card card-full\">" +
                "<div style=\"display:flex;align-items:center;gap:1rem;flex-wrap:wrap;\">" +
                "<span style=\"font-weight:600;color:#4a4a4a;\">Whitelist</span>" +
                "<button id=\"wl-toggle\" class=\"toggle-sw\" onclick=\"toggleWhitelist()\" aria-label=\"Toggle whitelist\"></button>" +
                "<span id=\"wl-status\" style=\"font-size:0.875rem;color:#8a847c;\">Loading\u2026</span>" +
                "</div>" +
                "</div>" +
                "<div class=\"card card-full\" style=\"margin-top:1rem;\">" +
                "<div class=\"card-header\"><span class=\"dot dot-amber\" aria-hidden=\"true\"></span><h2>Add Player</h2></div>" +
                "<div class=\"token-row\">" +
                "<input id=\"wl-add-input\" class=\"input\" type=\"text\" autocomplete=\"off\" spellcheck=\"false\" placeholder=\"Player name\u2026\" style=\"flex:1\" onkeydown=\"if(event.key==='Enter')addWhitelist()\">" +
                "<button class=\"btn btn-primary\" onclick=\"addWhitelist()\">Add</button>" +
                "</div>" +
                "</div>" +
                "<div class=\"card card-full\" style=\"margin-top:1rem;\">" +
                "<div class=\"card-header\"><span class=\"dot dot-amber\" aria-hidden=\"true\"></span><h2>Whitelisted Players</h2></div>" +
                "<div id=\"wl-list\"><div class=\"empty\">Loading\u2026</div></div>" +
                "</div>" +
                "</div>" +
                "</section>" +
                /* === USER MANAGEMENT === */
                "<section id=\"page-users\" class=\"page\">" +
                "<div class=\"page-content\">" +
                "<h1 style=\"font-size:1.5rem;font-weight:700;color:#3d3d3d;margin-bottom:1.25rem;\">User Management</h1>" +
                "<div class=\"card card-full\">" +
                "<div class=\"card-header\"><span class=\"dot\" style=\"background:#a89ec4\" aria-hidden=\"true\"></span><h2>Add User</h2></div>" +
                "<div class=\"token-row\">" +
                "<input id=\"user-add-name\" class=\"input\" type=\"text\" autocomplete=\"off\" spellcheck=\"false\" placeholder=\"Username\u2026\" style=\"flex:1\" onkeydown=\"if(event.key==='Enter')addUser()\">" +
                "<input id=\"user-add-pass\" class=\"input\" type=\"password\" autocomplete=\"off\" placeholder=\"Password\u2026\" style=\"flex:1\" onkeydown=\"if(event.key==='Enter')addUser()\">" +
                "<button class=\"btn btn-primary\" onclick=\"addUser()\">Add</button>" +
                "</div>" +
                "</div>" +
                "<div class=\"card card-full\" style=\"margin-top:1rem;\">" +
                "<div class=\"card-header\"><span class=\"dot\" style=\"background:#a89ec4\" aria-hidden=\"true\"></span><h2>Users</h2></div>" +
                "<div id=\"user-list\"><div class=\"empty\">Loading\u2026</div></div>" +
                "</div>" +
                "</div>" +
                "</section>" +
                "</div>" + /* end main-area */
                "</div>" + /* end app */
                /* Modals */
                "<div id=\"tp-modal\" class=\"modal-overlay\" role=\"dialog\" aria-modal=\"true\" aria-labelledby=\"tp-modal-title\">" +
                "<div class=\"modal\">" +
                "<h3 id=\"tp-modal-title\">Teleport Player</h3>" +
                "<p id=\"tp-modal-desc\"></p>" +
                "<label for=\"tp-target\" class=\"sr-only\">Target player name</label>" +
                "<input id=\"tp-target\" class=\"input\" type=\"text\" autocomplete=\"off\" spellcheck=\"false\" placeholder=\"Enter target player name\u2026\" style=\"width:100%\">" +
                "<div class=\"modal-actions\">" +
                "<button class=\"btn\" style=\"background:#ece7df;color:#4a4a4a;\" onclick=\"closeTeleportModal()\">Cancel</button>" +
                "<button class=\"btn btn-primary\" onclick=\"doTeleport()\">Teleport</button>" +
                "</div>" +
                "</div>" +
                "</div>" +
                /* Go To Modal */
                "<div id=\"goto-modal\" class=\"modal-overlay\" role=\"dialog\" aria-modal=\"true\" aria-labelledby=\"goto-modal-title\">" +
                "<div class=\"modal\">" +
                "<h3 id=\"goto-modal-title\">Go To Player</h3>" +
                "<p id=\"goto-modal-desc\"></p>" +
                "<label for=\"goto-admin\" class=\"sr-only\">Your player name</label>" +
                "<input id=\"goto-admin\" class=\"input\" type=\"text\" autocomplete=\"off\" spellcheck=\"false\" placeholder=\"Enter your player name\u2026\" style=\"width:100%\">" +
                "<div class=\"modal-actions\">" +
                "<button class=\"btn\" style=\"background:#ece7df;color:#4a4a4a;\" onclick=\"closeGoToModal()\">Cancel</button>" +
                "<button class=\"btn btn-primary\" onclick=\"doGoTo()\">Go To</button>" +
                "</div>" +
                "</div>" +
                "</div>" +
                /* Footer */
                "<footer class=\"footer\">Server Web Admin &copy; 2026</footer>" +
                /* Toast */
                "<div id=\"toast\" class=\"toast\" role=\"status\" aria-live=\"polite\"></div>" +
                "</div>" + /* end app-container */
                /* Scripts */
                "<script>" +
                "var token=localStorage.getItem('adminToken')||'';" +
                /* Toast */
                "function showToast(msg){" +
                " var t=document.getElementById('toast');" +
                " t.textContent=msg;t.classList.add('show');" +
                " setTimeout(function(){t.classList.remove('show')},2800)" +
                "}" +
                /* Login */
                "function doLogin(){" +
                " var u=document.getElementById('login-username').value.trim();" +
                " var p=document.getElementById('login-password').value.trim();" +
                " if(!u||!p){showLoginError('Please enter username and password');return;}" +
                " fetch('/api/login?username='+encodeURIComponent(u)+'&password='+encodeURIComponent(p),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){" +
                "   if(data.success){" +
                "     token=data.token;" +
                "     localStorage.setItem('adminToken',token);" +
                "     document.getElementById('login-page').style.display='none';" +
                "     document.getElementById('app-container').style.display='block';" +
                "     clearPageTimers();" +
                "     loadPlayers();loadServerInfo();loadConsoleDash();" +
                "     pageTimers.p=setInterval(loadPlayers,5000);" +
                "     pageTimers.s=setInterval(loadServerInfo,5000);" +
                "     pageTimers.d=setInterval(loadConsoleDash,5000);" +
                "   }else{" +
                "     showLoginError(data.message);" +
                "   }" +
                " }).catch(function(){showLoginError('Connection error. Please try again.');});" +
                "}" +
                "function showLoginError(msg){" +
                " var e=document.getElementById('login-error');" +
                " e.textContent=msg;e.style.display='block';" +
                " setTimeout(function(){e.style.display='none'},4000);" +
                "}" +
                "function doLogout(){" +
                " localStorage.removeItem('adminToken');" +
                " token='';" +
                " document.getElementById('login-page').style.display='flex';" +
                " document.getElementById('app-container').style.display='none';" +
                " document.getElementById('login-username').value='';" +
                " document.getElementById('login-password').value='';" +
                " clearPageTimers();" +
                "}" +
                /* Page switching */
                "var currentPage='dashboard';" +
                "var pageTimers={};" +
                "function switchPage(page){" +
                " document.querySelectorAll('.page.active').forEach(function(p){p.classList.remove('active')});" +
                " document.getElementById('page-'+page).classList.add('active');" +
                " document.querySelectorAll('.nav-btn.active').forEach(function(b){b.classList.remove('active')});" +
                " document.querySelector('.nav-btn[data-page=\"'+page+'\"]').classList.add('active');" +
                " currentPage=page;location.hash=page;clearPageTimers();" +
                " if(page==='console'){loadConsole();pageTimers.c=setInterval(loadConsole,3000);}" +
                " if(page==='bans')loadBans();" +
                " if(page==='whitelist')loadWhitelist();" +
                " if(page==='users')loadUsers();" +
                "}" +
                "var hash=location.hash.replace('#','');" +
                "if(hash&&['dashboard','console','bans','whitelist','users'].indexOf(hash)!==-1){" +
                " currentPage=hash;" +
                " document.querySelector('.nav-btn.active').classList.remove('active');" +
                " document.querySelector('.nav-btn[data-page=\"'+hash+'\"]').classList.add('active');" +
                "}else{location.hash='dashboard';}" +
                "function clearPageTimers(){" +
                " for(var k in pageTimers){clearInterval(pageTimers[k]);}pageTimers={};" +
                "}" +
                /* Players - enhanced with health + quick actions */
                "function loadPlayers(){" +
                " fetch('/api/players?token='+encodeURIComponent(token))" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){" +
                "   if(data.success===false){" +
                "     document.getElementById('players').innerHTML='<div class=\"empty\">Invalid or missing token. Please set your admin token.</div>';" +
                "     document.getElementById('player-count').textContent='';" +
                "     return;" +
                "   }" +
                "   document.getElementById('online').textContent=data.online;" +
                "   document.getElementById('player-count').textContent=data.online+' online';" +
                "   var html='';" +
                "   data.players.forEach(function(p){" +
                "     var hpPct=Math.round(p.health*5);var hpC=hpPct>60?'#8cbfa8':hpPct>25?'#dbbc7c':'#e49494';" +
                "     html+='<div class=\"player-row\">'+" +
                "       '<div class=\"player-info\">'+" +
                "         '<span class=\"player-name\">'+p.name+'</span>'+" +
                "         '<div class=\"player-meta\">'+" +
                "           '<span class=\"player-coords\">XYZ: '+p.x+' / '+p.y+' / '+p.z+'</span>'+" +
                "           '<span class=\"player-gm\">'+p.gamemode+'</span>'+" +
                "           '<span>HP <span class=\"hp-bar\"><span class=\"hp-fill\" style=\"width:'+hpPct+'%;background:'+hpC+'\"></span></span> '+p.health+'</span>'+" +
                "         '</div>'+" +
                "       '</div>'+" +
                "     '</div>'+" +
                "     '<div style=\"display:flex;gap:0.375rem;flex-wrap:wrap;margin-top:0.5rem;align-items:center;\">'+" +
                "       '<select class=\"gm-select\" onchange=\"playerAction(\\''+p.name+'\\',\\'gamemode \\'+this.value)\"><option value=\"\">GM...</option><option value=\"survival\">Survival</option><option value=\"creative\">Creative</option><option value=\"adventure\">Adventure</option><option value=\"spectator\">Spectator</option></select>'+" +
                "       '<button class=\"btn\" style=\"background:#c4e8d4;color:#3a5c4a;font-size:0.6875rem;padding:0.25rem 0.5rem;\" onclick=\"playerAction(\\''+p.name+'\\',\\'heal\\')\">Heal</button>'+" +
                "       '<button class=\"btn\" style=\"background:#d4dce8;color:#3a4a5c;font-size:0.6875rem;padding:0.25rem 0.5rem;\" onclick=\"playerAction(\\''+p.name+'\\',\\'feed\\')\">Feed</button>'+" +
                "       '<button class=\"btn\" style=\"background:#e8d4e0;color:#5c3a4a;font-size:0.6875rem;padding:0.25rem 0.5rem;\" onclick=\"playerAction(\\''+p.name+'\\',\\'kill\\')\">Kill</button>'+" +
                "       '<span style=\"color:#e5ded5;margin:0 0.25rem;font-size:0.75rem;\">|</span>'+" +
                "       '<button class=\"btn btn-goto\" onclick=\"showGoToModal(\\''+p.name+'\\')\">Go To</button>'+" +
                "       '<button class=\"btn btn-tp\" onclick=\"showTeleportModal(\\''+p.name+'\\')\">Teleport To</button>'+" +
                "       '<button class=\"btn btn-kick\" onclick=\"kickPlayer(\\''+p.name+'\\')\">Kick</button>'+" +
                "       '<button class=\"btn btn-ban\" onclick=\"banPlayer(\\''+p.name+'\\')\">Ban</button>'+" +
                "     '</div>';" +
                "   });" +
                "   document.getElementById('players').innerHTML=html||'<div class=\"empty\">No players online</div>';" +
                " }).catch(function(){" +
                "   document.getElementById('players').innerHTML='<div class=\"empty\">Unable to load players. Check your token and connection.</div>';" +
                " });" +
                "}" +
                /* Player action */
                "function playerAction(player,action){" +
                " fetch('/api/player-action?player='+encodeURIComponent(player)+'&action='+encodeURIComponent(action)+'&token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);setTimeout(loadPlayers,300);});" +
                "}" +
                /* Console command */
                "function sendCommand(){" +
                " var cmd=document.getElementById('console-cmd').value.trim();" +
                " if(!cmd)return;" +
                " fetch('/api/command?token='+encodeURIComponent(token)+'&command='+encodeURIComponent(cmd),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);document.getElementById('console-cmd').value='';setTimeout(loadConsole,500);});" +
                "}" +
                "function loadConsole(){" +
                " fetch('/api/console?token='+encodeURIComponent(token)+'&lines=50')" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){" +
                "   var el=document.getElementById('console-full');" +
                "   if(el)el.textContent=data.lines?data.lines.join('\\n'):'No output';" +
                "   var cnt=document.getElementById('console-line-count');" +
                "   if(cnt)cnt.textContent=data.totalLines?data.totalLines+' lines':'';" +
                " }).catch(function(){});" +
                "}" +
                "function loadConsoleDash(){" +
                " fetch('/api/console?token='+encodeURIComponent(token)+'&lines=15')" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){" +
                "   var el=document.getElementById('console-dash');" +
                "   if(el)el.textContent=data.lines?data.lines.join('\\n'):'No output';" +
                " }).catch(function(){});" +
                "}" +
                /* Bans */
                "function loadBans(){" +
                " fetch('/api/bans?token='+encodeURIComponent(token))" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){" +
                "   if(data.success===false)return;" +
                "   var html='';" +
                "   if(!data.length){html='<div class=\"empty\">No banned players</div>';}" +
                "   else{" +
                "     html+='<table class=\"tbl\"><thead><tr><th>Player</th><th>Reason</th><th>Source</th><th></th></tr></thead><tbody>';" +
                "     data.forEach(function(b){" +
                "       html+='<tr><td>'+b.name+'</td><td>'+b.reason+'</td><td>'+b.source+'</td>';" +
                "       html+='<td><button class=\"btn\" style=\"background:#ecc8c8;color:#6b3a3a;font-size:0.6875rem;padding:0.25rem 0.625rem;\" onclick=\"unbanPlayer(\\''+b.name+'\\')\">Unban</button></td></tr>';" +
                "     });" +
                "     html+='</tbody></table>';" +
                "   }" +
                "   document.getElementById('ban-list').innerHTML=html;" +
                " }).catch(function(){});" +
                "}" +
                "function unbanPlayer(name){" +
                " if(!confirm('Unban '+name+'?'))return;" +
                " fetch('/api/unban?player='+encodeURIComponent(name)+'&token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);loadBans();});" +
                "}" +
                /* Whitelist */
                "function loadWhitelist(){" +
                " fetch('/api/whitelist?token='+encodeURIComponent(token))" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){" +
                "   if(data.success===false)return;" +
                "   var toggle=document.getElementById('wl-toggle');" +
                "   if(data.enabled){toggle.classList.add('on');}else{toggle.classList.remove('on');}" +
                "   document.getElementById('wl-status').textContent=data.enabled?'Enabled':'Disabled';" +
                "   var html='';" +
                "   if(!data.players||!data.players.length){html='<div class=\"empty\">No whitelisted players</div>';}" +
                "   else{" +
                "     html+='<table class=\"tbl\"><thead><tr><th>Player</th><th>UUID</th><th></th></tr></thead><tbody>';" +
                "     data.players.forEach(function(p){" +
                "       html+='<tr><td>'+p.name+'</td><td style=\"font-size:0.6875rem;color:#8a847c;\">'+p.uuid+'</td>';" +
                "       html+='<td><button class=\"btn\" style=\"background:#ecc8c8;color:#6b3a3a;font-size:0.6875rem;padding:0.25rem 0.625rem;\" onclick=\"removeWhitelist(\\''+p.name+'\\')\">Remove</button></td></tr>';" +
                "     });" +
                "     html+='</tbody></table>';" +
                "   }" +
                "   document.getElementById('wl-list').innerHTML=html;" +
                " }).catch(function(){});" +
                "}" +
                "function toggleWhitelist(){" +
                " fetch('/api/whitelist/toggle?token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);loadWhitelist();});" +
                "}" +
                "function addWhitelist(){" +
                " var name=document.getElementById('wl-add-input').value.trim();" +
                " if(!name){showToast('Please enter a player name');return;}" +
                " fetch('/api/whitelist/add?player='+encodeURIComponent(name)+'&token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);document.getElementById('wl-add-input').value='';loadWhitelist();});" +
                "}" +
                "function removeWhitelist(name){" +
                " if(!confirm('Remove '+name+' from whitelist?'))return;" +
                " fetch('/api/whitelist/remove?player='+encodeURIComponent(name)+'&token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);loadWhitelist();});" +
                "}" +
                /* Existing: kick, ban, save, reload, stop */
                "function kickPlayer(player){" +
                " if(!confirm('Kick '+player+'?')) return;" +
                " fetch('/api/kick?player='+encodeURIComponent(player)+'&token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);loadPlayers();});" +
                "}" +
                "function banPlayer(player){" +
                " if(!confirm('Ban '+player+'?')) return;" +
                " fetch('/api/ban?player='+encodeURIComponent(player)+'&token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);loadPlayers();});" +
                "}" +
                "function saveServer(){" +
                " showToast('Saving world\u2026');" +
                " fetch('/api/save?token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);});" +
                "}" +
                "function reloadServer(){" +
                " if(!confirm('Are you sure you want to reload the server?')) return;" +
                " showToast('Reloading server\u2026');" +
                " fetch('/api/reload?token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);});" +
                "}" +
                "function stopServer(){" +
                " if(!confirm('Stop the server? The world will be saved automatically before shutdown. All players will be disconnected.')) return;" +
                " showToast('Saving world then stopping\u2026');" +
                " fetch('/api/stop?token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);});" +
                "}" +
                /* Weather command */
                "function weatherClear(){" +
                " showToast('Weather Clear\u2026');" +
                " fetch('/api/clear?token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);});" +
                "}" +
                "function weatherRain(){" +
                " showToast('Weather Rain\u2026');" +
                " fetch('/api/rain?token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);});" +
                "}" +
                "function weatherThunder(){" +
                " showToast('Weather Thunder\u2026');" +
                " fetch('/api/thunder?token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);});" +
                "}" +
                /* Time Command */
                "function timeDay(){" +
                " showToast('Time Day\u2026');" +
                " fetch('/api/day?token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);});" +
                "}" +
                "function timeNight(){" +
                " showToast('Time Night\u2026');" +
                " fetch('/api/night?token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);});" +
                "}" +
                "function timeNoon(){" +
                " showToast('Time Noon\u2026');" +
                " fetch('/api/noon?token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);});" +
                "}" +
                "function timeMidnight(){" +
                " showToast('Time Midnight\u2026');" +
                " fetch('/api/midnight?token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);});" +
                "}" +
                /* Teleport modal */
                "var tpFromPlayer='';" +
                "function showTeleportModal(player){" +
                " tpFromPlayer=player;" +
                " document.getElementById('tp-modal-desc').textContent='Teleport '+player+' to:';" +
                " document.getElementById('tp-target').value='';" +
                " document.getElementById('tp-modal').classList.add('show');" +
                " setTimeout(function(){document.getElementById('tp-target').focus()},100);" +
                "}" +
                "function closeTeleportModal(){" +
                " document.getElementById('tp-modal').classList.remove('show');" +
                "}" +
                "function doTeleport(){" +
                " var toPlayer=document.getElementById('tp-target').value.trim();" +
                " if(!toPlayer){showToast('Please enter a target player name');return;}" +
                " closeTeleportModal();" +
                " fetch('/api/teleport?from='+encodeURIComponent(tpFromPlayer)+'&to='+encodeURIComponent(toPlayer)+'&token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);});" +
                "}" +
                "document.getElementById('tp-modal').addEventListener('click',function(e){" +
                " if(e.target===this) closeTeleportModal();" +
                "});" +
                /* Go To modal */
                "var gotoToPlayer='';" +
                "function showGoToModal(player){" +
                " gotoToPlayer=player;" +
                " document.getElementById('goto-modal-desc').textContent='Go to '+player+'. Enter your player name:';" +
                " document.getElementById('goto-admin').value='';" +
                " document.getElementById('goto-modal').classList.add('show');" +
                " setTimeout(function(){document.getElementById('goto-admin').focus()},100);" +
                "}" +
                "function closeGoToModal(){" +
                " document.getElementById('goto-modal').classList.remove('show');" +
                "}" +
                "function doGoTo(){" +
                " var adminName=document.getElementById('goto-admin').value.trim();" +
                " if(!adminName){showToast('Please enter your player name');return;}" +
                " closeGoToModal();" +
                " fetch('/api/teleport?from='+encodeURIComponent(adminName)+'&to='+encodeURIComponent(gotoToPlayer)+'&token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);});" +
                "}" +
                "document.getElementById('goto-modal').addEventListener('click',function(e){" +
                " if(e.target===this) closeGoToModal();" +
                "});" +
                /* Global key handler */
                "document.addEventListener('keydown',function(e){" +
                " if(e.key==='Escape'){closeTeleportModal();closeGoToModal();}" +
                " if(e.key==='Enter'&&currentPage==='console'&&document.activeElement===document.getElementById('console-cmd')){sendCommand();}" +
                "});" +
                /* Server info */
                "function loadServerInfo(){" +
                " fetch('/api/server?token='+encodeURIComponent(token))" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){" +
                "   if(data.success===false)return;" +
                "   document.getElementById('ram').textContent=data.ramUsed+' / '+data.ramMax+' MB';" +
                "   document.getElementById('uptime').textContent=formatUptime(data.uptimeSeconds);" +
                "   if(data.motd) document.getElementById('server-motd').textContent=data.motd;" +
                "   var tps=document.getElementById('tps');" +
                "   tps.textContent=data.tps;" +
                "   tps.style.color=data.tps>=18?'#8cbfa8':data.tps>=13?'#dbbc7c':'#e49494';" +
                " }).catch(function(){});" +
                "}" +
                "function formatUptime(seconds){" +
                " var h=Math.floor(seconds/3600);" +
                " var m=Math.floor((seconds%3600)/60);" +
                " var s=seconds%60;" +
                " return h+'h '+m+'m '+s+'s';" +
                "}" +
                /* User Management */
                "function loadUsers(){" +
                " fetch('/api/users?token='+encodeURIComponent(token))" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){" +
                "   if(data.success===false)return;" +
                "   var html='';" +
                "   if(!data.length){html='<div class=\"empty\">No users found</div>';}" +
                "   else{" +
                "     html+='<table class=\"tbl\"><thead><tr><th>Username</th><th></th></tr></thead><tbody>';" +
                "     data.forEach(function(u){" +
                "       html+='<tr><td>'+u.username+'</td>';" +
                "       html+='<td style=\"display:flex;gap:0.375rem;\">';" +
                "       html+='<button class=\"btn\" style=\"background:#d4dce8;color:#3a4a5c;font-size:0.6875rem;padding:0.25rem 0.5rem;\" onclick=\"showChangePassword(\\''+u.username+'\\')\">Password</button>';" +
                "       html+='<button class=\"btn\" style=\"background:#ecc8c8;color:#6b3a3a;font-size:0.6875rem;padding:0.25rem 0.5rem;\" onclick=\"removeUser(\\''+u.username+'\\')\">Remove</button>';" +
                "       html+='</td></tr>';" +
                "     });" +
                "     html+='</tbody></table>';" +
                "   }" +
                "   document.getElementById('user-list').innerHTML=html;" +
                " }).catch(function(){});" +
                "}" +
                "function addUser(){" +
                " var n=document.getElementById('user-add-name').value.trim();" +
                " var p=document.getElementById('user-add-pass').value.trim();" +
                " if(!n||!p){showToast('Please enter username and password');return;}" +
                " fetch('/api/users/add?username='+encodeURIComponent(n)+'&password='+encodeURIComponent(p)+'&token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){" +
                "   showToast(data.message);" +
                "   document.getElementById('user-add-name').value='';" +
                "   document.getElementById('user-add-pass').value='';" +
                "   loadUsers();" +
                " });" +
                "}" +
                "function removeUser(name){" +
                " if(!confirm('Remove user '+name+'?'))return;" +
                " fetch('/api/users/remove?username='+encodeURIComponent(name)+'&token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);loadUsers();});" +
                "}" +
                "function showChangePassword(name){" +
                " var p=prompt('Enter new password for '+name+':');" +
                " if(!p)return;" +
                " fetch('/api/users/change-password?username='+encodeURIComponent(name)+'&password='+encodeURIComponent(p)+'&token='+encodeURIComponent(token),{method:'POST'})" +
                " .then(function(r){return r.json()})" +
                " .then(function(data){showToast(data.message);loadUsers();});" +
                "}" +
                /* Init */
                "if(token){" +
                " document.getElementById('login-page').style.display='none';" +
                " document.getElementById('app-container').style.display='block';" +
                " loadPlayers();loadServerInfo();loadConsoleDash();" +
                " pageTimers.p=setInterval(loadPlayers,5000);" +
                " pageTimers.s=setInterval(loadServerInfo,5000);" +
                " pageTimers.d=setInterval(loadConsoleDash,5000);" +
                "}else{" +
                " document.getElementById('login-page').style.display='flex';" +
                " document.getElementById('app-container').style.display='none';" +
                "}" +
                "</script>" +
                "</body>" +
                "</html>";

        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }

    private boolean isAuthorized(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String token = params.get("token");

        if (token == null || token.isEmpty()) return false;
        for (String password : users.values()) {
            if (token.equals(password)) return true;
        }
        return false;
    }

    private Response unauthorized() {
        return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                "{\"success\":false,\"message\":\"Unauthorized\"}"
        );
    }

    private String getClientIp(IHTTPSession session) {
        String ip = session.getHeaders().get("remote-addr");

        if (ip == null || ip.isEmpty()) {
            ip = "unknown";
        }

        return ip;
    }

    private Response json(String data) {
        return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                data
        );
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}