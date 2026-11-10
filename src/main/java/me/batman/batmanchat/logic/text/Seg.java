package me.batman.batmanchat.logic.text;

/** Un trozo de texto con un unico estilo (resultado de interpretar &amp;c, &amp;#RRGGBB, degradados...). */
public final class Seg {
    public final String text;
    /** Color RGB o -1 si no tiene. */
    public final int rgb;
    public final boolean bold;
    public final boolean italic;
    public final boolean underlined;
    public final boolean strike;
    public final boolean obfuscated;

    public Seg(String text, int rgb, boolean bold, boolean italic, boolean underlined, boolean strike, boolean obfuscated) {
        this.text = text;
        this.rgb = rgb;
        this.bold = bold;
        this.italic = italic;
        this.underlined = underlined;
        this.strike = strike;
        this.obfuscated = obfuscated;
    }

    public boolean sameStyle(Seg o) {
        return rgb == o.rgb && bold == o.bold && italic == o.italic && underlined == o.underlined
                && strike == o.strike && obfuscated == o.obfuscated;
    }

    public Seg withText(String t) {
        return new Seg(t, rgb, bold, italic, underlined, strike, obfuscated);
    }

    public Seg withRgb(int c) {
        return new Seg(text, c, bold, italic, underlined, strike, obfuscated);
    }
}
