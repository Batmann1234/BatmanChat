package me.batman.batmanchat.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.lang.reflect.Method;

/** Integraciones opcionales: PlaceholderAPI, Vault, LuckPerms y vanish. Nada de esto es obligatorio. */
public final class Hooks {
    private Hooks() {
    }

    // ------------------------------------------------------------------ PlaceholderAPI (por reflexion)

    private static Method papiMethod;
    private static boolean papiChecked;

    public static boolean hasPapi() {
        return Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI");
    }

    public static String papi(Player player, String text) {
        if (text == null || text.indexOf('%') < 0 || player == null) {
            return text == null ? "" : text;
        }
        try {
            if (!papiChecked) {
                papiChecked = true;
                if (hasPapi()) {
                    Class<?> c = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
                    papiMethod = c.getMethod("setPlaceholders", OfflinePlayer.class, String.class);
                }
            }
            if (papiMethod == null || !hasPapi()) {
                return text;
            }
            Object r = papiMethod.invoke(null, player, text);
            return r == null ? text : r.toString();
        } catch (Throwable t) {
            return text;
        }
    }

    // ------------------------------------------------------------------ Vault (por reflexion)

    private static Object economy;
    private static Method ecoBalance;
    private static Method ecoFormat;
    private static boolean ecoChecked;

    private static <T> Object provider(Class<T> type) {
        RegisteredServiceProvider<T> rsp = Bukkit.getServicesManager().getRegistration(type);
        return rsp == null ? null : rsp.getProvider();
    }

    private static void initEconomy() {
        if (ecoChecked && economy != null) {
            return;
        }
        ecoChecked = true;
        try {
            if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) {
                return;
            }
            Class<?> eco = Class.forName("net.milkbowl.vault.economy.Economy");
            Object prov = provider(eco);
            if (prov == null) {
                return;
            }
            economy = prov;
            ecoBalance = eco.getMethod("getBalance", OfflinePlayer.class);
            ecoFormat = eco.getMethod("format", double.class);
        } catch (Throwable ignored) {
        }
    }

    public static boolean hasEconomy() {
        initEconomy();
        return economy != null;
    }

    /** Dinero formateado por el plugin de economia, o null si no hay Vault/economia. */
    public static String money(Player p) {
        initEconomy();
        if (economy == null) {
            return null;
        }
        try {
            double bal = ((Number) ecoBalance.invoke(economy, p)).doubleValue();
            Object f = ecoFormat.invoke(economy, bal);
            return f == null ? String.valueOf(bal) : f.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ LuckPerms

    public static boolean hasLuckPerms() {
        return Bukkit.getPluginManager().isPluginEnabled("LuckPerms");
    }

    /** Grupo primario de LuckPerms (minusculas) o null si no hay LuckPerms. */
    public static String primaryGroup(Player p) {
        if (!hasLuckPerms()) {
            return null;
        }
        try {
            return LP.primary(p);
        } catch (Throwable t) {
            return null;
        }
    }

    /** true si el jugador esta en ese grupo (permiso group.&lt;nombre&gt;, funciona con LuckPerms y otros). */
    public static boolean inGroup(Player p, String group) {
        return p.hasPermission("group." + group.toLowerCase());
    }

    private static final class LP {
        static String primary(Player p) {
            net.luckperms.api.LuckPerms api = net.luckperms.api.LuckPermsProvider.get();
            net.luckperms.api.model.user.User u = api.getUserManager().getUser(p.getUniqueId());
            return u == null ? null : u.getPrimaryGroup().toLowerCase();
        }
    }

    // ------------------------------------------------------------------ vanish

    /** true si el jugador esta en vanish (Essentials, SuperVanish, PremiumVanish usan el metadata "vanished"). */
    public static boolean vanished(Player p) {
        for (MetadataValue v : p.getMetadata("vanished")) {
            if (v.asBoolean()) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ permisos

    /** true si tiene cualquiera de los permisos (util para aceptar tambien los nombres viejos de ChatSentry/ChatManager). */
    public static boolean has(CommandSender s, String... nodes) {
        for (String n : nodes) {
            if (s.hasPermission(n)) {
                return true;
            }
        }
        return false;
    }
}
