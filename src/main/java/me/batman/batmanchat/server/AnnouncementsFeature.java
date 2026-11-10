package me.batman.batmanchat.server;

import me.batman.batmanchat.AbstractFeature;
import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.util.Display;
import me.batman.batmanchat.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anuncios automaticos (antes InfiniteAnnouncements).
 * Archivos: anuncios/config.yml, anuncios/anuncios.yml, anuncios/placeholders.yml, anuncios/mensajes.yml.
 */
public final class AnnouncementsFeature extends AbstractFeature {
    private static final String[] FILES = {"anuncios/config.yml", "anuncios/anuncios.yml",
            "anuncios/placeholders.yml", "anuncios/mensajes.yml"};
    private static final Pattern PLACEHOLDER = Pattern.compile("%%([A-Za-z0-9_\\-]+)%%");
    private static final Pattern CENTER = Pattern.compile("(?is)^\\s*<center>(.*)</center>\\s*$");

    private final Random random = new Random();
    private final Set<UUID> off = new HashSet<>();
    private final Set<UUID> soundOff = new HashSet<>();
    private int cursor;

    public AnnouncementsFeature(BatmanChat plugin) {
        super(plugin);
    }

    @Override
    public String id() {
        return "anuncios";
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public void enable() {
        loadPlayers();
        cursor = 0;
        YamlConfiguration cfg = plugin.cfg("anuncios/config.yml");
        ConfigurationSection all = plugin.cfg("anuncios/anuncios.yml").getConfigurationSection("Announcements");
        if (all == null) {
            plugin.getLogger().warning("[Anuncios] anuncios/anuncios.yml no tiene la seccion Announcements.");
        } else if (cfg.getBoolean("announcement.enable", true)) {
            long ticks = Math.max(1, cfg.getInt("announcement.interval", 180)) * 20L;
            repeat(this::sendNext, ticks, ticks);
        } else {
            for (String id : all.getKeys(false)) {
                long ticks = Math.max(1, all.getInt(id + ".interval", 60)) * 20L;
                final String fid = id;
                repeat(() -> broadcast(fid), ticks, ticks);
            }
        }
        Cmd cmd = new Cmd();
        command("anuncios", cmd);
        plugin.getLogger().info("[Anuncios] " + (all == null ? 0 : all.getKeys(false).size()) + " anuncios cargados.");
    }

    private void reloadSelf() {
        disable();
        plugin.dropConfig(FILES);
        enable();
    }

    // ------------------------------------------------------------------ envio

    private List<String> ids() {
        ConfigurationSection all = plugin.cfg("anuncios/anuncios.yml").getConfigurationSection("Announcements");
        return all == null ? new ArrayList<>() : new ArrayList<>(all.getKeys(false));
    }

    private void sendNext() {
        List<String> ids = ids();
        if (ids.isEmpty()) {
            return;
        }
        String id;
        if (plugin.cfg("anuncios/config.yml").getBoolean("announcement.random", false)) {
            id = ids.get(random.nextInt(ids.size()));
        } else {
            if (cursor >= ids.size()) {
                cursor = 0;
            }
            id = ids.get(cursor++);
        }
        broadcast(id);
    }

    /** Envia el anuncio a todos los jugadores que cumplan mundo, permiso y preferencia. */
    private void broadcast(String id) {
        ConfigurationSection a = plugin.cfg("anuncios/anuncios.yml").getConfigurationSection("Announcements." + id);
        if (a == null) {
            return;
        }
        List<String> worlds = a.getStringList("worlds");
        String perm = a.getString("permission");
        List<Player> targets = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (off.contains(p.getUniqueId())) {
                continue;
            }
            if (!worlds.isEmpty() && worlds.stream().noneMatch(w -> w.equalsIgnoreCase(p.getWorld().getName()))) {
                continue;
            }
            if (perm != null && !perm.isBlank() && !perm.equalsIgnoreCase("null") && !p.hasPermission(perm)) {
                continue;
            }
            targets.add(p);
        }
        deliver(a, targets, true);
    }

