package systems.grebe.devtools.mcp.modules.ide.spi;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/**
 * Anbindung an eine installierte IDE. Alle Pfade sind absolut und normalisiert; ob sie freigegeben sind, hat das Modul
 * vorher geprüft. Die Aktionen wirken auf dem Bildschirm des Nutzers und kehren zurück, sobald die IDE den Auftrag
 * angenommen hat – sie warten nicht, bis ein Projekt fertig geladen ist.
 *
 * <p>Nur {@link #probe()}, {@link #openProject} und {@link #show} sind Pflicht. Was darüber hinaus geht, meldet
 * {@link #capabilities()}; die {@code default}-Methoden lehnen ab.
 */
public interface Ide {

    /** Was eine IDE von außen kann. */
    enum Capability {
        /** Projekt bzw. Ordner öffnen ({@link #openProject}). */
        OPEN_PROJECT("Projekt öffnen"),
        /** Geöffnetes Projekt schließen ({@link #closeProject}). */
        CLOSE_PROJECT("Projekt schließen"),
        /** Datei an einer Zeile/Spalte zeigen ({@link #show}). */
        SHOW("Code-Stelle zeigen"),
        /** Zwei Dateien nebeneinander vergleichen ({@link #diff}). */
        DIFF("Dateien vergleichen");

        private final String label;

        Capability(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * Ergebnis von {@link #probe()}.
     *
     * @param available  ob die IDE gefunden wurde und aufrufbar ist
     * @param executable gefundenes Programm bzw. App (zur Anzeige), sonst {@code null}
     * @param message    Hinweis für den Nutzer, z.B. warum sie nicht gefunden wurde
     */
    record Availability(boolean available, String executable, String message) {

        public static Availability found(String executable) {
            return new Availability(true, executable, "gefunden");
        }

        public static Availability missing(String message) {
            return new Availability(false, null, message);
        }
    }

    /**
     * Eine Stelle in einer Datei.
     *
     * @param file    Datei (absolut)
     * @param line    Zeile ab 1, {@code 0} = keine
     * @param column  Spalte ab 1, {@code 0} = keine
     * @param project Projektverzeichnis, zu dem die Datei gehört (absolut) – IDEs mit Fenster je Projekt zeigen die
     *                Datei dort; {@code null}, wenn unbekannt
     */
    record Location(Path file, int line, int column, Path project) {

        public Location {
            Objects.requireNonNull(file, "file");
            line = Math.max(0, line);
            column = line == 0 ? 0 : Math.max(0, column);
        }

        /** {@code datei:zeile:spalte} bzw. kürzer, wie es viele IDEs auf der Befehlszeile verstehen. */
        public String spec() {
            return file + (line > 0 ? ":" + line + (column > 0 ? ":" + column : "") : "");
        }
    }

    /** Was diese IDE kann; mindestens {@link Capability#OPEN_PROJECT} und {@link Capability#SHOW}. */
    default Set<Capability> capabilities() {
        return Set.of(Capability.OPEN_PROJECT, Capability.SHOW);
    }

    /** Sucht die Installation. Darf das Dateisystem ansehen und kurz laufende Programme starten. */
    Availability probe();

    /**
     * Öffnet ein Projekt bzw. einen Ordner und holt die IDE nach vorn; ist es schon offen, wird sein Fenster aktiv.
     *
     * @return kurze Meldung, was geöffnet wurde (z.B. die gewählte Projektmappe)
     */
    String openProject(Path dir);

    /**
     * Schließt ein geöffnetes Projekt. Ungespeicherte Änderungen dürfen dabei nicht verloren gehen: lieber ablehnen
     * oder den Nutzer fragen lassen.
     *
     * @return kurze Meldung
     * @throws UnsupportedOperationException wenn die IDE das von außen nicht kann
     */
    default String closeProject(Path dir) {
        throw new UnsupportedOperationException("Diese IDE kann Projekte nicht von außen schließen.");
    }

    /** Öffnet die Datei, setzt den Cursor auf die Stelle und holt die IDE nach vorn. */
    String show(Location location);

    /**
     * Zeigt zwei Dateien im Vergleich.
     *
     * @throws UnsupportedOperationException wenn die IDE keinen Vergleich von außen anbietet
     */
    default String diff(Path left, Path right) {
        throw new UnsupportedOperationException("Diese IDE bietet keinen Dateivergleich von außen an.");
    }
}
