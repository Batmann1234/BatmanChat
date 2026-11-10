package me.batman.batmanchat.protection;

import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.util.Display;
import me.batman.batmanchat.util.Hooks;
import me.batman.batmanchat.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centro de las protecciones: registro de infracciones (DB), avisos a admins, advertencias,
 * auto-castigos, notificaciones a Discord, command spy, lockdown y comandos.
 */
public final class ViolationService {
    private static final class W {
        int count;
        long last;
    }

    private final BatmanChat plugin;
    private Database db;
    private DiscordNotifier discord;
    private YamlConfiguration main;
    private YamlConfiguration notifier;
    private YamlConfiguration punisher;

    /** uuid -> (modulo o "manual") -> advertencias. Todo acceso dentro de synchronized(warns). */
    private final Map<UUID, Map<String, W>> warns = new HashMap<>();
    private final Set<UUID> tvnOff = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spyOff = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String[]> modified = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private BukkitTask expiryTask;
    private CommandSpy spy;
    private Lockdown lockdown;
    private ProtectionCommands commands;

    public ViolationService(BatmanChat plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ ciclo de vida

    public void start() {
        main = plugin.cfg("proteccion/config.yml");
        notifier = plugin.cfg("proteccion/modulos/admin-notifier.yml");
        punisher = plugin.cfg("proteccion/modulos/auto-punisher.yml");
        discord = new DiscordNotifier(plugin);
        db = new Database(plugin);
        try {
            db.open();
            synchronized (warns) {
                for (Database.WarnRow r : db.loadWarnings()) {
                    W w = new W();
                    w.count = r.count;
                    w.last = r.last;
                    warns.computeIfAbsent(r.uuid, k -> new HashMap<>()).put(r.manual ? "manual" : r.module, w);
                }
            }
            tvnOff.addAll(db.loadPrefOff("tvn"));
            spyOff.addAll(db.loadPrefOff("commandspy"));
            db.cleanOlderThan(main.getInt("clean-logs-older-than", 30));
        } catch (Exception e) {
            plugin.getLogger().severe("[Proteccion] No se pudo abrir la base de datos (se desactivan registros y advertencias): " + e);
            if (db != null) {
                db.close();
            }
            db = null;
        }

        Listener l = new ServiceListener();
        Bukkit.getPluginManager().registerEvents(l, plugin);
        listeners.add(l);

        spy = new CommandSpy(this, plugin);
        spy.start();
        lockdown = new Lockdown(plugin);
        lockdown.start();
        commands = new ProtectionCommands(this, plugin, lockdown);
        commands.start();

        if (db != null && main.getBoolean("auto-punisher.enabled", false)) {
            double hours = Math.max(0.1, punisher.getDouble("check-expiry-interval", 12));
            long ticks = (long) (hours * 72000L);
            expiryTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::expireWarnings, 1200L, ticks);
        }
    }

    public void stop() {
        if (expiryTask != null) {
            expiryTask.cancel();
        }
        for (Listener l : listeners) {
            HandlerList.unregisterAll(l);
        }
        listeners.clear();
        if (spy != null) {
            spy.stop();
        }
        if (lockdown != null) {
            lockdown.stop();
        }
        if (commands != null) {
            commands.stop();
        }
        if (db != null) {
            db.close();
            db = null;
        }
    }

    YamlConfiguration main() {
        return main;
    }

    boolean hasDb() {
        return db != null;
    }

    // ------------------------------------------------------------------ API para los modulos

    /** Thread-safe: se puede llamar desde el hilo async del chat. */
    public void report(Player player, String moduleId, String context, String typeKey, String blockedContent,
                       String entireMessage, boolean warn, boolean notify, boolean log) {
        String name = player == null ? "-" : player.getName();
        UUID uuid = player == null ? null : player.getUniqueId();
        if (log && db != null && main.getBoolean("enable-violations-log", false)
                && main.getBoolean("enable-logging-for." + moduleId, false)) {
            db.logViolation(uuid, name, moduleId, context, blockedContent, entireMessage);
        }
        if (notify && main.getBoolean("admin-notifier.enabled", false)
                && notifier.getBoolean("enable-notifications-for." + moduleId, false)) {
            notifyAdmins(player, context, typeKey, blockedContent, entireMessage, null);
        }
        discord.moduleTrigger(name, moduleId, context, blockedContent, entireMessage);
        if (warn && player != null) {
            addAutoWarning(player, moduleId, blockedContent != null ? blockedContent : entireMessage);
        }
    }

