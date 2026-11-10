package me.batman.batmanchat.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.batman.batmanchat.AbstractFeature;
import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.SubCommand;
import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.protection.ProtectionFeature;
import me.batman.batmanchat.util.Display;
import me.batman.batmanchat.util.Hooks;
import me.batman.batmanchat.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mensajes privados (/msg, /r) e ignore (/ignore, /unignore, /ignoreall).
 * Config: chat/mensajes-privados.yml. Datos de ignore: chat/ignorados.yml.
 *
 *  - Los tokens de items-interactivos (!i, !inv, !ender, !pv 3, !ping, !dinero ...) funcionan dentro de /msg y /r:
 *    el que escribe los convierte en componentes con hover/clic y los ve tanto el emisor como el receptor.
 *  - /ignore <jugador>: no ves sus mensajes del chat ni sus mensajes privados (y el no puede escribirte por /msg).
 *  - /ignoreall: no ves el chat de NADIE ni mensajes privados (salvo quienes tengan batmanchat.ignore.exempt).
 *  - Si Reemplazar_Comandos_Existentes es true, tambien se intercepta el /msg, /r, /ignore ... de Essentials/vanilla.
 */
public final class PrivateMessagesFeature extends AbstractFeature {
    private static final String FILE = "chat/mensajes-privados.yml";
    private static final String EXEMPT = "batmanchat.ignore.exempt";
    private static final UUID CONSOLE = new UUID(0L, 0L);
    private static final Set<String> NAMESPACES = Set.of("essentials", "essentialsx", "minecraft", "batmanchat");

    /** quien le escribio por ultima vez a quien (para /r), en ambos sentidos. */
    private final Map<UUID, UUID> lastContact = new ConcurrentHashMap<>();
    private final Set<UUID> ignoreAll = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<UUID>> ignored = new ConcurrentHashMap<>();
    private final Set<UUID> spies = ConcurrentHashMap.newKeySet();

    private final Map<String, String> labels = new ConcurrentHashMap<>();
    private boolean active;

    public PrivateMessagesFeature(BatmanChat plugin) {
        super(plugin);
    }

    @Override
    public String id() {
        return "mensajes-privados";
    }

    private YamlConfiguration cfg() {
        return plugin.cfg(FILE);
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public void enable() {
        active = false;
        lastContact.clear();
        spies.clear();
        loadData();
        if (!cfg().getBoolean("Enable", true)) {
            return;
        }
        active = true;
        loadLabels();
        listen(new Events());
        Cmds cmds = new Cmds();
        command("msg", cmds);
        command("r", cmds);
        command("ignore", cmds);
        command("unignore", cmds);
        command("ignoreall", cmds);
        plugin.registerSub("spy", new SpySub());
    }

    @Override
    protected void onDisable() {
        plugin.unregisterSub("spy");
        if (active) {
            saveData(false);
        }
        active = false;
        lastContact.clear();
        spies.clear();
    }

    private void loadLabels() {
        labels.clear();
        put("msg", "Comandos.Msg", List.of("msg", "m", "w", "tell", "whisper", "pm", "mp", "t", "dm", "mensaje", "privado", "message"));
        put("r", "Comandos.Responder", List.of("r", "reply", "respuesta", "responder"));
        put("ignore", "Comandos.Ignorar", List.of("ignore", "ignorar"));
        put("unignore", "Comandos.Designorar", List.of("unignore", "designorar"));
        put("ignoreall", "Comandos.IgnorarTodos", List.of("ignoreall", "ignorartodos", "ignoretodos"));
    }

    private void put(String canonical, String path, List<String> def) {
        List<String> list = cfg().isList(path) ? cfg().getStringList(path) : def;
        for (String s : list) {
            String k = s.toLowerCase(Locale.ROOT).trim();
            if (k.startsWith("/")) {
                k = k.substring(1);
            }
            if (!k.isEmpty()) {
                labels.put(k, canonical);
            }
        }
        labels.put(canonical, canonical);
    }

    // ------------------------------------------------------------------ datos de ignore

    private File dataFile() {
        return plugin.dataFile("chat/ignorados.yml");
    }

    private void loadData() {
        ignoreAll.clear();
        ignored.clear();
        File f = dataFile();
        if (!f.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        for (String s : y.getStringList("ignoreall")) {
            UUID u = uuid(s);
            if (u != null) {
                ignoreAll.add(u);
            }
        }
        ConfigurationSection sec = y.getConfigurationSection("ignorados");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                UUID owner = uuid(key);
                if (owner == null) {
                    continue;
                }
                Set<UUID> set = ConcurrentHashMap.newKeySet();
                for (String s : sec.getStringList(key)) {
                    UUID u = uuid(s);
                    if (u != null) {
                        set.add(u);
                    }
                }
                if (!set.isEmpty()) {
                    ignored.put(owner, set);
                }
            }
        }
    }

