package me.batman.batmanchat.protection;

import me.batman.batmanchat.BatmanChat;
import me.batman.batmanchat.util.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Envia avisos a Discord por webhook (configuracion en proteccion/modulos/discord-notifier.yml). */
final class DiscordNotifier {
    private final BatmanChat plugin;
    private final YamlConfiguration cfg;
    private final boolean enabled;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    DiscordNotifier(BatmanChat plugin) {
        this.plugin = plugin;
        this.cfg = plugin.cfg("proteccion/modulos/discord-notifier.yml");
        this.enabled = plugin.cfg("proteccion/config.yml").getBoolean("discord-notifier.enabled", false);
    }

    // ------------------------------------------------------------------ eventos

    void moduleTrigger(String player, String module, String context, String blocked, String entire) {
        ConfigurationSection s = section("notification-config.module-triggers");
        if (s == null || !s.getBoolean("enabled") || !s.getBoolean("enable-notifications-for." + module, false)) {
            return;
        }
        ConfigurationSection w = s.getConfigurationSection("webhook");
        if (w == null) {
            return;
        }
        String name = rep(w.getString("display-name", "{MODULE} - {CONTEXT}"), "MODULE", module, "CONTEXT", context, "PLAYER", player);
        String header = rep(w.getString("header", "{PLAYER}"), "PLAYER", player, "MODULE", module, "CONTEXT", context);
        List<String[]> fields = new ArrayList<>();
        if (blocked != null && !blocked.isEmpty()) {
            fields.add(new String[]{w.getString("blocked-content-field", "Flagged Content"), blocked});
        }
        if (entire != null && !entire.isEmpty()) {
            fields.add(new String[]{w.getString("full-content-field", "Full Message"), entire});
        }
        post(w, name, header, null, fields);
    }

    void manualWarning(String warner, String warned, int count) {
        simple("notification-config.manual-warnings", "content", "WARNER", warner, "WARNED", warned, "COUNT", String.valueOf(count));
    }

    void autoWarning(String module, String warned, int count, double lifetimeHours) {
        simple("notification-config.auto-warnings", "content", "MODULE", module, "WARNED", warned, "COUNT", String.valueOf(count),
                "WARNING_LIFETIME", String.valueOf(lifetimeHours), "EXPIRY_DATE", "");
    }

    void warningExpired(String module, String player, int remaining) {
        simple("notification-config.warning-pardons", "autowarning-expire-content", "PARDONED", player, "MODULE", module, "COUNT", String.valueOf(remaining));
    }

    void pardonedManual(String pardoner, String pardoned, int remaining) {
        simple("notification-config.warning-pardons", "pardoned-manual-content", "PARDONER", pardoner, "PARDONED", pardoned, "COUNT", String.valueOf(remaining));
    }

    void pardonedAllManual(String pardoner, String pardoned) {
        simple("notification-config.warning-pardons", "pardoned-all-manual-content", "PARDONER", pardoner, "PARDONED", pardoned);
    }

    void clearedModule(String pardoner, String pardoned, String module) {
        simple("notification-config.warning-pardons", "cleared-module-content", "PARDONER", pardoner, "PARDONED", pardoned, "MODULE", module);
    }

    void clearedAll(String pardoner, String pardoned) {
        simple("notification-config.warning-pardons", "cleared-all-modules-content", "PARDONER", pardoner, "PARDONED", pardoned);
    }

    void punished(String player, String module, int count) {
        simple("notification-config.auto-punisher", "content", "PLAYER", player, "MODULE", module, "COUNT", String.valueOf(count));
    }

    // ------------------------------------------------------------------ interno

    private ConfigurationSection section(String path) {
        return enabled ? cfg.getConfigurationSection(path) : null;
    }

    private void simple(String path, String contentKey, String... kv) {
        ConfigurationSection s = section(path);
        if (s == null || !s.getBoolean("enabled")) {
            return;
        }
        ConfigurationSection w = s.getConfigurationSection("webhook");
        if (w == null) {
            return;
        }
        String content = rep(w.getString(contentKey, ""), kv);
        String name = rep(w.getString("display-name", "BatmanChat"), kv);
        post(w, name, null, content, new ArrayList<>());
    }

    private static String rep(String s, String... kv) {
        String r = Text.vars(s, kv);
        return r.replace("\\n", "\n");
    }

    private void post(ConfigurationSection w, String authorName, String title, String description, List<String[]> fields) {
        String url = w.getString("url", "");
        if (url.isBlank() || !url.startsWith("https://")) {
            return;
        }
        int color = 0x4b7bec;
        try {
            color = Integer.parseInt(w.getString("color", "#4b7bec").replace("#", ""), 16);
        } catch (NumberFormatException ignored) {
        }
        StringBuilder j = new StringBuilder("{");
        String outside = w.getString("outside-embed-content", "");
        if (!outside.isEmpty()) {
            j.append("\"content\":").append(q(outside)).append(',');
        }
        j.append("\"embeds\":[{");
        j.append("\"color\":").append(color).append(',');
        j.append("\"author\":{\"name\":").append(q(authorName)).append("},");
        if (title != null && !title.isEmpty()) {
            j.append("\"title\":").append(q(title)).append(',');
        }
        if (description != null && !description.isEmpty()) {
            j.append("\"description\":").append(q(description)).append(',');
        }
        if (!fields.isEmpty()) {
            j.append("\"fields\":[");
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    j.append(',');
                }
                j.append("{\"name\":").append(q(fields.get(i)[0])).append(",\"value\":").append(q(fields.get(i)[1])).append("}");
            }
            j.append("],");
        }
        String footer = cfg.getString("notification-config.footer-text", "BatmanChat");
        j.append("\"footer\":{\"text\":").append(q(footer)).append("}");
        j.append("}]}");
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(j.toString()))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).exceptionally(t -> {
                plugin.getLogger().warning("[Proteccion] No se pudo enviar el aviso a Discord: " + t.getMessage());
                return null;
            });
        } catch (Exception e) {
            plugin.getLogger().warning("[Proteccion] Webhook de Discord invalido: " + e.getMessage());
        }
    }

    private static String q(String s) {
        if (s == null) {
            return "\"\"";
        }
        String clean = Text.strip(s);
        if (clean.length() > 1000) {
            clean = clean.substring(0, 1000) + "...";
        }
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            switch (c) {
                case '"':
                    b.append("\\\"");
                    break;
                case '\\':
                    b.append("\\\\");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        return b.append('"').toString();
    }
}