    public void reportCustom(Player playerOrNull, String moduleId, String context, String typeKey, String notification, boolean warn) {
        if (main.getBoolean("admin-notifier.enabled", false)
                && notifier.getBoolean("enable-notifications-for." + moduleId, true)) {
            notifyAdmins(playerOrNull, context, typeKey, null, null, notification);
        }
        if (warn && playerOrNull != null) {
            addAutoWarning(playerOrNull, moduleId, notification);
        }
    }

    public void noteCommandModified(Player player, String originalCommand, String newCommand) {
        modified.put(player.getUniqueId(), new String[]{originalCommand, newCommand, String.valueOf(System.currentTimeMillis())});
    }

    /** Devuelve {original, nuevo} si hay un cambio reciente (menos de 5 s) para ese jugador y lo borra. */
    String[] takeModified(UUID uuid) {
        String[] m = modified.remove(uuid);
        if (m == null || System.currentTimeMillis() - Long.parseLong(m[2]) > 5000) {
            return null;
        }
        return m;
    }

    // ------------------------------------------------------------------ avisos a admins

    private boolean canSeeNotifications(Player a) {
        return (a.isOp() || a.hasPermission("batmanchat.proteccion.notify") || a.hasPermission("chatsentry.getnotified"))
                && !tvnOff.contains(a.getUniqueId());
    }

    private void notifyAdmins(Player p, String context, String typeKey, String blocked, String entire, String custom) {
        String type = Lang.violationType(typeKey);
        String ctx = Lang.context(context);
        String display = p == null ? "-" : Text.plain(p.displayName());
        String[] base = {
                "VIOLATION_TYPE", type, "CONTEXT", ctx,
                "PLAYER", p == null ? "-" : p.getName(),
                "PLAYER_DISPLAYNAME", display,
                "PLAYER_DISPLAYNAME_STRIPPED", Text.strip(display),
                "BLOCKED_CONTENT", Text.strip(blocked == null ? "" : blocked),
                "ENTIRE_MESSAGE", Text.strip(entire == null ? "" : entire),
                "NOTIFICATION", custom == null ? "" : custom
        };
        String path;
        if (custom != null) {
            path = "modules.admin-notifier.custom-notify-msg";
        } else if (blocked != null && !blocked.isEmpty()) {
            path = "modules.admin-notifier.notify-msg";
        } else {
            path = "modules.admin-notifier.no-content-notify-msg";
        }
        final String line = Lang.text(path, base);
        final boolean toConsole = notifier.getBoolean("send-to-console", true);
        Runnable send = () -> {
            for (Player a : Bukkit.getOnlinePlayers()) {
                if (canSeeNotifications(a)) {
                    a.sendMessage(Text.parse(a, line));
                }
            }
            if (toConsole) {
                Bukkit.getConsoleSender().sendMessage(Text.parse(line));
            }
        };
        runMain(send);
    }

