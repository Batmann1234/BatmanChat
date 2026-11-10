package me.batman.batmanchat;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/** Base con helpers que se limpian solos en disable(). */
public abstract class AbstractFeature implements Feature {
    protected final BatmanChat plugin;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final List<PluginCommand> commands = new ArrayList<>();

    protected AbstractFeature(BatmanChat plugin) {
        this.plugin = plugin;
    }

    protected void listen(Listener listener) {
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        listeners.add(listener);
    }

    /** Asigna el ejecutor a un comando declarado en plugin.yml; se quita solo en disable(). */
    protected void command(String name, CommandExecutor executor) {
        PluginCommand c = plugin.getCommand(name);
        if (c == null) {
            plugin.getLogger().warning("Comando no declarado en plugin.yml: " + name);
            return;
        }
        c.setExecutor(executor);
        if (executor instanceof TabCompleter t) {
            c.setTabCompleter(t);
        }
        commands.add(c);
    }

    protected BukkitTask repeat(Runnable r, long delayTicks, long periodTicks) {
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, r, delayTicks, periodTicks);
        tasks.add(t);
        return t;
    }

    protected BukkitTask repeatAsync(Runnable r, long delayTicks, long periodTicks) {
        BukkitTask t = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, r, delayTicks, periodTicks);
        tasks.add(t);
        return t;
    }

    protected BukkitTask later(Runnable r, long delayTicks) {
        BukkitTask t = Bukkit.getScheduler().runTaskLater(plugin, r, delayTicks);
        tasks.add(t);
        return t;
    }

    /** Se llama desde disable() despues de cancelar listeners y tareas. */
    protected void onDisable() {
    }

    @Override
    public final void disable() {
        for (Listener l : listeners) {
            HandlerList.unregisterAll(l);
        }
        listeners.clear();
        for (BukkitTask t : tasks) {
            try {
                t.cancel();
            } catch (Exception ignored) {
            }
        }
        tasks.clear();
        for (PluginCommand c : commands) {
            c.setExecutor(null);
            c.setTabCompleter(null);
        }
        commands.clear();
        onDisable();
    }
}
