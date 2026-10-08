package systems.grebe.devtools.mcp.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Zusammenführen von Verzeichnislisten ({@code pfad} / {@code name=pfad}, eine Zeile je Eintrag). */
public final class DirectoryLists {

    private DirectoryLists() {
    }

    /**
     * Fügt {@code additions} zur Liste {@code existing} hinzu. Ein Eintrag, dessen Verzeichnis schon in der Liste
     * steht, wird bei {@code additionsWin == false} nicht noch einmal angehängt; bei {@code true} ersetzt er die
     * vorhandenen Zeilen desselben Verzeichnisses (der Name des Projekts gewinnt). Ein ungültiger Pfad zählt als
     * "steht nicht darin".
     */
    public static String merge(String existing, List<String> additions, boolean additionsWin) {
        List<String> lines = new ArrayList<>(ModuleConfig.splitLines(existing == null ? "" : existing));
        for (String addition : additions) {
            Optional<Path> dir = DirectoryEntry.parse(addition).directory();
            if (additionsWin) {
                dir.ifPresent(p -> lines.removeIf(l -> DirectoryEntry.parse(l).sameDirectory(p)));
                lines.add(addition);
            } else if (dir.isEmpty() || lines.stream().noneMatch(l -> DirectoryEntry.parse(l).sameDirectory(dir.get()))) {
                lines.add(addition);
            }
        }
        return String.join("\n", lines);
    }
}
