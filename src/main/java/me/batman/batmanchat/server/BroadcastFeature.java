package me.batman.batmanchat.server;

import me.batman.batmanchat.AbstractFeature;
import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.util.Display;
import me.batman.batmanchat.util.Hooks;
import me.batman.batmanchat.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.Locale;

/** /broadcast, /announcement y /warning (antes ChatManager). Config: servidor/broadcast.yml. */
public final class BroadcastFeature extends AbstractFeature {
    private static final String FILE = "servidor/broadcast.yml";

    public BroadcastFeature(BatmanChat plugin) {
        super(plugin);
    }

    @Override
    public String id() {
        return "broadcast";
    }

    @Override
    public void enable() {
        command("broadcast", new Cmd("broadcast"));
        command("announcement", new Cmd("announcement"));
        command("warning", new Cmd("warning"));
    }

    private void sound(ConfigurationSection s) {
        if (s == null || !s.getBoolean("sound.toggle", false)) {
            return;
        }
        Display.sound(Bukkit.getOnlinePlayers(), s.getString("sound.value", ""),
                (float) s.getDouble("sound.volume", 1.0), (float) s.getDouble("sound.pitch", 1.0));
    }

    private final class Cmd implements CommandExecutor {
        private final String kind;

        Cmd(String kind) {
            this.kind = kind;
        }

        @Override
        public boolean onCommand(CommandSender s, Command c, String label, String[] args) {
            if (!Hooks.has(s, "batmanchat." + kind, "chatmanager." + kind)) {
                Text.sendKey(s, "Message.No_Permission");
                return true;
            }
            YamlConfiguration y = plugin.cfg(FILE);
            if (args.length == 0) {
                Text.send(s, y.getString("Mensajes.Uso", "&cUso: /{command} <mensaje>"), "command", label);
                return true;
            }
            String message = String.join(" ", args);
            ConfigurationSection sec = y.getConfigurationSection(capitalize(kind));
            if (sec == null) {
                return true;
            }
            if (kind.equals("broadcast")) {
                String text = sec.getString("Prefix", "") + sec.getString("Default_Color", "") + message;
                Bukkit.broadcast(Text.parse(text));
            } else {
                for (String line : sec.getStringList("Message")) {
                    Bukkit.broadcast(Text.parse(Text.vars(line, "message", message)));
                }
            }
            sound(sec);
            return true;
        }

        private String capitalize(String s) {
            return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1);
        }
    }
}
