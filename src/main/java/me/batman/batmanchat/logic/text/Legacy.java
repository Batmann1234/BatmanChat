package me.batman.batmanchat.logic.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Interprete de texto con codigos de color estilo Minecraft: {@code &c}, {@code &#RRGGBB}, {@code #RRGGBB},
 * formatos ({@code &l &o &n &m &k &r}) y etiquetas {@code <gradient:#A:#B>...</gradient>} / {@code <rainbow>...</rainbow>}.
 * Un codigo de color reinicia los formatos (igual que en el chat de Minecraft).
 * Es independiente de Adventure para poder probarlo por separado.
 */
public final class Legacy {
    /** Los caracteres U+E000..U+E0FF se reservan como "marcadores" donde se insertan componentes. */
    public static final char MARKER_BASE = '';

    private static final int[] COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    private Legacy() {
    }

    private static final class State {
        int rgb = -1;
        boolean b;
        boolean i;
        boolean u;
        boolean s;
        boolean o;

        State copy() {
            State c = new State();
            c.rgb = rgb;
            c.b = b;
            c.i = i;
            c.u = u;
            c.s = s;
            c.o = o;
            return c;
        }

        void clearFormats() {
            b = false;
            i = false;
            u = false;
            s = false;
            o = false;
        }

        Seg seg(String text) {
            return new Seg(text, rgb, b, i, u, s, o);
        }
    }

    public static List<Seg> parse(String s, Allow allow) {
        List<Seg> out = parse(s, allow, new State());
        return merge(out);
    }

    public static List<Seg> parse(String s) {
        return parse(s, Allow.ALL);
    }

    private static List<Seg> parse(String s, Allow allow, State st) {
        List<Seg> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '&' || ch == '§') {
                if (i + 1 < n) {
                    char nx = s.charAt(i + 1);
                    if (nx == '#' && allow.hex && i + 8 <= n && isHex(s, i + 2, 6)) {
                        flush(out, buf, st);
                        st.rgb = Integer.parseInt(s.substring(i + 2, i + 8), 16);
                        st.clearFormats();
                        i += 8;
                        continue;
                    }
                    if (isCode(nx) && allow.code(nx)) {
                        flush(out, buf, st);
                        apply(st, Character.toLowerCase(nx));
                        i += 2;
                        continue;
                    }
                }
            } else if (ch == '#' && allow.hex && i + 7 <= n && isHex(s, i + 1, 6)) {
                flush(out, buf, st);
                st.rgb = Integer.parseInt(s.substring(i + 1, i + 7), 16);
                st.clearFormats();
                i += 7;
                continue;
            } else if (ch == '<' && allow.gradient) {
                int consumed = tag(s, i, allow, st, out, buf);
                if (consumed > 0) {
                    i += consumed;
                    continue;
                }
            }
            buf.append(ch);
            i++;
        }
        flush(out, buf, st);
        return out;
    }

    /** Intenta interpretar un degradado/arcoiris en la posicion i. Devuelve cuantos caracteres consumio (0 = no es etiqueta). */
    private static int tag(String s, int i, Allow allow, State st, List<Seg> out, StringBuilder buf) {
        String lower = s.toLowerCase(Locale.ROOT);
        boolean grad = lower.startsWith("<gradient:", i);
        boolean rain = lower.startsWith("<rainbow", i);
        if (!grad && !rain) {
            return 0;
        }
        int gt = s.indexOf('>', i);
        if (gt < 0) {
            return 0;
        }
        List<Integer> stops = new ArrayList<>();
        if (grad) {
            for (String p : s.substring(i + 10, gt).split(":")) {
                p = p.trim();
                if (p.startsWith("#") && p.length() == 7 && isHex(p, 1, 6)) {
                    stops.add(Integer.parseInt(p.substring(1), 16));
                }
            }
            if (stops.size() < 2) {
                return 0;
            }
        }
        String closeTag = grad ? "</gradient>" : "</rainbow>";
        int close = lower.indexOf(closeTag, gt + 1);
        String inner = s.substring(gt + 1, close < 0 ? s.length() : close);
        flush(out, buf, st);
        List<Seg> innerSegs = parse(inner, allow, st.copy());
        int total = 0;
        for (Seg sg : innerSegs) {
            for (int k = 0; k < sg.text.length(); k++) {
                if (!isMarker(sg.text.charAt(k))) {
                    total++;
                }
            }
        }
        int idx = 0;
        for (Seg sg : innerSegs) {
            for (int k = 0; k < sg.text.length(); k++) {
                char c = sg.text.charAt(k);
                if (isMarker(c)) {
                    out.add(sg.withText(String.valueOf(c)));
                    continue;
                }
                double t = total <= 1 ? 0 : (double) idx / (total - 1);
                int color = grad ? lerp(stops, t) : rainbow(t);
                out.add(sg.withText(String.valueOf(c)).withRgb(color));
                idx++;
            }
        }
        int end = close < 0 ? s.length() : close + closeTag.length();
        return end - i;
    }

    private static void flush(List<Seg> out, StringBuilder buf, State st) {
        if (buf.length() > 0) {
            out.add(st.seg(buf.toString()));
            buf.setLength(0);
        }
    }

    private static void apply(State st, char code) {
        int idx = "0123456789abcdef".indexOf(code);
        if (idx >= 0) {
            st.rgb = COLORS[idx];
            st.clearFormats();
            return;
        }
        switch (code) {
            case 'l' -> st.b = true;
            case 'o' -> st.i = true;
            case 'n' -> st.u = true;
            case 'm' -> st.s = true;
            case 'k' -> st.o = true;
            case 'r' -> {
                st.rgb = -1;
                st.clearFormats();
            }
            default -> {
            }
        }
    }

    public static boolean isMarker(char c) {
        return c >= MARKER_BASE && c <= '';
    }

    private static boolean isCode(char c) {
        return "0123456789abcdefklmnorABCDEFKLMNOR".indexOf(c) >= 0;
    }

    private static boolean isHex(String s, int from, int len) {
        if (from + len > s.length()) {
            return false;
        }
        for (int k = from; k < from + len; k++) {
            if (Character.digit(s.charAt(k), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int lerp(List<Integer> stops, double t) {
        double pos = t * (stops.size() - 1);
        int a = Math.min((int) Math.floor(pos), stops.size() - 2);
        double f = pos - a;
        int c1 = stops.get(a);
        int c2 = stops.get(a + 1);
        int r = (int) Math.round(((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * f);
        int g = (int) Math.round(((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * f);
        int b = (int) Math.round((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * f);
        return (r << 16) | (g << 8) | b;
    }

    private static int rainbow(double t) {
        // HSB(h, 1, 1) -> RGB sin depender de java.awt
        double h6 = (t - Math.floor(t)) * 6.0;
        int sector = (int) Math.floor(h6) % 6;
        double f = h6 - Math.floor(h6);
        int q = (int) Math.round(255 * (1 - f));
        int up = (int) Math.round(255 * f);
        return switch (sector) {
            case 0 -> (255 << 16) | (up << 8);
            case 1 -> (q << 16) | (255 << 8);
            case 2 -> (255 << 8) | up;
            case 3 -> (q << 8) | 255;
            case 4 -> (up << 16) | 255;
            default -> (255 << 16) | q;
        };
    }

    private static List<Seg> merge(List<Seg> in) {
        List<Seg> out = new ArrayList<>();
        for (Seg s : in) {
            if (s.text.isEmpty()) {
                continue;
            }
            if (!out.isEmpty() && out.get(out.size() - 1).sameStyle(s)) {
                Seg last = out.remove(out.size() - 1);
                out.add(last.withText(last.text + s.text));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** Texto sin ningun codigo de color/formato. */
    public static String plain(String s) {
        StringBuilder sb = new StringBuilder();
        for (Seg sg : parse(s, Allow.ALL)) {
            sb.append(sg.text);
        }
        return sb.toString();
    }
}
