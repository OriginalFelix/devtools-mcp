package systems.grebe.devtools.mcp.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entfernt aus Tool-Ergebnissen, was für das LLM nur Tokens kostet: Terminal-Steuerzeichen, überschriebene
 * Fortschrittsanzeigen, Framework-Frames in Stacktraces, Wiederholungen und Leerzeilen-Serien.
 *
 * <p>Zwei Stufen: {@link #clean} ist verlustfrei im Sinne des Inhalts (nur Steuerzeichen und überschriebene Zeilen) und
 * gilt für jedes Ergebnis; {@link #compact} fasst zusätzlich zusammen und ist nur für Ausgaben gedacht, die nicht
 * wortgetreu gebraucht werden (Logs, Builds, Befehlsausgaben – nicht Dateiinhalte oder Diffs).
 */
public final class OutputCleaner {

    /** CSI ({@code ESC [ … Buchstabe}), OSC ({@code ESC ] … BEL/ST}) und einzelne ESC-Sequenzen. */
    private static final Pattern ANSI = Pattern.compile(
            "\u001B\\[[0-?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|\u001B[@-Z\\\\-_]");
    /** Übrige Steuerzeichen außer Tab und Zeilenumbruch. */
    private static final Pattern CONTROL = Pattern.compile("[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]");
    private static final Pattern FRAME = Pattern.compile("^\\s+at\\s+(?:[\\w.$/@-]+/)?([\\w$.<>]+)\\(.*\\)\\s*$");

    /**
     * Präfixe von Frames, die bei der Fehlersuche fast nie helfen: Reflection, Test- und Build-Infrastruktur,
     * Proxies, Server-Container. Der erste Frame eines Blocks bleibt immer stehen.
     */
    static final List<String> NOISE = List.of(
            "java.lang.reflect.", "jdk.internal.", "sun.reflect.", "jdk.proxy", "com.sun.proxy.",
            "org.junit.platform.", "org.junit.jupiter.engine.", "org.junit.runners.", "org.junit.internal.",
            "org.gradle.", "worker.org.gradle.", "org.apache.maven.surefire.", "org.apache.maven.plugin.surefire.",
            "org.springframework.aop.", "org.springframework.cglib.", "org.springframework.cglib",
            "org.springframework.web.servlet.FrameworkServlet", "org.springframework.web.filter.",
            "org.apache.catalina.", "org.apache.tomcat.", "org.apache.coyote.", "org.eclipse.jetty.", "io.undertow.",
            "io.netty.", "reactor.core.", "org.codehaus.groovy.runtime.callsite.", "org.codehaus.groovy.vmplugin.",
            "org.codehaus.groovy.reflection.", "groovy.lang.MetaMethod", "groovy.lang.MetaClassImpl",
            "java.util.concurrent.ThreadPoolExecutor", "java.util.concurrent.FutureTask", "java.lang.Thread.run",
            "java.util.stream.", "kotlinx.coroutines.");

    /** Ab so vielen gleichen Zeilen in Folge wird zusammengefasst. */
    static final int REPEAT_MIN = 3;

    private OutputCleaner() {
    }

    /** Terminal-Steuerzeichen raus, überschriebene Fortschrittszeilen ({@code \r}) auf ihren Endstand. */
    public static String clean(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = text.indexOf('\u001B') >= 0 ? ANSI.matcher(text).replaceAll("") : text;
        if (s.indexOf('\r') >= 0) {
            s = s.replace("\r\n", "\n");
            if (s.indexOf('\r') >= 0) {
                s = lastSegments(s);
            }
        }
        return CONTROL.matcher(s).find() ? CONTROL.matcher(s).replaceAll("") : s;
    }

    /** Je Zeile nur, was nach dem letzten Wagenrücklauf steht (so zeigt es auch das Terminal). */
    private static String lastSegments(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            int cr = line.lastIndexOf('\r');
            while (cr >= 0 && cr == line.length() - 1) { // abschließendes \r ohne Folgetext
                line = line.substring(0, cr);
                cr = line.lastIndexOf('\r');
            }
            sb.append(cr < 0 ? line : line.substring(cr + 1)).append('\n');
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    /**
     * {@link #clean} plus Zusammenfassen: Framework-Frames in Stacktraces, gleiche Zeilen in Folge, Leerzeilen-Serien.
     */
    public static String compact(String text) {
        String s = clean(text);
        if (s == null || s.isEmpty()) {
            return s;
        }
        List<String> lines = new ArrayList<>(List.of(s.split("\n", -1)));
        lines = compressFrames(lines);
        lines = collapseRepeats(lines);
        lines = collapseBlankRuns(lines);
        return String.join("\n", lines);
    }

    /** Lässt in jedem Stacktrace-Block die Framework-Frames weg (erster Frame bleibt) und nennt, wie viele. */
    static List<String> compressFrames(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        int skipped = 0;
        Set<String> groups = new LinkedHashSet<>();
        boolean firstInBlock = true;
        String indent = "\t";
        for (String line : lines) {
            Matcher m = FRAME.matcher(line);
            if (!m.matches()) {
                flushSkipped(out, skipped, groups, indent);
                skipped = 0;
                groups.clear();
                firstInBlock = true;
                out.add(line);
                continue;
            }
            String group = noise(m.group(1));
            if (group != null && !firstInBlock) {
                skipped++;
                groups.add(group);
            } else {
                flushSkipped(out, skipped, groups, indent);
                skipped = 0;
                groups.clear();
                indent = line.substring(0, line.length() - line.stripLeading().length());
                out.add(line);
            }
            firstInBlock = false;
        }
        flushSkipped(out, skipped, groups, indent);
        return out;
    }

    private static void flushSkipped(List<String> out, int skipped, Set<String> groups, String indent) {
        if (skipped == 1) {
            out.add(indent + "at … (1 Frame " + String.join(", ", groups) + ")");
        } else if (skipped > 1) {
            out.add(indent + "at … (" + skipped + " Frames " + String.join(", ", groups) + ")");
        }
    }

    /** Kurzname der Rauschquelle eines Frames oder {@code null}, wenn der Frame bleibt. */
    static String noise(String frame) {
        for (String p : NOISE) {
            if (frame.startsWith(p)) {
                String g = p.replace("worker.", "");
                int dot = g.indexOf('.', g.indexOf('.') + 1);
                return dot > 0 ? g.substring(0, dot) : g.replaceAll("[./]+$", "");
            }
        }
        return null;
    }

    /** Gleiche Zeilen in Folge: eine bleibt stehen, dahinter die Anzahl. */
    static List<String> collapseRepeats(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            int j = i + 1;
            while (j < lines.size() && lines.get(j).equals(line)) {
                j++;
            }
            int n = j - i;
            if (n >= REPEAT_MIN && !line.isBlank()) {
                out.add(line);
                out.add("… (vorige Zeile " + n + "× in Folge)");
            } else {
                for (int k = i; k < j; k++) {
                    out.add(line);
                }
            }
            i = j;
        }
        return out;
    }

    /** Höchstens eine Leerzeile in Folge, keine am Anfang und Ende. */
    static List<String> collapseBlankRuns(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        boolean blank = true; // führende Leerzeilen weglassen
        for (String line : lines) {
            boolean b = line.isBlank();
            if (b && blank) {
                continue;
            }
            out.add(b ? "" : line);
            blank = b;
        }
        while (!out.isEmpty() && out.getLast().isEmpty()) {
            out.removeLast();
        }
        return out;
    }
}
