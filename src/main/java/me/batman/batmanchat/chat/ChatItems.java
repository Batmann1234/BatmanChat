package me.batman.batmanchat.chat;

import me.batman.batmanchat.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import com.google.common.collect.Multimap;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.potion.PotionEffect;

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
    /** Conservar el contenido de las shulker boxes (cada item de dentro tambien se limpia). */
    private static volatile boolean keepShulker = true;
    /** Conservar el color de la armadura de cuero. */
    private static volatile boolean keepDye = true;
    /** Conservar los trims de armadura. */
    private static volatile boolean keepTrim = true;
    /** Conservar los efectos/tipo/color de pociones y flechas con efecto. */
    private static volatile boolean keepPotion = true;

    /** Lo llama InteractiveTokensFeature al cargar/recargar items-interactivos.yml. */
    static void configure(boolean tooltip, boolean skull, boolean shulker, boolean dye, boolean trim, boolean potion) {
        keepTooltip = tooltip;
        keepSkull = skull;
        keepShulker = shulker;
        keepDye = dye;
        keepTrim = trim;
        keepPotion = potion;
    }

    /**
     * Copia LIMPIA de un item para mostrarlo: un item nuevo del mismo material y cantidad que solo conserva
     * nombre, lore, encantamientos (y los almacenados de los libros), atributos y, si estan activados en
     * items-interactivos.yml (Copiar.*), datos puramente VISUALES: ItemFlags/hide flags, hide tooltip, estilo de
     * tooltip, brillo forzado, irrompible, textura de cabezas (skull), contenido de shulkers (cada item de
     * dentro tambien se limpia), color de cuero, trims y efectos de pociones.
     * Todo lo demas (NBT/componentes custom, PersistentData de otros plugins como MythicMobs, libros escritos,
     * durabilidad...) se descarta SIEMPRE, tambien en los items de dentro de las shulkers: la copia se construye
     * desde un item nuevo, nunca se clona el original. No anade ninguna marca ni NBT propio.
     * Devuelve null si el item esta vacio.
     */
    static ItemStack clean(ItemStack src) {
        return clean(src, 0);
    }

    private static ItemStack clean(ItemStack src, int depth) {
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
        if (keepDye) {
            copyDye(in, meta);
        }
        if (keepTrim) {
            copyTrim(in, meta);
        }
        if (keepPotion) {
            copyPotion(in, meta);
        }
        if (keepShulker && depth == 0) {
            copyShulker(in, meta, depth);
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

    /** Color de armadura de cuero. */
    private static void copyDye(ItemMeta in, ItemMeta out) {
        if (!(in instanceof LeatherArmorMeta dyeIn) || !(out instanceof LeatherArmorMeta dyeOut)) {
            return;
        }
        try {
            Color color = dyeIn.getColor();
            if (color != null) {
                dyeOut.setColor(color);
            }
        } catch (RuntimeException ignored) {
            // sin color
        }
    }

    /** Trim de armadura (material + patron). */
    private static void copyTrim(ItemMeta in, ItemMeta out) {
        if (!(in instanceof ArmorMeta trimIn) || !(out instanceof ArmorMeta trimOut)) {
            return;
        }
        try {
            ArmorTrim trim = trimIn.getTrim();
            if (trim != null) {
                trimOut.setTrim(trim);
            }
        } catch (RuntimeException ignored) {
            // sin trim
        }
    }

    /** Tipo base, efectos personalizados y color de pociones / flechas con efecto. */
    private static void copyPotion(ItemMeta in, ItemMeta out) {
        if (!(in instanceof PotionMeta potIn) || !(out instanceof PotionMeta potOut)) {
            return;
        }
        try {
            if (potIn.getBasePotionType() != null) {
                potOut.setBasePotionType(potIn.getBasePotionType());
            }
        } catch (RuntimeException ignored) {
            // sin tipo base
        }
        try {
            for (PotionEffect effect : potIn.getCustomEffects()) {
                potOut.addCustomEffect(effect, true);
            }
        } catch (RuntimeException ignored) {
            // sin efectos
        }
        try {
            if (potIn.hasColor() && potIn.getColor() != null) {
                potOut.setColor(potIn.getColor());
            }
        } catch (RuntimeException ignored) {
            // sin color
        }
    }

    /** Contenido de una shulker box: cada item de dentro pasa por clean(), asi no arrastra NBT ni PersistentData. */
    private static void copyShulker(ItemMeta in, ItemMeta out, int depth) {
        if (!(in instanceof BlockStateMeta bsIn) || !(out instanceof BlockStateMeta bsOut)) {
            return;
        }
        try {
            if (!bsIn.hasBlockState() || !(bsIn.getBlockState() instanceof ShulkerBox boxIn)) {
                return;
            }
            BlockState outState = bsOut.getBlockState();
            if (!(outState instanceof ShulkerBox boxOut)) {
                return;
            }
            ItemStack[] contents = boxIn.getInventory().getContents();
            for (int i = 0; i < contents.length && i < boxOut.getInventory().getSize(); i++) {
                ItemStack inner = clean(contents[i], depth + 1);
                if (inner != null) {
                    boxOut.getInventory().setItem(i, inner);
                }
            }
            bsOut.setBlockState(boxOut);
        } catch (RuntimeException ignored) {
            // shulker rara: se muestra vacia
        }
    }
}
