package com.extantape.bluemapauth.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * BlueMapAuth's own two pages: the hand-off that turns a one-time token into the map's own cookie, and the sign-in page a
 * visitor sees before that. Plain HTML that reads well with a screen reader, and works in light and dark. The one
 * script is allowed by its hash and nothing else is.
 */
final class Pages {
    static final String CSS = "body{font:17px/1.5 system-ui,sans-serif;max-width:34rem;margin:3rem auto;padding:0 1rem;color:#1a1a1a;background:#fff}"
            + "a{color:#0b57d0}@media(prefers-color-scheme:dark){body{color:#eee;background:#141414}a{color:#8ab4f8}}";

    static final String SCRIPT = "(function(){var m=document.getElementById('m'),t=location.hash.slice(1);"
            + "history.replaceState(null,'',location.pathname);"
            + "function say(s){m.textContent=s;}"
            + "if(!/^[A-Za-z0-9_-]{43}$/.test(t)){say('This link has no sign-in in it. Open the map again from the invite page.');return;}"
            + "fetch('/handoff',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:'token='+t,credentials:'same-origin'})"
            + ".then(function(r){if(r.ok){location.replace('/');return;}"
            + "say(r.status===429?'Too many tries. Wait a few minutes, then open the map again from the invite page.'"
            + ":'That link didn\\'t work. It works once, for a minute. Open the map again from the invite page.');})"
            + ".catch(function(){say('Couldn\\'t reach the map. Try again in a moment.');});})();";

    static final String STRICT_CSP = "default-src 'none'; style-src '" + sha256(CSS) + "'; script-src '" + sha256(SCRIPT)
            + "'; connect-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

    private Pages() {}

    static String handoff(String signInUrl) {
        return page("Opening the map",
                "<h1>Opening the map</h1><p id=\"m\" role=\"status\" aria-live=\"polite\">Signing you in…</p>"
                        + "<noscript><p>The map needs JavaScript.</p></noscript>" + back(signInUrl), true);
    }

    static String signIn(String signInUrl) {
        return page("Sign in to see the map",
                "<h1>Sign in to see the map</h1><p>The map is for players on the server. Sign in on the invite page, then choose <strong>Open the map</strong>.</p>"
                        + back(signInUrl), false);
    }

    private static String back(String signInUrl) {
        return signInUrl.isEmpty() ? "" : "<p><a href=\"" + esc(signInUrl) + "\">Go to the invite page</a></p>";
    }

    private static String page(String title, String body, boolean script) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"referrer\" content=\"no-referrer\">"
                + "<title>" + esc(title) + "</title><style>" + CSS + "</style></head><body><main>" + body + "</main>"
                + (script ? "<script>" + SCRIPT + "</script>" : "") + "</body></html>";
    }

    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    static String sha256(String s) {
        try {
            return "sha256-" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
