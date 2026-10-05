# Fenstersteuerung (`window_*`) – Design

Ziel: Die KI bekommt über MCP die Kontrolle über die Fenster eines freigegebenen Prozesses – sehen (Screenshot),
klicken, tippen, Tasten drücken. Eingaben über `java.awt.Robot`, Fenster ↔ Prozess/Thread über native APIs per FFM,
Prozessbaum über `ProcessHandle`.

## Entscheidungen

| Frage | Entscheidung |
|---|---|
| Zielprozesse | alle Prozesse mit sichtbaren Fenstern, gefiltert per Include/Exclude-Regex; fest ausgeschlossen: diese App, Anmelde-/Credential-Dialoge, Passwortmanager |
| Wahrnehmung | Screenshot des Fensters als MCP-Bild (PNG), Koordinaten relativ zum Bild |
| Plattformen | Windows (user32/dwmapi), macOS (CoreGraphics/AppKit/AX), Linux X11 (libX11); Wayland ohne X nicht |
| Bindung | an einen Prozess und (optional) alle seine Nachfahren; nur abwärts im Prozessbaum – der Elternprozess nie, Geschwisterprozesse (samt Nachfahren) nur mit Schalter `allowSiblings` (Standard aus) |
| Native Schicht | FFM (`java.lang.foreign`), keine neue Abhängigkeit |
| Sicherheit | Lesen und Eingabe getrennt schaltbar; Fokus-Prüfung vor jeder Eingabe; Not-Aus durch Mausbewegung |

## Tools

Lesend (Modul an): `window_list`, `window_bind`, `window_windows`, `window_screenshot`, `window_unbind`.
Maus (Schalter `allowInput`, Standard aus): `window_click`, `window_scroll`, `window_drag`.
Tastatur (Schalter `allowKeyboard`, Standard aus, nur zusammen mit `allowInput`): `window_type`, `window_key`;
ohne ihn lehnt `window_click` gehaltene Tasten (`modifiers`) ab. Eine zweite Tastatur gibt es nicht – Tastatur-
Eingaben holen das Fenster nach vorn und nutzen die echte Tastatur.

- Bindung je `ToolScope` (Benutzer); verfällt, wenn der Prozess endet (`ProcessHandle.isAlive`).
- Fenster-ID = natives Handle (HWND, CGWindowID, X11 Window), hexadezimal ausgegeben. Ohne Angabe: Vordergrundfenster
  des gebundenen Prozesses, sonst sein größtes Fenster.
- Thread-ID des Fensters nur unter Windows (`GetWindowThreadProcessId`), sonst leer.
- Screenshot wird auf `maxImageWidth` (Standard 1280) verkleinert; der Faktor wird je Fenster gemerkt und von
  Klick/Scroll/Drag zurückgerechnet.

## Sicherheit

- `InputGuard`: Fenster gehört (neu ermittelt) zum gebundenen Prozessbaum → aktivieren → bis 500 ms warten, bis es im
  Vordergrund ist → Punkt liegt in den aktuellen Fenstergrenzen. Bei Text alle 20 Zeichen erneut; Abbruch meldet, wie
  viel schon getippt wurde.
- `UserPresenceMonitor`: weicht die Mausposition um mehr als 8 px von der zuletzt per Robot gesetzten ab, wird
  abgebrochen und Eingabe für `cooldownSeconds` (Standard 10) gesperrt – auch zwischen zwei Aufrufen.
- `InputExecutor`: ein globales Lock für alle Robot-Aktionen.
- Tastenkombinationen mit der Windows-/Super-Taste werden abgelehnt (wirken auf die Shell, nicht auf das Fenster);
  unter macOS ist Cmd erlaubt.
- Headless-Betrieb oder nicht unterstützte Plattform: das Modul liefert keine Tools und nennt den Grund.

## Programme starten (`window_launch`)

