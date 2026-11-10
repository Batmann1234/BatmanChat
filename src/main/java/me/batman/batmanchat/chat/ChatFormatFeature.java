package me.batman.batmanchat.chat;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import me.batman.batmanchat.AbstractFeature;
import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.SubCommand;
import me.batman.batmanchat.util.Display;
import me.batman.batmanchat.util.Hooks;
import me.batman.batmanchat.util.Ranks;
import me.batman.batmanchat.util.Text;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formato del chat por rango, colores por permiso, emojis, menciones, hover sobre el nombre, retraso por rango
 * y respuestas automaticas (antes ChatManager). Config: chat/formato.yml, emojis.yml, menciones.yml,
 * respuestas-automaticas.yml y items-interactivos.yml.
 */
public final class ChatFormatFeature extends AbstractFeature {
    private static final Pattern CODE = Pattern.compile("&([0-9a-fA-Fk-oK-OrR])");
    private static final Pattern HEX = Pattern.compile("(?i)&#[0-9A-F]{6}|(?<!&)#[0-9A-F]{6}");
    private static final Pattern GRADIENT_TAG = Pattern.compile("(?i)</?gradient[^>]*>");
    private static final Pattern CENTER_TAG = Pattern.compile("(?i)</?center>");
    private static final Pattern ACCENTS = Pattern.compile("\\p{M}+");

    private final Map<UUID, Long> lastMessage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastMention = new ConcurrentHashMap<>();
    private final Map<String, Long> lastAuto = new ConcurrentHashMap<>();
    private final Set<UUID> mentionsOff = ConcurrentHashMap.newKeySet();

    public ChatFormatFeature(BatmanChat plugin) {
        super(plugin);
    }

    @Override
    public String id() {
        return "formato-chat";
    }

    @Override
    public void enable() {
        lastMessage.clear();
        lastMention.clear();
        lastAuto.clear();
        loadPlayers();
        listen(new Events());
        plugin.registerSub("menciones", new MentionToggle());
    }

    @Override
    protected void onDisable() {
        plugin.unregisterSub("menciones");
    }

    private YamlConfiguration fmt() {
        return plugin.cfg("chat/formato.yml");
    }

    private YamlConfiguration mentions() {
        return plugin.cfg("chat/menciones.yml");
    }

    // ------------------------------------------------------------------ jugadores (avisos de menciones)

    private File playersFile() {
        return plugin.dataFile("chat/jugadores.yml");
    }

