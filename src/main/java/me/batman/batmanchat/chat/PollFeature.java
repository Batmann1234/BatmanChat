package me.batman.batmanchat.chat;

import me.batman.batmanchat.AbstractFeature;
import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.util.Display;
import me.batman.batmanchat.util.Text;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Encuestas en el chat: /encuesta crear|votar|resultados|cerrar. Config: encuestas.yml. */
public final class PollFeature extends AbstractFeature {
    private static final class Poll {
        String question;
        String creator;
        List<String> options = new ArrayList<>();
        Map<UUID, Integer> votes = new HashMap<>();
        long endAt;
        long totalMs;
        BossBar bar;
        BukkitTask task;
    }

    private Poll poll;

    public PollFeature(BatmanChat plugin) {
        super(plugin);
    }

    @Override
    public String id() {
        return "encuestas";
    }

    @Override
    public void enable() {
        poll = null;
        Cmd cmd = new Cmd();
        command("encuesta", cmd);
        listen(new Listener() {
            @EventHandler
            public void onJoin(PlayerJoinEvent e) {
                if (poll != null && poll.bar != null) {
                    e.getPlayer().showBossBar(poll.bar);
                }
            }
        });
    }

    @Override
    protected void onDisable() {
        if (poll != null) {
            hideBar(poll);
            poll = null;
        }
    }

    private YamlConfiguration cfg() {
        return plugin.cfg("encuestas.yml");
    }

