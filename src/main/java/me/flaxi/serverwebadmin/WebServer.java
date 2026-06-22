/*
 * MinePanel
 * Copyright (C) 2026 FlaxiLabs
 *
 * Licensed under GNU GPL v3
 * https://www.gnu.org/licenses/gpl-3.0.html
 */

package me.flaxi.serverwebadmin;

import fi.iki.elonen.NanoHTTPD;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.mindrot.jbcrypt.BCrypt;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import java.lang.management.ManagementFactory;

public class WebServer extends NanoHTTPD {

    private final ServerWebAdmin plugin;
    private final Map<String, UserInfo> users;
    private final Map<String, String> alerts;
    private final ActionLogger actionLogger;
    private final TpsMonitor tpsMonitor;
    private final Map<String, SessionInfo> sessions = new HashMap<>();
    private final long sessionTimeoutMs;

    public static class UserInfo {
        final String password;
        final String role;
        boolean mustChangePassword;

        UserInfo(String password, String role, boolean mustChangePassword) {
            this.password = password;
            this.role = role;
            this.mustChangePassword = mustChangePassword;
        }
    }

    private static class SessionInfo {
        final String username;
        final String role;
        long expiry;
        SessionInfo(String username, String role, long expiry) {
            this.username = username;
            this.role = role;
            this.expiry = expiry;
        }
    }

    private static final Map<String, Set<String>> ROLE_PERMISSIONS = new HashMap<>();
    static {
        Set<String> all = new HashSet<>(Arrays.asList(
            "dashboard.view", "console.view", "console.command",
            "players.view", "player.kick", "player.ban",
            "whitelist.view", "whitelist.add", "whitelist.remove",
            "weather.clear", "weather.rain", "weather.thunder",
            "time.day", "time.noon", "time.night", "time.midnight",
            "bans.view",
            "files.view", "files.read", "files.edit", "files.upload",
            "files.download", "files.delete", "files.rename", "files.mkdir",
            "users.view", "users.create", "users.edit", "users.delete",
            "server.reload", "server.stop"
        ));
        ROLE_PERMISSIONS.put("owner", all);
        ROLE_PERMISSIONS.put("admin", new HashSet<>(Arrays.asList(
            "dashboard.view", "console.view", "console.command",
            "players.view", "player.kick", "player.ban",
            "whitelist.view", "whitelist.add", "whitelist.remove",
            "weather.clear", "weather.rain", "weather.thunder",
            "time.day", "time.noon", "time.night", "time.midnight",
            "bans.view",
            "files.view", "files.read", "files.edit", "files.download"
        )));
        ROLE_PERMISSIONS.put("moderator", new HashSet<>(Arrays.asList(
            "dashboard.view", "console.view", "players.view", "player.kick"
        )));
        ROLE_PERMISSIONS.put("viewer", new HashSet<>(Arrays.asList(
            "dashboard.view", "players.view"
        )));
    }

