package me.batman.batmanchat.protection;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.batman.batmanchat.AbstractFeature;
import me.batman.batmanchat.util.Display;
import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.chat.PrivateMessagesFeature;
import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.Context;
import me.batman.batmanchat.logic.core.Flag;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.mod.AntiChatFlood;
import me.batman.batmanchat.logic.mod.AntiCommandPrefix;
import me.batman.batmanchat.logic.mod.AntiParrot;
import me.batman.batmanchat.logic.mod.AutoGrammar;
import me.batman.batmanchat.logic.mod.CapLimiter;
import me.batman.batmanchat.logic.mod.ChatCooldown;
import me.batman.batmanchat.logic.mod.ChatExecutor;
import me.batman.batmanchat.logic.mod.LinkAdBlocker;
import me.batman.batmanchat.logic.mod.SpamBlocker;
import me.batman.batmanchat.logic.mod.UnicodeRemover;
import me.batman.batmanchat.logic.mod.WordFilter;
import me.batman.batmanchat.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Protecciones de chat (antes ChatSentry): filtro de palabras, links, spam, cooldown, flood, unicode,
 * mayusculas, anti-parrot, anti-bots, ejecutor de chat, avisos, castigos automaticos, etc.
 * Cada proteccion se activa o desactiva en proteccion/config.yml.
 */
public final class ProtectionFeature extends AbstractFeature {
    private final Object lock = new Object();
    private final Object joinLock = new Object();

    private ViolationService service;
    private YamlConfiguration cfg;
    private boolean respect;
    private final List<FilterModule> modules = new ArrayList<>();
    private AntiStatueSpambot statue;
    private long enabledAt;

    // anti join flood / anti relog spam
    private boolean floodOn;
    private int floodLimit;
    private boolean floodSkipStartup;
    private final Deque<Long> recentJoins = new ArrayDeque<>();
    private boolean relogOn;
    private boolean relogDynamic;
    private int relogCooldown;
    private final Map<UUID, Deque<Long>> userJoins = new HashMap<>();
    private final Map<UUID, Long> relogBlockedUntil = new HashMap<>();

    public ProtectionFeature(BatmanChat plugin) {
        super(plugin);
    }

    @Override
    public String id() {
        return "proteccion";
    }

    @Override
    public void enable() {
        cfg = plugin.cfg("proteccion/config.yml");
        respect = cfg.getBoolean("respect-cancelled-events", true);
        enabledAt = System.currentTimeMillis();
        recentJoins.clear();
        userJoins.clear();
        relogBlockedUntil.clear();

        service = new ViolationService(plugin);
        service.start();

        modules.clear();
        statue = new AntiStatueSpambot();
        add(statue);
        add(new ChatCooldown());
        add(new UnicodeRemover());
        ChatExecutor executor = new ChatExecutor();
        add(executor);
        for (String err : executor.errors) {
            plugin.getLogger().warning("[Proteccion] Chat executor: " + err);
        }
        WordFilter words = new WordFilter();
        words.isRegisteredCommand = s -> {
            String n = s.startsWith("/") ? s.substring(1) : s;
            return Bukkit.getCommandMap().getCommand(n.toLowerCase(Locale.ROOT)) != null;
        };
        add(words);
        add(new LinkAdBlocker());
        add(new AntiChatFlood());
        add(new CapLimiter());
        add(new SpamBlocker());
        add(new AntiParrot());
        add(new AntiCommandPrefix());
        add(new AutoGrammar());

        floodOn = cfg.getBoolean("anti-join-flood.enabled", false);
        YamlConfiguration jf = plugin.cfg("proteccion/modulos/anti-join-flood.yml");
        floodLimit = Math.max(1, jf.getInt("allowed-joins-per-minute", 12));
        floodSkipStartup = jf.getBoolean("disable-join-flood-check-on-startup", true);
        relogOn = cfg.getBoolean("anti-relog-spam.enabled", false);
        YamlConfiguration rl = plugin.cfg("proteccion/modulos/anti-relog-spam.yml");
        relogDynamic = rl.getBoolean("use-dynamically-increasing-cooldown", false);
        relogCooldown = Math.max(1, rl.getInt("cooldown", 15));

        listen(new Events());
        plugin.getLogger().info("[Proteccion] Protecciones activas: " + activeNames());
    }

    @Override
    protected void onDisable() {
        if (service != null) {
            service.stop();
            service = null;
        }
        synchronized (lock) {
            modules.clear();
        }
    }

    private void add(FilterModule m) {
        YamlConfiguration own = plugin.cfg("proteccion/modulos/" + m.id + ".yml");
        ConfigurationSection overlay = cfg.getConfigurationSection(m.id);
        m.reload(new YamlOpts(own, overlay));
        modules.add(m);
    }

    private String activeNames() {
        List<String> n = new ArrayList<>();
        for (FilterModule m : modules) {
            if (m.enabled) {
                n.add(m.id);
            }
        }
        if (floodOn) {
            n.add("anti-join-flood");
        }
        if (relogOn) {
            n.add("anti-relog-spam");
        }
        return String.join(", ", n);
    }

