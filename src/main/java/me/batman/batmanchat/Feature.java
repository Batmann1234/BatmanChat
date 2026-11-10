package me.batman.batmanchat;

/**
 * Un modulo de BatmanChat. Se puede activar/desactivar desde config.yml (seccion "modulos").
 * enable() debe leer sus configs con plugin.cfg(...) y registrar listeners/tareas con los
 * helpers de AbstractFeature para que disable() los limpie solos (asi funciona /batmanchat reload).
 */
public interface Feature {
    /** Nombre corto del modulo (solo para logs). */
    String id();

    void enable();

    void disable();
}