    private void loadPlayers() {
        mentionsOff.clear();
        File f = playersFile();
        if (!f.exists()) {
            return;
        }
        for (String s : YamlConfiguration.loadConfiguration(f).getStringList("menciones-desactivadas")) {
            try {
                mentionsOff.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void savePlayers() {
        List<String> l = new ArrayList<>();
        for (UUID u : mentionsOff) {
            l.add(u.toString());
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("menciones-desactivadas", l);
        File f = playersFile();
        f.getParentFile().mkdirs();
        try {
            y.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("[Chat] No se pudo guardar chat/jugadores.yml: " + e.getMessage());
        }
    }

    private final class MentionToggle implements SubCommand {
        @Override
        public String permission() {
            return "batmanchat.menciones";
        }

        @Override
        public boolean run(CommandSender sender, String[] args) {
            if (!(sender instanceof Player p)) {
                Text.sendKey(sender, "Message.Only_Players");
                return true;
            }
            if (mentionsOff.remove(p.getUniqueId())) {
                Text.send(p, mentions().getString("Mensajes.Activadas", ""));
            } else {
                mentionsOff.add(p.getUniqueId());
                Text.send(p, mentions().getString("Mensajes.Desactivadas", ""));
            }
            savePlayers();
            return true;
        }
    }

    // ------------------------------------------------------------------ formato

    private String formatFor(Player p) {
        YamlConfiguration y = fmt();
        ConfigurationSection groups = y.getConfigurationSection("Groups");
        if (groups != null) {
            String primary = Hooks.primaryGroup(p);
            if (primary != null) {
                for (String key : groups.getKeys(false)) {
                    if (key.equalsIgnoreCase(primary)) {
                        return groups.getString(key + ".Format", "");
                    }
                }
            }
            String found = null;
            for (String key : groups.getKeys(false)) {
                if (Hooks.inGroup(p, key)) {
                    found = groups.getString(key + ".Format", "");
                }
            }
            if (found != null) {
                return found;
            }
        }
        return y.getString("Default_Format", "&7%player_name% &8» &7{message}");
    }

    private Component nameHover(Player p, Component base) {
        YamlConfiguration y = fmt();
        if (!y.getBoolean("Hover_Nombre.Enable", false)) {
            return base;
        }
        Component hover = Component.empty();
        List<String> lines = y.getStringList("Hover_Nombre.Lineas");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                hover = hover.append(Component.newline());
            }
            hover = hover.append(Text.parse(p, Text.vars(lines.get(i), "player", p.getName())));
        }
        final Component fh = hover;
        String type = y.getString("Hover_Nombre.Clic.Tipo", "NONE").toUpperCase(Locale.ROOT);
        String content = Text.vars(y.getString("Hover_Nombre.Clic.Contenido", ""), "player", p.getName());
        final ClickEvent click;
        switch (type) {
            case "SUGGEST_COMMAND":
                click = ClickEvent.suggestCommand(content);
                break;
            case "RUN_COMMAND":
                click = ClickEvent.runCommand(content);
                break;
            case "COPY_TO_CLIPBOARD":
                click = ClickEvent.copyToClipboard(content);
                break;
            case "OPEN_URL":
                click = ClickEvent.openUrl(content);
                break;
            default:
                click = null;
                break;
        }
        return base.replaceText(b -> b.matchLiteral(p.getName()).once().replacement((r, tb) -> {
            tb.hoverEvent(HoverEvent.showText(fh));
            if (click != null) {
                tb.clickEvent(click);
            }
            return tb;
        }));
    }

    // ------------------------------------------------------------------ mensaje del jugador

    private static boolean color(Player p, char c) {
        return Hooks.has(p, "batmanchat.color.all", "batmanchat.color." + c,
                "chatmanager.color.all", "chatmanager.color." + c);
    }

    private static boolean format(Player p, char c) {
        return Hooks.has(p, "batmanchat.format.all", "batmanchat.format." + c,
                "chatmanager.format.all", "chatmanager.format." + c);
    }

    private Component messageComponent(Player p, String seg) {
        if (!fmt().getBoolean("Colores_En_Mensajes", false)) {
            return Component.text(seg);
        }
        String s = CENTER_TAG.matcher(seg).replaceAll("");
        Matcher m = CODE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            char c = Character.toLowerCase(m.group(1).charAt(0));
            boolean ok;
            if (c >= 'k' && c <= 'o') {
                ok = format(p, c);
            } else if (c == 'r') {
                ok = true;
            } else {
                ok = color(p, c);
            }
            m.appendReplacement(sb, ok ? Matcher.quoteReplacement(m.group()) : "");
        }
        m.appendTail(sb);
        s = sb.toString();
        if (!Hooks.has(p, "batmanchat.color.hex", "chatmanager.color.hex", "batmanchat.color.all", "chatmanager.color.all")) {
            s = HEX.matcher(s).replaceAll("");
        }
        if (!Hooks.has(p, "batmanchat.color.gradient", "batmanchat.color.all")) {
            s = GRADIENT_TAG.matcher(s).replaceAll("");
        }
        return Text.parse(s);
    }

    private Component applyEmojis(Player p, Component msg, String plain) {
        YamlConfiguration y = plugin.cfg("chat/emojis.yml");
        if (!y.getBoolean("Enable", true) || plain.indexOf(':') < 0) {
            return msg;
        }
        ConfigurationSection emojis = y.getConfigurationSection("Emojis");
        if (emojis == null) {
            return msg;
        }
        boolean perEmoji = y.getBoolean("Permiso_Por_Emoji", false);
        if (!Hooks.has(p, "batmanchat.emojis")) {
            return msg;
        }
        String lower = plain.toLowerCase(Locale.ROOT);
        for (String key : emojis.getKeys(false)) {
            if (!lower.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (perEmoji && !p.hasPermission("batmanchat.emojis." + key.replace(":", "").toLowerCase(Locale.ROOT))) {
                continue;
            }
            final Component rep = Text.parse(emojis.getString(key, key));
            msg = msg.replaceText(b -> b.match(Pattern.compile(Pattern.quote(key), Pattern.CASE_INSENSITIVE)).replacement(rep));
        }
        return msg;
    }

    private static Pattern mentionPattern(String name, boolean requireAt) {
        return Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])" + (requireAt ? "@" : "@?") + Pattern.quote(name)
                + "(?![\\p{L}\\p{N}_])");
    }

    private static String normalize(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return ACCENTS.matcher(n).replaceAll("");
    }

    // ------------------------------------------------------------------ eventos

    private final class Events implements Listener {
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onChat(AsyncChatEvent e) {
            YamlConfiguration y = fmt();
            Player p = e.getPlayer();
            long now = System.currentTimeMillis();

            // ---- retraso por rango
            if (y.getBoolean("Retraso_Chat.Enable", false) && !p.hasPermission("batmanchat.delay.bypass")) {
                double secs = Ranks.min(p, y.getConfigurationSection("Retraso_Chat.Segundos"), 0);
                Long last = lastMessage.get(p.getUniqueId());
                if (secs > 0 && last != null && now - last < secs * 1000) {
                    long left = (long) Math.ceil((secs * 1000 - (now - last)) / 1000.0);
                    e.setCancelled(true);
                    Text.send(p, y.getString("Retraso_Chat.Mensaje", ""), "segundos", String.valueOf(left));
                    return;
                }
            }
            lastMessage.put(p.getUniqueId(), now);

            if (!y.getBoolean("Enable", true)) {
                return;
            }

            final String plain = Text.plain(e.message());

            // ---- mensaje: items interactivos / colores / emojis
            Component msg = null;
            if (plugin.isRunning(InteractiveTokensFeature.class)) {
                InteractiveTokensFeature itf = plugin.feature(InteractiveTokensFeature.class);
                if (itf != null) {
                    msg = itf.process(p, plain, seg -> messageComponent(p, seg));
                }
            }
            if (msg == null) {
                msg = messageComponent(p, plain);
            }
            msg = applyEmojis(p, msg, plain);

            // ---- menciones
            YamlConfiguration my = mentions();
            final Set<UUID> mentioned = new HashSet<>();
            boolean everyone = false;
            if (my.getBoolean("Enable", true) && Hooks.has(p, "batmanchat.menciones")) {
                boolean requireAt = !my.getBoolean("Detectar_Sin_Arroba", true);
                boolean self = my.getBoolean("Ignorar_Autoreferencia", true);
                for (Player o : Bukkit.getOnlinePlayers()) {
                    if (self && o.getUniqueId().equals(p.getUniqueId())) {
                        continue;
                    }
                    if (Hooks.vanished(o) && !p.hasPermission("batmanchat.vanish.see")) {
                        continue;
                    }
                    if (mentionPattern(o.getName(), requireAt).matcher(plain).find()) {
                        mentioned.add(o.getUniqueId());
                    }
                }
                if (my.getBoolean("Todos.Enable", true) && Hooks.has(p, "batmanchat.menciones.todos")) {
                    String lower = plain.toLowerCase(Locale.ROOT);
                    for (String w : my.getStringList("Todos.Palabras")) {
                        if (lower.contains(w.toLowerCase(Locale.ROOT))) {
                            everyone = true;
                            final String word = w;
                            msg = msg.replaceText(b -> b.match(Pattern.compile(Pattern.quote(word), Pattern.CASE_INSENSITIVE))
                                    .replacement(Text.parse(Text.vars(my.getString("Todos.Formato_Resaltado", "{palabra}"), "palabra", word))));
                        }
                    }
                }
            }

            // ---- formato por rango
            Component base = Text.parse(p, formatFor(p));
            base = nameHover(p, base);
            final Component basef = base;
            final Component msgf = msg;
            final Component normal = base.replaceText(b -> b.matchLiteral("{message}").once().replacement(msgf));
            final String highlight = my.getString("Formato_Resaltado", "&e@{mentioned}");
            e.renderer((ChatRenderer) (source, name, message, viewer) -> {
                if (viewer instanceof Player v && mentioned.contains(v.getUniqueId()) && !mentionsOff.contains(v.getUniqueId())) {
                    Component hl = msgf.replaceText(b -> b.match(mentionPattern(v.getName(), false))
                            .replacement(Text.parse(Text.vars(highlight, "mentioned", v.getName()))));
                    return basef.replaceText(b -> b.matchLiteral("{message}").once().replacement(hl));
                }
                return normal;
            });

            // ---- efectos despues de enviar (hilo principal)
            final boolean everyoneF = everyone;
            final List<Player> viewers = new ArrayList<>();
            if (y.getBoolean("Sonido_Al_Recibir_Mensaje.toggle", false)) {
                for (Audience a : e.viewers()) {
                    if (a instanceof Player v && !v.getUniqueId().equals(p.getUniqueId())) {
                        viewers.add(v);
                    }
                }
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!viewers.isEmpty()) {
                    Display.sound(viewers, y.getString("Sonido_Al_Recibir_Mensaje.value", ""),
                            (float) y.getDouble("Sonido_Al_Recibir_Mensaje.volume", 1.0),
                            (float) y.getDouble("Sonido_Al_Recibir_Mensaje.pitch", 1.0));
                }
                notifyMentions(p, mentioned, everyoneF);
            });

            autoReplies(p, plain);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            lastMessage.remove(e.getPlayer().getUniqueId());
            lastMention.remove(e.getPlayer().getUniqueId());
        }
    }

    // ------------------------------------------------------------------ avisos de mencion

    private void notifyMentions(Player from, Set<UUID> mentioned, boolean everyone) {
        YamlConfiguration y = mentions();
        List<Player> targets = new ArrayList<>();
        if (everyone) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.getUniqueId().equals(from.getUniqueId())) {
                    targets.add(o);
                }
            }
        } else {
            for (UUID u : mentioned) {
                Player o = Bukkit.getPlayer(u);
                if (o != null) {
                    targets.add(o);
                }
            }
        }
        long now = System.currentTimeMillis();
        long cd = (long) (y.getDouble("Cooldown_Aviso", 3) * 1000);
        for (Player o : targets) {
            if (mentionsOff.contains(o.getUniqueId())) {
                continue;
            }
            Long last = lastMention.get(o.getUniqueId());
            if (last != null && now - last < cd) {
                continue;
            }
            lastMention.put(o.getUniqueId(), now);
            if (y.getBoolean("Sonido.toggle", true)) {
                Display.sound(o, y.getString("Sonido.value", ""), (float) y.getDouble("Sonido.volume", 1.0),
                        (float) y.getDouble("Sonido.pitch", 1.0));
            }
            if (y.getBoolean("Actionbar.Enable", false)) {
                Display.actionbar(o, Text.parse(o, Text.vars(y.getString("Actionbar.Mensaje", ""),
                        "player", from.getName(), "mentioned", o.getName())));
            }
            if (y.getBoolean("Title.Enable", false)) {
                Display.title(o,
                        Text.parse(o, Text.vars(y.getString("Title.Titulo", ""), "player", from.getName(), "mentioned", o.getName())),
                        Text.parse(o, Text.vars(y.getString("Title.Subtitulo", ""), "player", from.getName(), "mentioned", o.getName())),
                        y.getInt("Title.Fade_In", 5), y.getInt("Title.Stay", 40), y.getInt("Title.Fade_Out", 10));
            }
        }
    }

    // ------------------------------------------------------------------ respuestas automaticas

    private void autoReplies(Player p, String plain) {
        YamlConfiguration y = plugin.cfg("chat/respuestas-automaticas.yml");
        if (!y.getBoolean("Enable", true)) {
            return;
        }
        ConfigurationSection all = y.getConfigurationSection("Respuestas");
        if (all == null) {
            return;
        }
        String norm = normalize(plain);
        long now = System.currentTimeMillis();
        for (String id : all.getKeys(false)) {
            ConfigurationSection r = all.getConfigurationSection(id);
            if (r == null) {
                continue;
            }
            boolean hit = false;
            for (String phrase : r.getStringList("Contiene")) {
                if (!phrase.isBlank() && norm.contains(normalize(phrase))) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                for (String rx : r.getStringList("Regex")) {
                    try {
                        if (Pattern.compile(rx, Pattern.CASE_INSENSITIVE).matcher(plain).find()) {
                            hit = true;
                            break;
                        }
                    } catch (Exception ignored) {
                        // regex invalida: se ignora
                    }
                }
            }
            if (!hit) {
                continue;
            }
            String key = p.getUniqueId() + ":" + id;
            Long last = lastAuto.get(key);
            if (last != null && now - last < r.getLong("Cooldown", 30) * 1000L) {
                continue;
            }
            lastAuto.put(key, now);
            final List<String> lines = r.getStringList("Respuesta");
            long delay = Math.max(1, y.getLong("Retraso_Ticks", 5));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline()) {
                    return;
                }
                for (String l : lines) {
                    Text.send(p, l, "player", p.getName());
                }
            }, delay);
        }
    }
}