    // ------------------------------------------------------------------ nucleo

    private boolean bypass(Player p, String id) {
        if (cfg.getBoolean("override-bypass-permissions." + id, false)) {
            return false;
        }
        return p.isOp() || p.hasPermission("batmanchat.proteccion.bypass")
                || p.hasPermission("batmanchat.proteccion." + id + ".bypass")
                || p.hasPermission("chatsentry." + id.replace("-", "") + ".bypass");
    }

    private static String[] kv(Map<String, String> vars) {
        String[] out = new String[vars.size() * 2];
        int i = 0;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out[i++] = e.getKey();
            out[i++] = e.getValue();
        }
        return out;
    }

    private Check run(Player p, Context ctx, String text) {
        return run(p, ctx, text, ctx.key);
    }

    /**
     * Revisa un mensaje privado (/msg, /r) con los mismos modulos que el chat (los que tengan "chat: true" en
     * proteccion/config.yml) y con los mismos permisos de bypass. Devuelve null si las protecciones no estan activas.
     * Si el resultado trae blocked=true no hay que enviar el mensaje; si trae modified=true hay que usar text.
     * Los avisos al jugador y las advertencias ya se enviaron aqui. Hilo principal.
     */
    public Check filterPrivate(Player p, String text) {
        if (service == null || text == null) {
            return null;
        }
        String t = text;
        if (cfg.getBoolean("truncate-unsafe-chat", true) && t.length() > 256) {
            t = t.substring(0, 256);
        }
        Check c = run(p, Context.CHAT, t, "private");
        if (!c.modified && !t.equals(text)) {
            c.setText(t);
        }
        return c;
    }

    private Check run(Player p, Context ctx, String text, String reportKey) {
        Check c = new Check(p.getUniqueId(), p.getName(), ctx, text, System.currentTimeMillis());
        Set<String> names = new HashSet<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            names.add(o.getName());
        }
        c.onlineNames = names;
        synchronized (lock) {
            for (FilterModule m : modules) {
                if (!m.appliesTo(ctx) || bypass(p, m.id)) {
                    continue;
                }
                m.check(c);
                if (c.blocked) {
                    break;
                }
            }
        }
        for (Check.Reply r : c.replies) {
            Lang.send(p, r.langPath, kv(r.vars));
        }
        for (Flag f : c.flags) {
            service.report(p, f.moduleId, reportKey, f.typeKey, f.blockedContent, c.original, f.warn, f.notify, f.log);
        }
        if (!c.actions.isEmpty()) {
            final List<String> actions = new ArrayList<>(c.actions);
            Runnable r = () -> {
                for (String a : actions) {
                    execAction(p, a);
                }
            };
            if (Bukkit.isPrimaryThread()) {
                r.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, r);
            }
        }
        return c;
    }

    private void execAction(Player p, String action) {
        int ix = action.indexOf("}:");
        if (!action.startsWith("{") || ix < 0) {
            return;
        }
        String type = action.substring(1, ix).toLowerCase(Locale.ROOT).trim();
        String rest = action.substring(ix + 2);
        if (rest.startsWith(" ")) {
            rest = rest.substring(1);
        }
        switch (type) {
            case "player_msg":
                p.sendMessage(Text.parse(p, rest));
                break;
            case "console_cmd":
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), rest.startsWith("/") ? rest.substring(1) : rest);
                break;
            case "player_cmd":
                p.performCommand(rest.startsWith("/") ? rest.substring(1) : rest);
                break;
            case "broadcast":
                Display.broadcast(Text.parse(rest));
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ eventos

    private final class Events implements Listener {
        @EventHandler(priority = EventPriority.LOW)
        public void onChat(AsyncChatEvent e) {
            if (respect && e.isCancelled()) {
                return;
            }
            Player p = e.getPlayer();
            String text = Text.plain(e.message());
            boolean truncated = false;
            if (cfg.getBoolean("truncate-unsafe-chat", true) && text.length() > 256) {
                text = text.substring(0, 256);
                truncated = true;
            }
            Check c = run(p, Context.CHAT, text);
            if (c.blocked) {
                e.setCancelled(true);
                return;
            }
            if (c.modified || truncated) {
                e.message(Component.text(c.text));
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onCommand(PlayerCommandPreprocessEvent e) {
            if (respect && e.isCancelled()) {
                return;
            }
            // /msg y /r los revisa el modulo de mensajes privados (con las reglas del chat): no se revisan dos veces
            if (plugin.isRunning(PrivateMessagesFeature.class)) {
                PrivateMessagesFeature pm = plugin.feature(PrivateMessagesFeature.class);
                if (pm != null && pm.filtersCommand(e.getMessage())) {
                    return;
                }
            }
            Player p = e.getPlayer();
            String msg = e.getMessage();
            boolean truncated = false;
            if (cfg.getBoolean("truncate-unsafe-chat", true) && msg.length() > 256) {
                msg = msg.substring(0, 256);
                truncated = true;
            }
            Check c = run(p, Context.COMMAND, msg);
            if (c.blocked) {
                e.setCancelled(true);
                return;
            }
            if (c.modified || truncated) {
                if (c.modified && !c.text.equals(msg)) {
                    service.noteCommandModified(p, msg, c.text);
                }
                e.setMessage(c.text);
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onSign(SignChangeEvent e) {
            if (respect && e.isCancelled()) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Component line : e.lines()) {
                String t = Text.plain(line);
                if (!t.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(t);
                }
            }
            if (sb.length() == 0) {
                return;
            }
            Check c = run(e.getPlayer(), Context.SIGN, sb.toString());
            if (c.blocked) {
                e.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onAnvil(PrepareAnvilEvent e) {
            if (!(e.getView().getPlayer() instanceof Player p)) {
                return;
            }
            String name = e.getView().getRenameText();
            if (name == null || name.isEmpty() || e.getResult() == null) {
                return;
            }
            Check c = run(p, Context.ANVIL, name);
            if (c.blocked) {
                e.setResult(null);
            }
        }

        @EventHandler(priority = EventPriority.LOW)
        public void onBook(PlayerEditBookEvent e) {
            if (respect && e.isCancelled()) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (Component page : e.getNewBookMeta().pages()) {
                sb.append(Text.plain(page)).append('\n');
            }
            String title = e.getNewBookMeta().hasTitle() && e.getNewBookMeta().title() != null
                    ? Text.plain(e.getNewBookMeta().title()) : "";
            String all = (title + "\n" + sb).trim();
            if (all.isEmpty()) {
                return;
            }
            Check c = run(e.getPlayer(), Context.BOOK, all);
            if (c.blocked) {
                e.setCancelled(true);
            }
        }

        @EventHandler
        public void onJoin(PlayerJoinEvent e) {
            statue.joined(e.getPlayer().getUniqueId());
        }

        @EventHandler(ignoreCancelled = true)
        public void onMove(PlayerMoveEvent e) {
            if (statue.isFrozen(e.getPlayer().getUniqueId()) && e.hasChangedBlock()) {
                statue.moved(e.getPlayer().getUniqueId());
            }
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            UUID u = e.getPlayer().getUniqueId();
            synchronized (lock) {
                for (FilterModule m : modules) {
                    m.forget(u);
                }
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onKick(PlayerKickEvent e) {
            if (cfg.getBoolean("disable-vanilla-spam-kick", true) && e.getCause() == PlayerKickEvent.Cause.SPAM) {
                e.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPreLogin(AsyncPlayerPreLoginEvent e) {
            if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
                return;
            }
            long now = System.currentTimeMillis();
            synchronized (joinLock) {
                if (relogOn && relogCheck(e, now)) {
                    return;
                }
                if (floodOn && floodCheck(e, now)) {
                    return;
                }
            }
        }
    }

    /** true si se rechazo la conexion. */
    private boolean relogCheck(AsyncPlayerPreLoginEvent e, long now) {
        UUID u = e.getUniqueId();
        Deque<Long> times = userJoins.computeIfAbsent(u, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > 60_000) {
            times.pollFirst();
        }
        Long until = relogBlockedUntil.get(u);
        if (until != null && now >= until) {
            relogBlockedUntil.remove(u);
            times.clear();
            until = null;
        }
        if (until != null) {
            if (relogDynamic) {
                until = until + 5000;
                relogBlockedUntil.put(u, until);
            }
            denyRelog(e, until, now);
            return true;
        }
        if (times.size() >= 2) {
            until = now + relogCooldown * 1000L;
            relogBlockedUntil.put(u, until);
            denyRelog(e, until, now);
            return true;
        }
        times.addLast(now);
        if (userJoins.size() > 5000) {
            userJoins.values().removeIf(Deque::isEmpty);
        }
        return false;
    }

    private void denyRelog(AsyncPlayerPreLoginEvent e, long until, long now) {
        String secs = String.valueOf(Math.max(1, (until - now + 999) / 1000));
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Lang.joined("modules.anti-relog-spam.on-cooldown-kick", "SECONDS", secs));
        service.reportCustom(null, "anti-relog-spam", "join", "anti-join-flood-block",
                Lang.text("modules.anti-relog-spam.notification", "PLAYER", e.getName(), "SECONDS", secs), false);
    }

    private boolean floodCheck(AsyncPlayerPreLoginEvent e, long now) {
        if (floodSkipStartup && now - enabledAt < 150_000L) {
            return false;
        }
        while (!recentJoins.isEmpty() && now - recentJoins.peekFirst() > 60_000) {
            recentJoins.pollFirst();
        }
        if (recentJoins.size() >= floodLimit) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Lang.joined("modules.anti-join-flood.kick"));
            service.reportCustom(null, "anti-join-flood", "join", "anti-join-flood-block",
                    "Se bloqueo la entrada de " + e.getName() + ": demasiadas conexiones por minuto (limite " + floodLimit + ")", false);
            return true;
        }
        recentJoins.addLast(now);
        return false;
    }
}
