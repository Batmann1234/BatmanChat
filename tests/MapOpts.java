import me.batman.batmanchat.logic.core.Opts;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Opts sobre Maps/Listas (JSON ya parseado). Solo para las pruebas. */
final class MapOpts implements Opts {
    private final Object root;

    MapOpts(Object root) {
        this.root = root;
    }

    private Object get(String path) {
        Object cur = root;
        if (path.isEmpty()) {
            return cur;
        }
        for (String p : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m) || !m.containsKey(p)) {
                return null;
            }
            cur = m.get(p);
        }
        return cur;
    }

    @Override
    public boolean bool(String path, boolean def) {
        Object o = get(path);
        return o instanceof Boolean b ? b : def;
    }

    @Override
    public int integer(String path, int def) {
        Object o = get(path);
        return o instanceof Number n ? n.intValue() : def;
    }

    @Override
    public double dbl(String path, double def) {
        Object o = get(path);
        return o instanceof Number n ? n.doubleValue() : def;
    }

    @Override
    public String str(String path, String def) {
        Object o = get(path);
        return o == null ? def : String.valueOf(o);
    }

    @Override
    public List<String> list(String path) {
        Object o = get(path);
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) {
            for (Object x : l) {
                out.add(String.valueOf(x));
            }
        }
        return out;
    }

    @Override
    public Opts sub(String path) {
        Object o = get(path);
        return new MapOpts(o instanceof Map ? o : Map.of());
    }

    @Override
    public Set<String> keys(String path) {
        Object o = get(path);
        Set<String> out = new LinkedHashSet<>();
        if (o instanceof Map<?, ?> m) {
            for (Object k : m.keySet()) {
                out.add(String.valueOf(k));
            }
        }
        return out;
    }

    @Override
    public boolean has(String path) {
        return get(path) != null;
    }
}
