package me.batman.batmanchat.protection;

import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.Context;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.core.Opts;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Bots estatua: el jugador no puede hablar (ni usar comandos, si el contexto esta activo) hasta moverse. */
final class AntiStatueSpambot extends FilterModule {
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Set<String> commandWhitelist = new HashSet<>();

    AntiStatueSpambot() {
        super("anti-statue-spambot");
    }

    @Override
    protected void load(Opts o) {
        commandWhitelist.clear();
        for (String s : o.list("join-command-whitelist")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            commandWhitelist.add(t.startsWith("/") ? t : "/" + t);
        }
    }

    void joined(UUID uuid) {
        frozen.add(uuid);
    }

    void moved(UUID uuid) {
        frozen.remove(uuid);
    }

    boolean isFrozen(UUID uuid) {
        return frozen.contains(uuid);
    }

    @Override
    public void check(Check c) {
        if (!frozen.contains(c.uuid)) {
            return;
        }
        if (c.ctx == Context.COMMAND && commandWhitelist.contains(c.commandBase)) {
            return;
        }
        c.reply("modules.anti-statue-spambot.trigger");
        c.flag(id, "anti-statue-spambot-block", null);
        c.block();
    }

    @Override
    public void forget(UUID uuid) {
        frozen.remove(uuid);
    }
}
