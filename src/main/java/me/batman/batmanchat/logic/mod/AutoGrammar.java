package me.batman.batmanchat.logic.mod;

import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.Context;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.core.Opts;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Auto Grammar: mayuscula inicial, punto final y correccion de errores definidos en la lista. */
public final class AutoGrammar extends FilterModule {
    private static final Pattern TOKEN = Pattern.compile("\\S+");

    private boolean capitalize;
    private boolean addPeriods;
    private boolean fixTypos;
    private final Map<String, String> corrections = new HashMap<>();
    private final List<String> affected = new ArrayList<>();

    public AutoGrammar() {
        super("auto-grammar");
    }

    @Override
    protected void load(Opts o) {
        capitalize = o.bool("capitalize", true);
        addPeriods = o.bool("add-periods", true);
        fixTypos = o.bool("fix-typos", true);
        corrections.clear();
        for (String s : o.list("corrections")) {
            String[] p = s.split("->");
            if (p.length == 2 && !p[0].trim().isEmpty()) {
                corrections.put(p[0].trim().toLowerCase(Locale.ROOT), p[1].trim());
            }
        }
        affected.clear();
        for (String s : o.list("affected-commands")) {
            affected.add(slash(s));
        }
    }

    @Override
    public void check(Check c) {
        String prefix = "";
        String body = c.text;
        if (c.ctx == Context.COMMAND) {
            if (!affected(affected, c.commandBase)) {
                return;
            }
            int skip = (c.commandBase.equals("/r") || c.commandBase.equals("/reply")) ? 1 : 2;
            int pos = 0;
            for (int i = 0; i < skip; i++) {
                while (pos < body.length() && body.charAt(pos) == ' ') {
                    pos++;
                }
                while (pos < body.length() && body.charAt(pos) != ' ') {
                    pos++;
                }
            }
            prefix = body.substring(0, pos);
            body = body.substring(pos).stripLeading();
            if (!prefix.isEmpty()) {
                prefix += " ";
            }
        }
        if (body.isEmpty()) {
            return;
        }
        String out = process(body);
        c.setText(prefix + out);
    }

    String process(String body) {
        String s = body;
        if (fixTypos && !corrections.isEmpty()) {
            Matcher m = TOKEN.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(fixToken(m.group())));
            }
            m.appendTail(sb);
            s = sb.toString();
        }
        if (capitalize && s.length() > 3) {
            StringBuilder sb = new StringBuilder(s);
            boolean start = true;
            for (int i = 0; i < sb.length(); i++) {
                char ch = sb.charAt(i);
                if (start && Character.isLetter(ch)) {
                    sb.setCharAt(i, Character.toUpperCase(ch));
                    start = false;
                } else if (start && (Character.isDigit(ch) || ch == ':' || ch == '@' || ch == '/')) {
                    start = false;
                } else if ((ch == '.' || ch == '!' || ch == '?') && i + 1 < sb.length() && sb.charAt(i + 1) == ' ') {
                    start = true;
                }
            }
            s = sb.toString();
        }
        if (addPeriods && s.length() >= 6 && s.contains(" ")) {
            char last = s.charAt(s.length() - 1);
            if (Character.isLetterOrDigit(last)) {
                s = s + ".";
            }
        }
        return s;
    }

    private String fixToken(String token) {
        int a = 0;
        int b = token.length();
        while (a < b && !Character.isLetterOrDigit(token.charAt(a))) {
            a++;
        }
        while (b > a && !Character.isLetterOrDigit(token.charAt(b - 1))) {
            b--;
        }
        if (a >= b) {
            return token;
        }
        String core = token.substring(a, b);
        String rep = corrections.get(core.toLowerCase(Locale.ROOT));
        if (rep == null) {
            return token;
        }
        if (core.length() > 1 && core.equals(core.toUpperCase(Locale.ROOT))) {
            rep = rep.toUpperCase(Locale.ROOT);
        }
        return token.substring(0, a) + rep + token.substring(b);
    }
}