    private void deliver(ConfigurationSection a, List<Player> targets, boolean withSound) {
        String type = a.getString("type", "CHAT").toUpperCase(Locale.ROOT);
        List<String> lines = a.getStringList("lines");
        String sound = a.getString("sound", "");
        float volume = (float) a.getDouble("volume", 1.0);
        float pitch = (float) a.getDouble("pitch", 1.0);

        switch (type) {
            case "ACTIONBAR": {
                for (Player p : targets) {
                    Display.actionbar(p, line(p, firstNonEmpty(lines)));
                }
                break;
            }
            case "TITLE": {
                String t = lines.size() > 0 ? lines.get(0) : "";
                String s = lines.size() > 1 ? lines.get(1) : "";
                for (Player p : targets) {
                    Display.title(p, line(p, t), line(p, s), 10, 70, 20);
                }
                break;
            }
            case "BOSSBAR": {
                ConfigurationSection b = a.getConfigurationSection("bossbar");
                String color = b == null ? "RED" : b.getString("color", "RED");
                String style = b == null ? "PROGRESS" : b.getString("style", "PROGRESS");
                float progress = b == null ? 1f : (float) b.getDouble("progress", 1.0);
                int time = b == null ? 5 : Math.max(1, Math.min(60, b.getInt("time", 5)));
                String text = firstNonEmpty(lines);
                // La bossbar es la misma para todos, asi que se usa el titulo sin PlaceholderAPI por jugador.
                for (Player p : targets) {
                    Display.bossbar(List.of(p), line(p, text), color, style, progress, time);
                }
                break;
            }
            default: {
                for (Player p : targets) {
                    for (String l : lines) {
                        p.sendMessage(line(p, l));
                    }
                }
                break;
            }
        }
        if (withSound) {
            for (Player p : targets) {
                if (!soundOff.contains(p.getUniqueId())) {
                    Display.sound(p, sound, volume, pitch);
                }
            }
        }
    }

