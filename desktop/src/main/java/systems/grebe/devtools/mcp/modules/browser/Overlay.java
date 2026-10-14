package systems.grebe.devtools.mcp.modules.browser;

import java.util.List;
import java.util.Map;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.WebElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zeigt im Browser, was das LLM tut: leuchtender Rahmen, solange gesteuert wird, ein eigener Mauszeiger, der zum Ziel
 * gleitet, eine Welle beim Klick und eine Leiste mit Beschriftung, aktueller Aktion und „Stopp“. WebDriver bewegt nur
 * einen virtuellen Zeiger im Browser – ohne diese Ebene sähe der Nutzer davon nichts.
 *
 * <p>Die Ebene hängt als Shadow-DOM an {@code <html>} ({@code #__mcp_overlay}, {@code pointer-events: none}), fängt
 * also keine Klicks ab; nur „Stopp“ ist anklickbar und während einer Aktion abgeschaltet. Vor Screenshots wird sie
 * ausgeblendet; browser_read_page und browser_find übergehen sie. Nach einer Navigation legt die nächste Aktion sie
 * neu an. Ohne Fenster (headless) bleibt alles aus.
 *
 * <p>„Stopp“ setzt {@code window.__mcpStop}; die nächste Eingabe liest es, merkt es sich in der Sitzung und lehnt ab,
 * bis der Nutzer „Fortsetzen“ drückt.
 */
final class Overlay {

    private static final Logger LOG = LoggerFactory.getLogger(Overlay.class);
    static final String HOST_ID = "__mcp_overlay";

    /** Ebene anlegen bzw. aktualisieren. Argumente: Beschriftung, gestoppt, Zeiger x, Zeiger y. */
    private static final String ENSURE = """
            const label = arguments[0], stopped = !!arguments[1], cx = arguments[2], cy = arguments[3];
            let host = document.getElementById('__mcp_overlay');
            if (!host) {
              host = document.createElement('div');
              host.id = '__mcp_overlay';
              host.style.cssText = 'position:fixed;inset:0;pointer-events:none;z-index:2147483647;display:block';
              const root = host.attachShadow({mode: 'open'});
              root.innerHTML = `<style>
                .glow{position:fixed;inset:0;pointer-events:none;border-radius:3px;
                  box-shadow:inset 0 0 0 2px rgba(217,119,87,.95),inset 0 0 22px 6px rgba(217,119,87,.45);
                  animation:pulse 2.2s ease-in-out infinite}
                @keyframes pulse{0%,100%{opacity:.6}50%{opacity:1}}
                .cursor{position:fixed;left:0;top:0;width:24px;height:24px;pointer-events:none;
                  transform:translate(-200px,-200px);transition:transform var(--d,400ms) cubic-bezier(.25,.8,.3,1);
                  filter:drop-shadow(0 2px 3px rgba(0,0,0,.35))}
                .ripple{position:fixed;left:0;top:0;width:34px;height:34px;margin:-17px 0 0 -17px;border-radius:50%;
                  border:2px solid #d97757;background:rgba(217,119,87,.18);opacity:0;pointer-events:none}
                .ripple.go{animation:ripple .55s ease-out}
                @keyframes ripple{from{transform:scale(.3);opacity:1}to{transform:scale(1.5);opacity:0}}
                .pill{position:fixed;top:12px;left:50%;transform:translateX(-50%);display:flex;align-items:center;gap:10px;
                  max-width:min(92vw,640px);padding:6px 6px 6px 14px;border-radius:999px;background:rgba(31,30,29,.94);
                  color:#faf9f5;font:500 13px/1.25 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;
                  box-shadow:0 6px 20px rgba(0,0,0,.28);pointer-events:auto;user-select:none}
                .dot{flex:none;width:8px;height:8px;border-radius:50%;background:#d97757;animation:pulse 1.2s infinite}
                .label{flex:none;font-weight:600}
                .act{min-width:0;opacity:.72;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
                button{all:unset;flex:none;cursor:pointer;padding:4px 12px;border-radius:999px;background:#d97757;
                  color:#fff;font-weight:600}
                button:hover{background:#c4633f}
                :host([data-busy]) .pill{pointer-events:none}
                :host([data-stopped]) .glow{box-shadow:inset 0 0 0 2px rgba(140,140,140,.9);animation:none}
                :host([data-stopped]) .dot{background:#8c8c8c;animation:none}
                :host([data-stopped]) .cursor{opacity:0}
              </style>
              <div class="glow"></div><div class="ripple"></div>
              <svg class="cursor" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 2.5v17.2l4.6-4.3 3.1 6.8 3-1.4
                -3.1-6.7h6.3z" fill="#fff" stroke="#1f1e1d" stroke-width="1.6" stroke-linejoin="round"/></svg>
              <div class="pill" role="status"><span class="dot"></span><span class="label"></span><span class="act"></span>
                <button type="button"></button></div>`;
              root.querySelector('button').addEventListener('click', e => {
                e.preventDefault();
                e.stopPropagation();
                if (host.hasAttribute('data-stopped')) {
                  window.__mcpResume = true;
                  window.__mcpStop = false;
                  host.removeAttribute('data-stopped');
                } else {
                  window.__mcpStop = true;
                  window.__mcpResume = false;
                  host.setAttribute('data-stopped', '');
                }
                root.querySelector('button').textContent = host.hasAttribute('data-stopped') ? 'Fortsetzen' : 'Stopp';
                root.querySelector('.act').textContent = host.hasAttribute('data-stopped') ? 'angehalten' : '';
              }, true);
              (document.documentElement || document.body).appendChild(host);
              if (cx >= 0 && cy >= 0) {
                const c = root.querySelector('.cursor');
                c.style.transition = 'none';
                c.style.transform = 'translate(' + (cx - 5) + 'px,' + (cy - 2.5) + 'px)';
                c.getBoundingClientRect();
                c.style.transition = '';
              }
            }
            const root = host.shadowRoot;
            if (!window.__mcpStop && !window.__mcpResume) {
              if (stopped) host.setAttribute('data-stopped', ''); else host.removeAttribute('data-stopped');
            }
            root.querySelector('.label').textContent = label;
            root.querySelector('button').textContent = host.hasAttribute('data-stopped') ? 'Fortsetzen' : 'Stopp';
            if (host.hasAttribute('data-stopped')) root.querySelector('.act').textContent = 'angehalten';
            const out = {stop: window.__mcpStop === true, resume: window.__mcpResume === true};
            window.__mcpStop = false;
            window.__mcpResume = false;
            return out;
            """;

    /** Zeiger bewegen. Argumente: x, y (CSS-Pixel im Ausschnitt), Dauer in ms, Aktion als Text. */
    private static final String MOVE = """
            const host = document.getElementById('__mcp_overlay');
            if (!host) return;
            const root = host.shadowRoot, c = root.querySelector('.cursor');
            host.setAttribute('data-busy', '');
            c.style.setProperty('--d', arguments[2] + 'ms');
            c.style.transform = 'translate(' + (arguments[0] - 5) + 'px,' + (arguments[1] - 2.5) + 'px)';
            if (arguments[3]) root.querySelector('.act').textContent = arguments[3];
            """;

    /** Klick-Welle. Argumente: x, y. */
    private static final String RIPPLE = """
            const host = document.getElementById('__mcp_overlay');
            if (!host) return;
            const r = host.shadowRoot.querySelector('.ripple');
            r.style.left = arguments[0] + 'px';
            r.style.top = arguments[1] + 'px';
            r.classList.remove('go');
            r.getBoundingClientRect();
            r.classList.add('go');
            """;

    /** Aktion beendet: Leiste wieder anklickbar. Argument: Text der Aktion (oder leer). */
    private static final String IDLE = """
            const host = document.getElementById('__mcp_overlay');
            if (!host) return;
            host.removeAttribute('data-busy');
            if (arguments[0] != null && !host.hasAttribute('data-stopped')) host.shadowRoot.querySelector('.act').textContent = arguments[0];
            """;

    /** Nur die Aktion in der Leiste. Argument: Text. */
    private static final String CAPTION = """
            const host = document.getElementById('__mcp_overlay');
            if (!host) return;
            host.setAttribute('data-busy', '');
            if (!host.hasAttribute('data-stopped')) host.shadowRoot.querySelector('.act').textContent = arguments[0];
            """;

    private static final String VISIBLE = """
            const host = document.getElementById('__mcp_overlay');
            if (host) host.style.display = arguments[0] ? 'block' : 'none';
            """;

    private static final String CENTER = """
            const r = arguments[0].getBoundingClientRect();
            return [Math.round(r.left + r.width / 2), Math.round(r.top + r.height / 2)];
            """;

    private Overlay() {
    }

    /**
     * Vor einer Eingabe: Ebene sicherstellen und „Stopp“/„Fortsetzen“ des Nutzers übernehmen.
     *
     * @throws IllegalStateException wenn der Nutzer die Steuerung angehalten hat
     */
    static void guard(BrowserSession s, WebDriver d) {
        if (!s.showActions) {
            return;
        }
        Map<?, ?> r = ensure(s, d);
        if (r != null && Boolean.TRUE.equals(r.get("resume"))) {
            s.stoppedByUser = false;
        }
        if (r != null && Boolean.TRUE.equals(r.get("stop"))) {
            s.stoppedByUser = true;
        }
        if (s.stoppedByUser) {
            ensure(s, d);
            throw new IllegalStateException("Der Nutzer hat die Steuerung im Browser angehalten („Stopp“) – nicht "
                    + "weiter bedienen, sondern beim Nutzer nachfragen. Er kann im Browser „Fortsetzen“ drücken.");
        }
    }

    /** Nach Navigationen: Ebene auf der neuen Seite anlegen, Leiste wieder freigeben. */
    static void refresh(BrowserSession s, WebDriver d, String action) {
        if (!s.showActions) {
            return;
        }
        Map<?, ?> r = ensure(s, d);
        if (r != null && Boolean.TRUE.equals(r.get("stop"))) {
            s.stoppedByUser = true;
        }
        run(d, IDLE, action);
    }

    private static Map<?, ?> ensure(BrowserSession s, WebDriver d) {
        try {
            Object r = ((JavascriptExecutor) d).executeScript(ENSURE, s.overlayLabel, s.stoppedByUser,
                    s.cursorX, s.cursorY);
            return r instanceof Map<?, ?> m ? m : null;
        } catch (WebDriverException e) {
            LOG.debug("Overlay nicht angelegt", e);
            return null;
        }
    }

    /** Zeiger zum Element gleiten lassen; liefert dessen Mitte (CSS-Pixel). */
    static int[] moveTo(BrowserSession s, WebDriver d, WebElement el, String action) {
        if (!s.showActions) {
            return null;
        }
        try {
            List<?> c = (List<?>) ((JavascriptExecutor) d).executeScript(CENTER, el);
            int[] p = {((Number) c.get(0)).intValue(), ((Number) c.get(1)).intValue()};
            moveTo(s, d, p[0], p[1], action);
            return p;
        } catch (WebDriverException | ClassCastException e) {
            LOG.debug("Zeiger nicht bewegt", e);
            return null;
        }
    }

    /** Zeiger zu einer Stelle gleiten lassen und warten, bis er dort ist. */
    static void moveTo(BrowserSession s, WebDriver d, int x, int y, String action) {
        if (!s.showActions) {
            return;
        }
        double dist = s.cursorX < 0 ? 400 : Math.hypot(x - s.cursorX, y - s.cursorY);
        // kurze Wege schneller, damit kleine Schritte nicht träge wirken
        int ms = (int) Math.round(s.animationMillis * Math.min(1.0, 0.35 + dist / 600));
        run(d, MOVE, x, y, ms, action);
        s.cursorX = x;
        s.cursorY = y;
        sleep(ms + 40);
    }

    static void ripple(BrowserSession s, WebDriver d, int[] p) {
        if (s.showActions && p != null) {
            run(d, RIPPLE, p[0], p[1]);
        }
    }

    /** Nur die Aktion in der Leiste anzeigen (Tasten, Tippen ohne Ziel). */
    static void caption(BrowserSession s, WebDriver d, String action) {
        if (s.showActions) {
            run(d, CAPTION, action);
        }
    }

    /** Für Screenshots aus- und wieder einblenden. */
    static void visible(BrowserSession s, WebDriver d, boolean visible) {
        if (s.showActions) {
            run(d, VISIBLE, visible);
        }
    }

    private static void run(WebDriver d, String script, Object... args) {
        try {
            ((JavascriptExecutor) d).executeScript(script, args);
        } catch (WebDriverException e) {
            LOG.debug("Overlay-Skript fehlgeschlagen", e);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
