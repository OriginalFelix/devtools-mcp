package systems.grebe.devtools.mcp.modules.jdbc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ordnet eine SQL-Anweisung ein, damit jedes Tool nur ausführt, wofür es freigegeben ist. Kein vollständiger Parser,
 * sondern ein Tokenizer, der Zeichenketten, Kommentare und quotierte Bezeichner überspringt und im Zweifel vorsichtig
 * entscheidet: Was er nicht sicher als lesend erkennt, gilt nicht als lesend.
 *
 * <p>Datenbanken lesen denselben Text unterschiedlich – {@code \'} ist in MySQL ein maskiertes Hochkomma, in SQL Server
 * und Oracle das Ende der Zeichenkette; {@code #} beginnt in MySQL einen Kommentar, {@code //} in H2. Deshalb wird jede
 * Anweisung in mehreren Lesarten ({@link Dialect}) untersucht; weichen sie ab, zählt die Vereinigung, also das Ergebnis
 * mit den meisten nötigen Rechten. So lässt sich keine zweite Anweisung in einer Zeichenkette verstecken, die nur
 * eine Lesart für eine hält.
 */
final class SqlStatements {

    /** Art einer Anweisung – jede Art hat ihren Schalter im Modul und ihr Tool. */
    enum Kind {
        QUERY(JdbcModule.ALLOW_QUERY, "jdbc_query", "Datensätze lesen", "lesen"),
        INSERT(JdbcModule.ALLOW_INSERT, "jdbc_insert", "Datensätze einfügen", "einfügen"),
        UPDATE(JdbcModule.ALLOW_UPDATE, "jdbc_update", "Datensätze ändern", "ändern"),
        DELETE(JdbcModule.ALLOW_DELETE, "jdbc_delete", "Datensätze löschen", "löschen"),
        DDL(JdbcModule.ALLOW_DDL, "jdbc_ddl", "Struktur ändern", "Struktur ändern"),
        OTHER(JdbcModule.ALLOW_EXECUTE, "jdbc_execute", "Beliebiges SQL ausführen", "beliebiges SQL");

        /** Schlüssel des Schalters im Modul. */
        final String setting;
        final String tool;
        final String label;
        final String shortLabel;

        Kind(String setting, String tool, String label, String shortLabel) {
            this.setting = setting;
            this.tool = tool;
            this.label = label;
            this.shortLabel = shortLabel;
        }

        /** Datensätze ändernd (INSERT, UPDATE, DELETE). */
        boolean dml() {
            return this == INSERT || this == UPDATE || this == DELETE;
        }
    }

    /**
     * Ergebnis der Einordnung.
     *
     * @param primary    Art nach dem ersten Schlüsselwort (bei {@code WITH} nach der Hauptanweisung)
     * @param kinds      alle Arten, die die Anweisung braucht – zusätzlich zu {@code primary} z.B. ein UPDATE in einem
     *                   Upsert ({@code ON CONFLICT DO UPDATE}) oder ein DELETE in einem datenverändernden CTE
     * @param keyword    erstes Schlüsselwort in Großbuchstaben (leer ohne Anweisung)
     * @param statements Anzahl der durch {@code ;} getrennten Anweisungen
     * @param where      ob ein {@code WHERE} vorkommt
     * @param sql        die Anweisung ohne abschließende Semikolons, Kommentare und Leerraum
     */
    record Analysis(Kind primary, Set<Kind> kinds, String keyword, int statements, boolean where, String sql) {

        /** Nur lesend: weder Haupt- noch eingebettete Anweisung ändert etwas. */
        boolean readOnly() {
            return kinds.equals(EnumSet.of(Kind.QUERY));
        }
    }

    /** Lesarten von Zeichenketten, Kommentaren und quotierten Bezeichnern. */
    private enum Dialect {
        /** SQL Server, Oracle, DB2, H2: {@code ''}, {@code [bezeichner]}, {@code q'[…]'}, {@code $$…$$}, {@code //}. */
        STANDARD,
        /** MySQL, MariaDB: Backslash maskiert, {@code #}-Kommentare, {@code /*! … *}{@code /} wird ausgeführt. */
        MYSQL,
        /** PostgreSQL: {@code E'…'} mit Backslash, {@code $tag$…$tag$}. */
        POSTGRES
    }

    private static final Map<String, Kind> FIRST = Map.ofEntries(
            Map.entry("SELECT", Kind.QUERY), Map.entry("WITH", Kind.QUERY), Map.entry("VALUES", Kind.QUERY),
            Map.entry("TABLE", Kind.QUERY), Map.entry("SHOW", Kind.QUERY), Map.entry("DESCRIBE", Kind.QUERY),
            Map.entry("DESC", Kind.QUERY), Map.entry("EXPLAIN", Kind.QUERY),
            Map.entry("INSERT", Kind.INSERT),
            Map.entry("UPDATE", Kind.UPDATE), Map.entry("MERGE", Kind.UPDATE), Map.entry("UPSERT", Kind.UPDATE),
            Map.entry("REPLACE", Kind.UPDATE),
            Map.entry("DELETE", Kind.DELETE),
            Map.entry("CREATE", Kind.DDL), Map.entry("ALTER", Kind.DDL), Map.entry("DROP", Kind.DDL),
            Map.entry("TRUNCATE", Kind.DDL), Map.entry("RENAME", Kind.DDL), Map.entry("COMMENT", Kind.DDL));

    /** Schlüsselwörter, mit denen nach {@code WITH …} die Hauptanweisung beginnt. */
    private static final Set<String> MAIN_AFTER_WITH = Set.of("SELECT", "VALUES", "TABLE", "INSERT", "UPDATE",
            "DELETE", "MERGE");

    /** Vor {@code INTO} erlaubt – sonst ist es ein {@code SELECT … INTO} (legt eine Tabelle oder Datei an). */
    private static final Set<String> BEFORE_INTO = Set.of("INSERT", "MERGE", "REPLACE", "UPSERT", "IGNORE",
            "LOW_PRIORITY", "HIGH_PRIORITY", "DELAYED");

    private SqlStatements() {
    }

    static Analysis analyze(String sql) {
        String text = sql == null ? "" : sql;
        List<Analysis> all = Arrays.stream(Dialect.values()).map(d -> analyze(text, d)).toList();
        Analysis first = all.getFirst();
        if (all.stream().allMatch(first::equals)) {
            return first;
        }
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        Kind primary = first.primary();
        int statements = 0;
        boolean where = false;
        for (Analysis a : all) {
            kinds.addAll(a.kinds());
            if (a.primary() != primary) {
                primary = Kind.OTHER;
            }
            statements = Math.max(statements, a.statements());
            // WHERE dient nur dem Schutz vor versehentlichen Änderungen aller Zeilen – eine Lesart genügt
            where |= a.where();
        }
        kinds.add(primary);
        // Kommentare am Ende nur abschneiden, wenn alle Lesarten dort keinen Code sehen
        String trimmed = all.stream().map(Analysis::sql).distinct().count() == 1 ? first.sql() : stripSemicolons(text);
        return new Analysis(primary, kinds, first.keyword(), statements, where, trimmed);
    }

    /** Entfernt abschließende Semikolons und Leerraum (Oracle lehnt ein {@code ;} am Ende ab). */
    static String stripSemicolons(String sql) {
        String s = sql.strip();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).strip();
        }
        return s;
    }

    // ------------------------------------------------------------------ intern

    private record Word(String text, int depth) {
    }

    private static Analysis analyze(String sql, Dialect dialect) {
        List<List<Word>> statements = new ArrayList<>();
        List<Word> current = new ArrayList<>();
        int depth = 0;
        int end = 0; // Ende des letzten Zeichens, das zu einer Anweisung gehört
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (Character.isWhitespace(ch)) {
                i++;
                continue;
            }
            if (lineComment(sql, i, dialect)) {
                int eol = sql.indexOf('\n', i);
                i = eol < 0 ? n : eol + 1;
                continue;
            }
            if (ch == '/' && next == '*') {
                if (dialect == Dialect.MYSQL && executableComment(sql, i)) {
                    // /*! … */ und /*M! … */ führt MySQL bzw. MariaDB aus: nur den Anfang überspringen
                    i = sql.indexOf('!', i) + 1;
                    while (i < n && Character.isDigit(sql.charAt(i))) {
                        i++;
                    }
                    continue;
                }
                int close = sql.indexOf("*/", i + 2);
                i = close < 0 ? n : close + 2;
                continue;
            }
            if (ch == ';') {
                if (!current.isEmpty()) {
                    statements.add(current);
                    current = new ArrayList<>();
                }
                depth = 0;
                i++;
                continue;
            }
            int start = i;
            if (ch == '\'') {
                boolean backslash = dialect == Dialect.MYSQL || dialect == Dialect.POSTGRES && prefixed(sql, i, 'E');
                i = quoted(sql, i, backslash);
            } else if (ch == '"') {
                i = dialect == Dialect.MYSQL ? quoted(sql, i, true) : closing(sql, i, '"');
            } else if (ch == '`') {
                i = closing(sql, i, '`');
            } else if (ch == '[' && dialect == Dialect.STANDARD) {
                i = closing(sql, i, ']');
            } else if (ch == '$' && dialect != Dialect.MYSQL && dollarQuote(sql, i, dialect) > 0) {
                String tag = sql.substring(i, dollarQuote(sql, i, dialect));
                int close = sql.indexOf(tag, i + tag.length());
                i = close < 0 ? n : close + tag.length();
            } else if (identifierStart(ch)) {
                int j = i + 1;
                while (j < n && identifierPart(sql.charAt(j), dialect)) {
                    j++;
                }
                String word = sql.substring(i, j);
                if (dialect == Dialect.STANDARD && j + 1 < n && sql.charAt(j) == '\''
                        && (word.equalsIgnoreCase("q") || word.equalsIgnoreCase("nq"))) {
                    i = oracleQuote(sql, j); // q'[ … ]' – Hochkommas im Inhalt
                } else {
                    current.add(new Word(word.toUpperCase(Locale.ROOT), depth));
                    i = j;
                }
            } else {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth = Math.max(0, depth - 1);
                }
                i++;
            }
            if (current.isEmpty() && i > start) {
                // Zeichenketten oder Klammern vor dem ersten Wort, z.B. "(SELECT …) UNION …", gehören zur Anweisung
                current.add(new Word("", depth));
            }
            end = i;
        }
        if (!current.isEmpty()) {
            statements.add(current);
        }
        String trimmed = sql.substring(0, Math.min(end, n)).strip();
        if (statements.isEmpty()) {
            return new Analysis(Kind.OTHER, EnumSet.of(Kind.OTHER), "", 0, false, trimmed);
        }
        Set<Kind> kinds = EnumSet.noneOf(Kind.class);
        Kind primary = null;
        String keyword = "";
        boolean where = false;
        for (List<Word> words : statements) {
            String first = words.stream().map(Word::text).filter(t -> !t.isEmpty()).findFirst().orElse("");
            Kind kind = primary(words, first);
            if (primary == null) {
                primary = kind;
                keyword = first;
            }
            kinds.add(kind);
            if (kind == Kind.QUERY || kind.dml()) {
                kinds.addAll(embedded(words));
            }
            where |= words.stream().anyMatch(w -> w.text().equals("WHERE"));
        }
        return new Analysis(primary, kinds, keyword, statements.size(), where, trimmed);
    }

    private static Kind primary(List<Word> words, String first) {
        Kind kind = FIRST.getOrDefault(first, Kind.OTHER);
        if (!first.equals("WITH")) {
            return kind;
        }
        // WITH a AS (…), b AS (…) <Hauptanweisung>: das erste passende Schlüsselwort auf oberster Ebene
        for (Word w : words) {
            if (w.depth() == 0 && MAIN_AFTER_WITH.contains(w.text())) {
                return FIRST.get(w.text());
            }
        }
        return Kind.QUERY;
    }

    /** Ändernde Teile innerhalb einer lesenden oder DML-Anweisung. */
    private static Set<Kind> embedded(List<Word> words) {
        Set<Kind> out = EnumSet.noneOf(Kind.class);
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i).text();
            String prev = i > 0 ? words.get(i - 1).text() : "";
            String prev2 = i > 1 ? words.get(i - 2).text() : "";
            switch (w) {
                case "INSERT" -> out.add(Kind.INSERT);
                case "MERGE" -> out.add(Kind.UPDATE);
                // FOR UPDATE / FOR NO KEY UPDATE sperren nur; ON UPDATE / ON DELETE sind Fremdschlüssel-Aktionen
                case "UPDATE" -> {
                    if (!prev.equals("FOR") && !prev.equals("ON") && !(prev.equals("KEY") && prev2.equals("NO"))) {
                        out.add(Kind.UPDATE);
                    }
                }
                case "DELETE" -> {
                    if (!prev.equals("ON")) {
                        out.add(Kind.DELETE);
                    }
                }
                // SELECT … INTO legt eine Tabelle an (PostgreSQL, SQL Server) oder schreibt eine Datei (MySQL)
                case "INTO" -> {
                    if (!BEFORE_INTO.contains(prev)) {
                        out.add(Kind.OTHER);
                    }
                }
                case "CALL", "EXEC", "EXECUTE" -> out.add(Kind.OTHER);
                default -> {
                }
            }
        }
        return out;
    }

    /**
     * {@code --} beginnt überall einen Zeilenkommentar, in MySQL aber nur mit folgendem Leerzeichen ({@code 1--1} ist
     * dort eine Rechnung); {@code #} nur in MySQL, {@code //} nur in H2.
     */
    private static boolean lineComment(String sql, int i, Dialect dialect) {
        char ch = sql.charAt(i);
        char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';
        return switch (dialect) {
            case MYSQL -> ch == '#' || ch == '-' && next == '-'
                    && (i + 2 >= sql.length() || Character.isWhitespace(sql.charAt(i + 2))
                    || Character.isISOControl(sql.charAt(i + 2)));
            case STANDARD -> ch == '-' && next == '-' || ch == '/' && next == '/';
            case POSTGRES -> ch == '-' && next == '-';
        };
    }

    private static boolean executableComment(String sql, int i) {
        return sql.startsWith("/*!", i) || sql.regionMatches(true, i, "/*M!", 0, 4);
    }

    /** Ende einer Zeichenkette in Hochkommas; {@code ''} maskiert immer, {@code \} nur mit {@code backslash}. */
    private static int quoted(String sql, int i, boolean backslash) {
        char quote = sql.charAt(i);
        int j = i + 1;
        while (j < sql.length()) {
            char c = sql.charAt(j);
            if (backslash && c == '\\') {
                j += 2;
                continue;
            }
            if (c == quote) {
                if (j + 1 < sql.length() && sql.charAt(j + 1) == quote) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return sql.length();
    }

    /** Ende eines quotierten Bezeichners; das verdoppelte Schlusszeichen maskiert. */
    private static int closing(String sql, int i, char close) {
        int j = i + 1;
        while (j < sql.length()) {
            if (sql.charAt(j) == close) {
                if (j + 1 < sql.length() && sql.charAt(j + 1) == close) {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return sql.length();
    }

    /** Oracle {@code q'X…X'}: {@code j} zeigt auf das Hochkomma, danach folgt das Begrenzungszeichen. */
    private static int oracleQuote(String sql, int j) {
        char open = sql.charAt(j + 1);
        char close = switch (open) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> open;
        };
        int end = sql.indexOf(close + "'", j + 2);
        return end < 0 ? sql.length() : end + 2;
    }

    /** Ob direkt vor dem Hochkomma ein einzelnes Präfix wie {@code E'…'} steht. */
    private static boolean prefixed(String sql, int i, char prefix) {
        return i > 0 && Character.toUpperCase(sql.charAt(i - 1)) == prefix
                && (i < 2 || !identifierPart(sql.charAt(i - 2), Dialect.POSTGRES));
    }

    /**
     * Dollar-Quote: Ende des öffnenden Tags oder 0. PostgreSQL kennt {@code $tag$}, H2 nur {@code $$}. Steht das
     * {@code $} hinter einem Bezeichnerzeichen, gehört es zum Bezeichner (Oracle {@code V$SESSION}); {@code $1} ist ein
     * Parameter.
     */
    private static int dollarQuote(String sql, int i, Dialect dialect) {
        if (i > 0 && identifierPart(sql.charAt(i - 1), dialect)) {
            return 0;
        }
        int j = i + 1;
        if (dialect == Dialect.POSTGRES) {
            if (j < sql.length() && Character.isDigit(sql.charAt(j))) {
                return 0;
            }
            while (j < sql.length() && (Character.isLetterOrDigit(sql.charAt(j)) || sql.charAt(j) == '_')) {
                j++;
            }
        }
        return j < sql.length() && sql.charAt(j) == '$' ? j + 1 : 0;
    }

    private static boolean identifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean identifierPart(char c, Dialect dialect) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '@' || c == '#' && dialect != Dialect.MYSQL;
    }
}
