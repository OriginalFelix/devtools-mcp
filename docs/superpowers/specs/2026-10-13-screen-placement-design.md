# Bildschirm für KI-Programme – Design

Ergänzt die Fenstersteuerung ([2026-10-02-window-control-design.md](2026-10-02-window-control-design.md)).

## Ziel

Programme, die die KI mit `window_launch` startet, gehen auf einem Bildschirm auf, den der Nutzer in der App festlegt –
damit sein Arbeitsbildschirm frei bleibt. Die App merkt sich die Wahl je Kombination angeschlossener Bildschirme (Büro,
Homeoffice …) und wendet sie wieder an, sobald dieselbe Kombination angeschlossen ist. Wird ein Bildschirm im Betrieb
getrennt (z.B. HDMI gezogen) oder angesteckt, läuft die Steuerung ohne Fehler weiter.

## Entscheidungen

- Nur der Nutzer legt den Bildschirm fest (in der App); die KI kann ihn nicht ändern, `window_launch` bekommt keinen
  Parameter dafür.
- Je Kombination genau eine Einstellung: „KI-Programme öffnen auf: [Bildschirm]“ oder „Windows entscheidet“. Kein
  Ausweich-Bildschirm.
- Nichts konfiguriert für die aktuelle Kombination → Windows entscheidet, die App verschiebt nichts.
- Eine Kombination ist die Menge der angeschlossenen Bildschirme, erkannt an ihrer Hardware-Kennung (Hersteller,
  Modell, Seriennummer aus den EDID-Daten). Fehlt die Seriennummer, gehört der Anschluss (Gerätepfad des Monitors) zur
  Kennung – so lassen sich baugleiche Bildschirme unterscheiden.
- Ablage lokal auf dem Rechner (`~/.devtools-mcp/screens.json`), nicht im Profil des Team-Servers: Bildschirme gehören
  zu einem Gerät. Keine Änderung an Plugin-API oder Backend-Protokoll.
- Nur Windows. Unter macOS und X11 fehlt der Knopf (Begründung im Tooltip), `window_launch` verhält sich wie bisher.
- Den Nutzer nie stören (siehe Fenstersteuerung, „Den Nutzer nie stören“): Verschieben nur per
  `SetWindowPos(… SWP_NOACTIVATE | SWP_NOZORDER)`, nie aktivieren, nie Vordergrund nehmen.

## Aufbau

| Einheit | Projekt | Aufgabe |
|---|---|---|
| `DisplaySystem` (+ `Win32DisplaySystem`) | `natives` | Angeschlossene Bildschirme: Kennung, Anzeigename (z.B. „DELL U2720Q“), Grenzen, Arbeitsbereich, Skalierung, Hauptbildschirm. Fenster auf einen Bildschirm setzen ohne Aktivierung. |
| `ScreenProfiles` | `desktop` | Kennung der aktuellen Kombination; je Kombination Name und KI-Bildschirm laden/speichern (`screens.json`). |
| `ScreenWatcher` | `desktop` | Prüft alle 2 s die Bildschirme; meldet nur bei Änderung (`ScreensChanged`). |
| `ScreenPlacement` | `desktop` | Regeln: Zielbildschirm für die aktuelle Kombination, Lage eines Fensters auf dem Ziel berechnen. Rein, ohne native Aufrufe. |
| `ScreensDialog` | `desktop` (UI) | Knopf „Bildschirme…“ im Modul Fenstersteuerung. |

### `DisplaySystem` (Windows)

- `EnumDisplayMonitors` + `GetMonitorInfoW` (Grenzen, Arbeitsbereich, Hauptbildschirm, Gerätename `\\.\DISPLAYn`),
  `GetDpiForMonitor` (Skalierung), `EnumDisplayDevicesW` mit `EDD_GET_DEVICE_INTERFACE_NAME` (Gerätepfad des Monitors),
  EDID aus der Registry (`HKLM\SYSTEM\CurrentControlSet\Enum\DISPLAY\…\Device Parameters\EDID`): Hersteller-ID,
  Produktcode, Seriennummer, Modellname.
- Kennung eines Bildschirms: `Hersteller-Produktcode-Seriennummer`; ohne Seriennummer zusätzlich der Gerätepfad. Ist
  EDID nicht lesbar: Gerätepfad + Auflösung.
- Kennung einer Kombination: sortierte Kennungen aller Bildschirme, verbunden – unabhängig von Anordnung und
  Hauptbildschirm.
- `moveToScreen(window, screen)`: Größe behalten, relative Lage im Arbeitsbereich übernehmen, auf den Arbeitsbereich
  des Ziels begrenzen (zu große Fenster werden verkleinert). Maximierte Fenster sind danach auf dem Zielbildschirm
  maximiert, minimierte bleiben minimiert (nur ihre wiederhergestellte Lage wechselt den Bildschirm) – beides ohne
  Aktivierung; der Weg dahin (`SetWindowPlacement` u.a.) wird per Test darauf geprüft, dass der Vordergrund unverändert
  bleibt. Geht ein Zustand nur mit Aktivierung, bleibt das Fenster lieber, wo es ist.

## Verhalten

### Programmstart (`window_launch`)

Nach dem Start und Binden: Hat die aktuelle Kombination einen KI-Bildschirm und liegt das Fenster nicht schon dort,
wird jedes Fenster des gestarteten Prozesses dorthin gesetzt. Die Antwort nennt den Bildschirm („auf Bildschirm 2 –
DELL U2720Q geöffnet“). Ohne Konfiguration: keine Änderung, Antwort wie bisher. Der Fokus-Wächter läuft wie bisher.

