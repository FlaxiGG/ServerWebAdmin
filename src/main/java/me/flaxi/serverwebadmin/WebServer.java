package me.flaxi.serverwebadmin;

import fi.iki.elonen.NanoHTTPD;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Map;

import java.lang.management.ManagementFactory;

public class WebServer extends NanoHTTPD {

    private final ServerWebAdmin plugin;
    private final String adminToken;
    private final ActionLogger actionLogger;

    public WebServer(ServerWebAdmin plugin, int port, String adminToken) throws IOException {
        super(port);
        this.plugin = plugin;
        this.adminToken = adminToken;
        this.actionLogger = new ActionLogger(plugin);
        start(SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

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
                result.append("\"").append(player.getName()).append("\"");
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
                        target.kickPlayer("You have been kicked by Web Admin.");
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
                            "Banned by Web Admin",
                            null,
                            "WebAdmin"
                    );

                    Player target = Bukkit.getPlayer(playerName);

                    if (target != null) {
                        target.kickPlayer("You have been banned by Web Admin.");
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

            String data = "{"
                    + "\"status\":\"online\","
                    + "\"players\":" + Bukkit.getOnlinePlayers().size() + ","
                    + "\"ramUsed\":" + usedMemory + ","
                    + "\"ramMax\":" + maxMemory + ","
                    + "\"uptimeSeconds\":" + uptimeSeconds
                    + "}";

            return json(data);
        }

        String html =
                "<!DOCTYPE html>" +
                        "<html>" +
                        "<head>" +
                        "<meta charset='UTF-8'>" +
                        "<title>Server Web Admin</title>" +
                        "<style>" +
                        "body{font-family:Arial;background:#111827;color:white;padding:30px;}" +
                        ".card{background:#1f2937;padding:20px;border-radius:12px;margin-bottom:20px;}" +
                        "button{padding:8px 14px;border:0;border-radius:8px;cursor:pointer;margin-left:8px;}" +
                        ".kick{background:#f59e0b;color:white;}" +
                        ".ban{background:#ef4444;color:white;}" +
                        "input{padding:8px;border-radius:8px;border:0;margin-right:8px;}" +
                        "li{margin:10px 0;}" +
                        "</style>" +
                        "</head>" +
                        "<body>" +
                        "<h1>Server Web Admin</h1>" +

                        "<div class='card'>" +
                        "<h2>Status</h2>" +
                        "<p>Server: Online</p>" +
                        "<p>Players Online: <span id='online'>0</span></p>" +
                        "</div>" +

                        "<div class='card'>" +
                        "<h2>Server Performance</h2>" +
                        "<p>RAM: <span id='ram'>0 / 0 MB</span></p>" +
                        "<p>Uptime: <span id='uptime'>0s</span></p>" +
                        "</div>" +

                        "<div class='card'>" +
                        "<h2>Admin Token</h2>" +
                        "<input id='token' placeholder='ใส่ Token' style='width:300px;'>" +
                        "<button onclick='saveToken()'>Save Token</button>" +
                        "</div>" +

                        "<div class='card'>" +
                        "<h2>Online Players</h2>" +
                        "<ul id='players'></ul>" +
                        "</div>" +

                        "<script>" +
                        "let token=localStorage.getItem('adminToken')||'';" +
                        "document.getElementById('token').value=token;" +

                        "function saveToken(){" +
                        " token=document.getElementById('token').value;" +
                        " localStorage.setItem('adminToken',token);" +
                        " alert('Token saved');" +
                        " loadPlayers();" +
                        "}" +

                        "function loadPlayers(){" +
                        " fetch('/api/players?token='+encodeURIComponent(token))" +
                        " .then(r=>r.json())" +
                        " .then(data=>{" +
                        "   if(data.success===false){document.getElementById('players').innerHTML='<li>Unauthorized</li>';return;}" +
                        "   document.getElementById('online').innerText=data.online;" +
                        "   let html='';" +
                        "   data.players.forEach(p=>{" +
                        "     html+='<li>'+p+" +
                        "     ' <button class=\"kick\" onclick=\"kickPlayer(\\''+p+'\\')\">Kick</button>'+" +
                        "     ' <button class=\"ban\" onclick=\"banPlayer(\\''+p+'\\')\">Ban</button>'+" +
                        "     '</li>'; " +
                        "   });" +
                        "   document.getElementById('players').innerHTML=html||'<li>No players online</li>';" +
                        " });" +
                        "}" +

                        "function kickPlayer(player){" +
                        " if(!confirm('Kick '+player+'?')) return;" +
                        " fetch('/api/kick?player='+encodeURIComponent(player)+'&token='+encodeURIComponent(token),{method:'POST'})" +
                        " .then(r=>r.json()).then(data=>{alert(data.message);loadPlayers();});" +
                        "}" +

                        "function banPlayer(player){" +
                        " if(!confirm('Ban '+player+'?')) return;" +
                        " fetch('/api/ban?player='+encodeURIComponent(player)+'&token='+encodeURIComponent(token),{method:'POST'})" +
                        " .then(r=>r.json()).then(data=>{alert(data.message);loadPlayers();});" +
                        "}" +

                        "function loadServerInfo(){" +
                        " fetch('/api/server?token='+encodeURIComponent(token))" +
                        " .then(r=>r.json())" +
                        " .then(data=>{" +
                        "   if(data.success===false)return;" +
                        "   document.getElementById('ram').innerText=data.ramUsed+' / '+data.ramMax+' MB';" +
                        "   document.getElementById('uptime').innerText=formatUptime(data.uptimeSeconds);" +
                        " });" +
                        "}" +

                        "function formatUptime(seconds){" +
                        " let h=Math.floor(seconds/3600);" +
                        " let m=Math.floor((seconds%3600)/60);" +
                        " let s=seconds%60;" +
                        " return h+'h '+m+'m '+s+'s';" +
                        "}" +

                        "loadPlayers();" +
                        "loadServerInfo();" +
                        "setInterval(loadPlayers,5000);" +
                        "setInterval(loadServerInfo,5000);" +
                        "</script>" +
                        "</body>" +
                        "</html>";

        return newFixedLengthResponse(Response.Status.OK, "text/html", html);
    }

    private boolean isAuthorized(IHTTPSession session) {
        Map<String, String> params = session.getParms();
        String token = params.get("token");

        return token != null && token.equals(adminToken);
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
}