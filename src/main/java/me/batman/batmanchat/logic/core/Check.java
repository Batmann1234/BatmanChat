package me.batman.batmanchat.logic.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Estado de la revision de un unico texto (mensaje, comando, cartel...) a traves de todos los modulos.
 * Los modulos solo leen/escriben aqui; la parte de Bukkit se encarga de aplicar el resultado.
 */
public final class Check {
    public static final class Reply {
        public final String langPath;
        public final Map<String, String> vars;

        Reply(String langPath, Map<String, String> vars) {
            this.langPath = langPath;
            this.vars = vars;
        }
    }

    public final UUID uuid;
    public final String name;
    public final Context ctx;
    /** Texto tal como lo escribio el jugador. */
    public final String original;
    public final long now;

    /** Texto actual (puede ser modificado por los modulos). */
    public String text;
    public boolean blocked;
    public boolean modified;
    /** Para comandos: primer token en minusculas, con "/" (ej. "/msg"). */
    public String commandBase = "";
    public Set<String> onlineNames = Set.of();

    public final List<Flag> flags = new ArrayList<>();
    public final List<Reply> replies = new ArrayList<>();
    /** Acciones del Chat Executor ({player_msg}: ..., {console_cmd}: ...). */
    public final List<String> actions = new ArrayList<>();

    public Check(UUID uuid, String name, Context ctx, String text, long now) {
        this.uuid = uuid;
        this.name = name;
        this.ctx = ctx;
        this.original = text;
        this.text = text;
        this.now = now;
        if (ctx == Context.COMMAND) {
            String t = text.trim();
            int sp = t.indexOf(' ');
            this.commandBase = (sp < 0 ? t : t.substring(0, sp)).toLowerCase();
        }
    }

    public void reply(String langPath, String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i].toLowerCase(), kv[i + 1]);
        }
        replies.add(new Reply(langPath, m));
    }

    public void flag(String moduleId, String typeKey, String blockedContent) {
        flags.add(new Flag(moduleId, typeKey, blockedContent, true, true, true));
    }

    public void flag(Flag f) {
        flags.add(f);
    }

    public void block() {
        blocked = true;
    }

    public void setText(String newText) {
        if (!newText.equals(text)) {
            text = newText;
            modified = true;
        }
    }

    /** Texto del comando sin el primer token (los argumentos). Para chat devuelve el texto completo. */
    public String argsOrText() {
        if (ctx != Context.COMMAND) {
            return text;
        }
        int sp = text.indexOf(' ');
        return sp < 0 ? "" : text.substring(sp + 1);
    }
}
