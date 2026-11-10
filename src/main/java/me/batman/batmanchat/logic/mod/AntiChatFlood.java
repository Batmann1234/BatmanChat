package me.batman.batmanchat.logic.mod;

import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.Context;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.core.Opts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Anti Chat Flood: letras/simbolos repetidos y "palabras" larguisimas. */
public final class AntiChatFlood extends FilterModule {
    private static final Pattern LINK = Pattern.compile("(?i)^(https?://|www\\.)\\S+$|^\\S+\\.[a-z]{2,24}(/\\S*)?$");

    private int repeatLimit;
    private final Map<Character, Integer> custom = new HashMap<>();
    private int maxWord;
    private boolean ignoreLinks;
    private boolean modify;
    private boolean sendWhenModified;
    private final List<String> affected = new ArrayList<>();

    public AntiChatFlood() {
        super("anti-chat-flood");
    }

    @Override
    protected void load(Opts o) {
        repeatLimit = Math.max(2, o.integer("repeated-character-limit", 6));
        custom.clear();
        for (String s : o.list("custom-limits")) {
            String[] p = s.split(";;");
            if (p.length == 2 && !p[0].isEmpty()) {
                try {
                    custom.put(p[0].charAt(0), Integer.parseInt(p[1].trim()));
                } catch (NumberFormatException ignored) {
                    // entrada invalida: se ignora
                }
            }
        }
        maxWord = o.integer("max-word-length", 32);
        ignoreLinks = o.bool("ignore-long-links", true);
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
        String text = c.text;

        // 1) palabras demasiado largas
        for (String token : text.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (ignoreLinks && LINK.matcher(token).matches()) {
                continue;
            }
            int len = 0;
            for (char ch : token.toCharArray()) {
                if (!custom.containsKey(ch)) {
                    len++;
                }
            }
            if (maxWord > 0 && len > maxWord) {
                c.block();
                c.reply("modules.anti-chat-flood.trigger-too-long");
                c.flag(id, "anti-chat-flood-block", token.length() > 60 ? token.substring(0, 60) + "..." : token);
                return;
            }
        }

        // 2) caracteres repetidos
        StringBuilder out = new StringBuilder(text.length());
        boolean exceeded = false;
        String firstFlood = null;
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            int j = i;
            while (j < text.length() && text.charAt(j) == ch) {
                j++;
            }
            int run = j - i;
            int limit = custom.getOrDefault(ch, repeatLimit);
            if (run > limit) {
                exceeded = true;
                if (firstFlood == null) {
                    firstFlood = text.substring(i, Math.min(j, i + 12));
                }
                int keep = Math.max(1, (int) Math.ceil(limit * 0.8));
                out.append(String.valueOf(ch).repeat(keep));
            } else {
                out.append(text, i, j);
            }
            i = j;
        }
        if (!exceeded) {
            return;
        }
        if (modify) {
            c.setText(out.toString());
            if (sendWhenModified) {
                c.reply("modules.anti-chat-flood.trigger-repeated");
            }
        } else {
            c.block();
            c.reply("modules.anti-chat-flood.trigger-repeated");
        }
        c.flag(id, "anti-chat-flood-block", firstFlood);
    }
}
