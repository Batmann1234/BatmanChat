package me.batman.batmanchat.util;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Valores por rango. En el yml se escribe asi:
 * <pre>
 * Cooldown:
 *   default: 10
 *   vip: 5        # nombre del grupo de LuckPerms (permiso group.vip)
 *   hero: 2
 * </pre>
 * Se usa el MENOR valor de todos los grupos a los que pertenece el jugador ("los VIP esperan menos").
 */
public final class Ranks {
    private Ranks() {
    }

    public static double min(Player p, ConfigurationSection section, double fallback) {
        if (section == null) {
            return fallback;
        }
        double best = section.getDouble("default", fallback);
        boolean found = false;
        for (String key : section.getKeys(false)) {
            if (key.equalsIgnoreCase("default")) {
                continue;
            }
            if (Hooks.inGroup(p, key)) {
                double v = section.getDouble(key, fallback);
                if (!found || v < best) {
                    best = v;
                    found = true;
                }
            }
        }
        return best;
    }

    public static int minInt(Player p, ConfigurationSection section, int fallback) {
        return (int) Math.round(min(p, section, fallback));
    }
}
