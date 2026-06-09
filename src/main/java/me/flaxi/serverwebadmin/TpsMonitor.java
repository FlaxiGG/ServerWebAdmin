package me.flaxi.serverwebadmin;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class TpsMonitor implements Runnable {

    private int taskId = -1;
    private long lastTime = System.currentTimeMillis();
    private double tps = 20.0;

    public void start(JavaPlugin plugin) {
        taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(
                plugin,
                this,
                100L,
                1L
        );
    }

    public void stop() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
        }
    }

    @Override
    public void run() {
        long now = System.currentTimeMillis();
        long diff = now - lastTime;

        if (diff > 0) {
            tps = 1000.0 / diff;

            if (tps > 20.0) {
                tps = 20.0;
            }
        }

        lastTime = now;
    }

    public double getTps() {
        return Math.round(tps * 100.0) / 100.0;
    }
}