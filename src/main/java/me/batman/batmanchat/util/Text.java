package me.batman.batmanchat.util;

import me.batman.batmanchat.BatmanChat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Colores y formato para todo el plugin:
 * &amp;a (colores/formatos), &amp;#RRGGBB y #RRGGBB (hex), &lt;gradient:#A:#B&gt;texto&lt;/gradient&gt;
 * y &lt;center&gt;texto&lt;/center&gt; (centrado en el chat).
 */
public final class Text {
    private Text() {
    }

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final Pattern BARE_HEX = Pattern.compile("(?<!&)#([0-9A-Fa-f]{6})");
    private static final Pattern GRADIENT =
            Pattern.compile("<gradient:([^>]+)>(.*?)</gradient>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CENTER =
            Pattern.compile("<center>(.*?)</center>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STRIP = Pattern.compile("(?i)&#[0-9A-F]{6}|(?<!&)#[0-9A-F]{6}|&[0-9A-FK-OR]");
    private static final Pattern PREFIX_TOKEN = Pattern.compile("(?i)\\{prefix}");

    private static final int CENTER_PX = 154;

    // ------------------------------------------------------------------ parseo

    /** Convierte un texto con codigos a Component (sin PlaceholderAPI). */
    public static Component parse(String raw) {
        return LEGACY.deserialize(prepare(raw, null));
    }

    /** Igual que parse pero resolviendo PlaceholderAPI para el jugador (puede ser null). */
    public static Component parse(Player papiFor, String raw) {
        return LEGACY.deserialize(prepare(raw, papiFor));
    }

    /** Deja el texto en formato legacy con &amp;#RRGGBB, ya centrado y con gradientes expandidos. */
    public static String prepare(String raw, Player papiFor) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace('§', '&');
        if (papiFor != null) {
            s = Hooks.papi(papiFor, s).replace('§', '&');
        }
        s = expandGradients(s);
        s = BARE_HEX.matcher(s).replaceAll("&#$1");
        s = applyCenter(s);
        return s;
    }

    public static List<Component> parseLines(Player papiFor, List<String> raw) {
        List<Component> out = new ArrayList<>();
        for (String l : raw) {
            out.add(parse(papiFor, l));
        }
        return out;
    }

    /** Quita todos los codigos de color/formato (&amp;a, &amp;#hex, #hex). */
    public static String strip(String s) {
        if (s == null) {
            return "";
        }
        return STRIP.matcher(s.replace('§', '&')).replaceAll("");
    }

    public static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    // ------------------------------------------------------------------ variables y envio

    /** Reemplaza {clave} (sin importar mayusculas) por valor. Pares: clave, valor, clave, valor... */
    public static String vars(String s, String... kv) {
        if (s == null) {
            return "";
        }
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String key = kv[i];
            String val = kv[i + 1] == null ? "" : kv[i + 1];
            s = Pattern.compile("(?i)\\{" + Pattern.quote(key) + "}").matcher(s).replaceAll(Matcher.quoteReplacement(val));
        }
        return s;
    }

    /** Prefijo del plugin (mensajes.yml: Message.Prefix). */
    public static String prefix() {
        return BatmanChat.get().cfg("mensajes.yml").getString("Message.Prefix", "#2AFF39&lCHAT &8»&r");
    }

    /** Aplica variables y {Prefix}. */
    public static String format(String raw, String... kv) {
        String s = vars(raw, kv);
        return PREFIX_TOKEN.matcher(s).replaceAll(Matcher.quoteReplacement(prefix()));
    }

    public static void send(CommandSender to, String raw, String... kv) {
        if (raw == null || raw.isEmpty() || to == null) {
            return;
        }
        Player p = to instanceof Player ? (Player) to : null;
        to.sendMessage(parse(p, format(raw, kv)));
    }

    public static void sendLines(CommandSender to, List<String> lines, String... kv) {
        for (String l : lines) {
            send(to, l, kv);
        }
    }

    /** Envia el mensaje de mensajes.yml en esa ruta (ej: "Message.No_Permission"). */
    public static void sendKey(CommandSender to, String path, String... kv) {
        send(to, BatmanChat.get().cfg("mensajes.yml").getString(path, ""), kv);
    }

    /** Lee un valor que puede ser texto o lista como lista de textos. */
    public static List<String> asList(YamlConfiguration cfg, String path) {
        if (cfg.isList(path)) {
            return cfg.getStringList(path);
        }
        String s = cfg.getString(path);
        List<String> out = new ArrayList<>();
        if (s != null && !s.isEmpty()) {
            out.add(s);
        }
        return out;
    }

    // ------------------------------------------------------------------ utilidades de texto

    private static final String SMALL_FROM = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALL_TO = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ";

