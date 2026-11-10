package me.batman.batmanchat.server;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.batman.batmanchat.AbstractFeature;
import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.util.Display;
import me.batman.batmanchat.util.Hooks;
import me.batman.batmanchat.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** /clearchat, /mutechat y /staffchat (antes ChatManager). Config: chat/clearchat.yml, mutechat.yml, staffchat.yml. */
public final class ChatControlFeature extends AbstractFeature {
    private boolean muted;
    private final Set<UUID> staffMode = new HashSet<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();

    public ChatControlFeature(BatmanChat plugin) {
        super(plugin);
    }

    @Override
    public String id() {
        return "control-chat";
    }

    @Override
    public void enable() {
        muted = false;
        staffMode.clear();
        bars.clear();
        listen(new Events());
        command("clearchat", new ClearCmd());
        command("mutechat", new MuteCmd());
        command("staffchat", new StaffCmd());
    }

    @Override
    protected void onDisable() {
        for (Map.Entry<UUID, BossBar> e : bars.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                p.hideBossBar(e.getValue());
            }
        }
        bars.clear();
        staffMode.clear();
    }

    private YamlConfiguration clear() {
        return plugin.cfg("chat/clearchat.yml");
    }

    private YamlConfiguration mute() {
        return plugin.cfg("chat/mutechat.yml");
    }

    private YamlConfiguration staff() {
        return plugin.cfg("chat/staffchat.yml");
    }

    private static boolean staffPerm(CommandSender s) {
        return Hooks.has(s, "batmanchat.staffchat", "chatmanager.staffchat");
    }

    private static boolean clearBypass(Player p) {
        return Hooks.has(p, "batmanchat.clearchat.bypass", "chatmanager.bypass.clearchat");
    }

    private static boolean muteBypass(Player p) {
        return Hooks.has(p, "batmanchat.mutechat.bypass", "chatmanager.bypass.mutechat");
    }

    private void blank(Player p) {
        int lines = Math.max(1, clear().getInt("Broadcasted_Lines", 300));
        Component empty = Component.text(" ");
        for (int i = 0; i < lines; i++) {
            p.sendMessage(empty);
        }
    }

    private String nameOf(CommandSender s) {
        return s instanceof Player ? s.getName() : "Consola";
    }

    // ------------------------------------------------------------------ staff chat

    private void sendStaff(Player from, String message) {
        YamlConfiguration y = staff();
        String fmt = Text.vars(y.getString("Format", "&cSTAFF &f{player} &8» &f{message}"),
                "player", from.getName(), "displayname", Text.plain(from.displayName()));
        Component c = Text.parse(from, fmt).replaceText(b -> b.matchLiteral("{message}").replacement(Component.text(message)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (staffPerm(p)) {
                p.sendMessage(c);
            }
        }
        Bukkit.getConsoleSender().sendMessage(c);
    }

    private void setStaffMode(Player p, boolean on) {
        YamlConfiguration y = staff();
        if (on) {
            staffMode.add(p.getUniqueId());
            if (y.getBoolean("Boss_Bar.Enable", true)) {
                BossBar bar = BossBar.bossBar(Text.parse(p, y.getString("Boss_Bar.Title", "Staff Chat")), 1f,
                        Display.enumOf(BossBar.Color.class, y.getString("Boss_Bar.Color", "GREEN"), BossBar.Color.GREEN),
                        Display.enumOf(BossBar.Overlay.class, y.getString("Boss_Bar.Style", "PROGRESS"), BossBar.Overlay.PROGRESS));
                bars.put(p.getUniqueId(), bar);
                p.showBossBar(bar);
            }
            Text.send(p, y.getString("Mensajes.Enabled", ""));
        } else {
            staffMode.remove(p.getUniqueId());
            BossBar bar = bars.remove(p.getUniqueId());
            if (bar != null) {
                p.hideBossBar(bar);
            }
            Text.send(p, y.getString("Mensajes.Disabled", ""));
        }
    }

    // ------------------------------------------------------------------ eventos

    private final class Events implements Listener {
        @EventHandler(priority = EventPriority.LOWEST)
        public void onChat(AsyncChatEvent e) {
            Player p = e.getPlayer();
            // staff chat
            if (staffMode.contains(p.getUniqueId())) {
                e.setCancelled(true);
                if (!staffPerm(p)) {
                    Bukkit.getScheduler().runTask(plugin, () -> setStaffMode(p, false));
                    return;
                }
                sendStaff(p, Text.plain(e.message()));
                return;
            }
            // chat silenciado
            if (muted && !muteBypass(p)) {
                e.setCancelled(true);
                Text.send(p, mute().getString("Mensajes.Denied_Message", ""));
            }
        }

        @EventHandler(priority = EventPriority.LOWEST)
        public void onCommand(PlayerCommandPreprocessEvent e) {
            if (!muted || !mute().getBoolean("Disable_Commands", false) || muteBypass(e.getPlayer())) {
                return;
            }
            String msg = e.getMessage().trim();
            int sp = msg.indexOf(' ');
            String base = (sp < 0 ? msg : msg.substring(0, sp)).toLowerCase(Locale.ROOT);
            for (String s : mute().getStringList("Disabled_Commands")) {
                String t = s.trim().toLowerCase(Locale.ROOT);
                if (!t.startsWith("/")) {
                    t = "/" + t;
                }
                if (t.equals(base)) {
                    e.setCancelled(true);
                    Text.send(e.getPlayer(), mute().getString("Mensajes.Blocked_Command", ""));
                    return;
                }
            }
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent e) {
            if (clear().getBoolean("Clear_On_Join", false)) {
                blank(e.getPlayer());
            }
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            staffMode.remove(e.getPlayer().getUniqueId());
            bars.remove(e.getPlayer().getUniqueId());
        }
    }

    // ------------------------------------------------------------------ comandos

    private final class ClearCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
            if (!Hooks.has(s, "batmanchat.clearchat", "chatmanager.clearchat")) {
                Text.sendKey(s, "Message.No_Permission");
                return true;
            }
            YamlConfiguration y = clear();
            String who = nameOf(s);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (clearBypass(p)) {
                    Text.send(p, y.getString("Staff_Message", ""), "player", who);
                    continue;
                }
                blank(p);
                for (String line : y.getStringList("Broadcast_Message")) {
                    Text.send(p, line, "player", who);
                }
            }
            return true;
        }
    }

    private final class MuteCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
            if (!Hooks.has(s, "batmanchat.mutechat", "chatmanager.mutechat")) {
                Text.sendKey(s, "Message.No_Permission");
                return true;
            }
            muted = !muted;
            String path = muted ? "Mensajes.Disabled" : "Mensajes.Enabled";
            String msg = mute().getString(path, "");
            String who = nameOf(s);
            for (Player p : Bukkit.getOnlinePlayers()) {
                Text.send(p, msg, "player", who);
            }
            Text.send(Bukkit.getConsoleSender(), msg, "player", who);
            return true;
        }
    }

    private final class StaffCmd implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
            if (!staffPerm(s)) {
                Text.sendKey(s, "Message.No_Permission");
                return true;
            }
            if (!staff().getBoolean("Enable", true)) {
                Text.send(s, staff().getString("Mensajes.Disabled_Module", ""));
                return true;
            }
            if (!(s instanceof Player p)) {
                if (args.length > 0) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Component msg = Text.parse(staff().getString("Format", "").replace("{player}", "Consola")
                                .replace("{displayname}", "Consola"))
                                .replaceText(b -> b.matchLiteral("{message}").replacement(Component.text(String.join(" ", args))));
                        for (Player o : Bukkit.getOnlinePlayers()) {
                            if (staffPerm(o)) {
                                o.sendMessage(msg);
                            }
                        }
                    });
                } else {
                    Text.sendKey(s, "Message.Only_Players");
                }
                return true;
            }
            if (args.length == 0) {
                setStaffMode(p, !staffMode.contains(p.getUniqueId()));
            } else {
                sendStaff(p, String.join(" ", args));
            }
            return true;
        }
    }
}
