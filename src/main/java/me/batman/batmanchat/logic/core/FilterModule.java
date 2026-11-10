package me.batman.batmanchat.logic.core;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Base de los modulos de proteccion (los de ChatSentry que revisan texto). */
public abstract class FilterModule {
    public final String id;
    public boolean enabled;
    protected final Set<Context> contexts = new HashSet<>();

    protected FilterModule(String id) {
        this.id = id;
    }

    /** Lee {@code enabled} y {@code contexts} y despues las opciones propias del modulo. */
    public final void reload(Opts o) {
        enabled = o.bool("enabled", false);
        contexts.clear();
        Opts c = o.sub("contexts");
        for (Context ctx : Context.values()) {
            if (c.bool(ctx.key, defaultContext(ctx))) {
                contexts.add(ctx);
            }
        }
        load(o);
    }

    /** Contexto por defecto cuando el yml no declara {@code contexts}. */
    protected boolean defaultContext(Context ctx) {
        return ctx == Context.CHAT;
    }

    protected abstract void load(Opts o);

    public boolean appliesTo(Context ctx) {
        return enabled && contexts.contains(ctx);
    }

    /** Revisa el texto. Debe marcar {@link Check#block()} o modificar {@link Check#text}. */
    public abstract void check(Check c);

    /** Limpia el historial de un jugador (al desconectarse). */
    public void forget(java.util.UUID uuid) {
    }

    // ---- utilidades comunes ----

    protected static boolean affected(List<String> affectedCommands, String base) {
        if (affectedCommands.isEmpty()) {
            return true;
        }
        for (String s : affectedCommands) {
            if (s.equalsIgnoreCase(base)) {
                return true;
            }
        }
        return false;
    }

    protected static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** Hace que un yml sin "/" delante tambien funcione. */
    protected static String slash(String s) {
        s = s.trim().toLowerCase(Locale.ROOT);
        return s.startsWith("/") ? s : "/" + s;
    }
}
