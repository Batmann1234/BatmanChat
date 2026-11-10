import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.Context;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.core.Opts;
import me.batman.batmanchat.logic.core.Similarity;
import me.batman.batmanchat.logic.mod.AntiChatFlood;
import me.batman.batmanchat.logic.mod.AntiCommandPrefix;
import me.batman.batmanchat.logic.mod.AntiParrot;
import me.batman.batmanchat.logic.mod.AutoGrammar;
import me.batman.batmanchat.logic.mod.CapLimiter;
import me.batman.batmanchat.logic.mod.ChatCooldown;
import me.batman.batmanchat.logic.mod.ChatExecutor;
import me.batman.batmanchat.logic.mod.LinkAdBlocker;
import me.batman.batmanchat.logic.mod.SpamBlocker;
import me.batman.batmanchat.logic.mod.UnicodeRemover;
import me.batman.batmanchat.logic.mod.WordFilter;
import me.batman.batmanchat.logic.text.Allow;
import me.batman.batmanchat.logic.text.Center;
import me.batman.batmanchat.logic.text.Legacy;
import me.batman.batmanchat.logic.text.Seg;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Pruebas de la logica pura (sin Bukkit). Ejecutar: java -cp out:tests-out CoreTests <carpeta-json> */
public final class CoreTests {
    static int pass;
    static int fail;
    static String dir;
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static long clock = 1_000_000L;

    static Opts load(String id) throws Exception {
        return new MapOpts(Json.parse(Files.readString(Path.of(dir, id + ".json"))));
    }

    static <T extends FilterModule> T mod(T m, String id) throws Exception {
        m.reload(load(id));
        return m;
    }

    static Check chat(UUID u, String name, String text) {
        Check c = new Check(u, name, Context.CHAT, text, clock);
        c.onlineNames = Set.of("Batman", "Robin");
        return c;
    }

