package me.batman.batmanchat.protection;

import me.batman.batmanchat.BatmanChat;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Bloqueo del servidor (proteccion/lockdown.yml). Persiste al reiniciar. */
final class Lockdown implements Listener {
    private final BatmanChat plugin;

    Lockdown(BatmanChat plugin) {
        this.plugin = plugin;
    }

    private YamlConfiguration cfg() {
        return plugin.cfg("proteccion/lockdown.yml");
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void stop() {
        HandlerList.unregisterAll(this);
    }

    boolean isActive() {
        return cfg().getBoolean("active", false);
    }

    String mode() {
        return cfg().getString("current-mode", "only-known");
    }

    List<String> exempt() {
        return new ArrayList<>(cfg().getStringList("exempt-usernames"));
    }

    void set(boolean active, String mode) {
        YamlConfiguration c = cfg();
        c.set("active", active);
        if (mode != null) {
            c.set("current-mode", mode);
        }
        save();
    }

    boolean addExempt(String name) {
        List<String> l = exempt();
        for (String s : l) {
            if (s.equalsIgnoreCase(name)) {
                return false;
            }
        }
        l.add(name);
        cfg().set("exempt-usernames", l);
        save();
        return true;
    }

    boolean removeExempt(String name) {
        List<String> l = exempt();
        boolean removed = l.removeIf(s -> s.equalsIgnoreCase(name));
        if (removed) {
            cfg().set("exempt-usernames", l);
            save();
        }
        return removed;
    }

    private void save() {
        try {
            File f = plugin.dataFile("proteccion/lockdown.yml");
            cfg().save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("[Proteccion] No se pudo guardar lockdown.yml: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!isActive() || e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        for (String s : exempt()) {
            if (s.equalsIgnoreCase(e.getName())) {
                return;
            }
        }
        if (mode().equalsIgnoreCase("only-exempt")) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Lang.joined("lockdown.only-exempt-allowed-message"));
            return;
        }
        if (!Bukkit.getOfflinePlayer(e.getUniqueId()).hasPlayedBefore()) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Lang.joined("lockdown.only-known-allowed-message"));
        }
    }
}
