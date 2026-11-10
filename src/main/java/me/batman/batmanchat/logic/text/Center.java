package me.batman.batmanchat.logic.text;

import java.util.List;
import java.util.function.IntUnaryOperator;

/** Calculo del relleno para centrar una linea en el chat (ancho de 320 px, fuente por defecto de Minecraft). */
public final class Center {
    private static final int CENTER_PX = 154;

    private Center() {
    }

    /** Ancho en pixeles (sin contar el pixel de separacion) de un caracter. */
    public static int width(char c, boolean bold) {
        int w;
        switch (c) {
            case 'I', '*', '(', ')', '{', '}', '[', ']', '"', 't', '<', '>' -> w = c == 't' || c == '<' || c == '>' ? 4 : 3;
            case 'f', 'k' -> w = 4;
            case 'i', 'l', '!', ':', ';', '|', '.', ',', '\'' -> w = 1;
            case '`' -> w = 2;
            case '@', '~' -> w = 6;
            case ' ' -> w = 3;
            default -> w = 5;
        }
        if (c > 127) {
            // caracteres fuera de ASCII: small caps y flechas ocupan ~5 px
            w = c == '→' || c == '←' ? 7 : 5;
        }
        return bold && c != ' ' ? w + 1 : w;
    }

    /** Pixeles totales de una linea (cada caracter suma su ancho + 1 de separacion). */
    public static int pixels(List<Seg> segs, IntUnaryOperator markerWidth) {
        int px = 0;
        for (Seg s : segs) {
            for (int i = 0; i < s.text.length(); i++) {
                char c = s.text.charAt(i);
                if (Legacy.isMarker(c)) {
                    px += markerWidth == null ? 0 : markerWidth.applyAsInt(c);
                } else {
                    px += width(c, s.bold) + 1;
                }
            }
        }
        return px;
    }

    /** Cantidad de espacios a anteponer para centrar. */
    public static int spaces(List<Seg> segs, IntUnaryOperator markerWidth) {
        int px = pixels(segs, markerWidth);
        int toCompensate = CENTER_PX - px / 2;
        if (toCompensate <= 0) {
            return 0;
        }
        return toCompensate / 4;
    }
}
