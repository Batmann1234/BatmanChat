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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tokens interactivos en el chat: !mano, !inv, !ender, !dinero, !ping (estilo InteractiveChat, sin dependencias).
 * Config: chat/items-interactivos.yml.
 *
 * Anti-dupe / anti-exploit de paquetes retrasados:
 *  - Todo lo que se muestra es una copia LIMPIA (ChatItems.clean): nombre, lore, encantamientos, atributos, flags del tooltip y skull.
 *  - La foto de inventario/ender/vault se toma en un unico tick (y el jugador queda bloqueado mientras se lee el vault).
 *  - Los menus son de solo lectura (clic, arrastre, creativo, mover, tirar, F) y se vacian al cerrarse.
 *  - Un jugador solo puede tener UNA captura de vault a la vez (antes dos mensajes seguidos quitaban el bloqueo).
 *  - El menu solo se abre si no hay otro inventario abierto (sin cierres cruzados con ventanas de otros plugins).
 */
public final class InteractiveTokensFeature extends AbstractFeature {
    private static final String FILE = "chat/items-interactivos.yml";

    private final Map<String, View> views = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastOpen = new ConcurrentHashMap<>();
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
        lastOpen.clear();
        capturing.clear();
        ChatItems.configure(cfg().getBoolean("Copiar.Tooltip", true), cfg().getBoolean("Copiar.Skull", true));
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
        lastOpen.clear();
        capturing.clear();
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
        lastOpen.entrySet().removeIf(e -> now - e.getValue() > 60_000L);
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