    /** "tienda" -> "ᴛɪᴇɴᴅᴀ". No toca los codigos de color. */
    public static String smallCaps(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                sb.append(c).append(s.charAt(++i));
                continue;
            }
            int idx = SMALL_FROM.indexOf(Character.toLowerCase(c));
            sb.append(idx >= 0 ? SMALL_TO.charAt(idx) : c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ gradientes

    private static String expandGradients(String s) {
        if (!s.toLowerCase().contains("<gradient:")) {
            return s;
        }
        Matcher m = GRADIENT.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String[] stopsRaw = m.group(1).split(":");
            List<int[]> stops = new ArrayList<>();
            for (String st : stopsRaw) {
                String h = st.trim().replace("&", "").replace("#", "");
                if (h.length() == 6) {
                    try {
                        int v = Integer.parseInt(h, 16);
                        stops.add(new int[]{(v >> 16) & 255, (v >> 8) & 255, v & 255});
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            String replacement = stops.size() >= 2 ? gradientize(m.group(2), stops) : m.group(2);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String gradientize(String body, List<int[]> stops) {
        List<Character> chars = new ArrayList<>();
        List<String> decors = new ArrayList<>();
        StringBuilder decor = new StringBuilder();
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '&' && i + 1 < body.length()) {
                char n = body.charAt(i + 1);
                if (n == '#' && i + 7 < body.length() + 0 && isHex(body, i + 2, 6)) {
                    i += 7;
                    continue;
                }
                if ("kKlLmMnNoO".indexOf(n) >= 0) {
                    decor.append('&').append(n);
                    i++;
                    continue;
                }
                if ("rR".indexOf(n) >= 0) {
                    decor.setLength(0);
                    i++;
                    continue;
                }
                if ("0123456789aAbBcCdDeEfF".indexOf(n) >= 0) {
                    i++;
                    continue;
                }
            }
            chars.add(c);
            decors.add(decor.toString());
        }
        int n = chars.size();
        StringBuilder sb = new StringBuilder();
        int segments = stops.size() - 1;
        for (int k = 0; k < n; k++) {
            double t = n == 1 ? 0 : k / (double) (n - 1);
            double pos = t * segments;
            int idx = Math.min((int) pos, segments - 1);
            double local = pos - idx;
            int[] a = stops.get(idx);
            int[] b = stops.get(idx + 1);
            int r = (int) Math.round(a[0] + (b[0] - a[0]) * local);
            int g = (int) Math.round(a[1] + (b[1] - a[1]) * local);
            int bl = (int) Math.round(a[2] + (b[2] - a[2]) * local);
            sb.append(String.format("&#%02X%02X%02X", r, g, bl)).append(decors.get(k)).append(chars.get(k));
        }
        return sb.toString();
    }

    private static boolean isHex(String s, int from, int len) {
        if (from + len > s.length()) {
            return false;
        }
        for (int i = from; i < from + len; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ centrado

    private static String applyCenter(String s) {
        if (!s.toLowerCase().contains("<center>")) {
            return s;
        }
        Matcher m = CENTER.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String inner = m.group(1);
            int px = width(inner);
            int compensate = CENTER_PX - px / 2;
            int spaces = Math.max(0, compensate / 4);
            m.appendReplacement(out, Matcher.quoteReplacement(" ".repeat(spaces) + inner));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Ancho en pixeles del texto en la fuente por defecto del chat (ignora codigos de color). */
    public static int width(String s) {
        int px = 0;
        boolean bold = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == '#' && isHex(s, i + 2, 6)) {
                    i += 7;
                    bold = false;
                    continue;
                }
                if (n == 'l' || n == 'L') {
                    bold = true;
                    i++;
                    continue;
                }
                if ("0123456789abcdefABCDEFrR".indexOf(n) >= 0) {
                    bold = false;
                    i++;
                    continue;
                }
                if ("kKmMnNoO".indexOf(n) >= 0) {
                    i++;
                    continue;
                }
            }
            px += charWidth(c) + (bold ? 1 : 0) + 1;
        }
        return px;
    }

    private static int charWidth(char c) {
        switch (c) {
            case 'i':
            case 'l':
            case '!':
            case ':':
            case ';':
            case '|':
            case '\'':
            case '.':
            case ',':
                return 1;
            case '`':
                return 2;
            case 'I':
            case '[':
            case ']':
            case '"':
            case ' ':
                return 3;
            case 'f':
            case 'k':
            case 't':
            case '(':
            case ')':
            case '<':
            case '>':
            case '{':
            case '}':
            case '*':
                return 4;
            case '@':
            case '~':
                return 6;
            case 'ɪ':
                return 3;
            case '→':
            case '←':
                return 7;
            default:
                return 5;
        }
    }
}
