package me.flaxi.serverwebadmin;

import fi.iki.elonen.NanoHTTPD;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.mindrot.jbcrypt.BCrypt;

import java.io.*;
import java.util.*;

import java.lang.management.ManagementFactory;

public class WebServer extends NanoHTTPD {

    private final ServerWebAdmin plugin;
    private final Map<String, String> users;
    private final Map<String, String> alerts;
    private final ActionLogger actionLogger;
    private final TpsMonitor tpsMonitor;
    private final Map<String, SessionInfo> sessions = new HashMap<>();

    private static class SessionInfo {
        final String username;
        long expiry;
        SessionInfo(String username, long expiry) {
            this.username = username;
            this.expiry = expiry;
        }
    }

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
            String storedHash = users.get(username);
            if (storedHash != null && BCrypt.checkpw(password, storedHash)) {
                final String ip = getClientIp(session);
                actionLogger.write("LOGIN", username, ip);

                sessions.values().removeIf(s -> s.username.equals(username));

                String sessionToken = UUID.randomUUID().toString();
                sessions.put(sessionToken, new SessionInfo(username, System.currentTimeMillis() + 300_000));

                boolean mustChange = BCrypt.checkpw("admin123", storedHash);
                Response resp = newFixedLengthResponse(Response.Status.OK, "application/json",
                        "{\"success\":true,\"token\":\"" + sessionToken + "\",\"username\":\"" + username + "\",\"mustChange\":" + mustChange + "}");
                resp.addHeader("Set-Cookie", "token=" + sessionToken + "; HttpOnly; Path=/; SameSite=Lax; Max-Age=300");
                return resp;
            }
            return json("{\"success\":false,\"message\":\"Invalid username or password\"}");
        }

        if (uri.equals("/api/logout") && method == Method.POST) {
            String token = getToken(session);
            if (token != null) {
                sessions.remove(token);
            }
            Response resp = newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"success\":true,\"message\":\"Logged out\"}");
            resp.addHeader("Set-Cookie", "token=; HttpOnly; Path=/; SameSite=Lax; Max-Age=0");
            return resp;
        }

        if (uri.equals("/api/heartbeat") && method == Method.GET) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            return json("{\"success\":true}");
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
            users.put(username, BCrypt.hashpw(password, BCrypt.gensalt()));
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
            users.put(username, BCrypt.hashpw(newPassword, BCrypt.gensalt()));
            plugin.saveUsers(users);
            final String ip = getClientIp(session);
            actionLogger.write("USER_PASSWORD", username, ip);
            return json("{\"success\":true,\"message\":\"Password changed\"}");
        }

        if (uri.equals("/api/change-password") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            Map<String, String> params = session.getParms();
            String username = params.get("username");
            String oldPassword = params.get("oldPassword");
            String newPassword = params.get("newPassword");
            String confirmPassword = params.get("confirmPassword");

            if (oldPassword == null || newPassword == null || confirmPassword == null ||
                    oldPassword.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty()) {
                return json("{\"success\":false,\"message\":\"All password fields are required\"}");
            }
            if (!newPassword.equals(confirmPassword)) {
                return json("{\"success\":false,\"message\":\"New passwords do not match\"}");
            }
            if (oldPassword.equals(newPassword)) {
                return json("{\"success\":false,\"message\":\"New password must be different from old password\"}");
            }

            String storedHash = users.get(username);
            if (storedHash == null || !BCrypt.checkpw(oldPassword, storedHash)) {
                return json("{\"success\":false,\"message\":\"Current password is incorrect\"}");
            }

            String newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
            users.put(username, newHash);
            plugin.saveUsers(users);

            final String ip = getClientIp(session);
            actionLogger.write("CHANGE_PASSWORD", username, ip);

            return json("{\"success\":true,\"message\":\"Password changed successfully\"}");
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

        if (uri.startsWith("/css/") || uri.startsWith("/js/") || uri.startsWith("/pages/")) {
            String mime = "text/plain";
            if (uri.endsWith(".css")) mime = "text/css";
            else if (uri.endsWith(".js")) mime = "application/javascript";
            else if (uri.endsWith(".html")) mime = "text/html";
            return serveResource(uri.substring(1), mime);
        }

        return serveResource("index.html", "text/html");
    }

    private Response serveResource(String resourcePath, String mimeType) {
        InputStream in = getClass().getClassLoader().getResourceAsStream("web/" + resourcePath);
        if (in == null) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found");
        }
        try (Scanner scanner = new Scanner(in, "UTF-8")) {
            String content = scanner.useDelimiter("\\A").next();
            return newFixedLengthResponse(Response.Status.OK, mimeType, content);
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error reading file");
        }
    }

    private String getToken(IHTTPSession session) {
        String token = session.getParms().get("token");
        if (token != null && !token.isEmpty()) return token;
        String cookieHeader = session.getHeaders().get("cookie");
        if (cookieHeader != null) {
            for (String c : cookieHeader.split(";")) {
                c = c.trim();
                if (c.startsWith("token=")) return c.substring(6);
            }
        }
        return null;
    }

    private Response unauthorized() {
        return newFixedLengthResponse(
                Response.Status.UNAUTHORIZED,
                "application/json",
                "{\"success\":false,\"message\":\"Unauthorized\"}"
        );
    }

    private boolean isAuthorized(IHTTPSession session) {
        String token = getToken(session);
        if (token == null || token.isEmpty()) return false;
        SessionInfo si = sessions.get(token);
        if (si == null) return false;
        if (System.currentTimeMillis() > si.expiry) {
            sessions.remove(token);
            return false;
        }
        si.expiry = System.currentTimeMillis() + 300_000;
        return true;
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
