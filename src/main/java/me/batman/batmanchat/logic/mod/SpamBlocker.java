package me.batman.batmanchat.logic.mod;

import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.Context;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.core.Opts;
import me.batman.batmanchat.logic.core.Similarity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Spam Blocker: mensajes "basura" y mensajes repetidos/similares en poco tiempo. */
public final class SpamBlocker extends FilterModule {
    private record Entry(long time, String norm) {
    }

    private boolean floodCombater;
    private boolean singular;
    private String sensitivity;
    private double similarity;
    private double whitelistSimilarity;
    private int allowedRepeats;
    private long cooldownMs;
    private final List<String> whitelist = new ArrayList<>();
    private final List<String> affected = new ArrayList<>();
    private final Map<UUID, ArrayDeque<Entry>> history = new ConcurrentHashMap<>();

    public SpamBlocker() {
        super("spam-blocker");
    }

    @Override
    protected boolean defaultContext(Context ctx) {
        return ctx == Context.CHAT;
    }

    @Override
    protected void load(Opts o) {
        floodCombater = o.bool("flood-combater", true);
        singular = o.bool("block-singular-message-spam", true);
        sensitivity = o.str("singular-message-spam-processor-sensitivity", "NORMAL").toUpperCase();
        similarity = o.dbl("block-repeated-message-similarity-threshold", 0.82);
        whitelistSimilarity = o.dbl("phrase-whitelist-similarity-threshold", 0.65);
        allowedRepeats = Math.max(1, o.integer("allowed-repeats", 4));
        cooldownMs = o.integer("repeat-cooldown-in-seconds", 15) * 1000L;
        whitelist.clear();
        for (String s : o.list("phrase-whitelist")) {
            whitelist.add(Similarity.normalize(s));
        }
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
        String body = c.ctx == Context.COMMAND ? c.argsOrText() : c.text;
        String norm = floodCombater ? Similarity.normalizeCombat(body) : Similarity.normalize(body);
        if (norm.isEmpty()) {
            return;
        }
        for (String w : whitelist) {
            if (!w.isEmpty() && Similarity.ratio(norm, w) >= whitelistSimilarity) {
                return;
            }
        }
        // el chequeo de "mensaje basura" usa el texto sin colapsar letras repetidas (si no, "aaaa..." quedaria en "aa")
        if (singular && isSingularSpam(Similarity.normalize(body))) {
            c.block();
            c.reply("modules.spam-blocker.singular-message-spam-trigger");
            c.flag(id, "spam-block", null);
            return;
        }
        ArrayDeque<Entry> q = history.computeIfAbsent(c.uuid, k -> new ArrayDeque<>());
        boolean spam;
        synchronized (q) {
            while (!q.isEmpty() && c.now - q.peekFirst().time > cooldownMs) {
                q.pollFirst();
            }
            int similar = 0;
            for (Entry e : q) {
                if (Similarity.ratio(norm, e.norm) >= similarity) {
                    similar++;
                }
            }
            spam = similar >= allowedRepeats;
            q.addLast(new Entry(c.now, norm));
            while (q.size() > 40) {
                q.pollFirst();
            }
        }
        if (spam) {
            c.block();
            c.reply("modules.spam-blocker.trigger");
            c.flag(id, "spam-block", null);
        }
    }

    @Override
    public void forget(UUID uuid) {
        history.remove(uuid);
    }

    // ---- deteccion de mensajes individuales "spam" ----

    private boolean isSingularSpam(String s) {
        double minLen;
        double charMax;
        double wordMax;
        int unitMin;
        switch (sensitivity) {
            case "LOWEST" -> { minLen = 24; charMax = 0.08; wordMax = 0.20; unitMin = 40; }
            case "LOW" -> { minLen = 20; charMax = 0.11; wordMax = 0.25; unitMin = 32; }
            case "HIGH" -> { minLen = 12; charMax = 0.22; wordMax = 0.45; unitMin = 16; }
            case "HIGHEST" -> { minLen = 8; charMax = 0.30; wordMax = 0.55; unitMin = 10; }
            default -> { minLen = 16; charMax = 0.15; wordMax = 0.34; unitMin = 24; }
        }
        int len = s.replace(" ", "").length();
        if (len < minLen) {
            return false;
        }
        Set<Character> chars = new HashSet<>();
        for (char ch : s.toCharArray()) {
            if (ch != ' ') {
                chars.add(ch);
            }
        }
        double charDiv = (double) chars.size() / len;
        if (charDiv < charMax) {
            return true;
        }
        String[] words = s.split(" ");
        if (words.length >= 5) {
            Map<String, Integer> seen = new HashMap<>();
            for (String w : words) {
                seen.merge(w, 1, Integer::sum);
            }
            if ((double) seen.size() / words.length < wordMax) {
                return true;
            }
        }
        return len >= unitMin && unitCoverage(s.replace(" ", "")) >= 0.85;
    }

    /** Que tan "periodica" es la cadena (abcabcabc = alta). */
    static double unitCoverage(String s) {
        int n = s.length();
        double best = 0;
        for (int p = 1; p <= n / 3; p++) {
            int match = 0;
            for (int i = p; i < n; i++) {
                if (s.charAt(i) == s.charAt(i - p)) {
                    match++;
                }
            }
            best = Math.max(best, (double) match / (n - p));
            if (best >= 0.95) {
                break;
            }
        }
        return best;
    }
}
