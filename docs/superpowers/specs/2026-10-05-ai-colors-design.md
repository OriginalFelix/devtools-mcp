# Eigene Farbe und eigener Zustand je KI

Stand: 2026-10-05 · Modul `window` (Desktop-App), Projekt `natives`

## Ziel

Man soll sehen, welche KI welches Fenster steuert. Jede KI (= jede MCP-Session) bekommt eine eigene Farbe für Rahmen,
Hinweis und Zeiger – und dafür einen eigenen Zustand (Bindung, Zeiger, Rahmen), damit mehrere KIs gleichzeitig
verschiedene Fenster steuern können.

## Farben

- Farbton bei voller Sättigung und Helligkeit (HSB 100 %/100 %) – die reinen Farben des RGB-Würfels.
- Vergabe „größte Lücke“: erste KI 0° (Rot); jede weitere bekommt die Mitte der größten Lücke zwischen den belegten
  Farbtönen auf dem Farbkreis (bei Gleichstand die erste ab 0°). Ergibt 0°, 180°, 90°, 270°, 45°, 135° …
- Stabil: eine KI behält ihre Farbe, bis ihre Session freigegeben wird; frei gewordene Lücken werden wieder vergeben.
- Schrift im Hinweis schwarz oder weiß – je nach relativer Leuchtdichte der Farbe (Schwelle 0,5).
- X11: den zweiten Zeiger (MPX-Master) zeichnet der X-Server; dort sind nur Rahmen und Hinweis farbig.

## Zustand je KI

- Schlüssel ist die MCP-Session-ID aus dem Exchange des Tool-Aufrufs; ohne Session (Tests, Aufrufe ohne MCP) gilt die
  Session `local`. Name der KI für den Hinweis: `clientInfo.name` des Clients, sonst „KI“.
- Je Session: Bindung, Screenshot-Faktoren, Farbe, eigenes Overlay (Rahmen, Hinweis, KI-Zeiger im Modus `maus`),
  eigene Eingabegeräte mit eigenem nativen Zeiger in ihrer Farbe.
- Hinweis: „<Client> steuert dieses Fenster“ + Modus („mit eigenem Zeiger“ bzw. „– Maus bewegen bricht ab“).
- Gemeinsam bleiben: echte Maus/Tastatur (Robot) im Modus `maus`/`tastatur`, die Eingabesperre (Eingaben
  nacheinander), Nutzer-Anwesenheit, der Prozessfilter und die per `window_launch` gestarteten Programme.
- Konflikt: `window_bind`/`window_launch` lehnen einen Prozess ab, den eine andere Session gebunden hat – auch wenn
  sich die Prozessbäume überschneiden (der neue Prozess liegt im Baum der fremden Bindung oder umgekehrt). Meldung:
  „<Prozess> wird gerade von <Client> gesteuert.“
- Aufräumen: 30 Minuten ohne Fenster-Tool-Aufruf einer Session → Bindung aufheben, Overlay und Zeiger schließen,
  Farbe freigeben, Session vergessen. Das Ausblenden des Zeigers nach 60 s Leerlauf bleibt unverändert.

## Aufbau

| Teil | Ort | Aufgabe |
|---|---|---|
| `ToolSession` | `desktop/core` | Session-ID und Client-Name des laufenden Aufrufs (ThreadLocal); `ManagedToolCallback` setzt ihn aus dem MCP-Exchange. |
| `AiColors` | `modules/window` | Vergibt Farbtöne nach „größter Lücke“, gibt sie frei; `color(hue)`, `textColor(color)`. |
| `WindowSession` | `modules/window` | zusätzlich Client, Farbe, Ressourcen (Overlay, Geräte) und Konfliktprüfung beim Binden; `close()`. |
| `WindowSessions` | `modules/window` | Session-ID → `WindowSession`; Leerlauf-Aufräumen; Konfliktprüfung über alle Sessions. |
| `WindowSupport` | `modules/window` | holt die Session je Aufruf (`Supplier<WindowSession>`). |
| `WindowModule` | `modules/window` | Overlay und Geräte je Session, Hinweis mit Client-Name. |
| `ControlOverlay` | `modules/window` | Farbe je Instanz, `close()`. |
| `VirtualCursorInputDevice`, `OverlayInputDevice` | `modules/window` | Farbe für den nativen Zeiger, `close()`. |
| `CursorController.create(x, y, Color)` | `natives` | neue Methode; `create(x, y)` nutzt `CursorImage.ACCENT`. |
| `CursorImage.render(scale, color)`, Zeigerfenster Windows/macOS | `natives` | zeichnen in der übergebenen Farbe. |

## Tests

- `AiColorsTest`: Reihenfolge 0/180/90/270/45; Freigabe und Wiedervergabe der Lücke; Textfarbe auf Gelb schwarz, auf
  Blau weiß.
- `WindowSessionsTest` (mit `FakeDesktop`-Prozessen bzw. dem Testprozess): zwei Sessions bekommen verschiedene Farben;
  derselbe Prozess wird für die zweite Session abgelehnt; Leerlauf räumt auf und gibt die Farbe frei.
- `ToolSession`: `ManagedToolCallback` setzt Session-ID während des Aufrufs (Test mit Fake-Exchange oder ohne Kontext →
  `local`).
- `CursorImageTest`: Pixel im Pfeil haben die übergebene Farbe.
