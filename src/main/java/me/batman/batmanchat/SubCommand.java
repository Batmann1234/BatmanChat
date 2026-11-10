package me.batman.batmanchat;

import org.bukkit.command.CommandSender;

import java.util.List;

/** Subcomando de /batmanchat (se registra con BatmanChat#registerSub dentro de enable()). */
public interface SubCommand {
    /** Permiso requerido o null si no necesita. */
    String permission();

    boolean run(CommandSender sender, String[] args);

    default List<String> tab(CommandSender sender, String[] args) {
        return List.of();
    }
}
