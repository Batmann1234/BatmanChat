package me.batman.batmanchat.logic.mod;

import me.batman.batmanchat.logic.core.Check;
import me.batman.batmanchat.logic.core.Context;
import me.batman.batmanchat.logic.core.FilterModule;
import me.batman.batmanchat.logic.core.Opts;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Link &amp; Ad Blocker: enlaces web e IPs/dominios de otros servidores. */
public final class LinkAdBlocker extends FilterModule {
    private static final Set<String> TLDS = new HashSet<>(List.of((
            "com net org info biz edu gov mil int io co me tv cc gg xyz top club online site tech store app dev shop fun live "
                    + "pro name mobi asia cloud link click world space website host email games game network zone ltd inc llc agency "
                    + "studio digital today news media wiki chat team lol ninja guru rocks pw tk ml ga cf gq ac ad ae af ag ai al am "
                    + "ao ar as at au aw ax az ba bb bd be bf bg bh bi bj bm bn bo br bs bt bw by bz ca cd cg ch ci ck cl cm cn cr "
                    + "cu cv cw cx cy cz de dj dk dm do dz ec ee eg er es et eu fi fj fk fm fo fr gd ge gf gh gi gl gm gn gp gr gs "
                    + "gt gu gw gy hk hm hn hr ht hu id ie il im in iq ir is it je jm jo jp ke kg kh ki km kn kp kr kw ky kz la lb "
                    + "lc li lk lr ls lt lu lv ly ma mc md mg mh mk mm mn mo mp mq mr ms mt mu mv mw mx my mz na nc ne nf ng ni nl "
                    + "no np nr nu nz om pa pe pf pg ph pk pl pm pn pr ps pt py qa re ro rs ru rw sa sb sc sd se sg sh si sk sl sm "
                    + "sn so sr ss st su sv sx sy sz tc td tf tg th tj tl tm tn to tr tt tw tz ua ug uk us uy uz va vc ve vg vi vn "
                    + "vu wf ws ye yt za zm zw").split(" ")));

    private static final Pattern DOMAIN_NO_AT = Pattern.compile(
            "(?i)(?<![\\w@.\\-])((?:https?://)?(?:www\\.)?(?:[a-z0-9](?:[a-z0-9\\-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24})(?::\\d{1,5})?(?:/\\S*)?(?![\\w\\-])");
    private static final Pattern DOMAIN_AT = Pattern.compile(
            "(?i)(?<![\\w.\\-])((?:https?://)?(?:www\\.)?(?:[a-z0-9](?:[a-z0-9\\-]{0,61}[a-z0-9])?\\.)+[a-z]{2,24})(?::\\d{1,5})?(?:/\\S*)?(?![\\w\\-])");
    private static final Pattern IP = Pattern.compile("(?<![\\d.])((?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3})(?::\\d{1,5})?(?![\\d])");
    private static final Pattern DOT_WORDS = Pattern.compile("(?i)\\s*[(\\[{<]\\s*(?:d\\s*_?\\s*o\\s*_?\\s*t|punto)\\s*[)\\]}>]\\s*");
    private static final Pattern DOT_SPOKEN = Pattern.compile("(?i)(?<=[a-z0-9])\\s+dot\\s+(?=[a-z0-9])");
    private static final Pattern DOT_COMMAS = Pattern.compile("(?<=[a-zA-Z0-9]),{2,3}(?=[a-zA-Z0-9])");
    private static final Pattern DOT_SPACED = Pattern.compile("(?i)(?<=[a-z0-9])\\s+\\.\\s*(?=(?:com|net|org|gg|xyz|me|io|tk|ml|ga|cf|gq|co|es|mx|ar|cl|pe|ve|us|uk|eu|tv|cc|club|top|fun|online|site|pw|ru|br)\\b)");

    private final Set<String> whitelist = new HashSet<>();
    private final Set<String> wildcard = new HashSet<>();
    private final Set<String> cmdWhitelist = new HashSet<>();
    private boolean onlyTld;
    private boolean extra;
    private boolean ignoreHandles;
    private boolean ignoreUsernames;

    public LinkAdBlocker() {
        super("link-and-ad-blocker");
    }

    @Override
    protected void load(Opts o) {
        whitelist.clear();
        wildcard.clear();
        for (String s : o.list("domain-whitelist")) {
            String d = cleanHost(s);
            if (d.startsWith("*.")) {
                wildcard.add(d.substring(2));
            } else if (!d.isEmpty()) {
                whitelist.add(d);
            }
        }
        cmdWhitelist.clear();
        for (String s : o.list("command-whitelist")) {
            cmdWhitelist.add(s.trim().toLowerCase(Locale.ROOT));
        }
        onlyTld = o.bool("only-filter-top-level-domains", false);
        extra = o.bool("extra-sensitive", false);
        ignoreHandles = o.bool("ignore-handles", false);
        ignoreUsernames = o.bool("ignore-usernames", true);
    }

    @Override
    public void check(Check c) {
        if (c.ctx == Context.COMMAND && cmdWhitelist.contains(c.commandBase)) {
            return;
        }
        String text = c.text;
        if (ignoreUsernames) {
            for (String n : c.onlineNames) {
                text = text.replaceAll("(?i)" + Pattern.quote(n), " ");
            }
        }
        if (extra) {
            text = DOT_WORDS.matcher(text).replaceAll(".");
            text = DOT_SPOKEN.matcher(text).replaceAll(".");
            text = DOT_COMMAS.matcher(text).replaceAll(".");
            text = DOT_SPACED.matcher(text).replaceAll(".");
        }
        String hit = find(text);
        if (hit != null) {
            c.block();
            c.reply("modules.link-and-ad-blocker.trigger", "blocked_content", hit);
            c.flag(id, "link-or-ad-block", hit);
        }
    }

    private String find(String text) {
        Matcher ip = IP.matcher(text);
        if (ip.find()) {
            return ip.group();
        }
        Matcher m = (ignoreHandles ? DOMAIN_NO_AT : DOMAIN_AT).matcher(text);
        while (m.find()) {
            String host = cleanHost(m.group(1));
            String tld = host.substring(host.lastIndexOf('.') + 1);
            if (onlyTld && !TLDS.contains(tld)) {
                continue;
            }
            if (isWhitelisted(host)) {
                continue;
            }
            return m.group();
        }
        return null;
    }

    private boolean isWhitelisted(String host) {
        if (whitelist.contains(host)) {
            return true;
        }
        for (String w : wildcard) {
            if (host.equals(w) || host.endsWith("." + w)) {
                return true;
            }
        }
        return false;
    }

    private static String cleanHost(String s) {
        s = s.trim().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "");
        if (s.startsWith("*.")) {
            return "*." + s.substring(2).replaceFirst("^www\\.", "").replaceAll("[/:].*$", "");
        }
        s = s.replaceFirst("^www\\.", "");
        return s.replaceAll("[/:].*$", "");
    }

    /** Para pruebas. */
    static List<String> tldSample() {
        return new ArrayList<>(TLDS);
    }
}
