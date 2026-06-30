package me.flaxi.serverwebadmin;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;

public class ConfigManager {

    private final JavaPlugin plugin;
    private final File configFile;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.configFile = new File(plugin.getDataFolder(), "config.yml");
    }

    public String getWebHost() { return getConfig().getString("web.host", "0.0.0.0"); }
    public int getWebPort() { return getConfig().getInt("web.port", 8080); }
    public boolean getAllowExternal() { return getConfig().getBoolean("web.allow-external", true); }
    public boolean getFallbackToAny() { return getConfig().getBoolean("web.fallback-to-any", true); }
    public int getSessionTimeout() { return getConfig().getInt("web.session-timeout", 5); }

    public String getAlert(String key, String def) { return getConfig().getString("alerts." + key, def); }

    public void saveWebSettings(String host, int port, boolean allowExternal, boolean fallbackToAny, int sessionTimeout) {
        FileConfiguration config = getConfig();
        config.set("web.host", host);
        config.set("web.port", port);
        config.set("web.allow-external", allowExternal);
        config.set("web.fallback-to-any", fallbackToAny);
        config.set("web.session-timeout", sessionTimeout);
        saveConfig(config);
    }

    public void saveAlertSettings(String kick, String banReason, String banKick) {
        FileConfiguration config = getConfig();
        config.set("alerts.kick", kick);
        config.set("alerts.ban_reason", banReason);
        config.set("alerts.ban_kick", banKick);
        saveConfig(config);
    }

    public void saveGeneralSettings(String theme, int refreshInterval, String landingPage) {
        FileConfiguration config = getConfig();
        config.set("general.theme", theme);
        config.set("general.refresh-interval", refreshInterval);
        config.set("general.landing-page", landingPage);
        saveConfig(config);
    }

    public FileConfiguration getFullConfig() {
        return getConfig();
    }

    private FileConfiguration getConfig() {
        return YamlConfiguration.loadConfiguration(configFile);
    }

    private void saveConfig(FileConfiguration config) {
        try {
            config.save(configFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save config.yml: " + e.getMessage());
        }
    }

    public void reloadConfig() {
        plugin.reloadConfig();
    }
}