    private void hideBar(Poll p) {
        if (p.bar != null) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                o.hideBossBar(p.bar);
            }
        }
        if (p.task != null) {
            p.task.cancel();
        }
    }

    private int[] counts(Poll p) {
        int[] c = new int[p.options.size()];
        for (int v : p.votes.values()) {
            if (v >= 0 && v < c.length) {
                c[v]++;
            }
        }
        return c;
    }

    private String bar(int pct) {
        YamlConfiguration y = cfg();
        int len = Math.max(1, y.getInt("Barra.Largo", 10));
        int filled = Math.round(pct * len / 100f);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            String piece = i < filled ? y.getString("Barra.Lleno", "&a█") : y.getString("Barra.Vacio", "&7░");
            sb.append(piece);
        }
        return sb.toString();
    }

    private List<Component> resultLines(Poll p) {
        YamlConfiguration y = cfg();
        int[] c = counts(p);
        int total = 0;
        int max = -1;
        for (int v : c) {
            total += v;
            max = Math.max(max, v);
        }
        List<Component> out = new ArrayList<>();
        for (int i = 0; i < c.length; i++) {
            int pct = total == 0 ? 0 : Math.round(c[i] * 100f / total);
            out.add(Text.parse(Text.vars(y.getString("Final.Linea", ""),
                    "n", String.valueOf(i + 1), "opcion", p.options.get(i), "votos", String.valueOf(c[i]),
                    "porcentaje", String.valueOf(pct), "barra", bar(pct))));
        }
        return out;
    }

    private void announceResults(Poll p) {
        YamlConfiguration y = cfg();
        for (String l : y.getStringList("Final.Encabezado")) {
            Bukkit.broadcast(Text.parse(Text.vars(l, "pregunta", p.question, "creador", p.creator)));
        }
        for (Component c : resultLines(p)) {
            Bukkit.broadcast(c);
        }
        int[] c = counts(p);
        int total = 0;
        int max = 0;
        for (int v : c) {
            total += v;
            max = Math.max(max, v);
        }
        if (total == 0) {
            Bukkit.broadcast(Text.parse(y.getString("Final.Sin_Votos", "")));
        } else {
            List<String> winners = new ArrayList<>();
            for (int i = 0; i < c.length; i++) {
                if (c[i] == max) {
                    winners.add(p.options.get(i));
                }
            }
            if (winners.size() == 1) {
                Bukkit.broadcast(Text.parse(Text.vars(y.getString("Final.Ganador", ""), "ganador", winners.get(0))));
            } else {
                Bukkit.broadcast(Text.parse(y.getString("Final.Empate", "")));
            }
        }
        for (String l : y.getStringList("Final.Pie")) {
            Bukkit.broadcast(Text.parse(l));
        }
    }

    private void finish(boolean announce) {
        Poll p = poll;
        if (p == null) {
            return;
        }
        poll = null;
        hideBar(p);
        if (announce) {
            announceResults(p);
        }
    }

    private void start(CommandSender s, int seconds, String question, List<String> options) {
        YamlConfiguration y = cfg();
        Poll p = new Poll();
        p.question = question;
        p.creator = s instanceof Player ? s.getName() : "Consola";
        p.options = options;
        p.totalMs = seconds * 1000L;
        p.endAt = System.currentTimeMillis() + p.totalMs;
        poll = p;

        for (String l : y.getStringList("Anuncio")) {
            Bukkit.broadcast(Text.parse(Text.vars(l, "pregunta", question, "creador", p.creator, "tiempo", String.valueOf(seconds))));
        }
        for (int i = 0; i < options.size(); i++) {
            Component c = Text.parse(Text.vars(y.getString("Opcion_Formato", ""), "n", String.valueOf(i + 1), "opcion", options.get(i)))
                    .hoverEvent(HoverEvent.showText(Text.parse(Text.vars(y.getString("Opcion_Hover", ""), "opcion", options.get(i)))))
                    .clickEvent(ClickEvent.runCommand("/encuesta votar " + (i + 1)));
            Bukkit.broadcast(c);
        }
        if (y.getBoolean("Sonido.toggle", false)) {
            Display.sound(new ArrayList<>(Bukkit.getOnlinePlayers()), y.getString("Sonido.value", ""),
                    (float) y.getDouble("Sonido.volume", 1.0), (float) y.getDouble("Sonido.pitch", 1.0));
        }
        if (y.getBoolean("Bossbar.Enable", true)) {
            p.bar = BossBar.bossBar(Component.empty(), 1f,
                    Display.enumOf(BossBar.Color.class, y.getString("Bossbar.Color", "YELLOW"), BossBar.Color.YELLOW),
                    Display.enumOf(BossBar.Overlay.class, y.getString("Bossbar.Style", "PROGRESS"), BossBar.Overlay.PROGRESS));
            for (Player o : Bukkit.getOnlinePlayers()) {
                o.showBossBar(p.bar);
            }
        }
        final Poll fp = p;
        p.task = repeat(() -> {
            if (poll != fp) {
                return;
            }
            long left = fp.endAt - System.currentTimeMillis();
            if (left <= 0) {
                finish(true);
                return;
            }
            if (fp.bar != null) {
                fp.bar.progress(Math.max(0f, Math.min(1f, (float) left / fp.totalMs)));
                fp.bar.name(Text.parse(Text.vars(cfg().getString("Bossbar.Titulo", ""),
                        "pregunta", fp.question, "tiempo", String.valueOf((left + 999) / 1000))));
            }
        }, 0L, 20L);
    }

    private final class Cmd implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
            YamlConfiguration y = cfg();
            if (!y.getBoolean("Enable", true)) {
                return true;
            }
            String sub = args.length == 0 ? "resultados" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "crear":
                case "create":
                    return create(s, args);
                case "cerrar":
                case "close":
                    if (!s.hasPermission("batmanchat.encuesta.crear")) {
                        Text.sendKey(s, "Message.No_Permission");
                        return true;
                    }
                    if (poll == null) {
                        Text.send(s, y.getString("Mensajes.Sin_Encuesta", ""));
                        return true;
                    }
                    finish(true);
                    return true;
                case "votar":
                case "vote":
                    return vote(s, args);
                default:
                    if (poll == null) {
                        Text.send(s, y.getString("Mensajes.Sin_Encuesta", ""));
                        return true;
                    }
                    Text.send(s, y.getString("Mensajes.Resultados_Parciales", ""));
                    s.sendMessage(Text.parse("&f" + poll.question));
                    for (Component line : resultLines(poll)) {
                        s.sendMessage(line);
                    }
                    return true;
            }
        }

        private boolean create(CommandSender s, String[] args) {
            YamlConfiguration y = cfg();
            if (!s.hasPermission("batmanchat.encuesta.crear")) {
                Text.sendKey(s, "Message.No_Permission");
                return true;
            }
            if (poll != null) {
                Text.send(s, y.getString("Mensajes.Ya_Hay", ""));
                return true;
            }
            if (args.length < 3) {
                Text.send(s, y.getString("Mensajes.Uso_Crear", ""));
                return true;
            }
            int seconds = y.getInt("Duracion_Defecto", 120);
            int first = 1;
            try {
                seconds = Math.max(5, Integer.parseInt(args[1]));
                first = 2;
            } catch (NumberFormatException ignored) {
                // sin duracion: la primera palabra ya es la pregunta
            }
            String joined = String.join(" ", java.util.Arrays.copyOfRange(args, first, args.length));
            String[] parts = joined.split("\\|");
            if (parts.length < 3) {
                Text.send(s, y.getString("Mensajes.Pocas_Opciones", ""));
                return true;
            }
            List<String> options = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String o = parts[i].trim();
                if (!o.isEmpty()) {
                    options.add(o);
                }
            }
            if (options.size() < 2) {
                Text.send(s, y.getString("Mensajes.Pocas_Opciones", ""));
                return true;
            }
            int max = y.getInt("Max_Opciones", 8);
            if (options.size() > max) {
                Text.send(s, y.getString("Mensajes.Muchas_Opciones", ""), "max", String.valueOf(max));
                return true;
            }
            start(s, seconds, parts[0].trim(), options);
            return true;
        }

        private boolean vote(CommandSender s, String[] args) {
            YamlConfiguration y = cfg();
            if (!(s instanceof Player p)) {
                Text.send(s, y.getString("Mensajes.Solo_Jugadores", ""));
                return true;
            }
            if (!p.hasPermission("batmanchat.encuesta.votar")) {
                Text.sendKey(s, "Message.No_Permission");
                return true;
            }
            if (poll == null) {
                Text.send(s, y.getString("Mensajes.Sin_Encuesta", ""));
                return true;
            }
            int n;
            try {
                n = Integer.parseInt(args.length > 1 ? args[1] : "") - 1;
            } catch (NumberFormatException e) {
                n = -1;
            }
            if (n < 0 || n >= poll.options.size()) {
                Text.send(s, y.getString("Mensajes.Opcion_Invalida", ""));
                return true;
            }
            Integer prev = poll.votes.get(p.getUniqueId());
            if (prev != null && !y.getBoolean("Permitir_Cambiar_Voto", true)) {
                Text.send(s, y.getString("Mensajes.Ya_Voto", ""));
                return true;
            }
            poll.votes.put(p.getUniqueId(), n);
            Text.send(s, y.getString(prev == null ? "Mensajes.Voto_Registrado" : "Mensajes.Voto_Cambiado", ""),
                    "opcion", poll.options.get(n));
            return true;
        }

        @Override
        public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] args) {
            List<String> out = new ArrayList<>();
            if (args.length == 1) {
                for (String o : List.of("crear", "votar", "resultados", "cerrar")) {
                    if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                        out.add(o);
                    }
                }
            } else if (args.length == 2 && args[0].equalsIgnoreCase("votar") && poll != null) {
                for (int i = 1; i <= poll.options.size(); i++) {
                    out.add(String.valueOf(i));
                }
            }
            return out;
        }
    }
}