    private void runMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, r);
        } catch (Exception ignored) {
            // plugin apagandose
        }
    }

    // ------------------------------------------------------------------ advertencias

    private boolean exemptAuto(Player p) {
        return p.isOp() || p.hasPermission("batmanchat.proteccion.autopunisher.exempt")
                || p.hasPermission("chatsentry.autopunisher.exempt");
    }

    private int maxKey(String moduleKey) {
        ConfigurationSection pun = punisher.getConfigurationSection("warning-config." + moduleKey + ".punishments");
        int max = 0;
        if (pun != null) {
            for (String k : pun.getKeys(false)) {
                try {
                    max = Math.max(max, Integer.parseInt(k));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return max;
    }

    private void addAutoWarning(Player p, String module, String content) {
        if (!main.getBoolean("auto-punisher.enabled", false)) {
            return;
        }
        ConfigurationSection wc = punisher.getConfigurationSection("warning-config." + module);
        if (wc == null || !wc.getBoolean("enabled", false) || exemptAuto(p)) {
            return;
        }
        int count;
        synchronized (warns) {
            W w = warns.computeIfAbsent(p.getUniqueId(), k -> new HashMap<>()).computeIfAbsent(module, k -> new W());
            w.count++;
            w.last = System.currentTimeMillis();
            count = w.count;
            if (db != null) {
                db.saveWarning(p.getUniqueId(), module, false, w.count, w.last);
            }
        }
        discord.autoWarning(module, p.getName(), count, punisher.getDouble("warning-lifetime", 12));
        runPunishments(module, p.getUniqueId(), p.getName(), Text.plain(p.displayName()), count, content);
    }

    private void runPunishments(String moduleKey, UUID uuid, String name, String display, int count, String content) {
        ConfigurationSection pun = punisher.getConfigurationSection("warning-config." + moduleKey + ".punishments");
        if (pun == null) {
            return;
        }
        List<String> actions = pun.getStringList(String.valueOf(count));
        if (actions.isEmpty()) {
            return;
        }
        final int max = maxKey(moduleKey);
        runMain(() -> {
            Player p = Bukkit.getPlayer(uuid);
            for (String a : actions) {
                execAction(a, p, name, display, count, max, content, moduleKey);
            }
        });
        discord.punished(name, moduleKey, count);
    }

    private void execAction(String action, Player p, String name, String display, int count, int max, String content, String module) {
        int ix = action.indexOf("}:");
        if (!action.startsWith("{") || ix < 0) {
            return;
        }
        String type = action.substring(1, ix).toLowerCase(Locale.ROOT).trim();
        String rest = action.substring(ix + 2);
        if (rest.startsWith(" ")) {
            rest = rest.substring(1);
        }
        rest = Text.vars(rest, "PLAYER", name, "PLAYER_DISPLAYNAME", display,
                "PLAYER_DISPLAYNAME_STRIPPED", Text.strip(display), "CURRENT_WARNS", String.valueOf(count),
                "MAX_WARNS", String.valueOf(max), "CONTENT", content == null ? "" : content, "MODULE", module);
        switch (type) {
            case "player_msg":
                if (p != null) {
                    p.sendMessage(Text.parse(p, rest));
                }
                break;
            case "console_cmd":
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rest.startsWith("/") ? rest.substring(1) : rest);
                break;
            case "player_cmd":
                if (p != null) {
                    p.performCommand(rest.startsWith("/") ? rest.substring(1) : rest);
                }
                break;
            case "broadcast":
                Display.broadcast(Text.parse(rest));
                break;
            default:
                break;
        }
    }

    private void expireWarnings() {
        double hours = punisher.getDouble("warning-lifetime", 12);
        if (hours <= 0) {
            return;
        }
        long life = (long) (hours * 3_600_000L);
        long now = System.currentTimeMillis();
        List<Object[]> expired = new ArrayList<>();
        synchronized (warns) {
            for (Map.Entry<UUID, Map<String, W>> e : warns.entrySet()) {
                for (Map.Entry<String, W> m : e.getValue().entrySet()) {
                    if (m.getKey().equals("manual")) {
                        continue;
                    }
                    W w = m.getValue();
                    long steps = (now - w.last) / life;
                    if (w.count > 0 && steps > 0) {
                        int dec = (int) Math.min(steps, w.count);
                        w.count -= dec;
                        w.last = now;
                        if (db != null) {
                            db.saveWarning(e.getKey(), m.getKey(), false, w.count, w.last);
                        }
                        expired.add(new Object[]{e.getKey(), m.getKey(), w.count});
                    }
                }
            }
        }
        for (Object[] x : expired) {
            OfflinePlayer op = Bukkit.getOfflinePlayer((UUID) x[0]);
            discord.warningExpired((String) x[1], op.getName() == null ? x[0].toString() : op.getName(), (Integer) x[2]);
        }
    }

    // ------------------------------------------------------------------ comandos (usados por ProtectionCommands)

    Map<String, Integer> warningsOf(UUID uuid) {
        Map<String, Integer> out = new LinkedHashMap<>();
        synchronized (warns) {
            Map<String, W> m = warns.get(uuid);
            if (m != null) {
                for (Map.Entry<String, W> e : m.entrySet()) {
                    if (e.getValue().count > 0) {
                        out.put(e.getKey(), e.getValue().count);
                    }
                }
            }
        }
        return out;
    }

    /** Modulos que tienen advertencias activadas en auto-punisher.yml (mas "manual"). */
    Collection<String> warnModules() {
        Set<String> out = new TreeSet<>();
        ConfigurationSection wc = punisher.getConfigurationSection("warning-config");
        if (wc != null) {
            for (String k : wc.getKeys(false)) {
                if (wc.getBoolean(k + ".enabled", false)) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    private boolean quiet(CommandSender by) {
        return by instanceof ConsoleCommandSender && !punisher.getBoolean("verbose-from-console", true);
    }

    private boolean silentToTarget(CommandSender by) {
        return by instanceof ConsoleCommandSender && punisher.getBoolean("suppress-pardons-from-console", true);
    }

    private void feedback(CommandSender by, String path, String... kv) {
        if (!quiet(by)) {
            Lang.reply(by, path, kv);
        }
    }

    private int maxManual() {
        return maxKey("manual");
    }

    void warnManual(CommandSender warner, Player target) {
        ConfigurationSection wc = punisher.getConfigurationSection("warning-config.manual");
        if (wc == null || !wc.getBoolean("enabled", false)) {
            Lang.reply(warner, "commands.warnings.warn.manual-warns-disabled");
            return;
        }
        if (target.hasPermission("batmanchat.proteccion.warn.exempt") || target.hasPermission("chatsentry.warn.exempt")) {
            Lang.reply(warner, "commands.warnings.warn.player-exempt", "INPUT", target.getName());
            return;
        }
        int max = maxManual();
        int count;
        synchronized (warns) {
            W w = warns.computeIfAbsent(target.getUniqueId(), k -> new HashMap<>()).computeIfAbsent("manual", k -> new W());
            if (max > 0 && w.count >= max) {
                Lang.reply(warner, "commands.warnings.warn.reached-max", "INPUT", target.getName());
                return;
            }
            w.count++;
            w.last = System.currentTimeMillis();
            count = w.count;
            if (db != null) {
                db.saveWarning(target.getUniqueId(), "manual", true, w.count, w.last);
            }
        }
        String warnerName = warner.getName();
        Lang.reply(target, "commands.warnings.been-warned", "PLAYER", warnerName, "CURRENT_WARNS", String.valueOf(count), "MAX_WARNS", String.valueOf(max));
        feedback(warner, "commands.warnings.warned-player", "PLAYER", target.getName(), "CURRENT_WARNS", String.valueOf(count), "MAX_WARNS", String.valueOf(max));
        if (punisher.getBoolean("broadcast-manual-warnings", true)) {
            String line = Lang.text("commands.warnings.warned-broadcast-message", "WARNED_DISPLAYNAME", Text.plain(target.displayName()),
                    "WARNER_DISPLAYNAME", warner instanceof Player ? Text.plain(((Player) warner).displayName()) : warnerName,
                    "CURRENT_WARNS", String.valueOf(count), "MAX_WARNS", String.valueOf(max));
            Display.broadcast(Text.parse(line));
        }
        discord.manualWarning(warnerName, target.getName(), count);
        runPunishments("manual", target.getUniqueId(), target.getName(), Text.plain(target.displayName()), count, null);
    }

    void pardonManual(CommandSender by, OfflinePlayer target) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        int count;
        synchronized (warns) {
            Map<String, W> m = warns.get(target.getUniqueId());
            W w = m == null ? null : m.get("manual");
            if (w == null || w.count <= 0) {
                Lang.reply(by, "commands.warnings.warnings.no-warnings-found", "INPUT", name);
                return;
            }
            w.count--;
            count = w.count;
            if (db != null) {
                db.saveWarning(target.getUniqueId(), "manual", true, w.count, w.last);
            }
        }
        String[] kv = {"PLAYER", by.getName(), "CURRENT_WARNS", String.valueOf(count), "MAX_WARNS", String.valueOf(maxManual())};
        Player online = target.getPlayer();
        if (online != null && !silentToTarget(by)) {
            Lang.reply(online, "commands.warnings.warning-was-pardoned", kv);
        }
        feedback(by, "commands.warnings.pardoned-warning", "PLAYER", name, "CURRENT_WARNS", String.valueOf(count), "MAX_WARNS", String.valueOf(maxManual()));
        discord.pardonedManual(by.getName(), name, count);
    }

    void pardonAllManual(CommandSender by, OfflinePlayer target) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        synchronized (warns) {
            Map<String, W> m = warns.get(target.getUniqueId());
            W w = m == null ? null : m.get("manual");
            if (w == null || w.count <= 0) {
                Lang.reply(by, "commands.warnings.warnings.no-warnings-found", "INPUT", name);
                return;
            }
            w.count = 0;
            if (db != null) {
                db.saveWarning(target.getUniqueId(), "manual", true, 0, w.last);
            }
        }
        Player online = target.getPlayer();
        if (online != null && !silentToTarget(by)) {
            Lang.reply(online, "commands.warnings.warnings-were-pardoned", "PLAYER", by.getName());
        }
        feedback(by, "commands.warnings.all-warnings-pardoned", "PLAYER", name);
        discord.pardonedAllManual(by.getName(), name);
    }

    /** module = nombre de modulo o "all" (todas las advertencias automaticas). */
    void clearModule(CommandSender by, OfflinePlayer target, String module) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        boolean all = module.equalsIgnoreCase("all");
        boolean any = false;
        synchronized (warns) {
            Map<String, W> m = warns.get(target.getUniqueId());
            if (m != null) {
                for (Map.Entry<String, W> e : m.entrySet()) {
                    if (e.getKey().equals("manual")) {
                        continue;
                    }
                    if ((all || e.getKey().equalsIgnoreCase(module)) && e.getValue().count > 0) {
                        e.getValue().count = 0;
                        any = true;
                        if (db != null) {
                            db.saveWarning(target.getUniqueId(), e.getKey(), false, 0, e.getValue().last);
                        }
                    }
                }
            }
        }
        if (!any) {
            Lang.reply(by, all ? "commands.warnings.warnings.no-warnings-found"
                    : "commands.warnings.warnings.no-warnings-found-for-module", "INPUT", name, "MODULE", module);
            return;
        }
        Player online = target.getPlayer();
        if (all) {
            if (online != null && !silentToTarget(by)) {
                Lang.reply(online, "commands.warnings.all-warnings-were-cleared", "PLAYER", by.getName());
            }
            feedback(by, "commands.warnings.cleared-all-warnings", "PLAYER", name);
            discord.clearedAll(by.getName(), name);
        } else {
            if (online != null && !silentToTarget(by)) {
                Lang.reply(online, "commands.warnings.module-warnings-were-cleared", "PLAYER", by.getName(), "MODULE_NAME", module);
            }
            feedback(by, "commands.warnings.cleared-module-warnings", "PLAYER", name, "MODULE_NAME", module);
            discord.clearedModule(by.getName(), name, module);
        }
    }

    /** Borra TODO (automaticas y manuales). */
    void clearEverything(CommandSender by, OfflinePlayer target) {
        String name = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        synchronized (warns) {
            Map<String, W> m = warns.get(target.getUniqueId());
            if (m != null) {
                for (Map.Entry<String, W> e : m.entrySet()) {
                    e.getValue().count = 0;
                    if (db != null) {
                        db.saveWarning(target.getUniqueId(), e.getKey(), e.getKey().equals("manual"), 0, e.getValue().last);
                    }
                }
            }
        }
        Player online = target.getPlayer();
        if (online != null && !silentToTarget(by)) {
            Lang.reply(online, "commands.warnings.all-warnings-were-cleared", "PLAYER", by.getName());
        }
        feedback(by, "commands.warnings.cleared-all-warnings", "PLAYER", name);
        discord.clearedAll(by.getName(), name);
    }

    // ------------------------------------------------------------------ preferencias

    boolean tvnOn(UUID u) {
        return !tvnOff.contains(u);
    }

    boolean toggleTvn(UUID u) {
        boolean nowOn;
        if (tvnOff.remove(u)) {
            nowOn = true;
        } else {
            tvnOff.add(u);
            nowOn = false;
        }
        if (db != null) {
            db.setPref(u, "tvn", nowOn);
        }
        return nowOn;
    }

    boolean spyOn(UUID u) {
        return !spyOff.contains(u);
    }

    boolean toggleSpy(UUID u) {
        boolean nowOn;
        if (spyOff.remove(u)) {
            nowOn = true;
        } else {
            spyOff.add(u);
            nowOn = false;
        }
        if (db != null) {
            db.setPref(u, "commandspy", nowOn);
        }
        return nowOn;
    }

    List<Database.Violation> lookup(String player, String module, int limit) throws Exception {
        if (db == null) {
            return new ArrayList<>();
        }
        return db.lookup(player, module, limit);
    }

    // ------------------------------------------------------------------ listeners

    public final class ServiceListener implements Listener {
        @EventHandler
        public void onJoin(PlayerJoinEvent e) {
            Player p = e.getPlayer();
            if (main.getBoolean("admin-notifier.enabled", false)
                    && notifier.getBoolean("remind-notifs-toggled-off-on-join", true)
                    && tvnOff.contains(p.getUniqueId())
                    && (p.isOp() || p.hasPermission("batmanchat.proteccion.notify") || p.hasPermission("chatsentry.getnotified"))) {
                Lang.reply(p, "modules.admin-notifier.join-reminder");
            }
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            modified.remove(e.getPlayer().getUniqueId());
        }
    }

    /** Para ProtectionCommands: ayuda de modulos con nombre valido. */
    boolean isKnownModule(String m) {
        return warnModules().contains(m.toLowerCase(Locale.ROOT)) || m.equalsIgnoreCase("all");
    }

    static String joinNames(Collection<String> c) {
        return String.join(", ", c);
    }

    boolean hasHooks() {
        return Hooks.hasPapi();
    }
}
