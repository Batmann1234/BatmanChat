package me.batman.batmanchat;

import me.batman.batmanchat.chat.ChatFormatFeature;
import me.batman.batmanchat.chat.DeathMessagesFeature;
import me.batman.batmanchat.chat.InteractiveTokensFeature;
import me.batman.batmanchat.chat.PollFeature;
import me.batman.batmanchat.chat.PrivateMessagesFeature;
import me.batman.batmanchat.chat.StoreFeature;
import me.batman.batmanchat.protection.ProtectionFeature;
import me.batman.batmanchat.server.AnnouncementsFeature;
import me.batman.batmanchat.server.BroadcastFeature;
import me.batman.batmanchat.server.ChatControlFeature;
import me.batman.batmanchat.server.ServerMessagesFeature;
import me.batman.batmanchat.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class BatmanChat extends JavaPlugin {

    private static BatmanChat instance;

    private final Map<String, YamlConfiguration> configs = new HashMap<>();
    /** clave de config.yml (modulos.<clave>) -> modulo, en orden de arranque. */
    private final Map<String, Feature> features = new LinkedHashMap<>();
    private final Map<String, Boolean> running = new HashMap<>();
    private final Map<String, SubCommand> subs = new LinkedHashMap<>();

    public static BatmanChat get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        extractResources();
        saveDefaultConfig();

        features.put("proteccion", new ProtectionFeature(this));
        features.put("anuncios", new AnnouncementsFeature(this));
        features.put("mensajes-servidor", new ServerMessagesFeature(this));
        features.put("broadcast", new BroadcastFeature(this));
        features.put("control-chat", new ChatControlFeature(this));
        features.put("items-interactivos", new InteractiveTokensFeature(this));
        features.put("mensajes-privados", new PrivateMessagesFeature(this));
        features.put("formato-chat", new ChatFormatFeature(this));
        features.put("mensajes-muerte", new DeathMessagesFeature(this));
        features.put("tienda", new StoreFeature(this));
        features.put("encuestas", new PollFeature(this));

        enableFeatures();

        PluginCommand cmd = getCommand("batmanchat");
        if (cmd != null) {
            MainCommand mc = new MainCommand();
            cmd.setExecutor(mc);
            cmd.setTabCompleter(mc);
        }
        getLogger().info("BatmanChat activado.");
    }

    @Override
    public void onDisable() {
        disableFeatures();
        instance = null;
    }

    private void enableFeatures() {
        for (Map.Entry<String, Feature> e : features.entrySet()) {
            boolean on = getConfig().getBoolean("modulos." + e.getKey(), true);
            if (!on) {
                running.put(e.getKey(), false);
                getLogger().info("Modulo desactivado en config.yml: " + e.getKey());
                continue;
            }
            try {
                e.getValue().enable();
                running.put(e.getKey(), true);
            } catch (Throwable t) {
                running.put(e.getKey(), false);
                getLogger().severe("No se pudo activar el modulo " + e.getKey() + ": " + t);
                t.printStackTrace();
            }
        }
    }

    private void disableFeatures() {
        List<Map.Entry<String, Feature>> list = new ArrayList<>(features.entrySet());
        for (int i = list.size() - 1; i >= 0; i--) {
            Map.Entry<String, Feature> e = list.get(i);
            if (Boolean.TRUE.equals(running.get(e.getKey()))) {
                try {
                    e.getValue().disable();
                } catch (Throwable t) {
                    getLogger().warning("Error al desactivar " + e.getKey() + ": " + t);
                }
            }
            running.put(e.getKey(), false);
        }
    }

    /** Recarga todos los ymls y reinicia todos los modulos. */
    public void reloadAll() {
        disableFeatures();
        synchronized (this) {
            configs.clear();
        }
        subs.clear();
        reloadConfig();
        enableFeatures();
    }

    /** Devuelve (y cachea) un yml de la carpeta del plugin. Si no existe lo copia del jar. Ej: cfg("chat/formato.yml"). */
    public synchronized YamlConfiguration cfg(String path) {
        YamlConfiguration cached = configs.get(path);
        if (cached != null) {
            return cached;
        }
        File f = new File(getDataFolder(), path);
        if (!f.exists()) {
            try {
                saveResource(path, false);
            } catch (IllegalArgumentException ignored) {
                // no existe en el jar
            }
        }
        YamlConfiguration y = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        try (InputStream in = getResource(path)) {
            if (in != null) {
                y.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) {
        }
        configs.put(path, y);
        return y;
    }

    /** Olvida un yml cacheado para que se vuelva a leer del disco (para recargas parciales). */
    public synchronized void dropConfig(String... paths) {
        for (String p : paths) {
            configs.remove(p);
        }
    }

    public File dataFile(String path) {
        return new File(getDataFolder(), path);
    }

    @SuppressWarnings("unchecked")
    public <T extends Feature> T feature(Class<T> type) {
        for (Feature f : features.values()) {
            if (type.isInstance(f)) {
                return (T) f;
            }
        }
        return null;
    }

    /** true si el modulo esta realmente corriendo ahora mismo. */
    public boolean isRunning(Class<? extends Feature> type) {
        for (Map.Entry<String, Feature> e : features.entrySet()) {
            if (type.isInstance(e.getValue())) {
                return Boolean.TRUE.equals(running.get(e.getKey()));
            }
        }
        return false;
    }

    public void registerSub(String name, SubCommand sub) {
        subs.put(name.toLowerCase(Locale.ROOT), sub);
    }

    public void unregisterSub(String name) {
        subs.remove(name.toLowerCase(Locale.ROOT));
    }

    private void extractResources() {
        try (JarFile jar = new JarFile(getFile())) {
            jar.stream()
                    .map(JarEntry::getName)
                    .filter(n -> n.endsWith(".yml") && !n.equals("plugin.yml") && !n.equals("config.yml") && !n.startsWith("META-INF"))
                    .forEach(n -> {
                        if (!new File(getDataFolder(), n).exists()) {
                            saveResource(n, false);
                        }
                    });
        } catch (IOException e) {
            getLogger().warning("No se pudieron extraer los ymls: " + e.getMessage());
        }
    }

    private final class MainCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("ayuda")) {
                Text.send(sender, "{Prefix} &7BatmanChat &f" + getPluginMeta().getVersion());
                Text.send(sender, "&8» &f/" + label + " reload &7- recarga todos los ymls y modulos");
                for (Map.Entry<String, SubCommand> e : subs.entrySet()) {
                    String perm = e.getValue().permission();
                    if (perm == null || sender.hasPermission(perm)) {
                        Text.send(sender, "&8» &f/" + label + " " + e.getKey());
                    }
                }
                return true;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("reload") || sub.equals("recargar")) {
                if (!sender.hasPermission("batmanchat.admin")) {
                    Text.sendKey(sender, "Message.No_Permission");
                    return true;
                }
                reloadAll();
                Text.sendKey(sender, "Message.Reload");
                return true;
            }
            SubCommand sc = subs.get(sub);
            if (sc == null) {
                Text.sendKey(sender, "Message.Invalid_Usage", "usage", "/" + label + " help");
                return true;
            }
            if (sc.permission() != null && !sender.hasPermission(sc.permission())) {
                Text.sendKey(sender, "Message.No_Permission");
                return true;
            }
            String[] rest = new String[args.length - 1];
            System.arraycopy(args, 1, rest, 0, rest.length);
            return sc.run(sender, rest);
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> out = new ArrayList<>();
            if (args.length == 1) {
                String p = args[0].toLowerCase(Locale.ROOT);
                if (sender.hasPermission("batmanchat.admin") && "reload".startsWith(p)) {
                    out.add("reload");
                }
                for (Map.Entry<String, SubCommand> e : subs.entrySet()) {
                    String perm = e.getValue().permission();
                    if (e.getKey().startsWith(p) && (perm == null || sender.hasPermission(perm))) {
                        out.add(e.getKey());
                    }
                }
                return out;
            }
            SubCommand sc = subs.get(args[0].toLowerCase(Locale.ROOT));
            if (sc != null && (sc.permission() == null || sender.hasPermission(sc.permission()))) {
                String[] rest = new String[args.length - 1];
                System.arraycopy(args, 1, rest, 0, rest.length);
                return sc.tab(sender, rest);
            }
            return out;
        }
    }
}
