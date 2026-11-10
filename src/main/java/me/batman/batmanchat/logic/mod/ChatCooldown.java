package me.batman.batmanchat.logic.mod;

import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.Context;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.core.Opts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/** Chat Cooldown: limita cuantos mensajes/comandos se pueden enviar en una ventana de tiempo. */
public final class ChatCooldown extends FilterModule {
    private long windowMs;
    private int allowedMessages;
    private int allowedCommands;
    private final List<String> affected = new ArrayList<>();
    private final Map<UUID, ArrayDeque<Long>> chat = new ConcurrentHashMap<>();
    private final Map<UUID, ArrayDeque<Long>> cmd = new ConcurrentHashMap<>();

    public ChatCooldown() {
        super("chat-cooldown");
    }

    @Override
    protected void load(Opts o) {
        windowMs = o.integer("chat-cooldown-in-ticks", 160) * 50L;
        allowedMessages = Math.max(1, o.integer("allowed-message-sends-per-cooldown", 4));
        allowedCommands = Math.max(1, o.integer("allowed-affected-command-runs-per-cooldown", 6));
        affected.clear();
        for (String s : o.list("affected-commands")) {
            affected.add(slash(s));
        }
    }

    @Override
    public void check(Check c) {
        boolean isCmd = c.ctx == Context.COMMAND;
        if (isCmd && !affected(affected, c.commandBase)) {
            return;
        }
        ArrayDeque<Long> q = (isCmd ? cmd : chat).computeIfAbsent(c.uuid, k -> new ArrayDeque<>());
        int limit = isCmd ? allowedCommands : allowedMessages;
        boolean over;
        synchronized (q) {
            while (!q.isEmpty() && c.now - q.peekFirst() > windowMs) {
                q.pollFirst();
            }
            over = q.size() >= limit;
            if (!over) {
                q.addLast(c.now);
            }
        }
        if (over) {
            c.block();
            c.reply("modules.chat-cooldown.trigger");
            c.flag(id, "on-cooldown", null);
        }
    }

    @Override
    public void forget(UUID uuid) {
        chat.remove(uuid);
        cmd.remove(uuid);
    }
}
