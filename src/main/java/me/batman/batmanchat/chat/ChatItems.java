package me.batman.batmanchat.chat;

import me.batman.batmanchat.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Helpers compartidos para mostrar items en el chat (tokens !mano y mensajes de muerte). */
final class ChatItems {
    private ChatItems() {
    }

    static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
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
     * Aplica el formato (con {item}) y devuelve el componente con hover del item (sobre un CLON).
     * Ejemplo de formato: "&8[&f{item}&8]".
     */
    static Component formatted(String fmt, ItemStack source, String... kv) {
        ItemStack item = source.clone();
        String f = Text.vars(fmt, kv);
        int i = f.toLowerCase().indexOf("{item}");
        Component c;
        if (i < 0) {
            c = Text.parse(f);
        } else {
            c = Component.text()
                    .append(Text.parse(f.substring(0, i)))
                    .append(name(item))
                    .append(Text.parse(f.substring(i + 6)))
                    .build();
        }
        return c.hoverEvent(item.asHoverEvent());
    }
}