Schalter `allowLaunch` (Standard aus). Das Programm muss den Prozessfilter passieren (Name und Kommandozeile, immer
ausgeschlossene Programme nie). Gestartet wird im Hintergrund – Windows: `ShellExecuteEx` mit `SW_SHOWNOACTIVATE`
(auch „App Paths“ wie `winword`), macOS: `open -g -a`, sonst direkt. Unter Windows wacht danach 20 s ein `FocusGuard`:
holt sich ein Fenster des Programms den Vordergrund, geht er sofort an das Fenster zurück, in dem der Nutzer arbeitet
(wechselt der Nutzer selbst, wird das neue Fenster gemerkt); ist dabei eine Maustaste gedrückt, hat der Nutzer es
angeklickt und der Wächter hört auf. Danach wird der Prozess gebunden. Gibt `ShellExecuteEx` keinen Prozess zurück
(Weitergabe an eine laufende Instanz), wird der neueste Prozess mit dem Programmnamen gesucht.

## Anzeige während der Steuerung

`ControlOverlay` über `OverlayInputDevice` (umhüllt `RobotInputDevice`): ab der ersten Eingabe ein Rahmen in
Akzentfarbe außerhalb der Fenstergrenzen (folgt dem Fenster, alle 400 ms neu gelesen), ein Hinweis („KI steuert dieses
Fenster – Maus bewegen bricht ab“) und der native KI-Zeiger aus `window.cursor` an der letzten Mausposition der KI –
nicht in Screenshots (`WDA_EXCLUDEFROMCAPTURE` bzw. `NSWindowSharingNone`). Ausgeblendet bei `window_unbind`, bei einem
Eingriff des Nutzers und nach 60 s ohne Aktion. Ohne Bildschirm oder ohne nativen Zeiger fehlt nur die Anzeige.

## Plattformschicht

`WindowSystem` (windows, window, foreground, activate, Prüfungen) mit `Win32WindowSystem`, `MacWindowSystem`,
`X11WindowSystem`. Fenstergrenzen werden in Java-User-Space-Koordinaten geliefert (`ScreenMapper`):
Windows physische Pixel → je Monitor skaliert mit festem Ursprung; macOS Punkte = User-Space; X11 Pixel / Skalierung.

Eingaben und Bildschirmaufnahme laufen über `InputDevice` (Implementierung `RobotInputDevice`), damit Guard, Monitor
und Tools ohne echten Bildschirm testbar sind.

## Zweiter Zeiger (`window.cursor`)

SPI per `ServiceLoader`: `CursorProvider` (`platform()` als `oshi.PlatformEnum`, `unsupportedReason()`,
`controller()`), Auswahl über `CursorProvider.current()`. Der `CursorController` bietet `create`, `destroy`, `move`,
`position`, `press`/`release`, `click` (1–3 Klicks), `clickAt`, `scroll`, `cursors`, `close`; Griffe sind
`VirtualCursor`. `AbstractCursorController` übernimmt Griffe, Position, gehaltene Tasten und Klickfolgen, die
Provider liefern nur die nativen Schritte (alles **JNA**, `jna-platform`):

| | Zeiger | Eingabe (Zeiger des Nutzers bleibt stehen) |
|---|---|---|
| `windows` | Layered-Fenster (`WS_EX_LAYERED \| TRANSPARENT \| TOPMOST \| NOACTIVATE`), eigener Thread, Per-Monitor-DPI v2, nicht in Bildschirmaufnahmen | klassische Fenster: `PostMessageW` an das Fenster unter dem Punkt (Client-Koordinaten im DPI-Kontext des Ziels, `*DBLCLK` bei `CS_DBLCLKS`, Hover). UWP/WinUI (lesen keine Maus-Nachrichten, echte Mausereignisse nur über den einen Systemzeiger): Linksklick per UI Automation auf das Element unter der Spitze (Invoke/Toggle/Select/Expand, Standardaktion), Rad per ScrollPattern; Ziehen, Rechtsklick (langes Drücken) und Rückfall per `InjectTouchInput` mit eigener Kontakt-ID. Geprüft und verworfen: `PT_MOUSE` (nicht erlaubt), `PT_PEN` (bewegt den Systemzeiger), `SendInput` (bewegt ihn ohnehin) |
| `macos` | randloses `NSWindow` (Bildschirmschoner-Ebene, mausdurchlässig, alle Spaces), AppKit per `dispatch_async_f` auf der Main-Queue | `CGEventPostToPid` an den Prozess des Fensters unter dem Punkt (`CGWindowListCopyWindowInfo`), Klickzähler im Ereignis; braucht „Bedienungshilfen“ |
| `x11` | echter MPX-Master (`XIAddMaster`/`XIWarpPointer`/`XIRemoveMaster`) | XTest über das XTEST-Gerät des eigenen Masters; Ziel, Ziehen und Doppelklick regelt der X-Server |

