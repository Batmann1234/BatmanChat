package me.batman.batmanchat.protection;

import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.util.Hooks;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /kcs, /cswarn, /cswarnings, /cslockdown y /commandspy. */
final class ProtectionCommands implements CommandExecutor, TabCompleter {
    private static final String[] NAMES = {"kcs", "cswarn", "cswarnings", "cslockdown", "commandspy"};

    private final ViolationService service;
    private final BatmanChat plugin;
    private final Lockdown lockdown;

    ProtectionCommands(ViolationService service, BatmanChat plugin, Lockdown lockdown) {
        this.service = service;
        this.plugin = plugin;
        this.lockdown = lockdown;
    }

    void start() {
        for (String n : NAMES) {
            PluginCommand c = plugin.getCommand(n);
            if (c != null) {
                c.setExecutor(this);
                c.setTabCompleter(this);
            }
        }
    }

    void stop() {
        for (String n : NAMES) {
            PluginCommand c = plugin.getCommand(n);
            if (c != null && c.getExecutor() == this) {
                c.setExecutor(null);
                c.setTabCompleter(null);
            }
        }
    }

    private static boolean can(CommandSender s, String perm, String legacy) {
        return s.hasPermission(perm) || s.hasPermission(legacy) || s.isOp();
    }

    private static OfflinePlayer resolve(String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p != null) {
            return p;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "kcs":
                return kcs(s, label, a);
            case "cswarn":
                return warn(s, a);
            case "cswarnings":
                return warnings(s, a);
            case "cslockdown":
                return lockdown(s, a);
            case "commandspy":
                return toggleSpy(s);
            default:
                return false;
        }
    }

    // ------------------------------------------------------------------ /kcs

    private boolean kcs(CommandSender s, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            Lang.reply(s, "commands.kcs.help");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "tvn":
                if (!(s instanceof Player)) {
                    Lang.reply(s, "commands.kcs.player-only");
                    return true;
                }
                if (!can(s, "batmanchat.proteccion.notify", "chatsentry.getnotified")) {
                    Lang.noPermission(s);
                    return true;
                }
                boolean on = service.toggleTvn(((Player) s).getUniqueId());
                Lang.reply(s, on ? "commands.tvn.toggled-on" : "commands.tvn.toggled-off");
                return true;
            case "commandspy":
                return toggleSpy(s);
            case "reload":
                if (!can(s, "batmanchat.admin", "chatsentry.admin")) {
                    Lang.noPermission(s);
                    return true;
                }
                plugin.reloadAll();
                Lang.reply(s, "commands.kcs.reloaded");
                return true;
            case "lookup":
                return lookup(s, a);
            default:
                Lang.reply(s, "commands.kcs.help");
                return true;
        }
    }

    private boolean lookup(CommandSender s, String[] a) {
        if (!can(s, "batmanchat.proteccion.lookup", "chatsentry.lookup")) {
            Lang.noPermission(s);
            return true;
        }
        if (a.length < 2) {
            Lang.reply(s, "commands.lookup.usage");
            return true;
        }
        if (!service.main().getBoolean("enable-violations-log", false) || !service.hasDb()) {
            Lang.reply(s, "commands.lookup.logging-disabled");
            return true;
        }
        final String player = a[1];
        final String module = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : null;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<Database.Violation> list;
            try {
                list = service.lookup(player, module, 15);
            } catch (Exception e) {
                list = new ArrayList<>();
            }
            final List<Database.Violation> result = list;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (result.isEmpty()) {
                    Lang.reply(s, "commands.lookup.none", "INPUT", player);
                    return;
                }
                Lang.reply(s, "commands.lookup.header", "INPUT", player);
                SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm");
                for (Database.Violation v : result) {
                    String content = v.blocked != null && !v.blocked.isEmpty() ? v.blocked : v.full;
                    Lang.reply(s, "commands.lookup.line", "TIME", f.format(new Date(v.time)), "MODULE", v.module,
                            "CONTEXT", Lang.context(v.context), "CONTENT", me.batman.batmanchat.util.Text.strip(content == null ? "" : content));
                }
            });
        });
        return true;
    }

    // ------------------------------------------------------------------ /commandspy

    private boolean toggleSpy(CommandSender s) {
        if (!(s instanceof Player)) {
            Lang.reply(s, "commands.kcs.player-only");
            return true;
        }
        if (!can(s, "batmanchat.proteccion.commandspy.toggle", "chatsentry.commandspy.toggle")) {
            Lang.noPermission(s);
            return true;
        }
        if (!plugin.cfg("proteccion/config.yml").getBoolean("command-spy.enabled", false)) {
            Lang.reply(s, "commands.commandspy.disabled");
            return true;
        }
        boolean on = service.toggleSpy(((Player) s).getUniqueId());
        Lang.reply(s, on ? "commands.commandspy.toggled-on" : "commands.commandspy.toggled-off");
        return true;
    }

    // ------------------------------------------------------------------ /cswarn

    private boolean warn(CommandSender s, String[] a) {
        if (!can(s, "batmanchat.proteccion.warn", "chatsentry.warn")) {
            Lang.noPermission(s);
            return true;
        }
        if (a.length < 1) {
            Lang.reply(s, "commands.warnings.warn.usage");
            return true;
        }
        Player t = Bukkit.getPlayerExact(a[0]);
        if (t == null) {
            Lang.reply(s, "commands.warnings.warn.invalid-player", "INPUT", a[0]);
            return true;
        }
        service.warnManual(s, t);
        return true;
    }

    // ------------------------------------------------------------------ /cswarnings

    private boolean warnings(CommandSender s, String[] a) {
        if (!can(s, "batmanchat.proteccion.warnings", "chatsentry.warnings")) {
            Lang.noPermission(s);
            return true;
        }
        if (a.length < 2) {
            Lang.reply(s, "commands.warnings.warnings.detailed-usage");
            Lang.reply(s, "commands.warnings.warnings.usage", "ARG", "view");
            Lang.reply(s, "commands.warnings.warnings.usage", "ARG", "pardon");
            Lang.reply(s, "commands.warnings.warnings.usage", "ARG", "pardonallmanual");
            Lang.reply(s, "commands.warnings.warnings.usage", "ARG", "clearallwarnings");
            Lang.reply(s, "commands.warnings.warnings.cmw-usage", "ARG", "clearmodulewarnings");
            Lang.reply(s, "commands.warnings.warnings.pardon-note");
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        OfflinePlayer t = resolve(a[1]);
        if (t == null) {
            Lang.reply(s, "commands.warnings.warnings.couldnt-fetch", "INPUT", a[1]);
            return true;
        }
        switch (sub) {
            case "view":
                viewWarnings(s, t, a[1]);
                return true;
            case "pardon":
                service.pardonManual(s, t);
                return true;
            case "pardonallmanual":
                service.pardonAllManual(s, t);
                return true;
            case "clearallwarnings":
                service.clearEverything(s, t);
                return true;
            case "clearmodulewarnings":
                if (a.length < 3) {
                    Lang.reply(s, "commands.warnings.warnings.cmw-usage", "ARG", "clearmodulewarnings");
                    return true;
                }
                if (!service.isKnownModule(a[2])) {
                    Lang.reply(s, "commands.warnings.warnings.invalid-module", "INPUT", a[2]);
                    return true;
                }
                service.clearModule(s, t, a[2].toLowerCase(Locale.ROOT));
                return true;
            default:
                Lang.reply(s, "commands.warnings.warnings.detailed-usage");
                return true;
        }
    }

    private void viewWarnings(CommandSender s, OfflinePlayer t, String input) {
        if (service.warnModules().isEmpty()) {
            Lang.reply(s, "commands.warnings.warnings.no-modules");
            return;
        }
        Map<String, Integer> w = service.warningsOf(t.getUniqueId());
        if (w.isEmpty()) {
            Lang.reply(s, "commands.warnings.warnings.no-warnings-found", "INPUT", input);
            return;
        }
        Lang.reply(s, "commands.warnings.warnings.view-header", "INPUT", input);
        for (Map.Entry<String, Integer> e : w.entrySet()) {
            Lang.reply(s, "commands.warnings.warnings.view-line", "MODULE", e.getKey(), "COUNT", String.valueOf(e.getValue()));
        }
    }

    // ------------------------------------------------------------------ /cslockdown

    private boolean lockdown(CommandSender s, String[] a) {
        if (!can(s, "batmanchat.proteccion.lockdown", "chatsentry.lockdown")) {
            Lang.noPermission(s);
            return true;
        }
        if (a.length == 0 || a[0].equalsIgnoreCase("off")) {
            if (lockdown.isActive()) {
                lockdown.set(false, null);
                Lang.reply(s, "commands.lockdown.toggled-off");
            } else {
                Lang.reply(s, "commands.lockdown.already-off");
            }
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "only-known":
            case "only-exempt":
                lockdown.set(true, sub);
                Lang.reply(s, "commands.lockdown.toggled-on", "MODE", sub);
                return true;
            case "list":
                List<String> ex = lockdown.exempt();
                if (ex.isEmpty()) {
                    Lang.reply(s, "commands.lockdown.no-exempt");
                } else {
                    Lang.reply(s, "commands.lockdown.exempt-list", "LIST", String.join(", ", ex));
                }
                return true;
            case "add":
                if (a.length < 2) {
                    Lang.reply(s, "commands.lockdown.no-exempt");
                    return true;
                }
                Lang.reply(s, lockdown.addExempt(a[1]) ? "commands.lockdown.exemption-added" : "commands.lockdown.already-exempt", "USERNAME", a[1]);
                return true;
            case "remove":
                if (a.length < 2) {
                    Lang.reply(s, "commands.lockdown.no-exempt");
                    return true;
                }
                Lang.reply(s, lockdown.removeExempt(a[1]) ? "commands.lockdown.exemption-removed" : "commands.lockdown.unknown-remove", "USERNAME", a[1]);
                return true;
            default:
                Lang.reply(s, "commands.lockdown.already-off");
                return true;
        }
    }

    // ------------------------------------------------------------------ tab

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        switch (name) {
            case "kcs":
                if (a.length == 1) {
                    options.addAll(List.of("tvn", "commandspy", "lookup", "reload", "help"));
                } else if (a.length == 2 && a[0].equalsIgnoreCase("lookup")) {
                    players(options);
                }
                break;
            case "cswarn":
                if (a.length == 1) {
                    players(options);
                }
                break;
            case "cswarnings":
                if (a.length == 1) {
                    options.addAll(List.of("view", "pardon", "pardonallmanual", "clearmodulewarnings", "clearallwarnings"));
                } else if (a.length == 2) {
                    players(options);
                } else if (a.length == 3 && a[0].equalsIgnoreCase("clearmodulewarnings")) {
                    options.add("all");
                    options.addAll(service.warnModules());
                }
                break;
            case "cslockdown":
                if (a.length == 1) {
                    options.addAll(List.of("only-known", "only-exempt", "off", "add", "remove", "list"));
                }
                break;
            default:
                break;
        }
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(last)) {
                out.add(o);
            }
        }
        return out;
    }

    private static void players(List<String> into) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            into.add(p.getName());
        }
    }

    boolean hooked() {
        return Hooks.hasPapi();
    }
}
