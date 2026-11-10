package me.batman.batmanchat.protection;

import me.batman.batmanchat.BatmanChat;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** SQLite (por defecto) o MySQL con JDBC. Todo el I/O pasa por un unico hilo. */
final class Database {
    static final class Violation {
        long time;
        String name;
        String module;
        String context;
        String blocked;
        String full;
    }

    static final class WarnRow {
        UUID uuid;
        String module;
        boolean manual;
        int count;
        long last;
    }

    private final BatmanChat plugin;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "BatmanChat-DB");
        t.setDaemon(true);
        return t;
    });
    private Connection conn;
    private boolean mysql;

    Database(BatmanChat plugin) {
        this.plugin = plugin;
    }

    void open() throws Exception {
        YamlConfiguration c = plugin.cfg("proteccion/almacenamiento.yml");
        mysql = c.getString("type", "SQLITE").equalsIgnoreCase("MYSQL");
        submit(() -> {
            if (mysql) {
                String url = "jdbc:mysql://" + c.getString("mysql.host", "localhost") + ":" + c.getInt("mysql.port", 3306)
                        + "/" + c.getString("mysql.database", "batmanchat") + "?useSSL=false&autoReconnect=true";
                conn = DriverManager.getConnection(url, c.getString("mysql.user", "root"), c.getString("mysql.password", ""));
            } else {
                File dir = plugin.dataFile("proteccion/datos");
                dir.mkdirs();
                try {
                    Class.forName("org.sqlite.JDBC");
                } catch (ClassNotFoundException ignored) {
                }
                conn = DriverManager.getConnection("jdbc:sqlite:" + new File(dir, "proteccion.db").getAbsolutePath());
            }
            String autoInc = mysql ? "BIGINT PRIMARY KEY AUTO_INCREMENT" : "INTEGER PRIMARY KEY AUTOINCREMENT";
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS violations (id " + autoInc + ", uuid VARCHAR(36), name VARCHAR(32), module VARCHAR(64), context VARCHAR(16), blocked TEXT, full_message TEXT, time BIGINT)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS warnings (uuid VARCHAR(36), module VARCHAR(64), manual INTEGER, count INTEGER, last_time BIGINT, PRIMARY KEY (uuid, module, manual))");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS preferences (uuid VARCHAR(36), pref VARCHAR(32), val VARCHAR(16), PRIMARY KEY (uuid, pref))");
            }
            return null;
        }).get(15, TimeUnit.SECONDS);
    }

    void close() {
        try {
            exec.submit(() -> {
                try {
                    if (conn != null) {
                        conn.close();
                    }
                } catch (SQLException ignored) {
                }
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        exec.shutdown();
    }

    private <T> Future<T> submit(Callable<T> c) {
        return exec.submit(c);
    }

    private void async(SqlTask t) {
        try {
            exec.execute(() -> {
                try {
                    t.run();
                } catch (SQLException e) {
                    plugin.getLogger().warning("[Proteccion] Error de base de datos: " + e.getMessage());
                }
            });
        } catch (Exception ignored) {
            // executor cerrado
        }
    }

    private interface SqlTask {
        void run() throws SQLException;
    }

    // ------------------------------------------------------------------ violaciones

    void logViolation(UUID uuid, String name, String module, String context, String blocked, String full) {
        async(() -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO violations (uuid, name, module, context, blocked, full_message, time) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, uuid == null ? "" : uuid.toString());
                ps.setString(2, name == null ? "" : name);
                ps.setString(3, module);
                ps.setString(4, context);
                ps.setString(5, blocked);
                ps.setString(6, full);
                ps.setLong(7, System.currentTimeMillis());
                ps.executeUpdate();
            }
        });
    }

    List<Violation> lookup(String playerName, String module, int limit) throws Exception {
        return submit(() -> {
            List<Violation> out = new ArrayList<>();
            String sql = "SELECT name, module, context, blocked, full_message, time FROM violations WHERE LOWER(name) = ?"
                    + (module != null ? " AND module = ?" : "") + " ORDER BY time DESC LIMIT " + Math.max(1, limit);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, playerName.toLowerCase(Locale.ROOT));
                if (module != null) {
                    ps.setString(2, module);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Violation v = new Violation();
                        v.name = rs.getString(1);
                        v.module = rs.getString(2);
                        v.context = rs.getString(3);
                        v.blocked = rs.getString(4);
                        v.full = rs.getString(5);
                        v.time = rs.getLong(6);
                        out.add(v);
                    }
                }
            }
            return out;
        }).get(10, TimeUnit.SECONDS);
    }

    void cleanOlderThan(int days) {
        if (days <= 0) {
            return;
        }
        async(() -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM violations WHERE time < ?")) {
                ps.setLong(1, System.currentTimeMillis() - days * 86_400_000L);
                ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ advertencias

    List<WarnRow> loadWarnings() throws Exception {
        return submit(() -> {
            List<WarnRow> out = new ArrayList<>();
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT uuid, module, manual, count, last_time FROM warnings")) {
                while (rs.next()) {
                    WarnRow w = new WarnRow();
                    try {
                        w.uuid = UUID.fromString(rs.getString(1));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    w.module = rs.getString(2);
                    w.manual = rs.getInt(3) == 1;
                    w.count = rs.getInt(4);
                    w.last = rs.getLong(5);
                    out.add(w);
                }
            }
            return out;
        }).get(10, TimeUnit.SECONDS);
    }

    void saveWarning(UUID uuid, String module, boolean manual, int count, long last) {
        async(() -> {
            if (count <= 0) {
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM warnings WHERE uuid = ? AND module = ? AND manual = ?")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, module);
                    ps.setInt(3, manual ? 1 : 0);
                    ps.executeUpdate();
                }
                return;
            }
            try (PreparedStatement ps = conn.prepareStatement("REPLACE INTO warnings (uuid, module, manual, count, last_time) VALUES (?,?,?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, module);
                ps.setInt(3, manual ? 1 : 0);
                ps.setInt(4, count);
                ps.setLong(5, last);
                ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ preferencias

    /** Devuelve los UUID que tienen esa preferencia con valor "off". */
    Set<UUID> loadPrefOff(String pref) throws Exception {
        return submit(() -> {
            Set<UUID> out = new HashSet<>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT uuid FROM preferences WHERE pref = ? AND val = 'off'")) {
                ps.setString(1, pref);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            out.add(UUID.fromString(rs.getString(1)));
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                }
            }
            return out;
        }).get(10, TimeUnit.SECONDS);
    }

    void setPref(UUID uuid, String pref, boolean on) {
        async(() -> {
            try (PreparedStatement ps = conn.prepareStatement("REPLACE INTO preferences (uuid, pref, val) VALUES (?,?,?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, pref);
                ps.setString(3, on ? "on" : "off");
                ps.executeUpdate();
            }
        });
    }

}
