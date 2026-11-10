package me.batman.batmanchat.logic.core;

import java.util.List;
import java.util.Set;

/**
 * Pequeña abstraccion sobre la configuracion (YAML) para que la logica de los filtros
 * no dependa de Bukkit y se pueda probar por separado.
 */
public interface Opts {
    boolean bool(String path, boolean def);

    int integer(String path, int def);

    double dbl(String path, double def);

    String str(String path, String def);

    /** Lista de textos (vacia si no existe). */
    List<String> list(String path);

    /** Subseccion (nunca null; si no existe devuelve una vacia). */
    Opts sub(String path);

    /** Claves hijas directas de una ruta (vacio si no existe). */
    Set<String> keys(String path);

    boolean has(String path);
}
