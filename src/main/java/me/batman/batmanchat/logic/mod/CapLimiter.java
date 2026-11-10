package me.batman.batmanchat.logic.mod;

import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.Context;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.core.Opts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cap Limiter: demasiadas mayusculas. Puede pasar el mensaje a minusculas o bloquearlo. */
public final class CapLimiter extends FilterModule {
    private static final Pattern COLOR = Pattern.compile("(?i)[&§]#[0-9a-f]{6}|#[0-9a-f]{6}|[&§][0-9a-fk-or]");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}_]+");

    private int runLimit;
    private int masterLimit;
    private boolean ignoreColors;
    private boolean modify;
    private boolean sendWhenModified;
    private final List<String> affected = new ArrayList<>();

    public CapLimiter() {
        super("cap-limiter");
    }

    @Override
    protected void load(Opts o) {
        runLimit = o.integer("repeated-cap-limit", 8);
        masterLimit = o.integer("master-cap-limit", 18);
        ignoreColors = o.bool("ignore-colorcodes", true);
        modify = o.bool("modify-message", true);
        sendWhenModified = o.bool("send-block-message-when-modified", true);
        affected.clear();
        for (String s : o.list("affected-commands")) {
            affected.add(slash(s));
        }
    }

    @Override
    public void check(Check c) {
        if (c.ctx == Context.COMMAND && !affected(affected, c.commandBase)) {
            return;
        }
        String s = c.text;
        boolean[] skip = new boolean[s.length()];
        if (ignoreColors) {
            Matcher m = COLOR.matcher(s);
            while (m.find()) {
                for (int i = m.start(); i < m.end(); i++) {
                    skip[i] = true;
                }
            }
        }
        Matcher w = WORD.matcher(s);
        while (w.find()) {
            String word = w.group().toLowerCase(Locale.ROOT);
            if (c.onlineNames.stream().anyMatch(n -> n.equalsIgnoreCase(word))) {
                for (int i = w.start(); i < w.end(); i++) {
                    skip[i] = true;
                }
            }
        }
        int total = 0;
        int run = 0;
        int maxRun = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (skip[i]) {
                continue;
            }
            if (Character.isUpperCase(ch)) {
                total++;
                run++;
                maxRun = Math.max(maxRun, run);
            } else if (Character.isLetter(ch)) {
                run = 0;
            }
        }
        if (maxRun <= runLimit && total <= masterLimit) {
            return;
        }
        if (modify) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                sb.append(!skip[i] && Character.isUpperCase(ch) ? Character.toLowerCase(ch) : ch);
            }
            c.setText(sb.toString());
            if (sendWhenModified) {
                c.reply("modules.cap-limiter.trigger");
            }
        } else {
            c.block();
            c.reply("modules.cap-limiter.trigger");
        }
        c.flag(id, "cap-limiter-block", null);
    }
}
