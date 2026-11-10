package me.batman.batmanchat.chat;

import me.batman.batmanchat.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import com.google.common.collect.Multimap;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.Map;

/** Helpers compartidos para mostrar items en el chat (tokens !mano y mensajes de muerte). */
final class ChatItems {
    private ChatItems() {
    }

    static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    /** Conservar lo que afecta al tooltip (ItemFlags/hide flags, hide tooltip, estilo, brillo, irrompible). */
    private static volatile boolean keepTooltip = true;
    /** Conservar la skin/textura de las cabezas de jugador (skull). */
    private static volatile boolean keepSkull = true;

    /** Lo llama InteractiveTokensFeature al cargar/recargar items-interactivos.yml. */
    static void configure(boolean tooltip, boolean skull) {
        keepTooltip = tooltip;
        keepSkull = skull;
    }

    /**
     * Copia LIMPIA de un item para mostrarlo: un item nuevo del mismo material y cantidad que solo conserva
     * nombre, lore, encantamientos (y los almacenados de los libros), atributos y, si estan activados en
     * items-interactivos.yml (Copiar.*), los datos que cambian como se VE el tooltip (ItemFlags/hide flags,
     * hide tooltip, estilo de tooltip, brillo forzado, irrompible) y la textura de las cabezas (skull).
     * Todo lo demas (NBT/componentes custom, PersistentData de otros plugins, contenido de shulkers, libros
     * escritos, durabilidad...) se descarta, asi aunque alguien lograra sacarlo del menu no podria usarse para
     * duplicar items especiales. No anade ninguna marca ni NBT propio. Devuelve null si el item esta vacio.
     */
    static ItemStack clean(ItemStack src) {
        if (isEmpty(src)) {
            return null;
        }
        Material type = src.getType();
        if (!type.isItem()) {
            return null;
        }
        ItemStack out = new ItemStack(type, Math.max(1, Math.min(src.getAmount(), type.getMaxStackSize())));
        if (!src.hasItemMeta()) {
            return out;
        }
        ItemMeta in = src.getItemMeta();
        ItemMeta meta = out.getItemMeta();
        if (in == null || meta == null) {
            return out;
        }
        try {
            if (in.hasDisplayName() && in.displayName() != null) {
                meta.displayName(in.displayName());
            }
            if (in.hasLore()) {
                List<Component> lore = in.lore();
                if (lore != null && !lore.isEmpty()) {
                    meta.lore(lore);
                }
            }
            for (Map.Entry<Enchantment, Integer> e : in.getEnchants().entrySet()) {
                meta.addEnchant(e.getKey(), e.getValue(), true);
            }
            if (in instanceof EnchantmentStorageMeta storedIn && meta instanceof EnchantmentStorageMeta storedOut) {
                for (Map.Entry<Enchantment, Integer> e : storedIn.getStoredEnchants().entrySet()) {
                    storedOut.addStoredEnchant(e.getKey(), e.getValue(), true);
                }
            }
            Multimap<Attribute, AttributeModifier> attrs = in.getAttributeModifiers();
            if (attrs != null) {
                for (Map.Entry<Attribute, AttributeModifier> e : attrs.entries()) {
                    try {
                        meta.addAttributeModifier(e.getKey(), e.getValue());
                    } catch (RuntimeException ignored) {
                        // modificador duplicado o invalido: se omite
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // meta rara: se muestra lo que se haya copiado
        }
        if (keepTooltip) {
            copyTooltip(in, meta);
        }
        if (keepSkull) {
            copySkull(in, meta);
        }
        out.setItemMeta(meta);
        return out;
    }

    /** Hide flags (HIDE_ENCHANTS, HIDE_ATTRIBUTES, ...) y demas ajustes visuales del tooltip. Cada uno por separado. */
    private static void copyTooltip(ItemMeta in, ItemMeta out) {
        try {
            for (ItemFlag flag : in.getItemFlags()) {
                out.addItemFlags(flag);
            }
        } catch (RuntimeException ignored) {
            // sin flags
        }
        try {
            if (in.isHideTooltip()) {
                out.setHideTooltip(true);
            }
        } catch (RuntimeException ignored) {
            // sin hide tooltip
        }
        try {
            if (in.hasTooltipStyle()) {
                out.setTooltipStyle(in.getTooltipStyle());
            }
        } catch (RuntimeException ignored) {
            // sin estilo
        }
        try {
            if (in.hasEnchantmentGlintOverride()) {
                out.setEnchantmentGlintOverride(in.getEnchantmentGlintOverride());
            }
        } catch (RuntimeException ignored) {
            // sin brillo forzado
        }
        try {
            // la linea "Irrompible" solo existe si el item lo es (y HIDE_UNBREAKABLE la oculta)
            if (in.isUnbreakable()) {
                out.setUnbreakable(true);
            }
        } catch (RuntimeException ignored) {
            // sin irrompible
        }
    }

    /** Textura/skin de una cabeza de jugador (el "id" de la skull). */
    private static void copySkull(ItemMeta in, ItemMeta out) {
        if (!(in instanceof SkullMeta skullIn) || !(out instanceof SkullMeta skullOut)) {
            return;
        }
        try {
            com.destroystokyo.paper.profile.PlayerProfile profile = skullIn.getPlayerProfile();
            if (profile != null) {
                skullOut.setPlayerProfile(profile);
            }
        } catch (RuntimeException ignored) {
            // cabeza sin perfil: se ve la cabeza por defecto
        }
    }

    static ItemStack[] cleanAll(ItemStack[] in) {
        ItemStack[] out = new ItemStack[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = clean(in[i]);
        }
        return out;
    }

    /** Nombre del item: el nombre personalizado si lo tiene, si no el nombre traducido del material. */
    static Component name(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        Component name;
        if (meta != null && meta.hasDisplayName() && meta.displayName() != null) {
            name = meta.displayName();
        } else {
            name = Component.translatable(item.getType().translationKey());
        }
        if (item.getAmount() > 1) {
            name = name.append(Component.text(" x" + item.getAmount()));
        }
        return name.colorIfAbsent(NamedTextColor.WHITE);
    }

    /**
     * Aplica el formato (con {item}) y devuelve el componente con hover del item (sobre una copia limpia: solo nombre, lore, encantamientos y atributos).
     * Ejemplo de formato: "&8[&f{item}&8]".
     */
    static Component formatted(String fmt, ItemStack source, String... kv) {
        ItemStack item = clean(source);
        if (item == null) {
            return Text.parse(Text.vars(fmt, kv));
        }
        String f = Text.vars(fmt, kv);
        int i = f.toLowerCase().indexOf("{item}");
        if (i < 0) {
            // sin {item}: se muestra solo el texto del formato (ej: [Objeto de Batman]); el item va en el hover
            return Text.parse(f).hoverEvent(item.asHoverEvent());
        }
        String before = f.substring(0, i);
        String after = f.substring(i + 6);
        Component c = Component.text()
                .append(Text.parse(before))
                .append(name(item))
                .append(Text.parse(after))
                .build();
        return c.hoverEvent(item.asHoverEvent());
    }
}
