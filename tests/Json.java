import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parser JSON minimo (solo para las pruebas; los ymls se convierten a JSON con Python). */
final class Json {
    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        return j.value();
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private Object value() {
        char c = s.charAt(i);
        if (c == '{') {
            i++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                ws();
                i++; // :
                ws();
                m.put(k, value());
                ws();
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                i++; // }
                return m;
            }
        }
        if (c == '[') {
            i++;
            List<Object> l = new ArrayList<>();
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                if (s.charAt(i) == ',') {
                    i++;
                    continue;
                }
                i++; // ]
                return l;
            }
        }
        if (c == '"') {
            return string();
        }
        if (s.startsWith("true", i)) {
            i += 4;
            return true;
        }
        if (s.startsWith("false", i)) {
            i += 5;
            return false;
        }
        if (s.startsWith("null", i)) {
            i += 4;
            return null;
        }
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        String num = s.substring(st, i);
        return num.contains(".") || num.contains("e") || num.contains("E") ? (Object) Double.parseDouble(num) : (Object) Long.parseLong(num);
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++; // "
        while (s.charAt(i) != '"') {
            char c = s.charAt(i++);
            if (c == '\\') {
                char n = s.charAt(i++);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        i++;
        return sb.toString();
    }
}
