package me.batman.batmanchat.server;

import me.batman.batmanchat.AbstractFeature;
import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.util.Display;
import me.batman.batmanchat.util.Hooks;
import me.batman.batmanchat.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;

/**
 * Primera entrada, mensajes de entrada/salida (por defecto y por grupo), actionbar, titulo y MOTD
 * (antes ChatManager). Configuracion: servidor/entradas-salidas.yml.
 */
public final class ServerMessagesFeature extends AbstractFeature {
    private static final String FILE = "servidor/entradas-salidas.yml";

    public ServerMessagesFeature(BatmanChat plugin) {
        super(plugin);
    }

    @Override
    public String id() {
        return "mensajes-servidor";
    }

    @Override
    public void enable() {
        listen(new Events());
    }

    private YamlConfiguration cfg() {
        return plugin.cfg(FILE);
    }

    private String fmt(Player p, String raw) {
        String serverName = cfg().getString("Server_Name", Bukkit.getServer().getName());
        return Text.vars(raw, "player", p.getName(), "server_name", serverName);
    }

    private Component comp(Player p, String raw) {
        return Text.parse(p, fmt(p, raw));
    }

    private void playSound(ConfigurationSection s) {
        if (s == null || !s.getBoolean("toggle", false)) {
            return;
        }
        Display.sound(Bukkit.getOnlinePlayers(), s.getString("value", ""),
                (float) s.getDouble("volume", 1.0), (float) s.getDouble("pitch", 1.0));
    }

    private void showTitle(Player p, String header, String footer, int in, int stay, int out) {
        if ((header == null || header.isEmpty()) && (footer == null || footer.isEmpty())) {
            return;
        }
        Display.title(p, comp(p, header == null ? "" : header), comp(p, footer == null ? "" : footer), in, stay, out);
    }

    /** Grupo cuyo permiso tiene el jugador. Gana el ultimo de la lista (como ChatManager). */
    private ConfigurationSection groupOf(Player p, ConfigurationSection groups) {
        ConfigurationSection found = null;
        for (String key : groups.getKeys(false)) {
            ConfigurationSection g = groups.getConfigurationSection(key);
            if (g == null) {
                continue;
            }
            String perm = g.getString("Permission", "");
            if (!perm.isEmpty() && p.hasPermission(perm)) {
                found = g;
            }
        }
        return found;
    }

    /** Jugadores con el permiso de Permiso_Ocultar_Mensajes no muestran mensaje de entrada ni de salida. */
    private static boolean silent(YamlConfiguration c, Player p) {
        String perm = c.getString("Permiso_Ocultar_Mensajes", "");
        return perm != null && !perm.isEmpty() && p.hasPermission(perm);
    }

    private final class Events implements Listener {
        @EventHandler(priority = EventPriority.HIGH)
        public void onJoin(PlayerJoinEvent e) {
            Player p = e.getPlayer();
            YamlConfiguration c = cfg();
            boolean async = c.getBoolean("Messages.Async", false);
            boolean vanished = (c.getBoolean("Ocultar_Mensajes_Vanish", true) && Hooks.vanished(p)) || silent(c, p);

            // ---- primera entrada
            boolean first = !p.hasPlayedBefore();
            boolean firstHandled = false;
            if (first) {
                ConfigurationSection fj = c.getConfigurationSection("Messages.First_Join");
                if (fj != null) {
                    if (fj.getBoolean("Welcome_Message.Enable", false)) {
                        firstHandled = true;
                        e.joinMessage(null);
                        String msg = fj.getString("Welcome_Message.First_Join_Message", "");
                        final ConfigurationSection snd = fj.getConfigurationSection("Welcome_Message.sound");
                        if (!vanished) {
                            Runnable r = () -> {
                                Bukkit.broadcast(comp(p, msg));
                                playSound(snd);
                            };
                            if (async) {
                                later(r, 1L);
                            } else {
                                r.run();
                            }
                        }
                    }
                    if (fj.getBoolean("Actionbar_Message.Enable", false)) {
                        String msg = fj.getString("Actionbar_Message.First_Join_Message", "");
                        later(() -> Display.actionbar(p, comp(p, msg)), 20L);
                    }
                    if (fj.getBoolean("Title_Message.Enable", false)) {
                        final ConfigurationSection t = fj.getConfigurationSection("Title_Message");
                        later(() -> showTitle(p, t.getString("First_Join_Message.Header", ""),
                                t.getString("First_Join_Message.Footer", ""),
                                t.getInt("Fade_In", 40), t.getInt("Stay", 20), t.getInt("Fade_Out", 40)), 20L);
                    }
                }
            }

            // ---- entrada normal / por grupo
            if (!firstHandled) {
                handleJoin(e, p, c, vanished, async);
            }

            // ---- MOTD
            if (c.getBoolean("MOTD.Enable", false)) {
                List<String> lines = c.getStringList("MOTD.Message");
                long delay = Math.max(0, c.getInt("MOTD.Delay", 2)) * 20L;
                later(() -> {
                    if (!p.isOnline()) {
                        return;
                    }
                    for (String l : lines) {
                        p.sendMessage(comp(p, l));
                    }
                }, delay);
            }
        }