    public WebServer(ServerWebAdmin plugin, String hostname, int port, int sessionTimeoutMin,
                     Map<String, UserInfo> users, Map<String, String> alerts, TpsMonitor tpsMonitor) throws IOException {
        super(hostname, port);
        this.plugin = plugin;
        this.users = users;
        this.alerts = alerts;
        this.tpsMonitor = tpsMonitor;
        this.sessionTimeoutMs = sessionTimeoutMin * 60_000L;
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
            UserInfo info = users.get(username);
            if (info != null && BCrypt.checkpw(password, info.password)) {
                final String ip = getClientIp(session);
                actionLogger.write("LOGIN", username, ip);

                sessions.values().removeIf(s -> s.username.equals(username));

                String sessionToken = UUID.randomUUID().toString();
                sessions.put(sessionToken, new SessionInfo(username, info.role, System.currentTimeMillis() + sessionTimeoutMs));

                Set<String> perms = ROLE_PERMISSIONS.getOrDefault(info.role, Collections.emptySet());
                StringBuilder permJson = new StringBuilder();
                int pi = 0;
                for (String p : perms) {
                    if (pi > 0) permJson.append(",");
                    permJson.append("\"").append(p).append("\"");
                    pi++;
                }

                Response resp = newFixedLengthResponse(Response.Status.OK, "application/json",
                        "{\"success\":true,\"token\":\"" + sessionToken + "\",\"username\":\"" + username +
                        "\",\"role\":\"" + info.role + "\",\"mustChange\":" + info.mustChangePassword +
                        ",\"permissions\":[" + permJson.toString() + "]}");
                resp.addHeader("Set-Cookie", "token=" + sessionToken + "; HttpOnly; Path=/; SameSite=Lax; Max-Age=" + (sessionTimeoutMs / 1000));
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
            if (!hasPermission(session, "users.view")) {
                return forbidden();
            }
            StringBuilder sb = new StringBuilder("[");
            int idx = 0;
            for (Map.Entry<String, UserInfo> u : users.entrySet()) {
                if (idx > 0) sb.append(",");
                sb.append("{\"username\":\"").append(escapeJson(u.getKey()))
                        .append("\",\"role\":\"").append(escapeJson(u.getValue().role)).append("\"}");
                idx++;
            }
            sb.append("]");
            return json(sb.toString());
        }

        if (uri.equals("/api/users/add") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            if (!hasPermission(session, "users.create")) {
                return forbidden();
            }
            Map<String, String> params = session.getParms();
            final String username = params.get("username");
            final String password = params.get("password");
            String role = params.get("role");
            if (role == null || role.isEmpty()) role = "viewer";
            if ("owner".equals(role)) role = "viewer";

            if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing username or password\"}");
            }
            if (users.containsKey(username)) {
                return json("{\"success\":false,\"message\":\"User already exists\"}");
            }
            users.put(username, new UserInfo(BCrypt.hashpw(password, BCrypt.gensalt()), role, false));
            plugin.saveUsers(users);
            final String ip = getClientIp(session);
            actionLogger.write("USER_ADD", username, ip);
            return json("{\"success\":true,\"message\":\"User added\"}");
        }

        if (uri.equals("/api/users/remove") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            if (!hasPermission(session, "users.delete")) {
                return forbidden();
            }
            Map<String, String> params = session.getParms();
            final String username = params.get("username");
            if (username == null || username.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing username parameter\"}");
            }
            UserInfo info = users.get(username);
            if (info == null) {
                return json("{\"success\":false,\"message\":\"User not found\"}");
            }
            if ("owner".equals(info.role)) {
                return json("{\"success\":false,\"message\":\"Cannot remove the owner account\"}");
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
            if (!hasPermission(session, "users.edit")) {
                return forbidden();
            }
            Map<String, String> params = session.getParms();
            final String username = params.get("username");
            final String newPassword = params.get("password");
            if (username == null || newPassword == null || username.isEmpty() || newPassword.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing username or password\"}");
            }
            UserInfo info = users.get(username);
            if (info == null) {
                return json("{\"success\":false,\"message\":\"User not found\"}");
            }
            users.put(username, new UserInfo(BCrypt.hashpw(newPassword, BCrypt.gensalt()), info.role, info.mustChangePassword));
            plugin.saveUsers(users);
            final String ip = getClientIp(session);
            actionLogger.write("USER_PASSWORD", username, ip);
            return json("{\"success\":true,\"message\":\"Password changed\"}");
        }

        if (uri.equals("/api/users/edit") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            if (!hasPermission(session, "users.edit")) {
                return forbidden();
            }
            Map<String, String> params = session.getParms();
            String username = params.get("username");
            String newRole = params.get("role");
            if (username == null || newRole == null || username.isEmpty() || newRole.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing username or role\"}");
            }
            UserInfo info = users.get(username);
            if (info == null) {
                return json("{\"success\":false,\"message\":\"User not found\"}");
            }
            if ("owner".equals(info.role)) {
                return json("{\"success\":false,\"message\":\"Cannot change the owner role\"}");
            }
            if ("owner".equals(newRole)) {
                return json("{\"success\":false,\"message\":\"Cannot assign owner role via API\"}");
            }
            if (!ROLE_PERMISSIONS.containsKey(newRole)) {
                return json("{\"success\":false,\"message\":\"Invalid role\"}");
            }
            users.put(username, new UserInfo(info.password, newRole, info.mustChangePassword));
            plugin.saveUsers(users);
            final String ip = getClientIp(session);
            actionLogger.write("USER_EDIT", username + " role=" + newRole, ip);
            return json("{\"success\":true,\"message\":\"Role changed to " + newRole + "\"}");
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

            UserInfo curInfo = users.get(username);
            if (curInfo == null || !BCrypt.checkpw(oldPassword, curInfo.password)) {
                return json("{\"success\":false,\"message\":\"Current password is incorrect\"}");
            }

            String newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt());
            curInfo.mustChangePassword = false;
            users.put(username, new UserInfo(newHash, curInfo.role, false));
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
            if (!hasPermission(session, "players.view")) {
                return forbidden();
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
            if (!hasPermission(session, "player.kick")) {
                return forbidden();
            }
            Map<String, String> params = session.getParms();
            final String playerName = params.get("player");
            final String reason = params.get("reason");

            if (playerName == null || playerName.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing player parameter\"}");
            }

            final String ip = getClientIp(session);
            final String kickMsg = (reason != null && !reason.isEmpty()) ? reason : alerts.get("kick");
            actionLogger.write("KICK", playerName + " (" + kickMsg + ")", ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Player target = Bukkit.getPlayer(playerName);

                    if (target != null) {
                        target.kickPlayer(kickMsg);
                    }
                }
            });

            return json("{\"success\":true,\"message\":\"Kick request sent\"}");
        }

        if (uri.equals("/api/ban") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            if (!hasPermission(session, "player.ban")) {
                return forbidden();
            }
            Map<String, String> params = session.getParms();
            final String playerName = params.get("player");
            final String reason = params.get("reason");

            if (playerName == null || playerName.isEmpty()) {
                return json("{\"success\":false,\"message\":\"Missing player parameter\"}");
            }

            final String ip = getClientIp(session);
            final String banReason = (reason != null && !reason.isEmpty()) ? reason : alerts.get("ban_reason");
            final String kickMsg = (reason != null && !reason.isEmpty()) ? reason : alerts.get("ban_kick");
            actionLogger.write("BAN", playerName + " (" + banReason + ")", ip);

            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override
                public void run() {
                    Bukkit.getBanList(BanList.Type.NAME).addBan(
                            playerName,
                            banReason,
                            null,
                            "WebAdmin"
                    );

                    Player target = Bukkit.getPlayer(playerName);

                    if (target != null) {
                        target.kickPlayer(kickMsg);
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

            String osName = System.getProperty("os.name");
            double cpu = getCpuLoad();
            String cpuStr = cpu < 0 ? "N/A" : String.format("%.1f", cpu) + "%";
            String mcVersion = Bukkit.getVersion();

            String data = "{"
                    + "\"status\":\"online\","
                    + "\"players\":" + Bukkit.getOnlinePlayers().size() + ","
                    + "\"ramUsed\":" + usedMemory + ","
                    + "\"ramMax\":" + maxMemory + ","
                    + "\"uptimeSeconds\":" + uptimeSeconds + ","
                    + "\"tps\":" + tpsMonitor.getTps() + ","
                    + "\"cpu\":\"" + cpuStr + "\","
                    + "\"os\":\"" + escapeJson(osName) + "\","
                    + "\"mcVersion\":\"" + escapeJson(mcVersion) + "\","
                    + "\"motd\":\"" + motd + "\""
                    + "}";

            return json(data);
        }

        //Server maintain command

        if (uri.equals("/api/reload") && method == Method.POST) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            if (!hasPermission(session, "server.reload")) {
                return forbidden();
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
            if (!hasPermission(session, "server.stop")) {
                return forbidden();
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
            if (!hasPermission(session, "weather.clear")) {
                return forbidden();
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
            if (!hasPermission(session, "weather.rain")) {
                return forbidden();
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
            if (!hasPermission(session, "weather.thunder")) {
                return forbidden();
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
            if (!hasPermission(session, "time.day")) {
                return forbidden();
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
            if (!hasPermission(session, "time.night")) {
                return forbidden();
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
            if (!hasPermission(session, "time.noon")) {
                return forbidden();
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
            if (!hasPermission(session, "time.midnight")) {
                return forbidden();
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
            if (!hasPermission(session, "player.kick")) {
                return forbidden();
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
            if (!hasPermission(session, "player.kick")) {
                return forbidden();
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
            if (!hasPermission(session, "console.command")) {
                return forbidden();
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
            if (!hasPermission(session, "console.view")) {
                return forbidden();
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
                } else {
                    sb.append("{\"totalLines\":0,\"lines\":[]}");
                }
            } catch (IOException e) {
                sb.append("{\"totalLines\":0,\"lines\":[]}");
            }

            return json(sb.toString());
        }

        if (uri.equals("/api/bans") && method == Method.GET) {
            if (!isAuthorized(session)) {
                return unauthorized();
            }
            if (!hasPermission(session, "bans.view")) {
                return forbidden();
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
            if (!hasPermission(session, "whitelist.view")) {
                return forbidden();
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
            if (!hasPermission(session, "whitelist.add")) {
                return forbidden();
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
            if (!hasPermission(session, "whitelist.remove")) {
                return forbidden();
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

        // === FILE MANAGER ===

        if (uri.equals("/api/files/list") && method == Method.GET) {
            if (!isAuthorized(session)) return unauthorized();
            if (!hasPermission(session, "files.view")) return forbidden();

            String path = session.getParms().get("path");
            if (path == null) path = "";
            File dir = resolvePath(path);
            if (dir == null || !dir.exists() || !dir.isDirectory()) {
                return json("{\"success\":false,\"message\":\"Directory not found\"}");
            }

            File[] children = dir.listFiles();
            StringBuilder sb = new StringBuilder("{\"success\":true,\"currentPath\":\"" + escapeJson(path) + "\",");
            try {
                File root = Bukkit.getWorldContainer().getParentFile();
                if (root == null) root = new File(".");
                root = new File(root, "").getCanonicalFile();
                String par = root.toPath().relativize(dir.getParentFile() != null ? dir.getParentFile().toPath() : dir.toPath()).toString().replace("\\", "/");
                if (par.isEmpty()) par = "";
                sb.append("\"parentPath\":\"" + escapeJson(par) + "\",");
            } catch (Exception e) {
                sb.append("\"parentPath\":\"\",");
            }
            sb.append("\"files\":[");
            if (children != null) {
                Arrays.sort(children, (a, b) -> {
                    if (a.isDirectory() && !b.isDirectory()) return -1;
                    if (!a.isDirectory() && b.isDirectory()) return 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                });
                for (int i = 0; i < children.length; i++) {
                    if (i > 0) sb.append(",");
                    File f = children[i];
                    sb.append("{\"name\":\"" + escapeJson(f.getName()) + "\",")
                            .append("\"isDir\":" + f.isDirectory() + ",")
                            .append("\"size\":" + (f.isDirectory() ? 0 : f.length()) + "}");
                }
            }
            sb.append("]}");
            return json(sb.toString());
        }

        if (uri.equals("/api/files/read") && method == Method.GET) {
            if (!isAuthorized(session)) return unauthorized();
            if (!hasPermission(session, "files.read")) return forbidden();

            String path = session.getParms().get("path");
            if (path == null || path.isEmpty()) return json("{\"success\":false,\"message\":\"Missing path\"}");
            File file = resolvePath(path);
            if (file == null || !file.exists() || file.isDirectory()) return json("{\"success\":false,\"message\":\"File not found\"}");
            if (!isAllowedTextFile(file)) return json("{\"success\":false,\"message\":\"File type not supported for reading\"}");
            if (file.length() > 1_048_576) return json("{\"success\":false,\"message\":\"File too large to view (max 1MB)\"}");

            try {
                String content = new Scanner(file, "UTF-8").useDelimiter("\\A").next();
                return json("{\"success\":true,\"content\":\"" + escapeJson(content) + "\",\"size\":" + file.length() + "}");
            } catch (Exception e) {
                return json("{\"success\":false,\"message\":\"Error reading file\"}");
            }
        }

        if (uri.equals("/api/files/save") && method == Method.POST) {
            if (!isAuthorized(session)) return unauthorized();
            if (!hasPermission(session, "files.edit")) return forbidden();

            String body = readPostBody(session);
            String path = extractJsonValue(body, "path");
            String content = extractJsonValue(body, "content");
            if (path == null || content == null) return json("{\"success\":false,\"message\":\"Missing path or content\"}");

            File file = resolvePath(path);
            if (file == null) return json("{\"success\":false,\"message\":\"Invalid path\"}");
            if (isBlockedExtension(file)) return json("{\"success\":false,\"message\":\"Cannot edit this file type\"}");

            try {
                FileWriter fw = new FileWriter(file);
                fw.write(content);
                fw.close();
                return json("{\"success\":true,\"message\":\"File saved\"}");
            } catch (IOException e) {
                return json("{\"success\":false,\"message\":\"Error saving file\"}");
            }
        }

        if (uri.equals("/api/files/download") && method == Method.GET) {
            if (!isAuthorized(session)) return unauthorized();
            if (!hasPermission(session, "files.download")) return forbidden();

            String path = session.getParms().get("path");
            if (path == null || path.isEmpty()) return json("{\"success\":false,\"message\":\"Missing path\"}");
            File file = resolvePath(path);
            if (file == null || !file.exists() || file.isDirectory()) return json("{\"success\":false,\"message\":\"File not found\"}");

            try {
                String mime = "application/octet-stream";
                FileInputStream fis = new FileInputStream(file);
                Response resp = newChunkedResponse(Response.Status.OK, mime, fis);
                resp.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
                return resp;
            } catch (IOException e) {
                return json("{\"success\":false,\"message\":\"Error downloading file\"}");
            }
        }

        if (uri.equals("/api/files/upload") && method == Method.POST) {
            if (!isAuthorized(session)) return unauthorized();
            if (!hasPermission(session, "files.upload")) return forbidden();

            try {
                Map<String, String> filesMap = new HashMap<>();
                session.parseBody(filesMap);
                String tmpPath = filesMap.get("file");
                if (tmpPath == null) return json("{\"success\":false,\"message\":\"No file uploaded\"}");

                File tmp = new File(tmpPath);
                if (!tmp.exists() || tmp.length() > 5_242_880) {
                    return json("{\"success\":false,\"message\":\"File too large (max 5MB)\"}");
                }

                String uploadName = new File(tmpPath).getName();
                String destPath = session.getParms().get("path");
                if (destPath == null) destPath = "";
                File destDir = resolvePath(destPath);
                if (destDir == null || !destDir.isDirectory()) {
                    // Try using the raw file name from temp
                    String rawName = filesMap.containsKey("content-disposition") ? filesMap.get("content-disposition") : null;
                    if (rawName != null && rawName.contains("filename=\"")) {
                        uploadName = rawName.substring(rawName.indexOf("filename=\"") + 10);
                        uploadName = uploadName.substring(0, uploadName.indexOf("\""));
                    }
                    destDir = resolvePath(destPath != null ? destPath : "");
                }

                File dest = new File(destDir, uploadName);
                if (isBlockedExtension(dest)) return json("{\"success\":false,\"message\":\"Cannot upload this file type\"}");

                java.nio.file.Files.copy(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                tmp.delete();
                return json("{\"success\":true,\"message\":\"File uploaded\"}");
            } catch (Exception e) {
                return json("{\"success\":false,\"message\":\"Upload failed: " + e.getMessage() + "\"}");
            }
        }

        if (uri.equals("/api/files/mkdir") && method == Method.POST) {
            if (!isAuthorized(session)) return unauthorized();
            if (!hasPermission(session, "files.mkdir")) return forbidden();

            String path = session.getParms().get("path");
            if (path == null || path.isEmpty()) return json("{\"success\":false,\"message\":\"Missing path\"}");
            File dir = resolvePath(path);
            if (dir == null) return json("{\"success\":false,\"message\":\"Invalid path\"}");
            if (dir.exists()) return json("{\"success\":false,\"message\":\"Already exists\"}");
            if (dir.mkdirs()) return json("{\"success\":true,\"message\":\"Folder created\"}");
            return json("{\"success\":false,\"message\":\"Cannot create folder\"}");
        }

        if (uri.equals("/api/files/rename") && method == Method.POST) {
            if (!isAuthorized(session)) return unauthorized();
            if (!hasPermission(session, "files.rename")) return forbidden();

            String oldPath = session.getParms().get("old");
            String newName = session.getParms().get("new");
            if (oldPath == null || newName == null || oldPath.isEmpty() || newName.isEmpty())
                return json("{\"success\":false,\"message\":\"Missing parameters\"}");

            File oldFile = resolvePath(oldPath);
            if (oldFile == null || !oldFile.exists()) return json("{\"success\":false,\"message\":\"File not found\"}");
            File newFile = new File(oldFile.getParentFile(), newName);
            if (isBlockedExtension(newFile)) return json("{\"success\":false,\"message\":\"Cannot rename to this file type\"}");

            if (oldFile.renameTo(newFile)) return json("{\"success\":true,\"message\":\"Renamed\"}");
            return json("{\"success\":false,\"message\":\"Rename failed\"}");
        }

        if (uri.equals("/api/files/delete") && method == Method.POST) {
            if (!isAuthorized(session)) return unauthorized();
            if (!hasPermission(session, "files.delete")) return forbidden();

            String path = session.getParms().get("path");
            if (path == null || path.isEmpty()) return json("{\"success\":false,\"message\":\"Missing path\"}");
            File file = resolvePath(path);
            if (file == null || !file.exists()) return json("{\"success\":false,\"message\":\"File not found\"}");
            if (isServerJar(file.getName())) return json("{\"success\":false,\"message\":\"Cannot delete server jar\"}");

            boolean deleted = file.isDirectory() ? deleteDir(file) : file.delete();
            if (deleted) return json("{\"success\":true,\"message\":\"Deleted\"}");
            return json("{\"success\":false,\"message\":\"Delete failed\"}");
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

    // === FILE MANAGER HELPERS ===

    private File resolvePath(String requestPath) {
        try {
            File root = Bukkit.getWorldContainer().getParentFile();
            if (root == null) root = new File(".");
            if (requestPath.startsWith("/") || requestPath.startsWith("\\")) requestPath = requestPath.substring(1);
            File target = new File(root, requestPath).getCanonicalFile();
            if (!target.getPath().startsWith(root.getCanonicalPath())) return null;
            return target;
        } catch (IOException e) {
            return null;
        }
    }

    private boolean isAllowedTextFile(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".txt") || name.endsWith(".yml") || name.endsWith(".yaml")
                || name.endsWith(".json") || name.endsWith(".properties")
                || name.endsWith(".log") || name.endsWith(".conf")
                || name.endsWith(".toml") || name.endsWith(".xml") || name.endsWith(".env");
    }

    private boolean isBlockedExtension(File file) {
        String name = file.getName().toLowerCase();
        return name.endsWith(".jar") || name.endsWith(".class") || name.endsWith(".exe")
                || name.endsWith(".bat") || name.endsWith(".sh") || name.endsWith(".dll");
    }

    private boolean isServerJar(String name) {
        String n = name.toLowerCase();
        return n.equals("paper.jar") || n.equals("spigot.jar") || n.equals("server.jar")
                || n.equals("bukkit.jar") || n.equals("craftbukkit.jar");
    }

    private String readPostBody(IHTTPSession session) {
        try {
            long len = Long.parseLong(session.getHeaders().getOrDefault("content-length", "0"));
            if (len > 10_000_000) return "";
            byte[] buf = new byte[(int) len];
            int total = 0;
            InputStream in = session.getInputStream();
            while (total < len) {
                int r = in.read(buf, total, (int) len - total);
                if (r == -1) break;
                total += r;
            }
            return new String(buf, 0, total, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private String extractJsonValue(String json, String key) {
        String search = "\"" + key + "\":\"";
        int start = json.indexOf(search);
        if (start == -1) return null;
        start += search.length();
        int end = start;
        while (end < json.length()) {
            if (json.charAt(end) == '"' && (end == 0 || json.charAt(end - 1) != '\\')) break;
            end++;
        }
        return end > start ? json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t") : null;
    }

    private boolean deleteDir(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) { deleteDir(child); } else { child.delete(); }
            }
        }
        return dir.delete();
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

    private double getCpuLoad() {
        try {
            Class<?> clazz = Class.forName("com.sun.management.OperatingSystemMXBean");
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            java.lang.reflect.Method method = clazz.getMethod("getProcessCpuLoad");
            double load = (double) method.invoke(bean);
            return load >= 0 ? Math.round(load * 10000.0) / 100.0 : -1;
        } catch (Exception ignored) {}
        double alt = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        return alt >= 0 ? Math.round(alt * 100.0) / 100.0 : -1;
    }

    private String getRole(IHTTPSession session) {
        String token = getToken(session);
        if (token == null) return null;
        SessionInfo si = sessions.get(token);
        return si != null ? si.role : null;
    }

    private boolean hasPermission(IHTTPSession session, String permission) {
        String role = getRole(session);
        if (role == null) return false;
        Set<String> perms = ROLE_PERMISSIONS.get(role);
        return perms != null && perms.contains(permission);
    }

    private Response forbidden() {
        return newFixedLengthResponse(Response.Status.FORBIDDEN,
                "application/json", "{\"success\":false,\"message\":\"Permission denied\"}");
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
        si.expiry = System.currentTimeMillis() + sessionTimeoutMs;
        return true;
    }

    private String getClientIp(IHTTPSession session) {
        String forwarded = session.getHeaders().get("x-forwarded-for");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
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
