package me.batman.batmanchat.logic.text;

import java.util.HashSet;
import java.util.Set;

/** Que codigos de formato se interpretan (para mensajes de jugadores segun sus permisos). */
public final class Allow {
    public static final Allow ALL = new Allow(true, Set.of(), true, true);
    public static final Allow NONE = new Allow(false, Set.of(), false, false);

    public final boolean all;
    private final Set<Character> codes;
    public final boolean hex;
    public final boolean gradient;

    public Allow(boolean all, Set<Character> codes, boolean hex, boolean gradient) {
        this.all = all;
        this.codes = new HashSet<>(codes);
        this.hex = all || hex;
        this.gradient = all || gradient;
    }

    public boolean code(char c) {
        return all || codes.contains(Character.toLowerCase(c));
    }

    public boolean any() {
        return all || !codes.isEmpty() || hex || gradient;
    }
}
