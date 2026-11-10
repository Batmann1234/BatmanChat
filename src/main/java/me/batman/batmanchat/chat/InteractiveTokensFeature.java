package me.batman.batmanchat.chat;

import me.batman.batmanchat.AbstractFeature;
import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.SubCommand;
import me.batman.batmanchat.util.Hooks;
import me.batman.batmanchat.util.Ranks;
import me.batman.batmanchat.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokens interactivos en el chat: !mano, !inv, !ender, !dinero, !ping (estilo InteractiveChat, sin dependencias).
 * Config: chat/items-interactivos.yml. Anti-dupe: se muestran copias, los menus son de solo lectura y caducan.
 */
public final class InteractiveTokensFeature extends AbstractFeature {
    private static final String FILE = "chat/items-interactivos.yml";

    private final Map<String, View> views = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    private final Set<UUID> capturing = ConcurrentHashMap.newKeySet();
    private final Set<String> numberTokens = new HashSet<>();
    private Pattern pattern;
    private final Map<String, String> aliasToToken = new HashMap<>();

    public InteractiveTokensFeature(BatmanChat plugin) {
        super(plugin);
    }

    @Override
    public String id() {
        return "items-interactivos";
    }

    // ------------------------------------------------------------------ ciclo de vida

    private YamlConfiguration cfg() {
        return plugin.cfg(FILE);
    }

    @Override
    public void enable() {
        views.clear();
        lastUse.clear();
        buildPattern();
        listen(new GuiEvents());
        plugin.registerSub("ver", new ViewSub());
        repeatAsync(this::cleanup, 20L * 60, 20L * 60);
    }

