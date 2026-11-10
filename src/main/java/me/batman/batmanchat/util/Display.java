package me.batman.batmanchat.util;

import me.batman.batmanchat.BatmanChat;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Sonidos, titulos, actionbar y bossbars. Llamar siempre desde el hilo principal. */
public final class Display {
    private Display() {
    }

    private static Map<String, String> soundKeys;

    /** name: "ENTITY_EXPERIENCE_ORB_PICKUP" o "entity.experience_orb.pickup". Vacio/none = no hace nada. */
    /**
     * Envia el mensaje a TODOS los jugadores conectados y a la consola. Se usa en vez de Bukkit.broadcast(Component),
     * que solo llega a quienes estan suscritos al permiso "bukkit.broadcast.user" (con algunos plugins de permisos
     * eso deja a todos sin recibirlo, y los mensajes de entrada/salida no se veian).
     */
    public static void broadcast(Component message) {
        if (message == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(message);
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }

    public static void sound(Player p, String name, float volume, float pitch) {
        if (p == null || name == null || name.isBlank() || name.equalsIgnoreCase("none") || name.equalsIgnoreCase("null")) {
            return;
        }
        String key = soundKey(name.trim());
        if (key == null) {
            return;
        }
        p.playSound(p.getLocation(), key, volume, pitch);
    }

    public static void sound(Collection<? extends Player> players, String name, float volume, float pitch) {
        for (Player p : players) {
            sound(p, name, volume, pitch);
        }
    }

    private static synchronized String soundKey(String name) {
        if (name.contains(".")) {
            return name.toLowerCase(Locale.ROOT);
        }
        if (soundKeys == null) {
            Map<String, String> m = new HashMap<>();
            for (Sound s : Registry.SOUNDS) {
                String k = s.getKey().getKey();
                m.put(k.replace('.', '_').toUpperCase(Locale.ROOT), k);
            }
            soundKeys = m;
        }
        return soundKeys.get(name.replace(' ', '_').toUpperCase(Locale.ROOT));
    }

    public static void title(Player p, Component title, Component subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks) {
        p.showTitle(Title.title(title, subtitle, Title.Times.times(
                Duration.ofMillis(fadeInTicks * 50L),
                Duration.ofMillis(stayTicks * 50L),
                Duration.ofMillis(fadeOutTicks * 50L))));
    }

    public static void actionbar(Player p, Component text) {
        p.sendActionBar(text);
    }

    /**
     * Muestra una bossbar a los jugadores y la quita pasados "seconds" segundos.
     * color: BLUE, GREEN, PINK, PURPLE, RED, WHITE, YELLOW.
     * style: PROGRESS, NOTCHED_6, NOTCHED_10, NOTCHED_12, NOTCHED_20.
     */
    public static BossBar bossbar(Collection<? extends Player> players, Component title, String color, String style,
                                  float progress, int seconds) {
        BossBar.Color c = enumOf(BossBar.Color.class, color, BossBar.Color.RED);
        BossBar.Overlay o = enumOf(BossBar.Overlay.class, style, BossBar.Overlay.PROGRESS);
        BossBar bar = BossBar.bossBar(title, Math.max(0f, Math.min(1f, progress)), c, o);
        final List<Player> targets = new ArrayList<>(players);
        for (Player p : targets) {
            p.showBossBar(bar);
        }
        Bukkit.getScheduler().runTaskLater(BatmanChat.get(), () -> {
            for (Player p : targets) {
                p.hideBossBar(bar);
            }
        }, Math.max(1, seconds) * 20L);
        return bar;
    }

    public static <E extends Enum<E>> E enumOf(Class<E> type, String name, E def) {
        if (name == null) {
            return def;
        }
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return def;
        }
    }
}