    /**
     * Lee el vault de AxVaults del jugador SIN depender de su API: ejecuta el comando de abrir vault como el
     * jugador, copia el contenido (limpio) en cuanto se abre y lo cierra. Se llama desde el hilo asincrono del
     * chat. El llamador DEBE tener ya el bloqueo "capturing" del jugador (lo libera el llamador en el hilo
     * principal): mientras dure se cancelan sus clics, arrastres, creativo, tirar item y cambio de mano.
     */
    private ItemStack[] capture(Player p, int number, ConfigurationSection t, YamlConfiguration y) {
        if (Bukkit.isPrimaryThread() || t == null) {
            return null;
        }
        String cmd = Text.vars(t.getString("Comando", "pv {numero}"), "numero", String.valueOf(number), "player", p.getName());
        long max = Math.max(500, t.getLong("Espera_Maxima_Ms", 2500));
        AtomicBoolean abort = new AtomicBoolean(false);
        AtomicBoolean opened = new AtomicBoolean(false);
        AtomicBoolean bad = new AtomicBoolean(false);
        boolean success = false;
        try {
            Boolean started = Bukkit.getScheduler().callSyncMethod(plugin, () -> {
                if (abort.get() || !p.isOnline()) {
                    return false;
                }
                InventoryType type = p.getOpenInventory().getTopInventory().getType();
                if (type != InventoryType.CRAFTING && type != InventoryType.CREATIVE) {
                    return false;
                }
                opened.set(true);
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
                    InventoryType tt = top.getType();
                    if (tt == InventoryType.CRAFTING || tt == InventoryType.CREATIVE) {
                        return null;
                    }
                    if (tt != InventoryType.CHEST || top.getHolder() instanceof ViewHolder) {
                        // no es un vault (otro menu): no se copia nada y se cierra
                        bad.set(true);
                        p.closeInventory();
                        return null;
                    }
                    ItemStack[] copy = ChatItems.cleanAll(top.getContents());
                    p.closeInventory();
                    return copy;
                }).get(2, TimeUnit.SECONDS);
                if (got != null) {
                    success = true;
                    return got;
                }
                if (bad.get()) {
                    break;
                }
                Thread.sleep(50);
            }
            Text.send(p, y.getString("Mensajes.Vault_No_Disponible", ""), "numero", String.valueOf(number));
            return null;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            return null;
        } finally {
            if (!success) {
                // timeout / error: que la tarea pendiente ya no abra nada y cerrar lo que hubiera quedado abierto
                abort.set(true);
                if (opened.get()) {
                    runMain(() -> {
                        Inventory top = p.getOpenInventory().getTopInventory();
                        InventoryType tt = top.getType();
                        if (tt != InventoryType.CRAFTING && tt != InventoryType.CREATIVE && !(top.getHolder() instanceof ViewHolder)) {
                            p.closeInventory();
                        }
                    });
                }
            }
        }
    }

    /** Ejecuta en el hilo principal sin bloquear (si el plugin ya se apago, lo ejecuta directamente). */
    private void runMain(Runnable r) {
        try {
            if (Bukkit.isPrimaryThread()) {
                r.run();
            } else {
                Bukkit.getScheduler().runTask(plugin, r);
            }
        } catch (RuntimeException ex) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // nada mas que hacer
            }
        }
    }

    /** Debe ejecutarse en el hilo principal. Todas las copias salen ya LIMPIAS. */
    private Snap take(Player p, Set<String> need) {
        Snap s = new Snap();
        if (need.contains("mano")) {
            s.hand = ChatItems.clean(p.getInventory().getItemInMainHand());
        }
        if (need.contains("inv")) {
            s.storage = ChatItems.cleanAll(p.getInventory().getStorageContents());
            s.armor = ChatItems.cleanAll(p.getInventory().getArmorContents());
            s.offhand = ChatItems.clean(p.getInventory().getItemInOffHand());
        }
        if (need.contains("ender")) {
            s.ender = ChatItems.cleanAll(p.getEnderChest().getContents());
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

        UUID id = p.getUniqueId();
        long now = System.currentTimeMillis();

        // cooldown: se reserva de forma atomica (dos mensajes a la vez ya no se saltan el cooldown)
        boolean reserved = false;
        Long previous = null;
        if (!p.hasPermission("batmanchat.items.cooldown.bypass")) {
            double cd = Ranks.min(p, y.getConfigurationSection("Cooldown"), 10);
            if (cd > 0) {
                final long[] left = {0};
                final Long[] prev = {null};
                lastUse.compute(id, (k, last) -> {
                    prev[0] = last;
                    if (last != null && now - last < cd * 1000) {
                        left[0] = (long) Math.ceil((cd * 1000 - (now - last)) / 1000.0);
                        return last;
                    }
                    return now;
                });
                if (left[0] > 0) {
                    Text.send(p, y.getString("Mensajes.Cooldown", ""), "segundos", String.valueOf(left[0]));
                    return null;
                }
                reserved = true;
                previous = prev[0];
            }
        }

        Component out = null;
        try {
            out = render(p, text, plain, y, found, now);
            return out;
        } finally {
            if (out == null && reserved) {
                // no se mostro nada: se devuelve el cooldown como estaba
                if (previous == null) {
                    lastUse.remove(id);
                } else {
                    lastUse.put(id, previous);
                }
            }
        }
    }

    private Component render(Player p, String text, Function<String, Component> plain, YamlConfiguration y,
                             List<Found> found, long now) {
        UUID id = p.getUniqueId();
        Set<String> need = new HashSet<>();
        Found pvFound = null;
        for (Found f : found) {
            need.add(f.token);
            if (pvFound == null && f.token.equals("pv")) {
                pvFound = f;
            }
        }

        // Un unico bloqueo por jugador mientras se lee el vault y se toma la foto (anti doble captura).
        boolean locked = false;
        if (pvFound != null) {
            if (!capturing.add(id)) {
                Text.send(p, y.getString("Mensajes.Ocupado", ""));
                return null;
            }
            locked = true;
        }
        Snap snap;
        try {
            if (pvFound != null) {
                pvFound.data = capture(p, pvFound.num == null ? 1 : pvFound.num, y.getConfigurationSection("Tokens.pv"), y);
            }
            // La foto del inventario/ender se toma DESPUES del vault y todavia con el jugador bloqueado:
            // asi inv, ender y vault son del mismo instante y no se puede mover un item entre ellos.
            try {
                if (Bukkit.isPrimaryThread()) {
                    snap = take(p, need);
                } else {
                    snap = Bukkit.getScheduler().callSyncMethod(plugin, () -> take(p, need)).get(2, TimeUnit.SECONDS);
                }
            } catch (Exception ex) {
                return null;
            }
        } finally {
            if (locked) {
                // se libera en el hilo principal, DESPUES de cualquier cierre pendiente de inventario
                runMain(() -> capturing.remove(id));
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
        lastUse.put(id, now);
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
                View v = new View(name, "mano", null, null, null, null);
                v.pv = new ItemStack[]{s.hand.clone()};
                String id = store(v);
                return ChatItems.formatted(fmt, s.hand, "player", name)
                        .clickEvent(ClickEvent.runCommand("/batmanchat ver " + id));
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

    private synchronized String store(View v) {
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

    /** Copia limpia (idempotente) para poner en el menu; null si esta vacio. */
    private static ItemStack shown(ItemStack it) {
        return ChatItems.clean(it);
    }

    private void open(Player p, View v) {
        YamlConfiguration y = cfg();
        ViewHolder h = new ViewHolder();
        Inventory inv;
        if (v.kind.equals("mano")) {
            String title = y.getString("Tokens.mano.Titulo_Menu", "&fItem de {player}");
            inv = Bukkit.createInventory(h, 27, Text.parse(Text.vars(title, "player", v.owner)));
            if (v.pv.length > 0) {
                inv.setItem(13, shown(v.pv[0]));
            }
        } else if (v.kind.equals("pv")) {
            String title = y.getString("Tokens.pv.Titulo_Menu", "&8Vault {numero} de {player}");
            int size = Math.max(9, Math.min(54, ((v.pv.length + 8) / 9) * 9));
            inv = Bukkit.createInventory(h, size, Text.parse(Text.vars(title, "player", v.owner, "numero", String.valueOf(v.number))));
            for (int i = 0; i < size && i < v.pv.length; i++) {
                inv.setItem(i, shown(v.pv[i]));
            }
        } else if (v.kind.equals("ender")) {
            String title = y.getString("Tokens.ender.Titulo_Menu", "&8Ender Chest de {player}");
            inv = Bukkit.createInventory(h, 27, Text.parse(Text.vars(title, "player", v.owner)));
            for (int i = 0; i < 27 && i < v.ender.length; i++) {
                inv.setItem(i, shown(v.ender[i]));
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
                inv.setItem(slot, shown(v.storage[i]));
            }
            // armadura: casco, peto, pantalones, botas (getArmorContents va de botas a casco)
            for (int i = 0; i < 4 && i < v.armor.length; i++) {
                ItemStack a = shown(v.armor[3 - i]);
                if (a != null) {
                    inv.setItem(45 + i, a);
                }
            }
            ItemStack off = shown(v.offhand);
            if (off != null) {
                inv.setItem(50, off);
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
            UUID id = p.getUniqueId();
            long now = System.currentTimeMillis();
            // anti-spam de paquetes: ignora aperturas repetidas casi a la vez
            Long last = lastOpen.put(id, now);
            if (last != null && now - last < 250) {
                return true;
            }
            if (capturing.contains(id)) {
                Text.send(p, y.getString("Mensajes.Ocupado", ""));
                return true;
            }
            // solo se abre sin otro inventario abierto: evita cierres cruzados con ventanas de otros plugins
            Inventory top = p.getOpenInventory().getTopInventory();
            InventoryType type = top.getType();
            if (type != InventoryType.CRAFTING && type != InventoryType.CREATIVE && !(top.getHolder() instanceof ViewHolder)) {
                Text.send(p, y.getString("Mensajes.Cierra_Para_Ver", ""));
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

        /** Jugador con un menu nuestro abierto o en plena captura de vault. */
        private boolean locked(org.bukkit.entity.HumanEntity who) {
            return capturing.contains(who.getUniqueId())
                    || who.getOpenInventory().getTopInventory().getHolder() instanceof ViewHolder;
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onClick(InventoryClickEvent e) {
            if (ours(e.getView()) || capturing.contains(e.getWhoClicked().getUniqueId())) {
                e.setCancelled(true);
                e.setResult(org.bukkit.event.Event.Result.DENY);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onCreative(InventoryCreativeEvent e) {
            if (ours(e.getView()) || capturing.contains(e.getWhoClicked().getUniqueId())) {
                e.setCancelled(true);
                e.setResult(org.bukkit.event.Event.Result.DENY);
                if (e.getWhoClicked() instanceof Player p) {
                    Bukkit.getScheduler().runTask(plugin, p::updateInventory);
                }
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDrag(InventoryDragEvent e) {
            if (ours(e.getView()) || capturing.contains(e.getWhoClicked().getUniqueId())) {
                e.setCancelled(true);
                e.setResult(org.bukkit.event.Event.Result.DENY);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onMove(InventoryMoveItemEvent e) {
            if (e.getSource().getHolder() instanceof ViewHolder || e.getDestination().getHolder() instanceof ViewHolder) {
                e.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onDrop(PlayerDropItemEvent e) {
            if (locked(e.getPlayer())) {
                e.setCancelled(true);
            }
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onSwap(PlayerSwapHandItemsEvent e) {
            if (locked(e.getPlayer())) {
                e.setCancelled(true);
            }
        }

        /** Al cerrar el menu se vacia: cualquier paquete retrasado encuentra los huecos vacios. */
        @EventHandler(priority = EventPriority.MONITOR)
        public void onClose(InventoryCloseEvent e) {
            if (e.getInventory().getHolder() instanceof ViewHolder) {
                e.getInventory().clear();
            }
        }

        @EventHandler
        public void onQuit(PlayerQuitEvent e) {
            UUID id = e.getPlayer().getUniqueId();
            lastUse.remove(id);
            lastOpen.remove(id);
            capturing.remove(id);
        }
    }
}
