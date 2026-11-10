package me.batman.batmanchat.logic.mod;

import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.Context;
import me.batman.batmanchat.logic.core.Flag;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.core.Opts;
import me.batman.batmanchat.logic.core.Similarity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Word &amp; Phrase Filter: palabras/frases prohibidas con lista blanca, censura y sustitucion de letras. */
public final class WordFilter extends FilterModule {
    private boolean ignoreUsernames;
    private boolean ignoreRegisteredCommands;
    private boolean censor;
    private boolean sendBlockWhenCensored;
    private boolean partial;
    private boolean shorten;
    private char censorChar;
    private boolean autowarnWhenCensored;
    private boolean notifyWhenCensored;
    private boolean substitution;
    private final Map<Character, Character> subst = new HashMap<>();
    private String matchMode;
    private final List<String> blocked = new ArrayList<>();
    private final Set<String> whitelist = new HashSet<>();
    private final Set<String> cmdWhitelist = new HashSet<>();
    /** Comandos registrados en el servidor (los pone la parte de Bukkit); se ignora su primer token. */
    public java.util.function.Predicate<String> isRegisteredCommand = s -> true;

    public WordFilter() {
        super("word-and-phrase-filter");
    }

    @Override
    protected void load(Opts o) {
        ignoreUsernames = o.bool("ignore-usernames", true);
        ignoreRegisteredCommands = o.bool("ignore-detected-registered-commands", true);
        Opts cz = o.sub("censor");
        censor = cz.bool("enabled", true);
        sendBlockWhenCensored = cz.bool("send-block-message-when-censored", false);
        partial = cz.bool("partially-censor", true);
        shorten = cz.bool("shorten", true);
        String cc = cz.str("censor-char", "*");
        censorChar = cc.isEmpty() ? '*' : cc.charAt(0);
        autowarnWhenCensored = cz.bool("autowarn-when-censored", true);
        notifyWhenCensored = cz.bool("notify-when-censored", true);
        substitution = o.bool("substitution-intelligence", false);
        subst.clear();
        for (String s : o.list("substitution-intelligence-charset")) {
            String[] p = s.split("\\(->\\)");
            if (p.length == 2 && !p[0].trim().isEmpty() && !p[1].trim().isEmpty()) {
                subst.put(p[0].trim().charAt(0), Similarity.foldChar(p[1].trim().charAt(0)));
            }
        }
        matchMode = o.str("match-mode", "SUBSTRING").toUpperCase(Locale.ROOT);

        Set<String> uniq = new LinkedHashSet<>();
        for (String s : o.list("blocked-words-and-phrases")) {
            String f = Similarity.fold(s.trim());
            if (!f.isEmpty()) {
                uniq.add(f);
            }
        }
        blocked.clear();
        blocked.addAll(uniq);
        blocked.sort(Comparator.comparingInt(String::length).reversed());
        whitelist.clear();
        for (String s : o.list("whitelisted-words-and-phrases")) {
            String f = Similarity.fold(s.trim());
            if (!f.isEmpty()) {
                whitelist.add(f);
            }
        }
        cmdWhitelist.clear();
        for (String s : o.list("whitelisted-commands")) {
            cmdWhitelist.add(slash(s));
        }
    }

    @Override
    public void check(Check c) {
        if (c.ctx == Context.COMMAND && cmdWhitelist.contains(c.commandBase)) {
            return;
        }
        String text = c.text;
        int offset = 0;
        if (c.ctx == Context.COMMAND && ignoreRegisteredCommands) {
            int sp = text.indexOf(' ');
            offset = sp < 0 ? text.length() : sp;
        }
        String folded = Similarity.fold(text);
        if (substitution && c.ctx != Context.COMMAND) {
            StringBuilder sb = new StringBuilder(folded.length());
            for (char ch : folded.toCharArray()) {
                sb.append(subst.getOrDefault(ch, ch));
            }
            folded = sb.toString();
        }
        boolean[] masked = new boolean[text.length()];
        if (ignoreUsernames) {
            String lowerText = text.toLowerCase(Locale.ROOT);
            for (String n : c.onlineNames) {
                String ln = n.toLowerCase(Locale.ROOT);
                int idx = lowerText.indexOf(ln);
                while (idx >= 0) {
                    for (int i = idx; i < idx + ln.length() && i < masked.length; i++) {
                        masked[i] = true;
                    }
                    idx = lowerText.indexOf(ln, idx + 1);
                }
            }
        }

        List<int[]> hits = new ArrayList<>();
        for (String entry : blocked) {
            int from = offset;
            int idx;
            while ((idx = folded.indexOf(entry, from)) >= 0) {
                from = idx + 1;
                int end = idx + entry.length();
                if (masked[idx]) {
                    continue;
                }
                int ts = idx;
                while (ts > 0 && wordChar(folded.charAt(ts - 1))) {
                    ts--;
                }
                int te = end;
                while (te < folded.length() && wordChar(folded.charAt(te))) {
                    te++;
                }
                if (whitelist.contains(folded.substring(ts, te))) {
                    continue;
                }
                if (matchMode.equals("WORD") && (ts != idx || te != end)) {
                    continue;
                }
                if (matchMode.equals("PREFIX") && ts != idx) {
                    continue;
                }
                hits.add(new int[]{idx, end});
            }
        }
        if (hits.isEmpty()) {
            return;
        }
        hits.sort(Comparator.comparingInt(h -> h[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] h : hits) {
            if (!merged.isEmpty() && h[0] <= merged.get(merged.size() - 1)[1]) {
                merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], h[1]);
            } else {
                merged.add(new int[]{h[0], h[1]});
            }
        }
        StringBuilder content = new StringBuilder();
        for (int[] h : merged) {
            if (content.length() > 0) {
                content.append(", ");
            }
            content.append(text, h[0], h[1]);
        }
        String flagged = content.toString();

        if (!censor) {
            c.block();
            c.reply("modules.word-and-phrase-filter.trigger", "blocked_content", flagged);
            c.flag(id, "message-filter-block", flagged);
            return;
        }
        StringBuilder out = new StringBuilder(text);
        for (int[] h : merged) {
            int len = h[1] - h[0];
            int keep = partial && len > 2 ? 2 : 0;
            for (int i = h[0] + keep; i < h[1]; i++) {
                out.setCharAt(i, censorChar);
            }
        }
        String censored = out.toString();
        if (shorten) {
            String run = java.util.regex.Pattern.quote(String.valueOf(censorChar));
            censored = censored.replaceAll("(?:" + run + "){7,}", String.valueOf(censorChar).repeat(5));
        }
        c.setText(censored);
        if (sendBlockWhenCensored) {
            c.reply("modules.word-and-phrase-filter.trigger", "blocked_content", flagged);
        }
        c.flag(new Flag(id, "message-filter-block", flagged, autowarnWhenCensored, notifyWhenCensored, true));
    }

    private static boolean wordChar(char ch) {
        return Character.isLetterOrDigit(ch) || ch == '_';
    }
}
