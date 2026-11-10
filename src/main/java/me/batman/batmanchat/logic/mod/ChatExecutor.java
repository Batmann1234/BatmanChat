package me.batman.batmanchat.logic.mod;

import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.Context;
import me.batman.batmanchat.logic.core.Flag;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.core.Opts;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Chat Executor: reemplaza/bloquea mensajes que coinciden con un patron y/o ejecuta acciones
 * ({player_msg}, {console_cmd}, {player_cmd}, {broadcast}).
 */
public final class ChatExecutor extends FilterModule {
    private static final class Entry {
        String key;
        boolean regex;
        boolean onlyCmd;
        boolean onlyChat;
        boolean onlyAnvil;
        boolean onlySign;
        Pattern pattern;
        String plain = "";
        boolean block;
        boolean dontModify;
        boolean dontNotify;
        boolean dontLog;
        String replacement = "";
        List<String> execute = new ArrayList<>();
    }

    private final List<Entry> entries = new ArrayList<>();
    /** Entradas con regex invalido (para avisar por consola). */
    public final List<String> errors = new ArrayList<>();

    public ChatExecutor() {
        super("chat-executor");
    }

    @Override
    protected boolean defaultContext(Context ctx) {
        // Los contextos se manejan por entrada; el modulo siempre revisa todo.
        return true;
    }

    @Override
    protected void load(Opts o) {
        entries.clear();
        errors.clear();
        for (String key : o.keys("entries")) {
            Opts e = o.sub("entries." + key);
            Entry en = new Entry();
            en.key = key;
            String match = e.str("match", "");
            while (match.startsWith("{")) {
                int end = match.indexOf('}');
                if (end < 0) {
                    break;
                }
                String tok = match.substring(1, end).toLowerCase(Locale.ROOT);
                boolean known = true;
                switch (tok) {
                    case "regex" -> en.regex = true;
                    case "text" -> en.regex = false;
                    case "only_commands" -> en.onlyCmd = true;
                    case "only_chat" -> en.onlyChat = true;
                    case "only_anvils" -> en.onlyAnvil = true;
                    case "only_signs" -> en.onlySign = true;
                    default -> known = false;
                }
                if (!known) {
                    break;
                }
                match = match.substring(end + 1);
            }
            if (match.isEmpty()) {
                continue;
            }
            if (en.regex) {
                try {
                    en.pattern = Pattern.compile(match, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
                } catch (PatternSyntaxException ex) {
                    errors.add("chat-executor entrada " + key + ": regex invalido (" + ex.getDescription() + ")");
                    continue;
                }
            } else {
                en.plain = match;
            }
            String set = e.str("set-matches-as", "");
            while (set.startsWith("{")) {
                int end = set.indexOf('}');
                if (end < 0) {
                    break;
                }
                String tok = set.substring(1, end).toLowerCase(Locale.ROOT);
                boolean known = true;
                switch (tok) {
                    case "block" -> en.block = true;
                    case "dont_modify" -> en.dontModify = true;
                    case "dont_notify" -> en.dontNotify = true;
                    case "dont_log" -> en.dontLog = true;
                    default -> known = false;
                }
                if (!known) {
                    break;
                }
                set = set.substring(end + 1);
            }
            en.replacement = set.trim();
            en.execute.addAll(e.list("execute"));
            entries.add(en);
        }
    }

    @Override
    public void check(Check c) {
        for (Entry en : entries) {
            if (!applies(en, c.ctx)) {
                continue;
            }
            String text = c.text;
            String matched = null;
            Matcher m = null;
            if (en.regex) {
                m = en.pattern.matcher(text);
                if (m.find()) {
                    matched = m.group();
                }
            } else {
                int idx = text.toLowerCase(Locale.ROOT).indexOf(en.plain.toLowerCase(Locale.ROOT));
                if (idx >= 0) {
                    matched = text.substring(idx, idx + en.plain.length());
                }
            }
            if (matched == null) {
                continue;
            }
            String[] words = text.trim().isEmpty() ? new String[0] : text.trim().split("\\s+");
            for (String action : en.execute) {
                c.actions.add(fill(action, c, words));
            }
            if (en.block) {
                c.block();
            } else if (!en.dontModify && !en.replacement.isEmpty()) {
                if (en.regex) {
                    c.setText(en.pattern.matcher(text).replaceFirst(Matcher.quoteReplacement(en.replacement)));
                } else {
                    int idx = text.toLowerCase(Locale.ROOT).indexOf(en.plain.toLowerCase(Locale.ROOT));
                    c.setText(text.substring(0, idx) + en.replacement + text.substring(idx + en.plain.length()));
                }
            }
            c.flag(new Flag(id, "chat-executor-match", matched, true, !en.dontNotify, !en.dontLog));
            return; // solo cuenta la primera entrada que coincide
        }
    }

    private static boolean applies(Entry en, Context ctx) {
        if (en.onlyAnvil || en.onlySign) {
            return (en.onlyAnvil && ctx == Context.ANVIL) || (en.onlySign && ctx == Context.SIGN);
        }
        if (en.onlyCmd || en.onlyChat) {
            return (en.onlyCmd && ctx == Context.COMMAND) || (en.onlyChat && ctx == Context.CHAT);
        }
        return ctx == Context.CHAT || ctx == Context.COMMAND;
    }

    /** {arg1}, {multiargs2}, {PLAYER}... (los argumentos empiezan en 1). */
    static String fill(String action, Check c, String[] words) {
        String s = action.replace("{PLAYER}", c.name).replace("{PLAYER_DISPLAYNAME}", c.name)
                .replace("{PLAYER_DISPLAYNAME_STRIPPED}", c.name);
        Matcher mm = Pattern.compile("\\{multiargs(\\d+)}", Pattern.CASE_INSENSITIVE).matcher(s);
        StringBuilder sb = new StringBuilder();
        while (mm.find()) {
            int n = Integer.parseInt(mm.group(1));
            StringBuilder rest = new StringBuilder();
            for (int i = n - 1; i < words.length; i++) {
                if (i >= 0) {
                    if (rest.length() > 0) {
                        rest.append(' ');
                    }
                    rest.append(words[i]);
                }
            }
            mm.appendReplacement(sb, Matcher.quoteReplacement(rest.toString()));
        }
        mm.appendTail(sb);
        s = sb.toString();
        Matcher am = Pattern.compile("\\{arg(\\d+)}", Pattern.CASE_INSENSITIVE).matcher(s);
        sb = new StringBuilder();
        while (am.find()) {
            int n = Integer.parseInt(am.group(1));
            am.appendReplacement(sb, Matcher.quoteReplacement(n >= 1 && n <= words.length ? words[n - 1] : ""));
        }
        am.appendTail(sb);
        return sb.toString();
    }
}
