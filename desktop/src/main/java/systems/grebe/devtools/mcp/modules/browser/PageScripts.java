package systems.grebe.devtools.mcp.modules.browser;

/**
 * JavaScript, das im Browser läuft (über {@code executeScript}, also in jedem WebDriver-Browser gleich).
 *
 * <p>Elemente bekommen beim Auflisten eine Referenz ({@code data-mcp-ref="e12"}), über die Klick- und Eingabe-Tools
 * sie wiederfinden – auch in offenen Shadow-DOMs. Die Nummern zählen je Dokument hoch und gelten bis zur nächsten
 * Navigation.
 */
final class PageScripts {

    private PageScripts() {
    }

    /** Gemeinsame Funktionen; danach folgt der eigentliche Aufruf mit {@code return …}. */
    private static final String COMMON = """
            const INTERACTIVE = 'a[href],button,input:not([type=hidden]),select,textarea,summary,[role=button],[role=link],\
            [role=checkbox],[role=radio],[role=tab],[role=menuitem],[role=menuitemcheckbox],[role=menuitemradio],\
            [role=option],[role=switch],[role=combobox],[role=textbox],[role=searchbox],[role=slider],[role=spinbutton],\
            [role=treeitem],[contenteditable=""],[contenteditable=true],[onclick],[tabindex]:not([tabindex="-1"])';
            const STRUCTURE = 'h1,h2,h3,h4,h5,h6,[role=heading],img[alt]:not([alt=""]),[role=img][aria-label],[role=alert],\
            [role=status],[role=dialog],dialog[open],label,th,legend,caption,p,li,td,dt,dd,pre,blockquote,iframe';
            function clip(s, n) {
              s = (s || '').replace(/\\s+/g, ' ').trim();
              return s.length > n ? s.slice(0, n - 1) + '…' : s;
            }
            function ref(el) {
              let r = el.getAttribute('data-mcp-ref');
              if (!r) {
                window.__mcpRef = (window.__mcpRef || 0) + 1;
                r = 'e' + window.__mcpRef;
                el.setAttribute('data-mcp-ref', r);
              }
              return r;
            }
            function role(el) {
              const r = el.getAttribute('role');
              if (r) return r;
              const t = el.tagName.toLowerCase();
              if (t === 'a') return 'link';
              if (t === 'button' || t === 'summary') return 'button';
              if (t === 'select') return el.multiple ? 'listbox' : 'combobox';
              if (t === 'textarea') return 'textbox';
              if (t === 'img') return 'img';
              if (/^h[1-6]$/.test(t)) return 'heading' + t[1];
              if (t === 'input') {
                const ty = (el.type || 'text').toLowerCase();
                if (['button', 'submit', 'reset', 'image'].includes(ty)) return 'button';
                if (ty === 'range') return 'slider';
                if (['checkbox', 'radio', 'file', 'color', 'date', 'datetime-local', 'time', 'month', 'week'].includes(ty)) return ty;
                return 'textbox';
              }
              if (el.isContentEditable) return 'textbox';
              return t;
            }
            function name(el) {
              let n = el.getAttribute('aria-label');
              if (!n) {
                const ids = el.getAttribute('aria-labelledby');
                if (ids) n = ids.split(/\\s+/).map(i => { const e = document.getElementById(i); return e ? (e.innerText || e.textContent) : ''; }).join(' ');
              }
              if (!n && el.labels && el.labels.length) n = Array.from(el.labels).map(l => l.innerText).join(' ');
              if (!n) n = el.getAttribute('alt') || '';
              if (!n && el.tagName === 'INPUT' && ['button', 'submit', 'reset'].includes(el.type)) n = el.value;
              if (!n && ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName)) n = el.getAttribute('placeholder') || el.getAttribute('name') || '';
              if (!n && el.tagName === 'IFRAME') n = el.getAttribute('title') || el.getAttribute('src') || '';
              if (!n && !['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName)) n = el.innerText || el.textContent || '';
              if (!n) n = el.getAttribute('title') || '';
              return clip(n, 100);
            }
            function visible(el, viewportOnly) {
              const r = el.getBoundingClientRect();
              if (r.width < 1 && r.height < 1) return false;
              if (el.checkVisibility && !el.checkVisibility({opacityProperty: true, visibilityProperty: true})) return false;
              if (viewportOnly && (r.bottom < 0 || r.top > innerHeight || r.right < 0 || r.left > innerWidth)) return false;
              return true;
            }
            function props(el) {
              const p = [];
              const t = el.tagName;
              const ty = (el.type || '').toLowerCase();
              if ((t === 'INPUT' || t === 'TEXTAREA') && !['button', 'submit', 'reset', 'checkbox', 'radio'].includes(ty)) {
                if (ty && !['text', 'textarea', 'checkbox', 'radio'].includes(ty)) p.push('type=' + ty);
                if (el.value) p.push(ty === 'password' ? 'value=***' : 'value="' + clip(el.value, 60) + '"');
              }
              if (t === 'SELECT') {
                p.push('selected="' + clip(Array.from(el.selectedOptions).map(o => o.text).join(', '), 60) + '"');
                p.push('options=' + el.options.length);
              }
              if (el.isContentEditable && t !== 'BODY') p.push('editable');
              if (el.checked || el.getAttribute('aria-checked') === 'true') p.push('checked');
              if (el.disabled || el.getAttribute('aria-disabled') === 'true') p.push('disabled');
              if (el.getAttribute('aria-expanded')) p.push('expanded=' + el.getAttribute('aria-expanded'));
              if (el.getAttribute('aria-selected') === 'true') p.push('selected');
              if (el.required) p.push('required');
              if (el.readOnly) p.push('readonly');
              if (t === 'A') {
                const h = el.getAttribute('href') || '';
                if (h && !h.startsWith('javascript:')) p.push('href=' + clip(h, 80));
              }
              if (t === 'IFRAME') p.push('(Inhalt nicht aufgelistet)');
              if (document.activeElement === el) p.push('focused');
              return p;
            }
            function line(el) {
              const n = name(el);
              const p = props(el);
              return '[' + ref(el) + '] ' + role(el) + (n ? ' "' + n.replace(/"/g, "'") + '"' : '') + (p.length ? ' ' + p.join(' ') : '');
            }
            function all(root, out) {
              for (const el of root.querySelectorAll('*')) {
                out.push(el);
                if (el.shadowRoot) all(el.shadowRoot, out);
              }
              return out;
            }
            function find(r) {
              return all(document, []).find(el => el.getAttribute('data-mcp-ref') === r) || null;
            }
            function ownText(el) {
              let s = '';
              for (const n of el.childNodes) if (n.nodeType === 3) s += n.textContent;
              return s.trim();
            }
            function page(lines, truncated) {
              return {url: location.href, title: document.title, lines: lines, truncated: truncated,
                scrollX: Math.round(scrollX), scrollY: Math.round(scrollY), innerWidth: innerWidth, innerHeight: innerHeight,
                scrollWidth: document.documentElement.scrollWidth, scrollHeight: document.documentElement.scrollHeight};
            }
            """;

