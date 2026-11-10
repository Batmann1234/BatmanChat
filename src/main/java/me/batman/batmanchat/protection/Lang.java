package me.batman.batmanchat.protection;

import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Lee proteccion/lang.yml (raiz "lang:"). Los paths son relativos a "lang.", ej: "modules.spam-blocker.trigger". */
public final class Lang {
    private Lang() {
    }

    private static YamlConfiguration cfg() {
        return BatmanChat.get().cfg("proteccion/lang.yml");
    }

    /** Texto crudo (si es una lista, las lineas unidas con salto de linea). Vacio si no existe. */
    public static String raw(String path) {
        return String.join("\n", lines(path));
    }

    /** Lineas crudas (texto o lista). */
    public static List<String> lines(String path) {
        YamlConfiguration c = cfg();
        String full = "lang." + path;
        List<String> out = new ArrayList<>();
        if (c.isList(full)) {
            out.addAll(c.getStringList(full));
        } else {
            String s = c.getString(full);
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    public static String prefix() {
        return cfg().getString("lang.misc.message-prefix", "&#2AFF39&lCHAT &8» &r");
    }

    /** Texto con {PREFIX} y variables (pares CLAVE,valor) aplicadas, sin convertir a Component. */
    public static String text(String path, String... kv) {
        return apply(raw(path), kv);
    }

    public static String apply(String s, String... kv) {
        String r = Text.vars(s, "PREFIX", prefix());
        return Text.vars(r, kv);
    }

    public static String violationType(String key) {
        return cfg().getString("lang.violation-types." + key, key);
    }

    public static String context(String key) {
        return cfg().getString("lang.contexts." + key, key);
    }

    /** Component de varias lineas (por ejemplo para kick), unidas con saltos de linea. */
    public static Component joined(String path, String... kv) {
        List<Component> comps = new ArrayList<>();
        for (String l : lines(path)) {
            comps.add(Text.parse(apply(l, kv)));
        }
        return Component.join(JoinConfiguration.newlines(), comps);
    }

    /** Envia el mensaje (cabecera + mensaje + pie). No hace nada si el texto esta vacio. */
    public static void send(CommandSender to, String path, String... kv) {
        List<String> ls = lines(path);
        if (ls.isEmpty() || to == null) {
            return;
        }
        Player p = to instanceof Player ? (Player) to : null;
        String header = cfg().getString("lang.misc.block-message-header", "");
        String footer = cfg().getString("lang.misc.block-message-footer", "");
        if (!header.isEmpty()) {
            to.sendMessage(Text.parse(p, apply(header, kv)));
        }
        for (String l : ls) {
            to.sendMessage(Text.parse(p, apply(l, kv)));
        }
        if (!footer.isEmpty()) {
            to.sendMessage(Text.parse(p, apply(footer, kv)));
        }
    }

    /** Igual que send pero sin cabecera/pie (para comandos). */
    public static void reply(CommandSender to, String path, String... kv) {
        if (to == null) {
            return;
        }
        Player p = to instanceof Player ? (Player) to : null;
        for (String l : lines(path)) {
            to.sendMessage(Text.parse(p, apply(l, kv)));
        }
    }

    public static void noPermission(CommandSender to) {
        reply(to, "misc.no-permission");
    }
}