    private static UUID uuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private synchronized void saveData(boolean async) {
        final YamlConfiguration y = new YamlConfiguration();
        List<String> all = new ArrayList<>();
        for (UUID u : ignoreAll) {
            all.add(u.toString());
        }
        y.set("ignoreall", all);
        for (Map.Entry<UUID, Set<UUID>> e : ignored.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            List<String> l = new ArrayList<>();
            for (UUID u : e.getValue()) {
                l.add(u.toString());
            }
            y.set("ignorados." + e.getKey(), l);
        }
        final File f = dataFile();
        Runnable write = () -> {
            synchronized (this) {
                f.getParentFile().mkdirs();
                try {
                    y.save(f);
                } catch (IOException ex) {
                    plugin.getLogger().warning("[MensajesPrivados] No se pudo guardar chat/ignorados.yml: " + ex.getMessage());
                }
            }
        };
        if (async) {
            try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
                return;
            } catch (RuntimeException ex) {
                // el plugin se esta apagando: se guarda directamente
            }
        }
        write.run();
    }

    // ------------------------------------------------------------------ API publica de ignore

    /**
     * true si "listener" NO debe ver lo que escribe "from" (chat publico, menciones y mensajes privados).
     * Los que tienen batmanchat.ignore.exempt (staff) no se pueden ignorar.
     */
    public boolean isIgnoring(Player listener, Player from) {
        if (!active || listener == null || from == null) {
            return false;
        }
        UUID l = listener.getUniqueId();
        if (l.equals(from.getUniqueId())) {
            return false;
        }
        if (Hooks.has(from, EXEMPT)) {
            return false;
        }
        if (ignoreAll.contains(l)) {
            return true;
        }
        Set<UUID> set = ignored.get(l);
        return set != null && set.contains(from.getUniqueId());
    }

    // ------------------------------------------------------------------ utilidades

    private static UUID id(CommandSender s) {
        return s instanceof Player p ? p.getUniqueId() : CONSOLE;
    }

    private String nameOf(CommandSender s) {
        if (s instanceof Player p) {
            return p.getName();
        }
        return cfg().getString("Nombre_Consola", "Consola");
    }

    private boolean isConsoleName(String n) {
        return n.equalsIgnoreCase("console") || n.equalsIgnoreCase("consola");
    }

    private String msg(String key) {
        return cfg().getString("Mensajes." + key, "");
    }

    private Player findPlayer(CommandSender viewer, String name) {
        Player found = Bukkit.getPlayerExact(name);
        if (found == null) {
            found = Bukkit.getPlayer(name);
        }
        if (found == null) {
            return null;
        }
        if (Hooks.vanished(found) && !Hooks.has(viewer, "batmanchat.vanish.see")) {
            return null;
        }
        return found;
    }

    private static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(args[i]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ /msg y /r

    private void doMsg(CommandSender sender, String[] args, boolean filter) {
        if (!Hooks.has(sender, "batmanchat.msg")) {
            Text.sendKey(sender, "Message.No_Permission");
            return;
        }
        if (args.length < 2) {
            Text.send(sender, msg("Uso_Msg"));
            return;
        }
        CommandSender target;
        if (isConsoleName(args[0])) {
            target = Bukkit.getConsoleSender();
        } else {
            target = findPlayer(sender, args[0]);
        }
        if (target == null) {
            Text.sendKey(sender, "Message.Player_Not_Found", "target", args[0]);
            return;
        }
        dispatch(sender, target, join(args, 1), filter);
    }

    private void doReply(CommandSender sender, String[] args, boolean filter) {
        if (!Hooks.has(sender, "batmanchat.msg")) {
            Text.sendKey(sender, "Message.No_Permission");
            return;
        }
        if (args.length < 1) {
            Text.send(sender, msg("Uso_Reply"));
            return;
        }
        UUID last = lastContact.get(id(sender));
        if (last == null) {
            Text.send(sender, msg("Nadie_Que_Responder"));
            return;
        }
        CommandSender target;
        if (last.equals(CONSOLE)) {
            target = Bukkit.getConsoleSender();
        } else {
            Player p = Bukkit.getPlayer(last);
            target = (p == null || (Hooks.vanished(p) && !Hooks.has(sender, "batmanchat.vanish.see"))) ? null : p;
        }
        if (target == null) {
            Text.send(sender, msg("Desconectado"));
            return;
        }
        dispatch(sender, target, join(args, 0), filter);
    }

    /** Comprueba ignores, convierte los tokens (!i, !pv 3, ...) y entrega el mensaje. Hilo principal. */
    private void dispatch(CommandSender from, CommandSender to, String rawText, boolean filter) {
        String text = rawText;
        if (id(from).equals(id(to))) {
            Text.send(from, msg("A_Ti_Mismo"));
            return;
        }
        if (from instanceof Player fp && to instanceof Player tp) {
            if (ignoreAll.contains(fp.getUniqueId()) && !Hooks.has(tp, EXEMPT)) {
                Text.send(from, msg("Tienes_Ignoreall"));
                return;
            }
            if (isIgnoring(fp, tp)) {
                Text.send(from, msg("Lo_Ignoras"), "target", tp.getName());
                return;
            }
            if (isIgnoring(tp, fp)) {
                if (cfg().getBoolean("Avisar_Si_Te_Ignoran", true)) {
                    Text.send(from, msg("Te_Ignora"), "target", tp.getName());
                }
                return;
            }
        }

        // protecciones (filtro de palabras, links, spam, cooldown...): las mismas reglas que el chat publico
        if (filter && from instanceof Player fp && plugin.isRunning(ProtectionFeature.class)) {
            ProtectionFeature pf = plugin.feature(ProtectionFeature.class);
            Check c = pf == null ? null : pf.filterPrivate(fp, text);
            if (c != null) {
                if (c.blocked) {
                    return;
                }
                if (c.modified && !c.text.isBlank()) {
                    text = c.text;
                }
            }
        }
        final String body = text;

        String prefix = plugin.cfg("chat/items-interactivos.yml").getString("Prefijo", "!");
        InteractiveTokensFeature itf = plugin.isRunning(InteractiveTokensFeature.class)
                ? plugin.feature(InteractiveTokensFeature.class) : null;
        if (from instanceof Player fp && itf != null && body.contains(prefix)) {
            // se procesa fuera del hilo principal: asi tambien funciona !pv (leer el vault necesita esperar)
            final InteractiveTokensFeature tokens = itf;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                Component built;
                try {
                    built = tokens.process(fp, body, s -> Component.text(s));
                } catch (RuntimeException ex) {
                    built = null;
                }
                final Component msgc = built == null ? Component.text(body) : built;
                Bukkit.getScheduler().runTask(plugin, () -> deliver(from, to, msgc));
            });
            return;
        }
        deliver(from, to, Component.text(body));
    }

    /** Entrega el mensaje (hilo principal). */
    private void deliver(CommandSender from, CommandSender to, Component message) {
        YamlConfiguration y = cfg();
        if (from instanceof Player fp && !fp.isOnline()) {
            return;
        }
        if (to instanceof Player tp) {
            if (!tp.isOnline()) {
                Text.send(from, msg("Desconectado"));
                return;
            }
            // el estado pudo cambiar mientras se procesaban los tokens
            if (from instanceof Player fp) {
                if (isIgnoring(tp, fp)) {
                    if (y.getBoolean("Avisar_Si_Te_Ignoran", true)) {
                        Text.send(from, msg("Te_Ignora"), "target", tp.getName());
                    }
                    return;
                }
            }
        }

        from.sendMessage(render(y.getString("Formato.Enviado", "&8[&7Yo &8» &f{target}&8] &7{message}"), from, to, message, false));
        to.sendMessage(render(y.getString("Formato.Recibido", "&8[&f{player} &8» &7Yo&8] &7{message}"), from, to, message, true));

        lastContact.put(id(from), id(to));
        lastContact.put(id(to), id(from));

        if (to instanceof Player tp && y.getBoolean("Sonido.toggle", true)) {
            Display.sound(tp, y.getString("Sonido.value", ""), (float) y.getDouble("Sonido.volume", 1.0),
                    (float) y.getDouble("Sonido.pitch", 1.0));
        }

        // social spy + registro en consola (solo si ninguno de los dos es la consola)
        if (from instanceof Player && to instanceof Player) {
            Component spy = render(y.getString("Formato.Spy", "&8[&cSPY&8] &7{player} &8» &7{target}&8: &7{message}"), from, to, message, false);
            for (UUID u : spies) {
                Player s = Bukkit.getPlayer(u);
                if (s != null && !u.equals(id(from)) && !u.equals(id(to)) && Hooks.has(s, "batmanchat.msg.spy")) {
                    s.sendMessage(spy);
                }
            }
            if (y.getBoolean("Log_Consola", true)) {
                Bukkit.getConsoleSender().sendMessage(spy);
            }
        }
    }

    private Component render(String fmt, CommandSender from, CommandSender to, Component message, boolean received) {
        String raw = Text.vars(fmt, "player", nameOf(from), "target", nameOf(to));
        Player papiFor = from instanceof Player p ? p : null;
        Component base = Text.parse(papiFor, raw);
        YamlConfiguration y = cfg();
        if (received && from instanceof Player && y.getBoolean("Responder_Con_Clic", true)) {
            base = base.clickEvent(ClickEvent.suggestCommand("/msg " + nameOf(from) + " "))
                    .hoverEvent(HoverEvent.showText(Text.parse(Text.vars(y.getString("Hover_Responder", "&eClic para responder"),
                            "player", nameOf(from)))));
        }
        final Component m = message;
        return base.replaceText(b -> b.matchLiteral("{message}").once().replacement(m));
    }

    // ------------------------------------------------------------------ /ignore, /unignore, /ignoreall

    private void doIgnore(CommandSender sender, String[] args, boolean removeOnly) {
        if (!(sender instanceof Player p)) {
            Text.sendKey(sender, "Message.Only_Players");
            return;
        }
        if (!Hooks.has(p, "batmanchat.ignore")) {
            Text.sendKey(p, "Message.No_Permission");
            return;
        }
        if (args.length == 0) {
            Text.send(p, msg(removeOnly ? "Uso_Unignore" : "Uso_Ignore"));
            return;
        }
        if (!removeOnly && (args[0].equalsIgnoreCase("list") || args[0].equalsIgnoreCase("lista"))) {
            Set<UUID> set = ignored.get(p.getUniqueId());
            if (set == null || set.isEmpty()) {
                Text.send(p, msg("Lista_Vacia"));
                return;
            }
            Text.send(p, msg("Lista_Titulo"));
            for (UUID u : set) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(u);
                String n = op.getName() == null ? u.toString() : op.getName();
                Text.send(p, msg("Lista_Linea"), "target", n);
            }
            return;
        }
        String name = args[0];
        Player online = Bukkit.getPlayerExact(name);
        if (online == null) {
            online = Bukkit.getPlayer(name);
        }
        UUID target;
        String shown;
        if (online != null) {
            target = online.getUniqueId();
            shown = online.getName();
        } else {
            OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
            if (off == null) {
                Text.sendKey(p, "Message.Player_Not_Found", "target", name);
                return;
            }
            target = off.getUniqueId();
            shown = off.getName() == null ? name : off.getName();
        }
        if (target.equals(p.getUniqueId())) {
            Text.send(p, msg("Ignorarte_A_Ti"));
            return;
        }
        Set<UUID> set = ignored.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
        if (set.contains(target)) {
            set.remove(target);
            Text.send(p, msg("Ignore_Quitado"), "target", shown);
            saveData(true);
            return;
        }
        if (removeOnly) {
            Text.send(p, msg("No_Ignorado"), "target", shown);
            return;
        }
        if (online != null && Hooks.has(online, EXEMPT)) {
            Text.send(p, msg("No_Se_Puede_Ignorar"), "target", shown);
            return;
        }
        set.add(target);
        Text.send(p, msg("Ignore_Activado"), "target", shown);
        saveData(true);
    }

    private void doIgnoreAll(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            Text.sendKey(sender, "Message.Only_Players");
            return;
        }
        if (!Hooks.has(p, "batmanchat.ignoreall")) {
            Text.sendKey(p, "Message.No_Permission");
            return;
        }
        if (ignoreAll.remove(p.getUniqueId())) {
            Text.send(p, msg("Ignoreall_Desactivado"));
        } else {
            ignoreAll.add(p.getUniqueId());
            Text.send(p, msg("Ignoreall_Activado"));
        }
        saveData(true);
    }

    /** {comando canonico, resto} si el mensaje ("/msg ...") es uno de los comandos de este modulo; si no, null. */
    private String[] split(String m) {
        if (m == null || m.length() < 2 || m.charAt(0) != '/') {
            return null;
        }
        String body = m.substring(1).trim();
        int sp = body.indexOf(' ');
        String label = (sp < 0 ? body : body.substring(0, sp)).toLowerCase(Locale.ROOT);
        String rest = sp < 0 ? "" : body.substring(sp + 1).trim();
        int colon = label.indexOf(':');
        if (colon >= 0) {
            if (!NAMESPACES.contains(label.substring(0, colon))) {
                return null;
            }
            label = label.substring(colon + 1);
        }
        String canonical = labels.get(label);
        return canonical == null ? null : new String[]{canonical, rest};
    }

    /**
     * true si BatmanChat atiende este comando como mensaje privado (/msg o /r). En ese caso es este modulo quien lo
     * pasa por las protecciones (con las reglas del chat) y ProtectionFeature no lo revisa otra vez.
     */
    public boolean filtersCommand(String message) {
        if (!active || !cfg().getBoolean("Reemplazar_Comandos_Existentes", true)) {
            return false;
        }
        String[] parts = split(message);
        return parts != null && (parts[0].equals("msg") || parts[0].equals("r"));
    }

    // ------------------------------------------------------------------ comandos

    private void run(String canonical, CommandSender sender, String[] args, boolean filter) {
        switch (canonical) {
            case "msg":
                doMsg(sender, args, filter);
                break;
            case "r":
                doReply(sender, args, filter);
                break;
            case "ignore":
                doIgnore(sender, args, false);
                break;
            case "unignore":
                doIgnore(sender, args, true);
                break;
            case "ignoreall":
                doIgnoreAll(sender);
                break;
            default:
                break;
        }
    }

    private final class Cmds implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            run(command.getName().toLowerCase(Locale.ROOT), sender, args, false);
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            String name = command.getName().toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            boolean names = args.length == 1 && (name.equals("msg") || name.equals("ignore") || name.equals("unignore"));
            if (!names) {
                return out;
            }
            String prefix = args[0].toLowerCase(Locale.ROOT);
            if (name.equals("ignore") && "list".startsWith(prefix)) {
                out.add("list");
            }
            if (name.equals("unignore") && sender instanceof Player p) {
                Set<UUID> set = ignored.get(p.getUniqueId());
                if (set != null) {
                    for (UUID u : set) {
                        String n = Bukkit.getOfflinePlayer(u).getName();
                        if (n != null && n.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                            out.add(n);
                        }
                    }
                }
                return out;
            }
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (Hooks.vanished(o) && !Hooks.has(sender, "batmanchat.vanish.see")) {
                    continue;
                }
                if (o.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(o.getName());
                }
            }
            return out;
        }
    }

    private final class SpySub implements SubCommand {
        @Override
        public String permission() {
            return "batmanchat.msg.spy";
        }

        @Override
        public boolean run(CommandSender sender, String[] args) {
            if (!(sender instanceof Player p)) {
                Text.sendKey(sender, "Message.Only_Players");
                return true;
            }
            if (spies.remove(p.getUniqueId())) {
                Text.send(p, msg("Spy_Desactivado"));
            } else {
                spies.add(p.getUniqueId());
                Text.send(p, msg("Spy_Activado"));
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ eventos

    private final class Events implements Listener {
        /** Quien te ignora (o tiene /ignoreall) no recibe tu mensaje del chat publico. */
        @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
        public void onChat(AsyncChatEvent e) {
            final Player sender = e.getPlayer();
            e.viewers().removeIf(a -> a instanceof Player v && isIgnoring(v, sender));
        }

        /** Reemplaza /msg, /r, /ignore... de Essentials / vanilla por los de BatmanChat. */
        @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
        public void onCommand(PlayerCommandPreprocessEvent e) {
            if (!cfg().getBoolean("Reemplazar_Comandos_Existentes", true)) {
                return;
            }
            String[] parts = split(e.getMessage());
            if (parts == null) {
                return;
            }
            e.setCancelled(true);
            String rest = parts[1];
            String[] args = rest.isEmpty() ? new String[0] : rest.split("\\s+");
            PrivateMessagesFeature.this.run(parts[0], e.getPlayer(), args, true);
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            UUID id = e.getPlayer().getUniqueId();
            lastContact.remove(id);
            spies.remove(id);
        }
    }
}
