package me.batman.batmanchat.chat;

import me.batman.batmanchat.AbstractFeature;
import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.SubCommand;
import me.batman.batmanchat.util.Display;
import me.batman.batmanchat.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.FireworkMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Anuncio de compras de la tienda (Tebex): /batmanchat compra &lt;jugador&gt; &lt;paquete&gt; [precio] [transaccion].
 * Config: tienda.yml.
 */
public final class StoreFeature extends AbstractFeature {
    private final Set<String> seen = new LinkedHashSet<>();

    public StoreFeature(BatmanChat plugin) {
        super(plugin);
    }

    @Override
    public String id() {
        return "tienda";
    }

    @Override
    public void enable() {
        loadSeen();
        plugin.registerSub("compra", new Sub());
    }

    @Override
    protected void onDisable() {
        plugin.unregisterSub("compra");
    }

    private File seenFile() {
        return plugin.dataFile("tienda-transacciones.yml");
    }

    private synchronized void loadSeen() {
        seen.clear();
        File f = seenFile();
        if (f.exists()) {
            seen.addAll(YamlConfiguration.loadConfiguration(f).getStringList("transacciones"));
        }
    }

    private synchronized boolean markSeen(String id, int max) {
        if (seen.contains(id)) {
            return false;
        }
        seen.add(id);
        while (seen.size() > Math.max(10, max)) {
            String first = seen.iterator().next();
            seen.remove(first);
        }
        YamlConfiguration y = new YamlConfiguration();
        y.set("transacciones", new ArrayList<>(seen));
        try {
            y.save(seenFile());
        } catch (IOException e) {
            plugin.getLogger().warning("[Tienda] No se pudo guardar tienda-transacciones.yml: " + e.getMessage());
        }
        return true;
    }

    private final class Sub implements SubCommand {
        @Override
        public String permission() {
            return "batmanchat.tienda.admin";
        }

        @Override
        public boolean run(CommandSender sender, String[] args) {
            YamlConfiguration y = plugin.cfg("tienda.yml");
            if (!y.getBoolean("Enable", true)) {
                return true;
            }
            if (args.length < 2) {
                Text.send(sender, y.getString("Mensajes.Uso", ""));
                return true;
            }
            String player = args[0];
            String pkgKey = args[1].toLowerCase(Locale.ROOT);
            String price = args.length > 2 ? args[2] : "";
            String transaction = args.length > 3 ? args[3] : "";

            if (!transaction.isEmpty() && !markSeen(transaction, y.getInt("Max_Transacciones_Recordadas", 500))) {
                Text.send(sender, y.getString("Mensajes.Duplicada", ""), "transaccion", transaction);
                return true;
            }

            ConfigurationSection pkg = y.getConfigurationSection("Paquetes." + pkgKey);
            if (pkg == null) {
                pkg = y.getConfigurationSection("Paquetes.default");
            }
            if (pkg == null) {
                return true;
            }
            String pkgName = pkg.getString("Nombre", pkgKey);
            String[] kv = {"player", player, "paquete", pkgName, "precio", price, "transaccion", transaction};

            for (String line : pkg.getStringList("Anuncio")) {
                Bukkit.broadcast(Text.parse(Text.vars(line, kv)));
            }
            if (pkg.getBoolean("Titulo.Enable", false)) {
                Component h = Text.parse(Text.vars(pkg.getString("Titulo.Header", ""), kv));
                Component f = Text.parse(Text.vars(pkg.getString("Titulo.Footer", ""), kv));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Display.title(p, h, f, pkg.getInt("Titulo.Fade_In", 10), pkg.getInt("Titulo.Stay", 60),
                            pkg.getInt("Titulo.Fade_Out", 20));
                }
            }
            if (pkg.getBoolean("Sonido.toggle", false)) {
                List<Player> all = new ArrayList<>(Bukkit.getOnlinePlayers());
                Display.sound(all, pkg.getString("Sonido.value", ""), (float) pkg.getDouble("Sonido.volume", 1.0),
                        (float) pkg.getDouble("Sonido.pitch", 1.0));
            }
            Player buyer = Bukkit.getPlayerExact(player);
            if (buyer != null) {
                for (String line : pkg.getStringList("Mensaje_Comprador")) {
                    buyer.sendMessage(Text.parse(buyer, Text.vars(line, kv)));
                }
                if (pkg.getBoolean("Fuegos.Enable", false)) {
                    fireworks(buyer, pkg.getString("Fuegos.Color", "FFFFFF"));
                }
            }
            Text.send(sender, y.getString("Mensajes.Anunciada", ""), kv);
            return true;
        }

        private void fireworks(Player p, String hex) {
            Color color;
            try {
                color = Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16));
            } catch (NumberFormatException e) {
                color = Color.WHITE;
            }
            final Color c = color;
            Firework fw = p.getWorld().spawn(p.getLocation().add(0, 1, 0), Firework.class);
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().withColor(c).withFade(Color.WHITE)
                    .with(FireworkEffect.Type.BALL_LARGE).flicker(true).build());
            meta.setPower(0);
            fw.setFireworkMeta(meta);
            Bukkit.getScheduler().runTaskLater(plugin, fw::detonate, 2L);
        }
    }
}
