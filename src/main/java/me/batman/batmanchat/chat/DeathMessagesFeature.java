package me.batman.batmanchat.chat;

import me.batman.batmanchat.AbstractFeature;
import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.util.Display;
import me.batman.batmanchat.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Mensajes de muerte personalizados por causa, con el arma en hover. Config: chat/muertes.yml. */
public final class DeathMessagesFeature extends AbstractFeature {
    private final Random random = new Random();

    public DeathMessagesFeature(BatmanChat plugin) {
        super(plugin);
    }

    @Override
    public String id() {
        return "mensajes-muerte";
    }

    @Override
    public void enable() {
        listen(new Events());
    }

    private final class Events implements Listener {
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onDeath(PlayerDeathEvent e) {
            YamlConfiguration y = plugin.cfg("chat/muertes.yml");
            if (!y.getBoolean("Enable", true) || e.deathMessage() == null) {
                return;
            }
            Player victim = e.getEntity();
            String key = "DEFAULT";
            String killerName = "";
            ItemStack weapon = null;
            LivingEntity killer = null;

            EntityDamageEvent last = victim.getLastDamageCause();
            String cause = last == null ? "CUSTOM" : last.getCause().name();
            Entity damager = null;
            if (last instanceof EntityDamageByEntityEvent byEntity) {
                damager = byEntity.getDamager();
                if (damager instanceof Projectile proj && proj.getShooter() instanceof LivingEntity shooter) {
                    damager = shooter;
                }
            }
            if (victim.getKiller() != null && !victim.getKiller().getUniqueId().equals(victim.getUniqueId())) {
                killer = victim.getKiller();
            } else if (damager instanceof LivingEntity le && !le.getUniqueId().equals(victim.getUniqueId())) {
                killer = le;
            }

            if (killer instanceof Player kp) {
                killerName = kp.getName();
                ItemStack hand = kp.getInventory().getItemInMainHand();
                if (ChatItems.isEmpty(hand)) {
                    key = "JUGADOR_SIN_ARMA";
                } else {
                    key = "JUGADOR";
                    weapon = hand.clone();
                }
            } else if (killer != null) {
                key = "MOB";
                killerName = killer.customName() != null ? Text.plain(killer.customName())
                        : entityName(killer);
            } else {
                key = cause;
            }

            List<String> options = new ArrayList<>(y.getStringList("Mensajes." + key));
            if (options.isEmpty() && (key.equals("JUGADOR_SIN_ARMA"))) {
                options = new ArrayList<>(y.getStringList("Mensajes.JUGADOR"));
            }
            if (options.isEmpty()) {
                options = new ArrayList<>(y.getStringList("Mensajes.DEFAULT"));
            }
            if (options.isEmpty()) {
                return;
            }
            String line = options.get(random.nextInt(options.size()));
            String raw = Text.vars(line,
                    "victima", victim.getName(),
                    "asesino", killerName,
                    "mundo", victim.getWorld().getName(),
                    "x", String.valueOf(victim.getLocation().getBlockX()),
                    "y", String.valueOf(victim.getLocation().getBlockY()),
                    "z", String.valueOf(victim.getLocation().getBlockZ()),
                    "causa", cause);
            Component msg = Text.parse(victim, raw);
            Component weaponComp;
            if (weapon != null) {
                weaponComp = ChatItems.formatted(y.getString("Formato_Arma", "&8[&f{item}&8]"), weapon);
            } else {
                weaponComp = Text.parse(y.getString("Arma_Vacia", "&7sus puños"));
            }
            final Component wc = weaponComp;
            msg = msg.replaceText(b -> b.matchLiteral("{arma}").replacement(wc));
            e.deathMessage(msg);

            if (y.getBoolean("Sonido.toggle", false)) {
                List<Player> targets = new ArrayList<>(Bukkit.getOnlinePlayers());
                Display.sound(targets, y.getString("Sonido.value", ""), (float) y.getDouble("Sonido.volume", 1.0),
                        (float) y.getDouble("Sonido.pitch", 1.0));
            }
        }
    }

    private static String entityName(Entity e) {
        String n = e.getType().name().toLowerCase().replace('_', ' ');
        return n.isEmpty() ? "?" : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