    /**
     * Seitenstruktur. Argumente: Modus ({@code interactive}|{@code all}), max. Zeilen, nur sichtbarer Ausschnitt,
     * Referenz eines Elements als Wurzel (oder leer).
     */
    static final String SNAPSHOT = COMMON + """
            const mode = arguments[0] || 'interactive', max = arguments[1] || 300, viewportOnly = !!arguments[2];
            const root = arguments[3] ? find(arguments[3]) : document;
            if (!root) return null;
            const listed = new Set(), lines = [];
            let truncated = false;
            for (const el of all(root, [])) {
              const interactive = el.matches(INTERACTIVE);
              const structure = mode === 'all' && !interactive && el.matches(STRUCTURE);
              if (!interactive && !structure) continue;
              if (!visible(el, viewportOnly)) continue;
              if (structure && !/^(H[1-6]|IMG|IFRAME|DIALOG)$/.test(el.tagName) && !el.getAttribute('role')) {
                if (el.querySelector(INTERACTIVE) && !ownText(el)) continue;
                let a = el.parentElement, nested = false;
                while (a) { if (listed.has(a) && !a.matches(INTERACTIVE)) { nested = true; break; } a = a.parentElement; }
                if (nested) continue;
              }
              if (lines.length >= max) { truncated = true; break; }
              listed.add(el);
              lines.push(structure && !/^(H[1-6]|IMG|IFRAME)$/.test(el.tagName) && !el.getAttribute('role')
                  ? '[' + ref(el) + '] ' + el.tagName.toLowerCase() + ' "' + clip(el.innerText, 160).replace(/"/g, "'") + '"'
                  : line(el));
            }
            return page(lines, truncated);
            """;

