package me.batman.batmanchat.logic.mod;

import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.Context;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.core.Opts;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Anti Command Prefix: convierte /plugin:comando en /comando (salvo los permitidos). */
public final class AntiCommandPrefix extends FilterModule {
    private final Set<String> allowedCommands = new HashSet<>();
    private final Set<String> allowedPrefixes = new HashSet<>();
    private boolean sendMsg;

    public AntiCommandPrefix() {
        super("anti-command-prefix");
    }

    @Override
    protected boolean defaultContext(Context ctx) {
        return ctx == Context.COMMAND;
    }

    @Override
    protected void load(Opts o) {
        allowedCommands.clear();
        for (String s : o.list("allowed-prefixed-commands")) {
            allowedCommands.add(s.trim().toLowerCase(Locale.ROOT));
        }
        allowedPrefixes.clear();
        for (String s : o.list("allowed-global-prefixes")) {
            allowedPrefixes.add(s.trim().toLowerCase(Locale.ROOT));
        }
        sendMsg = o.bool("send-msg-when-modified", true);
    }

    @Override
    public void check(Check c) {
        String text = c.text.trim();
        if (!text.startsWith("/")) {
            return;
        }
        int sp = text.indexOf(' ');
        String base = sp < 0 ? text : text.substring(0, sp);
        String rest = sp < 0 ? "" : text.substring(sp);
        int colon = base.indexOf(':');
        if (colon < 0) {
            return;
        }
        String lowerBase = base.toLowerCase(Locale.ROOT);
        String prefix = lowerBase.substring(1, colon + 1);
        if (allowedCommands.contains(lowerBase) || allowedPrefixes.contains(prefix)) {
            return;
        }
        c.setText("/" + base.substring(colon + 1) + rest);
        if (sendMsg) {
            c.reply("modules.anti-command-prefix.trigger");
        }
    }
}
