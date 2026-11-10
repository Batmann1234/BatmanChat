package me.batman.batmanchat.logic.core;

/** Donde se origino el texto que se esta revisando. */
public enum Context {
    CHAT("chat"),
    COMMAND("command"),
    SIGN("sign"),
    ANVIL("anvil"),
    BOOK("book"),
    JOIN("join"),
    OTHER("other");

    /** Clave usada en los ymls ({@code contexts:}) y en {@code lang.yml}. */
    public final String key;

    Context(String key) {
        this.key = key;
    }
}