Bei gehaltener Taste gehen alle Ereignisse an das Ziel des Drückens (wie `SetCapture`).

Zwei getrennte Einstellungen, frei kombinierbar (`VirtualCursorInputDevice` mit dem `CursorController`, je
Kombination ein Gerät):

- „Maus der KI“ (`pointerMode`): `eigener-zeiger` (Standard) – Klicks, Ziehen, Scrollen ohne das Fenster nach vorn zu
  holen; vor jedem Klick wird geprüft, dass kein fremdes Fenster an der Stelle darüber liegt. `maus` – Robot, echte Maus,
  Not-Aus bei Mausbewegung.
- „Tastatur der KI“ (`keyboardMode`): `eigene-tastatur` (Standard) – Tastatur des Zeigers (siehe unten), ohne das Fenster
  nach vorn zu holen; mit echter Maus steht der Zeiger dafür in der Mitte des Zielfensters. `tastatur` – Robot, echte
  Tastatur, Fenster wird aktiviert.

Eigene Tastatur: jeder Zeiger hat einen eigenen Fokus – das zuletzt angeklickte Element (vorher das fokussierte
Element des Fensters unter dem Zeiger, sonst das Fenster). Windows: `WM_CHAR` für Text, `WM_KEYDOWN`/`WM_KEYUP`
(`WM_SYS*` mit Alt) für Tasten direkt an diesen Fokus; Strg/Umschalt/Alt werden nur im Tastaturzustand des Ziel-Threads
gesetzt (`AttachThreadInput` + `SetKeyboardState`), auch für Strg-/Umschalt-Klicks (`MK_CONTROL`/`MK_SHIFT`). UWP/WinUI:
dieselben Nachrichten an das `CoreWindow` – Zeichen kommen an, reine Steuertasten (Escape, Enter) im Rechner nicht.
macOS: `CGEventCreateKeyboardEvent` (Text per `CGEventKeyboardSetUnicodeString`, Tasten über `kVK_*`, Modifikatoren als
Flags) per `CGEventPostToPid` an den Prozess des zuletzt angeklickten Fensters. X11: Master-Tastatur des MPX-Masters,
Fokus `PointerRoot` (folgt dem zweiten Zeiger), Tasten per `XTestFakeDeviceKeyEvent` auf deren XTEST-Gerät.

Getestet: Basisklasse mit Attrappe, ServiceLoader, Bild; Windows real (Fenster erzeugen/bewegen/zerstören, Nachrichten
mit Client-Koordinaten, Klick über die Fenstersuche – übersprungen, wenn z.B. der Sperrbildschirm darüber liegt).
macOS/X11 nicht automatisiert.

## Bilder als Tool-Ergebnis

`core/ToolImages` (wie `ToolProgress`): Tools hängen während des Aufrufs Bilder an, der Spezifikations-Wrapper in
`McpRuntime` fügt sie dem `CallToolResult` als `ImageContent` hinzu. Der Text bleibt das protokollierte Ergebnis.

## Tests

- Einheiten: `KeySpec`, `ScreenMapper`, `UserPresenceMonitor`, `InputGuard` und Tools mit gefälschtem
  `WindowSystem`/`InputDevice`, Prozessfilter, `ToolImages`.
- Windows-Integration (`@EnabledOnOs(WINDOWS)`): `Win32WindowSystem` findet das Fenster eines gestarteten
  Fixture-Prozesses samt PID und Thread-ID; übersprungen ohne interaktiven Desktop.
- macOS/X11 werden hier nicht automatisiert getestet.
