package me.batman.batmanchat.logic.core;

/** Una infraccion detectada por un modulo. */
public final class Flag {
    public final String moduleId;
    /** Clave dentro de {@code lang.yml -> lang.violation-types}. */
    public final String typeKey;
    /** Contenido exacto que se bloqueo (puede ser null). */
    public final String blockedContent;
    /** Si cuenta como advertencia para el auto castigo. */
    public final boolean warn;
    public final boolean notify;
    public final boolean log;

    public Flag(String moduleId, String typeKey, String blockedContent, boolean warn, boolean notify, boolean log) {
        this.moduleId = moduleId;
        this.typeKey = typeKey;
        this.blockedContent = blockedContent;
        this.warn = warn;
        this.notify = notify;
        this.log = log;
    }
}