        private void handleJoin(PlayerJoinEvent e, Player p, YamlConfiguration c, boolean vanished, boolean async) {
            ConfigurationSection jq = c.getConfigurationSection("Messages.Join_Quit_Messages");
            if (jq == null) {
                if (vanished) {
                    e.joinMessage(null);
                }
                return;
            }
            String msg = null;
            ConfigurationSection snd = null;
            boolean handled = false;

            ConfigurationSection groups = jq.getConfigurationSection("Group_Messages");
            if (groups != null && groups.getBoolean("Enable", false)) {
                ConfigurationSection g = groupOf(p, groups);
                if (g != null) {
                    handled = true;
                    msg = g.getString("Join_Message", "");
                    snd = g.getConfigurationSection("sound");
                    String ab = g.getString("Actionbar", "");
                    if (!ab.isEmpty()) {
                        later(() -> Display.actionbar(p, comp(p, ab)), 10L);
                    }
                    String h = g.getString("Title.Header", "");
                    String f = g.getString("Title.Footer", "");
                    if (!h.isEmpty() && !f.isEmpty()) {
                        int in = jq.getInt("Title_Message.Fade_In", 40);
                        int st = jq.getInt("Title_Message.Stay", 20);
                        int out = jq.getInt("Title_Message.Fade_Out", 40);
                        later(() -> showTitle(p, h, f, in, st, out), 10L);
                    }
                }
            }
            if (!handled && jq.getBoolean("Join_Message.Enable", false)) {
                handled = true;
                msg = jq.getString("Join_Message.Message", "");
                snd = jq.getConfigurationSection("Join_Message.sound");
                if (jq.getBoolean("Actionbar_Message.Enable", false)) {
                    String ab = jq.getString("Actionbar_Message.Message", "");
                    later(() -> Display.actionbar(p, comp(p, ab)), 10L);
                }
                if (jq.getBoolean("Title_Message.Enable", false)) {
                    ConfigurationSection t = jq.getConfigurationSection("Title_Message");
                    later(() -> showTitle(p, t.getString("Message.Header", ""), t.getString("Message.Footer", ""),
                            t.getInt("Fade_In", 40), t.getInt("Stay", 20), t.getInt("Fade_Out", 40)), 10L);
                }
            }
            if (!handled) {
                if (vanished) {
                    e.joinMessage(null);
                }
                return;
            }
            e.joinMessage(null);
            if (vanished || msg == null || msg.isEmpty()) {
                return;
            }
            final String text = msg;
            final ConfigurationSection sound = snd;
            Runnable r = () -> {
                Bukkit.broadcast(comp(p, text));
                playSound(sound);
            };
            if (async) {
                later(r, 1L);
            } else {
                r.run();
            }
        }

        @EventHandler(priority = EventPriority.HIGH)
        public void onQuit(PlayerQuitEvent e) {
            Player p = e.getPlayer();
            YamlConfiguration c = cfg();
            boolean vanished = (c.getBoolean("Ocultar_Mensajes_Vanish", true) && Hooks.vanished(p)) || silent(c, p);
            ConfigurationSection jq = c.getConfigurationSection("Messages.Join_Quit_Messages");
            if (jq == null) {
                if (vanished) {
                    e.quitMessage(null);
                }
                return;
            }
            String msg = null;
            ConfigurationSection snd = null;
            boolean handled = false;
            ConfigurationSection groups = jq.getConfigurationSection("Group_Messages");
            if (groups != null && groups.getBoolean("Enable", false)) {
                ConfigurationSection g = groupOf(p, groups);
                if (g != null) {
                    handled = true;
                    msg = g.getString("Quit_Message", "");
                    snd = g.getConfigurationSection("sound");
                }
            }
            if (!handled && jq.getBoolean("Quit_Message.Enable", false)) {
                handled = true;
                msg = jq.getString("Quit_Message.Message", "");
                snd = jq.getConfigurationSection("Quit_Message.sound");
            }
            if (!handled) {
                if (vanished) {
                    e.quitMessage(null);
                }
                return;
            }
            e.quitMessage(null);
            if (vanished || msg == null || msg.isEmpty()) {
                return;
            }
            Bukkit.broadcast(comp(p, msg));
            playSound(snd);
        }
    }
}