### Wechsel der Kombination

`ScreenWatcher` meldet eine neue Kombination:

- Ein Bildschirm fällt weg: Windows verschiebt dessen Fenster selbst; die App lässt sie dort. Bindungen bleiben,
  Rahmen und KI-Zeiger folgen dem Fenster (sie lesen dessen Grenzen ohnehin neu), Bildkoordinaten werden beim nächsten
  Screenshot neu berechnet.
- Hat die neue Kombination einen KI-Bildschirm: Die Fenster aller laufenden KI-Bindungen (alle Sitzungen) werden
  dorthin gesetzt – auch, wenn der Bildschirm gerade wieder angesteckt wurde („Rückkehr“).
- Ohne Konfiguration: nichts verschieben.
- Jede Sitzung mit Bindung erfährt beim nächsten `window_*`-Aufruf einmal: „Die Bildschirme haben sich geändert –
  Fenster liegt jetzt auf …; vor der nächsten Eingabe `window_screenshot` aufrufen.“ Eingaben mit Koordinaten (Klick,
  Scrollen, Ziehen) werden bis zum nächsten Screenshot dieses Fensters abgelehnt, damit keine alten Koordinaten
  treffen.

### Dialog „Bildschirme…“

- Knopf im Modul Fenstersteuerung (Desktop-UI, an das Modul gebunden – keine neue Feldart).
- Oben die aktuelle Kombination als Karte im richtigen Seitenverhältnis, je Bildschirm Nummer, Name, Auflösung,
  Skalierung; der Hauptbildschirm markiert. „Nummern zeigen“ blendet 3 s lang eine große Nummer auf jedem echten
  Bildschirm ein (durchklickbar, nie aktiviert, nicht in Screenshots).
- Auswahl „KI-Programme öffnen auf: [Bildschirm …] / Windows entscheidet“; Name der Kombination editierbar
  (Vorschlag: Liste der Modellnamen).
- Darunter alle gemerkten Kombinationen (Name, Bildschirme, gewählter Bildschirm, zuletzt gesehen); die aktive markiert;
  löschen möglich. Nicht angeschlossene Kombinationen sind nur lesbar.
- Speichern schreibt sofort `screens.json`; der Watcher wendet eine geänderte Wahl auf laufende Bindungen an.

### Ablage `screens.json`

```json
{ "version": 1,
  "combinations": [
    { "id": "DEL-41A3-ABC123|LEN-40BA-0|SAM-7123-H4ZT", "name": "Büro",
      "screens": [ { "id": "DEL-41A3-ABC123", "name": "DELL U2720Q" } ],
      "aiScreen": "DEL-41A3-ABC123", "lastSeen": "2026-10-13T09:12:00Z" } ] }
```

Schreiben über eine temporäre Datei und Umbenennen. Unlesbare Datei → als `screens.json.defekt-<Zeit>` beiseitelegen,
ohne Konfigurationen weiterarbeiten, Hinweis im Log und im Dialog.

## Fehlerfälle

- Fenster lässt sich nicht verschieben (Programm mit höheren Rechten, geschlossen): bleibt, wo es ist; die Antwort von
  `window_launch` bzw. der Hinweis nach einem Wechsel sagt es.
- Gewählter Bildschirm gehört nicht zur aktuellen Kombination (kann nicht vorkommen, da je Kombination gespeichert –
  bei manipulierter Datei): wie „Windows entscheidet“.
- Bildschirmabfrage scheitert: Watcher meldet keine Änderung, nächster Versuch in 2 s; `window_launch` platziert nicht.

## Tests

- **Vor jedem Testlauf mit echten Fenstern** (Arbeitsregel und Test-Infrastruktur): sicherstellen, dass weder Sperre
  noch Standby aktiv ist; sonst warten, bis der Bildschirm entsperrt ist. Tests mit echten Fenstern rufen dafür einen
  gemeinsamen Helfer `InteractiveDesktop.awaitUnlocked()` auf (wartet bis zu 10 min auf `WindowSystem.locked() ==
  false`, sonst überspringen mit Begründung). Auch nie den Fokus nehmen (Testfenster ohne Aktivierung).
- `ScreenProfiles`: Kennung unabhängig von Reihenfolge; Laden/Speichern; defekte Datei; Löschen.
- `ScreenPlacement`: Ziel je Kombination, keine Konfiguration → nichts; Lageberechnung (relative Lage, Begrenzen,
  Verkleinern, maximiert, minimiert); unterschiedliche Skalierung.
- `ScreenWatcher` mit simuliertem `DisplaySystem`: Abziehen → Hinweis an Sitzungen, kein Verschieben ohne
  Konfiguration; Anstecken einer konfigurierten Kombination → Fenster aller Bindungen auf den KI-Bildschirm; Eingaben mit
  Koordinaten bis zum nächsten Screenshot abgelehnt.
- `window_launch` mit simuliertem System: platziert auf den KI-Bildschirm, ohne Konfiguration nicht.
- `Win32DisplaySystem` gegen echte Bildschirme: Liste nicht leer, Kennungen stabil zwischen zwei Abfragen, genau ein
  Hauptbildschirm; Verschieben eines eigenen Testfensters auf einen anderen Bildschirm, ohne dass es aktiviert wird
  (Vordergrund unverändert, nur wenn mindestens zwei Bildschirme angeschlossen sind).
- Echtes Abziehen/Anstecken prüft der Nutzer einmal live.