    private static String firstNonEmpty(List<String> lines) {
        for (String l : lines) {
            if (l != null && !Text.strip(l).trim().isEmpty()) {
                return l;
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ lineas con %%placeholders%%

    private Component line(Player p, String raw) {
        if (raw == null) {
            return Component.empty();
        }
        ConfigurationSection ph = plugin.cfg("anuncios/placeholders.yml").getConfigurationSection("Placeholders");
        Matcher cm = CENTER.matcher(raw);
        boolean center = cm.matches();
        String inner = center ? cm.group(1) : raw;
        if (ph == null || !PLACEHOLDER.matcher(inner).find()) {
            return Text.parse(p, raw);
        }

        // texto plano para calcular el centrado
        StringBuilder flat = new StringBuilder();
        Matcher m = PLACEHOLDER.matcher(inner);
        int last = 0;
        List<Component> parts = new ArrayList<>();
        while (m.find()) {
            String seg = inner.substring(last, m.start());
            flat.append(seg);
            if (!seg.isEmpty()) {
                parts.add(Text.parse(p, seg));
            }
            ConfigurationSection s = ph.getConfigurationSection(m.group(1));
            if (s == null) {
                flat.append(m.group(0));
                parts.add(Component.text(m.group(0)));
            } else {
                String text = s.getString("Text", m.group(1));
                flat.append(text);
                Component c = Text.parse(p, text);
                String hover = s.getString("Hover", "");
                if (!hover.isEmpty()) {
                    c = c.hoverEvent(HoverEvent.showText(Text.parse(p, hover)));
                }
                String ctype = s.getString("Click.Type", "");
                String content = s.getString("Click.Content", "");
                if (!ctype.isEmpty() && !content.isEmpty()) {
                    switch (ctype.toUpperCase(Locale.ROOT)) {
                        case "RUN_COMMAND":
                            c = c.clickEvent(ClickEvent.runCommand(content));
                            break;
                        case "SUGGEST_COMMAND":
                            c = c.clickEvent(ClickEvent.suggestCommand(content));
                            break;
                        case "OPEN_URL":
                            c = c.clickEvent(ClickEvent.openUrl(content));
                            break;
                        case "COPY_TO_CLIPBOARD":
                            c = c.clickEvent(ClickEvent.copyToClipboard(content));
                            break;
                        default:
                            break;
                    }
                }
                parts.add(c);
            }
            last = m.end();
        }
        String tail = inner.substring(last);
        flat.append(tail);
        if (!tail.isEmpty()) {
            parts.add(Text.parse(p, tail));
        }

        Component out = Component.empty();
        if (center) {
            String centered = Text.prepare("<center>" + flat + "</center>", p);
            int pad = 0;
            while (pad < centered.length() && centered.charAt(pad) == ' ') {
                pad++;
            }
            if (pad > 0) {
                out = out.append(Component.text(" ".repeat(pad)));
            }
        }
        for (Component c : parts) {
            out = out.append(c);
        }
        return out;
    }

    // ------------------------------------------------------------------ jugadores (toggle)

    private File playersFile() {
        return plugin.dataFile("anuncios/jugadores.yml");
    }

    private void loadPlayers() {
        off.clear();
        soundOff.clear();
        File f = playersFile();
        if (!f.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        for (String s : y.getStringList("anuncios-desactivados")) {
            try {
                off.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
        for (String s : y.getStringList("sonido-desactivado")) {
            try {
                soundOff.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void savePlayers() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> a = new ArrayList<>();
        for (UUID u : off) {
            a.add(u.toString());
        }
        List<String> b = new ArrayList<>();
        for (UUID u : soundOff) {
            b.add(u.toString());
        }
        y.set("anuncios-desactivados", a);
        y.set("sonido-desactivado", b);
        File f = playersFile();
        f.getParentFile().mkdirs();
        try {
            y.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("[Anuncios] No se pudo guardar jugadores.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ comando

    private String msg(String path) {
        return plugin.cfg("anuncios/mensajes.yml").getString(path, "");
    }

    private void say(CommandSender to, String path, String... kv) {
        String prefix = msg("messages.prefix");
        Text.send(to, prefix + " " + msg(path), kv);
    }

    private final class Cmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
            String sub = args.length == 0 ? "toggle" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reload":
                case "recargar":
                    if (!s.hasPermission("batmanchat.anuncios.reload") && !s.hasPermission("infiniteannouncements.reload")) {
                        say(s, "command.no-permission");
                        return true;
                    }
                    reloadSelf();
                    say(s, "command.reload");
                    return true;
                case "toggle":
                case "alternar":
                    if (!(s instanceof Player p)) {
                        say(s, "messages.only-players");
                        return true;
                    }
                    if (!p.hasPermission("batmanchat.anuncios.toggle") && !p.hasPermission("infiniteannouncements.toggle")) {
                        say(s, "command.no-permission");
                        return true;
                    }
                    if (off.remove(p.getUniqueId())) {
                        say(s, "command.toggle-on");
                    } else {
                        off.add(p.getUniqueId());
                        say(s, "command.toggle-off");
                    }
                    savePlayers();
                    return true;
                case "togglesound":
                case "sonido":
                    if (!(s instanceof Player p)) {
                        say(s, "messages.only-players");
                        return true;
                    }
                    if (!p.hasPermission("batmanchat.anuncios.toggle") && !p.hasPermission("infiniteannouncements.toggle")) {
                        say(s, "command.no-permission");
                        return true;
                    }
                    if (soundOff.remove(p.getUniqueId())) {
                        say(s, "command.toggle-sound-on");
                    } else {
                        soundOff.add(p.getUniqueId());
                        say(s, "command.toggle-sound-off");
                    }
                    savePlayers();
                    return true;
                case "preview":
                case "vista":
                    return adminPreview(s, args);
                case "send":
                case "enviar":
                    return adminSend(s, args);
                case "list":
                case "lista":
                    return adminList(s);
                default:
                    say(s, "messages.invalid-usage", "command", "/" + label + " <reload|toggle|togglesound|preview|list|send>");
                    return true;
            }
        }

        private boolean admin(CommandSender s) {
            if (s.hasPermission("batmanchat.anuncios.admin") || s.hasPermission("infiniteannouncements.admin")) {
                return true;
            }
            say(s, "command.no-permission");
            return false;
        }

        private boolean adminPreview(CommandSender s, String[] args) {
            if (!admin(s)) {
                return true;
            }
            if (!(s instanceof Player p)) {
                say(s, "messages.only-players");
                return true;
            }
            if (args.length < 2) {
                say(s, "messages.invalid-usage", "command", "/anuncios preview <id>");
                return true;
            }
            ConfigurationSection a = plugin.cfg("anuncios/anuncios.yml").getConfigurationSection("Announcements." + args[1]);
            if (a == null) {
                say(s, "messages.invalid-id", "id", args[1]);
                return true;
            }
            say(s, "command.preview", "id", args[1]);
            deliver(a, List.of(p), true);
            return true;
        }

        private boolean adminSend(CommandSender s, String[] args) {
            if (!admin(s)) {
                return true;
            }
            if (args.length < 2) {
                say(s, "messages.invalid-usage", "command", "/anuncios send <id>");
                return true;
            }
            ConfigurationSection a = plugin.cfg("anuncios/anuncios.yml").getConfigurationSection("Announcements." + args[1]);
            if (a == null) {
                say(s, "messages.invalid-id", "id", args[1]);
                return true;
            }
            deliver(a, new ArrayList<>(Bukkit.getOnlinePlayers()), true);
            say(s, "command.send", "id", args[1]);
            return true;
        }

        private boolean adminList(CommandSender s) {
            if (!admin(s)) {
                return true;
            }
            ConfigurationSection all = plugin.cfg("anuncios/anuncios.yml").getConfigurationSection("Announcements");
            List<String> ids = ids();
            say(s, "command.list-header", "total", String.valueOf(ids.size()));
            if (all == null) {
                return true;
            }
            for (String id : ids) {
                ConfigurationSection a = all.getConfigurationSection(id);
                if (a == null) {
                    continue;
                }
                Text.send(s, msg("command.list-line"),
                        "id", id,
                        "type", a.getString("type", "CHAT"),
                        "interval", String.valueOf(a.getInt("interval", 0)),
                        "lines", String.valueOf(a.getStringList("lines").size()));
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
            List<String> out = new ArrayList<>();
            if (args.length == 1) {
                for (String o : List.of("toggle", "togglesound", "reload", "preview", "list", "send")) {
                    if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                        out.add(o);
                    }
                }
            } else if (args.length == 2 && (args[0].equalsIgnoreCase("preview") || args[0].equalsIgnoreCase("send"))) {
                for (String id : ids()) {
                    if (id.startsWith(args[1])) {
                        out.add(id);
                    }
                }
            }
            return out;
        }
    }
}