    /** Elemente mit Text (sichtbarer Text, Beschriftung, Platzhalter, Wert). Argumente: Text, max. Treffer. */
    static final String FIND_TEXT = COMMON + """
            const q = (arguments[0] || '').toLowerCase(), max = arguments[1] || 50;
            const hits = [];
            const label = el => [el.getAttribute('aria-label'), el.getAttribute('placeholder'), el.getAttribute('title'),
                el.getAttribute('alt'), el.tagName === 'INPUT' ? el.value : null].filter(Boolean).join(' ').toLowerCase();
            for (const el of all(document, [])) {
              if (/^(SCRIPT|STYLE|NOSCRIPT|HTML|BODY|HEAD)$/.test(el.tagName)) continue;
              const own = label(el).includes(q);
              // textContent vorab: innerText erzwingt Layout und ist auf großen Seiten teuer
              if (!own && !(el.textContent || '').toLowerCase().includes(q)) continue;
              const text = (el.innerText || '').toLowerCase();
              if (!own && !text.includes(q)) continue;
              if (!own && Array.from(el.children).some(c => (c.innerText || '').toLowerCase().includes(q))) continue;
              if (!visible(el, false)) continue;
              const target = el.closest(INTERACTIVE) || el;
              if (hits.includes(target)) continue;
              hits.push(target);
              if (hits.length >= max) break;
            }
            return page(hits.map(line), hits.length >= max);
            """;

    /** Beschreibung bereits gefundener Elemente (CSS/XPath aus Java). Argument: Liste der Elemente. */
    static final String DESCRIBE = COMMON + """
            return page(Array.from(arguments[0]).map(line), false);
            """;

    /** Element zu einer Referenz (oder {@code null}). Argument: Referenz. */
    static final String LOOKUP = COMMON + """
            return find(arguments[0]);
            """;

    /** Sichtbarer Text der Seite oder eines Elements. Argument: Referenz oder leer. */
    static final String TEXT = COMMON + """
            const root = arguments[0] ? find(arguments[0]) : document.body;
            if (!root) return null;
            const p = page([], false);
            p.text = root.innerText || root.textContent || '';
            return p;
            """;

    /** Zustand der Seite für Statuszeilen. */
    static final String STATE = COMMON + """
            return page([], false);
            """;

    /**
     * Ersatz für WebDriver BiDi (Safari, angehängte Browser): hängt sich an {@code console.*}, {@code error} und
     * {@code unhandledrejection} und puffert in {@code window.__mcpConsole}; liefert und leert, was seit dem letzten
     * Aufruf gepuffert wurde. Erfasst nur, was nach dem ersten Aufruf auf einer Seite geschieht.
     */
    static final String CONSOLE_HOOK = """
            const w = window;
            if (!w.__mcpConsole) {
              w.__mcpConsole = [];
              const text = a => {
                try {
                  return typeof a === 'string' ? a : a instanceof Error ? (a.stack || String(a)) : JSON.stringify(a);
                } catch (e) {
                  return String(a);
                }
              };
              const push = (level, args) => {
                w.__mcpConsole.push({t: Date.now(), level: level, text: Array.from(args).map(text).join(' ')});
                if (w.__mcpConsole.length > 1000) w.__mcpConsole.shift();
              };
              for (const [m, l] of [['log', 'info'], ['info', 'info'], ['warn', 'warn'], ['error', 'error'], ['debug', 'debug']]) {
                const orig = console[m];
                console[m] = function () { push(l, arguments); return orig.apply(console, arguments); };
              }
              w.addEventListener('error', e => push('exception', [e.message + (e.filename ? ' (' + e.filename + ':' + e.lineno + ')' : '')]));
              w.addEventListener('unhandledrejection', e => push('exception', ['Unhandled rejection: ' + text(e.reason)]));
            }
            return w.__mcpConsole.splice(0, w.__mcpConsole.length);
            """;

    /** Ersatz für BiDi-Netzwerk: Resource Timing der aktuellen Seite (ohne Methode; Status, wo der Browser ihn kennt). */
    static final String RESOURCES = """
            return performance.getEntriesByType('navigation').concat(performance.getEntriesByType('resource')).map(e => ({
              t: Math.round(performance.timeOrigin + e.startTime), url: e.name, type: e.initiatorType || 'navigation',
              status: e.responseStatus || 0, bytes: e.transferSize || 0, ms: Math.round(e.duration)}));
            """;
}
