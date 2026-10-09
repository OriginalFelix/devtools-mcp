# Fensterauswahl-Dialog und globale Freigaben für die Fenstersteuerung

Stand: 2026-10-05 · Modul `window` (Desktop-App)

## Ziel

Statt Prozessnamen von Hand als regulären Ausdruck einzutragen, wählt der Nutzer ein Fenster grafisch aus – ähnlich
wie beim Teilen eines Fensters in Discord oder Microsoft Teams. Außerdem gelten die globalen Freigaben (Modul
„Freigaben“) auch für Fenster: Programme aus freigegebenen Ordnern dürfen gesteuert werden.

## Bedienung

- Neben „Nur diese Prozesse“ und „Prozesse ausschließen“ steht je ein Button **„Fenster wählen…“**.
- Er öffnet einen modalen Dialog mit der **Übersicht aller Fenster** als Kacheln: Vorschaubild, darunter Fenstertitel
  und Prozessname.
- **Platzhalter:** Ist keine Vorschau möglich (minimiert, Erfassung fehlgeschlagen, macOS/Linux ohne
  Hintergrund-Screenshot), zeigt die Kachel Prozessname und PID.
- Der Dialog öffnet sofort mit Platzhaltern; die Vorschaubilder werden im Hintergrund nachgeladen und ersetzen sie.
- **Klick auf eine Kachel** zeigt das Fenster als große Vorschau mit Titel, Prozessname, PID und Programmpfad; Buttons
  „Zurück“ (zur Übersicht) und **„Hinzufügen“**. Abbrechen/Schließen ändert nichts.
- **Hinzufügen** hängt den Prozessnamen als Alternative an den vorhandenen Ausdruck an und schließt den Dialog:
  - leer → `winword`; `charmap` → `charmap|winword`
  - Sonderzeichen werden escaped: `notepad++` → `notepad\+\+`
  - passt der Ausdruck schon auf den Prozess, bleibt das Feld unverändert
  - eigene Ausdrücke im Feld bleiben erhalten
  - die Änderung ist wie jede Formularänderung erst nach „Speichern“ wirksam
- Nicht in der Übersicht: diese App selbst und immer ausgeschlossene Prozesse (Anmeldedialoge, Passwortmanager) sowie
  Prozesse, deren Programmpfad nicht lesbar ist – sie sind ohnehin nicht steuerbar.

## Globale Freigaben

- Das Modul `window` übernimmt die Verzeichnisse aus „Freigaben“ über `ToolModule.sharedDirectoryFields()` in einem
  internen Feld (nicht im Formular).
- Jedes Fenster gehört zu einem Prozess, der über eine ausführbare Datei gestartet wurde. Ihr Pfad kommt aus
  `ProcessHandle.info().command()`. Liegt er in einem freigegebenen Ordner oder einem beliebigen Unterordner davon
  (Pfad-Präfix nach Normalisierung, unter Windows ohne Groß-/Kleinschreibung), ist der Prozess – und damit jedes seiner
  Fenster – freigegeben: auch wenn er nicht zu „Nur diese Prozesse“ passt **oder unter „Prozesse ausschließen“ fällt**.
- Hart gesperrt bleiben diese App (samt Kindprozessen) und die immer ausgeschlossenen Prozesse (Anmelde-/UAC-Dialoge,
  Passwortmanager) – Sicherheitsgrenzen, keine Einstellung.
- Einträge der Form `name=pfad` werden wie bei den anderen Modulen auf den Pfad reduziert.
- Der Schalter „Beschränkung aufheben“ betrifft weiterhin nur Verzeichnisse, nicht die Fenstersteuerung.

## Warnung beim Speichern

Weil die Freigaben „Prozesse ausschließen“ überschreiben, warnt die App, bevor ein Konflikt gespeichert wird.

- **Wann:** beim Speichern der Fenstersteuerung und beim Speichern von „Freigaben“.
- **Suche:** die freigegebenen Ordner rekursiv nach ausführbaren Dateien (`.exe`, `.app`-Bundles; unter Linux/macOS
  reguläre Dateien mit Ausführungsrecht). Übersprungen werden `.git`, `node_modules`, `build`, `target`, `.gradle`,
  `.idea`, `out`, `dist`. Obergrenze 50 000 Einträge; wird sie erreicht, sagt der Dialog, dass die Suche unvollständig ist.
- **Prüfung:** jede gefundene Datei wie ein Prozess (Name ohne Endung, Pfad als Kommandozeile) gegen „Prozesse
  ausschließen“. Zusätzlich gefundene, immer ausgeschlossene Programme werden als „bleiben gesperrt“ genannt.
