package me.batman.batmanchat.protection;

import me.batman.batmanchat.logic.core.Opts;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Opts sobre un yml de Bukkit. Si se da "overlay" (la seccion del modulo en proteccion/config.yml),
 * las claves "enabled" y "contexts" salen de ahi y el resto del yml propio del modulo.
 */
final class YamlOpts implements Opts {
    private final ConfigurationSection s;
    private final ConfigurationSection overlay;

    YamlOpts(ConfigurationSection s, ConfigurationSection overlay) {
        this.s = s;
        this.overlay = overlay;
    }

    private ConfigurationSection pick(String path) {
        if (overlay != null && (path.equals("enabled") || path.equals("contexts") || path.startsWith("contexts."))) {
            return overlay;
        }
        return s;
    }

    @Override
    public boolean bool(String path, boolean def) {
        ConfigurationSection p = pick(path);
        return p == null ? def : p.getBoolean(path, def);
    }

    @Override
    public int integer(String path, int def) {
        ConfigurationSection p = pick(path);
        return p == null ? def : p.getInt(path, def);
    }

    @Override
    public double dbl(String path, double def) {
        ConfigurationSection p = pick(path);
        return p == null ? def : p.getDouble(path, def);
    }

    @Override
    public String str(String path, String def) {
        ConfigurationSection p = pick(path);
        return p == null ? def : p.getString(path, def);
    }

    @Override
    public List<String> list(String path) {
        ConfigurationSection p = pick(path);
        return p == null ? List.of() : p.getStringList(path);
    }

    @Override
    public Opts sub(String path) {
        ConfigurationSection p = pick(path);
        ConfigurationSection c = p == null ? null : p.getConfigurationSection(path);
        return new YamlOpts(c, null);
    }

    @Override
    public Set<String> keys(String path) {
        ConfigurationSection p = pick(path);
        ConfigurationSection c = p == null ? null : p.getConfigurationSection(path);
        return c == null ? new LinkedHashSet<>() : new LinkedHashSet<>(c.getKeys(false));
    }

    @Override
    public boolean has(String path) {
        ConfigurationSection p = pick(path);
        return p != null && p.contains(path);
    }
}
