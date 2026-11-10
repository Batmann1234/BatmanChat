package me.batman.batmanchat.chat;

import me.batman.batmanchat.AbstractFeature;
import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.util.Display;
import me.batman.batmanchat.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.minecart.ExplosiveMinecart;
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
import java.util.Locale;
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

            // ---- explosiones provocadas por un jugador (cristales del End, TNT...)
            String explosive = "";
            Player bomber = null;
            if (cause.equals("ENTITY_EXPLOSION") || cause.equals("BLOCK_EXPLOSION")) {
                DamageSource ds = e.getDamageSource();
                Entity direct = ds.getDirectEntity();
                Entity causing = ds.getCausingEntity();
                if (causing instanceof Player cp) {
                    bomber = cp;
                } else if (direct instanceof TNTPrimed tnt && tnt.getSource() instanceof Player sp) {
                    bomber = sp;
                }
                if (bomber != null) {
                    String kind = direct instanceof EnderCrystal ? "CRISTAL"
                            : (direct instanceof TNTPrimed || direct instanceof ExplosiveMinecart) ? "TNT" : "JUGADOR";
                    explosive = y.getString("Explosivos." + kind, "");
                    key = bomber.getUniqueId().equals(victim.getUniqueId()) ? "EXPLOSION_AUTO" : "EXPLOSION_" + kind;
                    killerName = bomber.getName();
                }
            }

            if (bomber != null) {
                // key ya definido arriba
            } else if (killer instanceof Player kp) {
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

            // ---- claves a probar, de la mas especifica a la mas general
            String typeKey = damageTypeKey(e);
            List<String> keys = new ArrayList<>();
            if (bomber != null) {
                keys.add(key);
                if (!key.equals("EXPLOSION_AUTO")) {
                    keys.add("EXPLOSION_JUGADOR");
                }
            } else if (killer instanceof Player) {
                if (weapon != null) {
                    switch (typeKey) {
                        case "MACE_SMASH" -> keys.add("JUGADOR_MAZA");
                        case "ARROW" -> keys.add("JUGADOR_FLECHA");
                        case "TRIDENT" -> keys.add("JUGADOR_TRIDENTE");
                        default -> { }
                    }
                }
                keys.add(key);
                if (key.equals("JUGADOR_SIN_ARMA")) {
                    keys.add("JUGADOR");
                }
            } else if (killer != null) {
                keys.add("MOB_" + killer.getType().name());
                keys.add("MOB");
            } else {
                if (cause.equals("FALL")) {
                    String fk = fallKey(victim);
                    if (fk != null) {
                        keys.add(fk);
                    }
                }
                if (!typeKey.isEmpty()) {
                    keys.add(typeKey);
                }
                keys.add(cause);
            }
            keys.add("DEFAULT");

            List<String> options = new ArrayList<>();
            for (String k : keys) {
                List<String> found = y.getStringList("Mensajes." + k);
                if (!found.isEmpty()) {
                    options.addAll(found);
                    break;
                }
            }
            if (options.isEmpty()) {
                return;
            }
            String line = options.get(random.nextInt(options.size()));
            String raw = Text.vars(line,
                    "victima", victim.getName(),
                    "asesino", killerName,
                    "explosivo", explosive,
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

    /** Id del tipo de dano de Minecraft en mayusculas (ej: FALLING_ANVIL, CACTUS, STALAGMITE), o "" si no se puede leer. */
    private static String damageTypeKey(PlayerDeathEvent e) {
        try {
            return e.getDamageSource().getDamageType().getKey().getKey().toUpperCase(Locale.ROOT);
        } catch (Throwable t) { // API distinta a la esperada: se usa solo la DamageCause
            return "";
        }
    }

    /** Variante de caida segun el bloque donde esta la victima (escalera, enredaderas, andamio), o null. */
    private static String fallKey(Player p) {
        Material m = p.getLocation().getBlock().getType();
        if (m == Material.LADDER) {
            return "FALL_LADDER";
        }
        if (m == Material.SCAFFOLDING) {
            return "FALL_SCAFFOLDING";
        }
        if (m.name().contains("VINE")) {
            return "FALL_VINES";
        }
        return null;
    }

    private static String entityName(Entity e) {
        String n = e.getType().name().toLowerCase().replace('_', ' ');
        return n.isEmpty() ? "?" : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }
}