- **Dialog:** Liste der Treffer (höchstens 20, dann „… und N weitere“), Buttons **„Trotzdem speichern“** und
  **„Abbrechen“**. Nur der Hinweis „bleiben gesperrt“ ohne Konflikt: kein Dialog.
- **Ablauf:** Suche in einem Hintergrund-`Task`; der Speichern-Button ist währenddessen gesperrt, der Status zeigt
  „Prüfe Freigaben …“.
- **Technik:** `ToolModule.saveWarnings(ModuleConfig config)` (Standard: keine). Die `ToolRegistry` liefert
  `saveWarnings(moduleId, values)`: für das gespeicherte Modul mit den (neuen) Werten plus freigegebenen Ordnern, beim
  Speichern von „Freigaben“ für alle Module mit `sharedDirectoryFields()` mit ihren gespeicherten Werten plus den neuen
  Ordnern. `ModuleDetailPane.save()` fragt sie vor `updateConfig` ab.

## Aufbau

| Teil | Ort | Aufgabe |
|---|---|---|
| `FieldType.PROCESS_PATTERN` | `shared` | Regulärer Ausdruck auf Prozesse. Desktop: Textfeld + „Fenster wählen…“; Team-Server: normales Textfeld (er sieht die Fenster des Entwickler-Rechners nicht). |
| `ConfigForm` | `desktop/ui` | Rendert `PROCESS_PATTERN`, öffnet den Dialog, schreibt das Ergebnis über `ProcessPatterns.append` ins Feld. |
| `WindowPickerDialog` | `desktop/ui` | JavaFX-Dialog: Übersicht (Kacheln in `FlowPane`/`ScrollPane`), Vorschau-Ansicht, „Hinzufügen“. Bilder asynchron. |
| `WindowCandidates` | `modules/window` | Liste der wählbaren Fenster (Fenster-ID, PID, Prozessname, Titel, Pfad) ohne Ausgeschlossene; `preview(candidate)` über `WindowSystem.captureInBackground`. Ohne JavaFX testbar. |
| `ProcessPatterns.append` | `modules/window` | Hängt einen Namen escaped als Alternative an; unverändert, wenn er schon passt. |
| `ProcessFilter` | `modules/window` | Zusätzlich die freigegebenen Ordner; Prozess mit Pfad darunter erfüllt die Positivliste. |
| `WindowModule` | `modules/window` | Felder als `PROCESS_PATTERN`; `sharedDirectoryFields()`; Ordner an den `ProcessFilter`; `saveWarnings`. |
| `SharedProgramScan` | `modules/window` | Rekursive Suche nach ausführbaren Dateien mit Ausnahmen und Obergrenze. |
| `ToolModule.saveWarnings`, `ToolRegistry.saveWarnings` | `desktop/core` | Warnungen vor dem Speichern, inkl. „Freigaben“. |
| `ModuleDetailPane` | `desktop/ui` | Warnungen im Hintergrund holen, Dialog „Trotzdem speichern“/„Abbrechen“. |

## Fehlerbehandlung

- Fenstersystem nicht verfügbar (`unsupportedReason`): Button deaktiviert, Tooltip mit dem Grund.
- Erfassung eines Fensters wirft oder liefert nichts: Platzhalter bleibt, kein Fehlerdialog.
- Fenster ist beim Hinzufügen schon geschlossen: Name ist bekannt, wird trotzdem übernommen.
- Ungültiger Ausdruck im Feld: `append` hängt trotzdem an; die bestehende Prüfung beim Speichern/Test meldet es.

## Tests

- `ProcessPatternsTest`: leer, Dublette (passt schon), Sonderzeichen, vorhandener eigener Ausdruck.
- `ProcessFilterTest`: Pfad im freigegebenen Ordner bzw. Unterordner wird zugelassen, auch wenn „Prozesse
  ausschließen“ passt; Passwortmanager und diese App bleiben gesperrt; Pfad außerhalb → wie bisher.
- `SharedProgramScanTest` (Temp-Ordner): findet in Unterordnern, überspringt `node_modules`/`.git`, Obergrenze.
- `WindowModule.saveWarnings`: Konflikt → Warnung; nur gesperrte Programme → keine Konflikt-Warnung.
- `ToolRegistry.saveWarnings`: beim Speichern von „Freigaben“ werden die Warnungen der Fenstersteuerung geliefert.
- `WindowCandidatesTest` mit `FakeDesktop`: eigene App und immer Ausgeschlossene fehlen; ohne Bild → leere Vorschau.
- Dialog: manuell (Übersicht, Platzhalter, Vorschau, Hinzufügen in beiden Feldern).
