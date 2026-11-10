package me.batman.batmanchat.logic.mod;

import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.core.Opts;
import me.batman.batmanchat.logic.core.Similarity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Anti Parrot: bots/hacks que copian el mensaje de otro jugador (o varios jugadores diciendo lo mismo a la vez). */
public final class AntiParrot extends FilterModule {
    private record Entry(long time, UUID uuid, String norm) {
    }

    private boolean intelligent;
    private boolean floodCombater;
    private boolean ignoreShort;
    private boolean ignoreUsernames;
    private double whitelistSimilarity;
    private long windowMs;
    private final List<String> whitelist = new ArrayList<>();
    private final ArrayDeque<Entry> recent = new ArrayDeque<>();

    public AntiParrot() {
        super("anti-parrot");
    }

    @Override
    protected void load(Opts o) {
        intelligent = o.bool("intelligent", true);
        floodCombater = o.bool("flood-combater", true);
        ignoreShort = o.bool("ignore-short", true);
        ignoreUsernames = o.bool("ignore-usernames", true);
        whitelistSimilarity = o.dbl("phrase-whitelist-similarity-threshold", 0.85);
        windowMs = o.integer("time-window-seconds", 7) * 1000L;
        whitelist.clear();
        for (String s : o.list("phrase-whitelist")) {
            whitelist.add(Similarity.normalize(s));
        }
    }

    @Override
    public void check(Check c) {
        String text = c.text;
        if (ignoreUsernames) {
            for (String n : c.onlineNames) {
                text = text.replaceAll("(?i)" + java.util.regex.Pattern.quote(n), " ");
            }
        }
        String norm = floodCombater ? Similarity.normalizeCombat(text) : Similarity.normalize(text);
        if (norm.isEmpty() || (ignoreShort && norm.length() <= 4)) {
            return;
        }
        for (String w : whitelist) {
            if (!w.isEmpty() && Similarity.ratio(norm, w) >= whitelistSimilarity) {
                return;
            }
        }
        double threshold = intelligent ? 0.85 : 0.97;
        boolean parrot = false;
        synchronized (recent) {
            while (!recent.isEmpty() && c.now - recent.peekFirst().time > windowMs) {
                recent.pollFirst();
            }
            for (Entry e : recent) {
                if (!e.uuid.equals(c.uuid) && Similarity.ratio(norm, e.norm) >= threshold) {
                    parrot = true;
                    break;
                }
            }
            if (!parrot) {
                recent.addLast(new Entry(c.now, c.uuid, norm));
                while (recent.size() > 100) {
                    recent.pollFirst();
                }
            }
        }
        if (parrot) {
            c.block();
            c.reply("modules.anti-parrot.trigger");
            c.flag(id, "anti-parrot-block", null);
        }
    }
}
