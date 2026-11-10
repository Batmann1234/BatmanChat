package me.batman.batmanchat.protection;

import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.List;
import java.util.Locale;

/** Muestra a los admins los comandos que ejecutan los jugadores (modulos/command-spy.yml). */
final class CommandSpy implements Listener {
    private final ViolationService service;
    private final BatmanChat plugin;
    private YamlConfiguration cfg;
    private boolean active;

    CommandSpy(ViolationService service, BatmanChat plugin) {
        this.service = service;
        this.plugin = plugin;
    }

    boolean isActive() {
        return active;
    }

    void start() {
        active = plugin.cfg("proteccion/config.yml").getBoolean("command-spy.enabled", false);
        if (!active) {
            return;
        }
        cfg = plugin.cfg("proteccion/modulos/command-spy.yml");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    void stop() {
        HandlerList.unregisterAll(this);
    }

    private static boolean startsWithAny(String cmd, List<String> list) {
        for (String s : list) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty() && cmd.startsWith(t)) {
                return true;
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (p.hasPermission("batmanchat.proteccion.commandspy.exempt") || p.hasPermission("chatsentry.commandspy.exempt")) {
            return;
        }
        String msg = e.getMessage();
        String lower = msg.toLowerCase(Locale.ROOT);
        if (cfg.getBoolean("use-command-whitelist", false)) {
            if (!startsWithAny(lower, cfg.getStringList("commandspy-command-whitelist"))) {
                return;
            }
        } else if (startsWithAny(lower, cfg.getStringList("commandspy-command-blacklist"))) {
            return;
        }
        String shown = Text.strip(msg);
        String[] mod = service.takeModified(p.getUniqueId());
        if (mod != null && mod[1].equals(msg)) {
            shown = "&m" + Text.strip(mod[0]) + "&r&7&o " + Text.strip(msg);
        }
        String line = Lang.text("modules.command-spy.format", "PLAYER", p.getName(), "COMMAND", shown);
        for (Player a : Bukkit.getOnlinePlayers()) {
            if (a.getUniqueId().equals(p.getUniqueId()) || !service.spyOn(a.getUniqueId())) {
                continue;
            }
            if (a.isOp() || a.hasPermission("batmanchat.proteccion.commandspy") || a.hasPermission("chatsentry.commandspy")) {
                a.sendMessage(Text.parse(a, line));
            }
        }
    }
}