    @Override
    protected void onDisable() {
        plugin.unregisterSub("ver");
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof ViewHolder) {
                p.closeInventory();
            }
        }
        views.clear();
        lastUse.clear();
    }

    private void buildPattern() {
        aliasToToken.clear();
        numberTokens.clear();
        List<String> aliases = new ArrayList<>();
        ConfigurationSection tokens = cfg().getConfigurationSection("Tokens");
        if (tokens != null) {
            for (String token : tokens.getKeys(false)) {
                List<String> list = tokens.getStringList(token + ".Alias");
                if (list.isEmpty()) {
                    list = List.of(token);
                }
                if (tokens.getBoolean(token + ".Acepta_Numero", false)) {
                    numberTokens.add(token.toLowerCase(Locale.ROOT));
                }
                for (String a : list) {
                    String k = a.toLowerCase(Locale.ROOT).trim();
                    if (!k.isEmpty()) {
                        aliasToToken.put(k, token.toLowerCase(Locale.ROOT));
                        aliases.add(k);
                    }
                }
            }
        }
        if (aliases.isEmpty()) {
            pattern = null;
            return;
        }
        aliases.sort((a, b) -> b.length() - a.length());
        StringBuilder alt = new StringBuilder();
        for (String a : aliases) {
            if (alt.length() > 0) {
                alt.append('|');
            }
            alt.append(Pattern.quote(a));
        }
        String prefix = Pattern.quote(cfg().getString("Prefijo", "!"));
        pattern = Pattern.compile("(?<![\\p{L}\\p{N}_])" + prefix + "(" + alt + ")(?:[ ]?(\\d{1,3}))?(?![\\p{L}\\p{N}_])",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    private void cleanup() {
        long ttl = cfg().getLong("Expiracion_Segundos", 600) * 1000L;
        long now = System.currentTimeMillis();
        views.values().removeIf(v -> now - v.created > ttl);
        lastUse.entrySet().removeIf(e -> now - e.getValue() > 3_600_000L);
    }

    // ------------------------------------------------------------------ datos

    private static final class View {
        final String owner;
        final String kind;
        final ItemStack[] storage;
        final ItemStack[] armor;
        final ItemStack offhand;
        final ItemStack[] ender;
        final long created = System.currentTimeMillis();
        ItemStack[] pv;
        int number;

        View(String owner, String kind, ItemStack[] storage, ItemStack[] armor, ItemStack offhand, ItemStack[] ender) {
            this.owner = owner;
            this.kind = kind;
            this.storage = storage;
            this.armor = armor;
            this.offhand = offhand;
            this.ender = ender;
        }
    }

    private static final class Snap {
        ItemStack hand;
        ItemStack[] storage;
        ItemStack[] armor;
        ItemStack offhand;
        ItemStack[] ender;
        String money;
        int ping;
    }

    private static ItemStack[] cloneAll(ItemStack[] in) {
        ItemStack[] out = new ItemStack[in.length];
        for (int i = 0; i < in.length; i++) {
            out[i] = in[i] == null ? null : in[i].clone();
        }
        return out;
    }

    /**
     * Lee el vault de AxVaults del jugador SIN depender de su API: ejecuta el comando de abrir vault como el
     * jugador, copia el contenido en cuanto se abre y lo cierra. Se llama desde el hilo asincrono del chat
     * (espera unos instantes). Mientras tanto se bloquean los clics del jugador para que no pueda tocar nada.
     */
    private ItemStack[] capture(Player p, int number, ConfigurationSection t, YamlConfiguration y) {
        if (Bukkit.isPrimaryThread() || t == null) {
            return null;
        }
        String cmd = Text.vars(t.getString("Comando", "pv {numero}"), "numero", String.valueOf(number), "player", p.getName());
        long max = Math.max(500, t.getLong("Espera_Maxima_Ms", 2500));
        try {
            Boolean started = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                InventoryType type = p.getOpenInventory().getTopInventory().getType();
                if (type != InventoryType.CRAFTING && type != InventoryType.CREATIVE) {
                    return false;
                }
                capturing.add(p.getUniqueId());
                p.performCommand(cmd.startsWith("/") ? cmd.substring(1) : cmd);
                return true;
            }).get(2, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(started)) {
                Text.send(p, y.getString("Mensajes.Cierra_Inventario", ""));
                return null;
            }
            long end = System.currentTimeMillis() + max;
            while (System.currentTimeMillis() < end) {
                ItemStack[] got = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                    Inventory top = p.getOpenInventory().getTopInventory();
                    if (top.getType() == InventoryType.CRAFTING || top.getType() == InventoryType.CREATIVE) {
                        return null;
                    }
                    ItemStack[] copy = cloneAll(top.getContents());
                    p.closeInventory();
                    return copy;
                }).get(2, TimeUnit.SECONDS);
                if (got != null) {
                    return got;
                }
                Thread.sleep(50);
            }
            Text.send(p, y.getString("Mensajes.Vault_No_Disponible", ""), "numero", String.valueOf(number));
            return null;
        } catch (Exception ex) {
            return null;
        } finally {
            capturing.remove(p.getUniqueId());
        }
    }

    /** Debe ejecutarse en el hilo principal. */
    private Snap take(Player p, Set<String> need) {
        Snap s = new Snap();
        if (need.contains("mano")) {
            s.hand = p.getInventory().getItemInMainHand().clone();
        }
        if (need.contains("inv")) {
            s.storage = cloneAll(p.getInventory().getStorageContents());
            s.armor = cloneAll(p.getInventory().getArmorContents());
            s.offhand = p.getInventory().getItemInOffHand().clone();
        }
        if (need.contains("ender")) {
            s.ender = cloneAll(p.getEnderChest().getContents());
        }
        if (need.contains("dinero")) {
            s.money = Hooks.money(p);
        }
        if (need.contains("ping")) {
            s.ping = p.getPing();
        }
        return s;
    }

    // ------------------------------------------------------------------ proceso del chat

    private static final class Found {
        final int start;
        final int end;
        final String token;
        final Integer num;
        ItemStack[] data;

        Found(int start, int end, String token, Integer num) {
            this.start = start;
            this.end = end;
            this.token = token;
            this.num = num;
        }
    }

    /**
     * Convierte los tokens del mensaje en componentes. Devuelve null si no hay ninguno que se pueda mostrar
     * (sin permiso, en cooldown, etc.). "plain" convierte los trozos de texto normales a componente.
     * Se puede llamar desde cualquier hilo.
     */
    public Component process(Player p, String text, Function<String, Component> plain) {
        YamlConfiguration y = cfg();
        if (!y.getBoolean("Enable", true) || pattern == null || text == null || text.isEmpty()) {
            return null;
        }
        Matcher m = pattern.matcher(text);
        List<Found> found = new ArrayList<>();
        int max = Math.max(1, y.getInt("Max_Por_Mensaje", 3));
        while (m.find() && found.size() < max) {
            String token = aliasToToken.get(m.group(1).toLowerCase(Locale.ROOT));
            if (token == null || !Hooks.has(p, "batmanchat.items." + token)) {
                continue;
            }
            Integer num = null;
            int end = m.end();
            if (m.group(2) != null) {
                if (numberTokens.contains(token)) {
                    num = Integer.parseInt(m.group(2));
                } else {
                    end = m.end(1);
                }
            }
            if (numberTokens.contains(token) && num == null) {
                continue;
            }
            found.add(new Found(m.start(), end, token, num));
        }
        if (found.isEmpty()) {
            return null;
        }

        long now = System.currentTimeMillis();
        if (!p.hasPermission("batmanchat.items.cooldown.bypass")) {
            double cd = Ranks.min(p, y.getConfigurationSection("Cooldown"), 10);
            Long last = lastUse.get(p.getUniqueId());
            if (cd > 0 && last != null && now - last < cd * 1000) {
                long left = (long) Math.ceil((cd * 1000 - (now - last)) / 1000.0);
                Text.send(p, y.getString("Mensajes.Cooldown", ""), "segundos", String.valueOf(left));
                return null;
            }
        }

        Set<String> need = new HashSet<>();
        for (Found f : found) {
            need.add(f.token);
        }
        Snap snap;
        try {
            if (Bukkit.isPrimaryThread()) {
                snap = take(p, need);
            } else {
                snap = Bukkit.getScheduler().callSyncMethod(plugin, () -> take(p, need)).get(2, TimeUnit.SECONDS);
            }
        } catch (Exception ex) {
            return null;
        }

        for (Found f : found) {
            if (f.token.equals("pv")) {
                f.data = capture(p, f.num == null ? 1 : f.num, y.getConfigurationSection("Tokens.pv"), y);
                break;
            }
        }

        Component out = Component.empty();
        int cursor = 0;
        boolean any = false;
        for (Found f : found) {
            if (f.start > cursor) {
                out = out.append(plain.apply(text.substring(cursor, f.start)));
            }
            Component c = build(f.token, p, snap, y, f);
            if (c == null) {
                out = out.append(plain.apply(text.substring(f.start, f.end)));
            } else {
                out = out.append(c);
                any = true;
            }
            cursor = f.end;
        }
        if (cursor < text.length()) {
            out = out.append(plain.apply(text.substring(cursor)));
        }
        if (!any) {
            return null;
        }
        lastUse.put(p.getUniqueId(), now);
        return out;
    }

    private Component hoverLines(Player p, ConfigurationSection t, String... kv) {
        List<String> lines = t.getStringList("Hover");
        Component hover = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                hover = hover.append(Component.newline());
            }
            hover = hover.append(Text.parse(Text.vars(lines.get(i), kv)));
        }
        return hover;
    }

    private Component build(String token, Player p, Snap s, YamlConfiguration y, Found f) {
        ConfigurationSection t = y.getConfigurationSection("Tokens." + token);
        if (t == null) {
            return null;
        }
        String fmt = t.getString("Formato", "[" + token + "]");
        String name = p.getName();
        switch (token) {
            case "mano": {
                if (ChatItems.isEmpty(s.hand)) {
                    Text.send(p, y.getString("Mensajes.Mano_Vacia", ""));
                    return null;
                }
                return ChatItems.formatted(fmt, s.hand, "player", name);
            }
            case "inv":
            case "ender": {
                String id = store(new View(name, token, s.storage, s.armor, s.offhand, s.ender));
                Component c = Text.parse(Text.vars(fmt, "player", name));
                Component hover = hoverLines(p, t, "player", name);
                return c.hoverEvent(HoverEvent.showText(hover)).clickEvent(ClickEvent.runCommand("/batmanchat ver " + id));
            }
            case "pv": {
                if (f.data == null) {
                    return null;
                }
                View v = new View(name, "pv", null, null, null, null);
                v.pv = f.data;
                v.number = f.num == null ? 1 : f.num;
                String id = store(v);
                String num = String.valueOf(v.number);
                Component c = Text.parse(Text.vars(fmt, "player", name, "numero", num));
                Component hover = hoverLines(p, t, "player", name, "numero", num);
                return c.hoverEvent(HoverEvent.showText(hover)).clickEvent(ClickEvent.runCommand("/batmanchat ver " + id));
            }
            case "dinero": {
                if (s.money == null) {
                    Text.send(p, y.getString("Mensajes.Sin_Economia", ""));
                    return null;
                }
                Component c = Text.parse(Text.vars(fmt, "player", name, "dinero", s.money));
                return c.hoverEvent(HoverEvent.showText(hoverLines(p, t, "player", name, "dinero", s.money)));
            }
            case "ping": {
                String ping = String.valueOf(s.ping);
                Component c = Text.parse(Text.vars(fmt, "player", name, "ping", ping));
                return c.hoverEvent(HoverEvent.showText(hoverLines(p, t, "player", name, "ping", ping)));
            }
            default: {
                return Text.parse(Text.vars(fmt, "player", name));
            }
        }
    }

    private String store(View v) {
        int max = Math.max(10, cfg().getInt("Max_Fotos", 300));
        if (views.size() >= max) {
            String oldest = null;
            long t = Long.MAX_VALUE;
            for (Map.Entry<String, View> e : views.entrySet()) {
                if (e.getValue().created < t) {
                    t = e.getValue().created;
                    oldest = e.getKey();
                }
            }
            if (oldest != null) {
                views.remove(oldest);
            }
        }
        String id;
        do {
            id = UUID.randomUUID().toString().substring(0, 8);
        } while (views.containsKey(id));
        views.put(id, v);
        return id;
    }

    // ------------------------------------------------------------------ menu de solo lectura

    private static final class ViewHolder implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private void open(Player p, View v) {
        YamlConfiguration y = cfg();
        ViewHolder h = new ViewHolder();
        Inventory inv;
        if (v.kind.equals("pv")) {
            String title = y.getString("Tokens.pv.Titulo_Menu", "&8Vault {numero} de {player}");
            int size = Math.max(9, Math.min(54, ((v.pv.length + 8) / 9) * 9));
            inv = Bukkit.createInventory(h, size, Text.parse(Text.vars(title, "player", v.owner, "numero", String.valueOf(v.number))));
            for (int i = 0; i < size && i < v.pv.length; i++) {
                if (v.pv[i] != null) {
                    inv.setItem(i, v.pv[i].clone());
                }
            }
        } else if (v.kind.equals("ender")) {
            String title = y.getString("Tokens.ender.Titulo_Menu", "&8Ender Chest de {player}");
            inv = Bukkit.createInventory(h, 27, Text.parse(Text.vars(title, "player", v.owner)));
            for (int i = 0; i < 27 && i < v.ender.length; i++) {
                if (v.ender[i] != null) {
                    inv.setItem(i, v.ender[i].clone());
                }
            }
        } else {
            String title = y.getString("Tokens.inv.Titulo_Menu", "&8Inventario de {player}");
            inv = Bukkit.createInventory(h, 54, Text.parse(Text.vars(title, "player", v.owner)));
            ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = pane.getItemMeta();
            meta.displayName(Component.text(" "));
            pane.setItemMeta(meta);
            for (int i = 36; i < 54; i++) {
                inv.setItem(i, pane.clone());
            }
            // filas 0-2: inventario (slots 9-35), fila 3: hotbar (slots 0-8)
            for (int i = 0; i < 36 && i < v.storage.length; i++) {
                int slot = i < 9 ? 27 + i : i - 9;
                if (v.storage[i] != null) {
                    inv.setItem(slot, v.storage[i].clone());
                }
            }
            // armadura: casco, peto, pantalones, botas (getArmorContents va de botas a casco)
            for (int i = 0; i < 4 && i < v.armor.length; i++) {
                ItemStack a = v.armor[3 - i];
                if (a != null) {
                    inv.setItem(45 + i, a.clone());
                }
            }
            if (v.offhand != null && !v.offhand.getType().isAir()) {
                inv.setItem(50, v.offhand.clone());
            }
        }
        h.inv = inv;
        p.openInventory(inv);
    }

    private final class ViewSub implements SubCommand {
        @Override
        public String permission() {
            return "batmanchat.items.ver";
        }

        @Override
        public boolean run(CommandSender sender, String[] args) {
            YamlConfiguration y = cfg();
            if (!(sender instanceof Player p)) {
                Text.send(sender, y.getString("Mensajes.Solo_Jugadores", ""));
                return true;
            }
            View v = args.length == 0 ? null : views.get(args[0]);
            long ttl = y.getLong("Expiracion_Segundos", 600) * 1000L;
            if (v == null || System.currentTimeMillis() - v.created > ttl) {
                Text.send(p, y.getString("Mensajes.Caducado", ""));
                return true;
            }
            open(p, v);
            return true;
        }
    }

    private final class GuiEvents implements Listener {
        private boolean ours(org.bukkit.inventory.InventoryView view) {
            return view.getTopInventory().getHolder() instanceof ViewHolder;
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onClick(InventoryClickEvent e) {
            if (ours(e.getView()) || capturing.contains(e.getWhoClicked().getUniqueId())) {
                e.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onCreative(InventoryCreativeEvent e) {
            if (ours(e.getView())) {
                e.setCancelled(true);
                if (e.getWhoClicked() instanceof Player p) {
                    Bukkit.getScheduler().runTask(plugin, p::updateInventory);
                }
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDrag(InventoryDragEvent e) {
            if (ours(e.getView()) || capturing.contains(e.getWhoClicked().getUniqueId())) {
                e.setCancelled(true);
            }
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            lastUse.remove(e.getPlayer().getUniqueId());
        }
    }
}