    static void expect(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("FALLA: " + name);
        }
    }

    public static void main(String[] args) throws Exception {
        dir = args[0];

        // ---------- Similarity ----------
        expect("sim igual", Similarity.ratio("hola", "hola") == 1.0);
        expect("sim parecido", Similarity.ratio("hola mundo", "hola mundoo") > 0.85);
        expect("sim distinto", Similarity.ratio("hola", "adios") < 0.4);
        expect("fold", Similarity.fold("Coño ÁÉÍ").equals("cono aei"));
        expect("normalizeCombat", Similarity.normalizeCombat("Buy now 4821").equals("buy now"));

        // ---------- Spam blocker ----------
        SpamBlocker spam = mod(new SpamBlocker(), "spam-blocker");
        int blockedAt = -1;
        for (int i = 1; i <= 8; i++) {
            clock += 500;
            Check c = chat(A, "Batman", "compro diamantes baratos");
            spam.check(c);
            if (c.blocked && blockedAt < 0) {
                blockedAt = i;
            }
        }
        expect("spam: repetido se bloquea (5to)", blockedAt == 5);
        Check wb = chat(B, "Robin", "wb");
        for (int i = 0; i < 8; i++) {
            spam.check(wb);
        }
        expect("spam: whitelist 'wb' nunca bloquea", !wb.blocked);
        Check big = chat(B, "Robin", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        spam.check(big);
        expect("spam: mensaje singular spam", big.blocked);
        Check ok = chat(B, "Robin", "Hola, alguien quiere ir a minar al nether conmigo?");
        spam.check(ok);
        expect("spam: mensaje normal pasa", !ok.blocked);
        Check abc = chat(B, "Robin", "abcabcabcabcabcabcabcabcabcabcabcabc");
        spam.check(abc);
        expect("spam: patron periodico", abc.blocked);

        // ---------- Chat cooldown ----------
        ChatCooldown cd = mod(new ChatCooldown(), "chat-cooldown");
        cd.enabled = true;
        int cdBlocked = 0;
        for (int i = 0; i < 6; i++) {
            Check c = chat(B, "Robin", "msg" + i);
            cd.check(c);
            if (c.blocked) {
                cdBlocked++;
            }
        }
        expect("cooldown: 4 permitidos, 2 bloqueados", cdBlocked == 2);

        // ---------- Anti chat flood ----------
        AntiChatFlood fl = mod(new AntiChatFlood(), "anti-chat-flood");
        Check f1 = chat(A, "Batman", "Heyyyyyyyyyyyyyyy");
        fl.check(f1);
        expect("flood: modifica letras repetidas -> " + f1.text, f1.text.equals("Heyyyyy") && !f1.blocked);
        Check f2 = chat(A, "Batman", "genial!!!!!!!!!!!!!!!!!!!!");
        fl.check(f2);
        expect("flood: '!' tiene limite 30", f2.text.equals("genial!!!!!!!!!!!!!!!!!!!!") && f2.flags.isEmpty());
        Check f3 = chat(A, "Batman", "qwertyuiopasdfghjklzxcvbnmqwertyuiopasdfghjkl");
        fl.check(f3);
        expect("flood: palabra larga bloqueada", f3.blocked);
        Check f4 = chat(A, "Batman", "mira https://www.estaesunaurlmuylargaquepasaporqueesunlink.com/asdasdasdasdasdasd");
        fl.check(f4);
        expect("flood: enlace largo ignorado", !f4.blocked);

        // ---------- Cap limiter ----------
        CapLimiter cap = mod(new CapLimiter(), "cap-limiter");
        cap.enabled = true;
        Check c1 = chat(A, "Batman", "HOLA A TODOS COMO ESTAN HOY MUY BIEN");
        cap.check(c1);
        expect("caps: pasa a minusculas -> " + c1.text, c1.text.equals("hola a todos como estan hoy muy bien") && c1.modified);
        Check c2 = chat(A, "Batman", "Hola Batman y Robin, que tal");
        cap.check(c2);
        expect("caps: normal no se toca", !c2.modified);
        Check c3 = chat(A, "Batman", "&#FF0000&lHOLA &#00FF00amigos");
        cap.check(c3);
        expect("caps: colores ignorados", !c3.modified);
        Check c4 = chat(A, "Batman", "Mira a ROBIN que ROBIN esta aqui AAAAAAAAAAAA");
        cap.check(c4);
        expect("caps: nombre de jugador respetado -> " + c4.text, c4.text.contains("ROBIN") && c4.text.contains("aaaa"));

        // ---------- Link & ad ----------
        LinkAdBlocker link = mod(new LinkAdBlocker(), "link-and-ad-blocker");
        String[] bad = {"entra a play.hypixel.net", "mira www.google.com", "visita mc-server.gg/ofertas", "mi ip es 192.168.1.1:25565",
                "juega en 1.2.3.4", "play dot hypixel dot net", "hypixel(dot)net", "google,,com", "https://discord.gg/abc123"};
        for (String b : bad) {
            Check c = chat(A, "Batman", b);
            link.check(c);
            expect("link: bloquea '" + b + "'", c.blocked);
        }
        String[] good = {"hola que tal", "version 1.21.7 es la mejor", "tengo 3.5 de promedio", "mira youtube.com/watch", "https://youtu.be/abc",
                "maps.google.com no", "Batman.pro mola", "el precio es 4.99"};
        for (String g : good) {
            Check c = chat(A, "Batman", g);
            link.check(c);
            if (g.equals("maps.google.com no")) {
                expect("link: (google.com no esta en la whitelist) '" + g + "' bloqueado", c.blocked);
            } else {
                expect("link: permite '" + g + "'", !c.blocked);
            }
        }
        Check cmdw = new Check(A, "Batman", Context.COMMAND, "/lp user x permission set a.b.c", clock);
        link.check(cmdw);
        expect("link: comando whitelist /lp", !cmdw.blocked);

        // ---------- Unicode ----------
        UnicodeRemover uni = mod(new UnicodeRemover(), "unicode-remover");
        Check u1 = chat(A, "Batman", "ｈｅｌｌｏ hola");
        uni.check(u1);
        expect("unicode: fullwidth removido -> '" + u1.text + "'", u1.text.equals(" hola") && u1.modified);
        Check u2 = chat(A, "Batman", "ｈｅｌ");
        uni.check(u2);
        expect("unicode: solo fullwidth -> bloquea", u2.blocked);
        Check u3 = chat(A, "Batman", "ñandú áéíóú ¿qué? → gracias");
        uni.check(u3);
        expect("unicode: español permitido -> '" + u3.text + "'", !u3.modified);
        Check u4 = chat(A, "Batman", "𝐇𝐞𝐥𝐥𝐨 hola");
        uni.check(u4);
        expect("unicode: matematicas removido", u4.modified && u4.text.equals(" hola"));

        // ---------- Word filter ----------
        WordFilter wf = mod(new WordFilter(), "word-and-phrase-filter");
        Check w1 = chat(A, "Batman", "eres un pendejo");
        wf.check(w1);
        expect("palabras: censura -> " + w1.text, w1.text.equals("eres un pe*****") && !w1.blocked && w1.flags.size() == 1);
        Check w2 = chat(A, "Batman", "mi computadora es una disputa de reputacion");
        wf.check(w2);
        expect("palabras: whitelist (computadora/disputa) -> " + w2.text, !w2.modified);
        Check w3 = chat(A, "Batman", "que puta madre");
        wf.check(w3);
        expect("palabras: frase -> " + w3.text, w3.modified);
        Check w4 = chat(A, "Batman", "hola Batman");
        wf.check(w4);
        expect("palabras: limpio pasa", !w4.modified);
        Check w5 = chat(A, "Batman", "PENDEJO");
        wf.check(w5);
        expect("palabras: mayusculas -> " + w5.text, w5.modified && w5.text.startsWith("PE"));
        Check w6 = chat(A, "Batman", "eres un p e n d e j o");
        wf.check(w6);
        Check w7 = new Check(A, "Batman", Context.COMMAND, "/msg Robin eres un pendejo", clock);
        wf.check(w7);
        expect("palabras: comandos tambien -> " + w7.text, w7.text.equals("/msg Robin eres un pe*****"));
        Check w8 = new Check(A, "Batman", Context.COMMAND, "/pendejo", clock);
        wf.check(w8);
        expect("palabras: nombre del comando se ignora", !w8.modified);

        // ---------- Anti parrot ----------
        AntiParrot par = mod(new AntiParrot(), "anti-parrot");
        Check p1 = chat(A, "Batman", "vendo mi casa por 5 diamantes");
        par.check(p1);
        clock += 1000;
        Check p2 = chat(B, "Robin", "vendo mi casa por 5 diamantes");
        par.check(p2);
        expect("loro: copia de otro jugador bloqueada", !p1.blocked && p2.blocked);
        Check p3 = chat(B, "Robin", "lol");
        par.check(p3);
        Check p4 = chat(A, "Batman", "lol");
        par.check(p4);
        expect("loro: mensajes cortos ignorados", !p3.blocked && !p4.blocked);

        // ---------- Chat executor ----------
        ChatExecutor ex = mod(new ChatExecutor(), "chat-executor");
        Check e1 = chat(A, "Batman", "i want admin");
        ex.check(e1);
        expect("ejecutor: {block} + player_msg", e1.blocked && e1.actions.size() == 2 && e1.actions.get(0).startsWith("{player_msg}"));
        Check e2 = chat(A, "Batman", "this server sucks");
        ex.check(e2);
        expect("ejecutor: reemplazo -> " + e2.text, e2.text.equals("I love this server :D"));
        Check e3 = new Check(A, "Batman", Context.COMMAND, "/plugins", clock);
        ex.check(e3);
        expect("ejecutor: /plugins bloqueado", e3.blocked);
        Check e4 = chat(A, "Batman", "is there a discord");
        ex.check(e4);
        expect("ejecutor: broadcast con {PLAYER} -> " + e4.actions, e4.actions.size() == 1 && e4.actions.get(0).contains("Batman,") && !e4.blocked && !e4.modified);
        Check e5 = chat(A, "Batman", "repeat me: hola mundo feo");
        ex.check(e5);
        expect("ejecutor: {multiargs} -> " + e5.actions, e5.actions.size() == 1 && e5.actions.get(0).contains("'me: hola mundo feo'"));
        expect("ejecutor: sin errores de regex " + ex.errors, ex.errors.isEmpty());

        // ---------- Auto grammar ----------
        AutoGrammar ag = mod(new AutoGrammar(), "auto-grammar");
        ag.enabled = true;
        Check g1 = chat(A, "Batman", "i dont know. its fine");
        ag.check(g1);
        expect("gramatica -> " + g1.text, g1.text.equals("I don't know. It's fine."));
        Check g2 = chat(A, "Batman", "xD");
        ag.check(g2);
        expect("gramatica: mensaje corto intacto", !g2.modified);

        // ---------- Anti command prefix ----------
        AntiCommandPrefix acp = mod(new AntiCommandPrefix(), "anti-command-prefix");
        acp.enabled = true;
        Check a1 = new Check(A, "Batman", Context.COMMAND, "/minecraft:me hola", clock);
        acp.check(a1);
        expect("prefijo: quita minecraft: -> " + a1.text, a1.text.equals("/me hola"));
        Check a2 = new Check(A, "Batman", Context.COMMAND, "/plugin:command x", clock);
        acp.check(a2);
        expect("prefijo: permitido se queda", !a2.modified);

        // ---------- Legacy / Center ----------
        List<Seg> segs = Legacy.parse("&#FF0000&lHERO &f%player% &8» #FF4B4B{message}");
        expect("legacy: segmentos " + segs.size(), segs.size() == 4);
        expect("legacy: negrita solo en HERO", segs.get(0).bold && segs.get(0).rgb == 0xFF0000 && !segs.get(1).bold);
        expect("legacy: &f reinicia formato", segs.get(1).rgb == 0xFFFFFF && !segs.get(1).bold);
        expect("legacy: hex pelado", segs.get(3).rgb == 0xFF4B4B);
        List<Seg> grad = Legacy.parse("<gradient:#FF0000:#0000FF>abc</gradient>x");
        expect("legacy: degradado 3 letras + 1", grad.size() == 4 && grad.get(0).rgb == 0xFF0000 && grad.get(2).rgb == 0x0000FF && grad.get(3).rgb == -1);
        List<Seg> none = Legacy.parse("&cHola &#FF0000mundo <gradient:#FF0000:#0000FF>x", Allow.NONE);
        expect("legacy: sin permisos todo literal", none.size() == 1 && none.get(0).text.equals("&cHola &#FF0000mundo <gradient:#FF0000:#0000FF>x"));
        Set<Character> onlyC = Set.of('c');
        List<Seg> some = Legacy.parse("&cHola &lmundo", new Allow(false, onlyC, false, false));
        expect("legacy: solo &c permitido", some.size() == 1 && some.get(0).rgb == 0xFF5555 && some.get(0).text.equals("Hola &lmundo"));
        expect("plain", Legacy.plain("&#94FF00&lTIENDA &fDEL SERVIDOR").equals("TIENDA DEL SERVIDOR"));
        int sp = Center.spaces(Legacy.parse("&#94FF00&lTIENDA DEL SERVIDOR"), null);
        System.out.println("info: espacios para centrar 'TIENDA DEL SERVIDOR' = " + sp);
        expect("center: 20..35 espacios", sp >= 20 && sp <= 35);

        System.out.println("OK=" + pass + " FALLAS=" + fail);
        if (fail > 0) {
            System.exit(1);
        }
    }
}
