package me.batman.batmanchat.logic.mod;

import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.Context;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.core.Opts;

import java.util.HashSet;
import java.util.Set;

/** Unicode Remover: quita letras "parecidas" (fullwidth, matematicas, small caps...) usadas para saltar filtros. */
public final class UnicodeRemover extends FilterModule {
    private boolean compat;
    private final Set<Integer> ignored = new HashSet<>();

    public UnicodeRemover() {
        super("unicode-remover");
    }

    @Override
    protected boolean defaultContext(Context ctx) {
        return ctx == Context.CHAT || ctx == Context.COMMAND;
    }

    @Override
    protected void load(Opts o) {
        compat = o.bool("enable-compatibility-mode", true);
        ignored.clear();
        for (String s : o.list("ignored-unicode-chars")) {
            s.codePoints().forEach(ignored::add);
        }
    }

    @Override
    public void check(Check c) {
        String s = c.text;
        StringBuilder out = new StringBuilder(s.length());
        StringBuilder removed = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (isBad(cp)) {
                removed.appendCodePoint(cp);
            } else {
                out.appendCodePoint(cp);
            }
        }
        if (removed.length() == 0) {
            return;
        }
        String result = out.toString();
        c.reply("modules.unicode-remover.trigger", "blocked_content", removed.toString());
        c.flag(id, "unicode-character-block", removed.toString());
        if (result.isBlank() || c.ctx == Context.ANVIL) {
            c.block();
        } else {
            c.setText(result);
        }
    }

    private boolean isBad(int cp) {
        if (cp >= 0x20 && cp <= 0x7E) {
            return false;
        }
        if (cp == '§' || cp == '\n' || cp == '\t') {
            return false;
        }
        if (ignored.contains(cp)) {
            return false;
        }
        if (!compat) {
            return true;
        }
        return (cp >= 0xFF01 && cp <= 0xFF5E)        // fullwidth
                || (cp >= 0x1D400 && cp <= 0x1D7FF)  // matematicas (negrita, cursiva...)
                || (cp >= 0x2100 && cp <= 0x214F)    // letterlike
                || (cp >= 0x2460 && cp <= 0x24FF)    // encerradas
                || (cp >= 0x1D00 && cp <= 0x1DBF)    // small caps / fonetica
                || (cp >= 0x0250 && cp <= 0x02AF)    // IPA
                || (cp >= 0x2070 && cp <= 0x209F)    // super/subindices
                || (cp >= 0x1F100 && cp <= 0x1F1FF)  // encerradas (cuadradas)
                || (cp >= 0x0300 && cp <= 0x036F)    // marcas combinadas (zalgo)
                || (cp >= 0x200B && cp <= 0x200F)    // ancho cero
                || cp == 0x2060 || cp == 0xFEFF;
    }
}
