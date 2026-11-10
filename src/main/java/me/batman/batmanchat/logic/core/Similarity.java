package me.batman.batmanchat.logic.core;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Utilidades de comparacion y normalizacion de texto. */
public final class Similarity {
    private static final Pattern COLOR_TOKENS = Pattern.compile("(?i)[&§]#[0-9a-f]{6}|#[0-9a-f]{6}|[&§][0-9a-fk-or]");

    private Similarity() {
    }

    /** 1.0 = identicos, 0.0 = nada en comun. */
    public static double ratio(String a, String b) {
        if (a.equals(b)) {
            return 1.0;
        }
        int max = Math.max(a.length(), b.length());
        if (max == 0) {
            return 1.0;
        }
        if (max > 400) {
            a = a.substring(0, Math.min(a.length(), 400));
            b = b.substring(0, Math.min(b.length(), 400));
            max = Math.max(a.length(), b.length());
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - ((double) prev[b.length()] / max);
    }

    /** Minusculas y sin tildes, conservando la longitud (1 caracter -> 1 caracter). */
    public static String fold(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            sb.append(foldChar(s.charAt(i)));
        }
        return sb.toString();
    }

    public static char foldChar(char c) {
        c = Character.toLowerCase(c);
        if (c < 128) {
            return c;
        }
        String d = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
        return d.isEmpty() ? c : d.charAt(0);
    }

    /** Quita codigos de color (&c, &#RRGGBB, #RRGGBB). */
    public static String stripColors(String s) {
        return COLOR_TOKENS.matcher(s).replaceAll("");
    }

    /** Forma comparable de un mensaje: sin colores, minusculas, sin tildes, espacios colapsados. */
    public static String normalize(String s) {
        s = stripColors(s).toLowerCase(Locale.ROOT);
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.replaceAll("[^\\p{L}\\p{N}\\s]", "");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** Como {@link #normalize} pero ademas intenta anular los "sufijos aleatorios" de los bots. */
    public static String normalizeCombat(String s) {
        String n = normalize(s);
        if (n.matches(".*\\p{L}.*")) {
            n = n.replaceAll("\\s*\\d+$", "");
        }
        return n.replaceAll("(.)\\1{2,}", "$1$1").trim();
    }
}
