package systems.grebe.devtools.mcp.modules.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import systems.grebe.devtools.mcp.core.ShellHints;
import systems.grebe.devtools.mcp.core.Text;
import systems.grebe.devtools.mcp.core.ToolHints;
import systems.grebe.devtools.mcp.modules.jdbc.JdbcEnvironment.Mode;
import systems.grebe.devtools.mcp.modules.jdbc.JdbcMetadata.Column;
import systems.grebe.devtools.mcp.modules.jdbc.JdbcMetadata.TableRef;

/** Lesende JDBC-Tools: Verbindungen und Struktur (Kataloge, Schemas, Tabellen, Spalten, Schlüssel, Indizes). */
@ToolHints(readOnly = true)
public class JdbcTools {

    static final String CONNECTION = "Name der Verbindung (siehe jdbc_connections); leer = die einzige konfigurierte";

    private final JdbcEnvironment env;

    JdbcTools(JdbcEnvironment env) {
        this.env = env;
    }

    @Tool(name = "connections", description = "Listet die in der DevTools-App hinterlegten Datenbankverbindungen: Name, "
            + "JDBC-URL (ohne Zugangsdaten), Benutzer, was auf der Verbindung erlaubt ist, Beschreibung und – nach dem "
            + "ersten Zugriff – Datenbankprodukt und Version. Passwörter werden nie ausgegeben." + ShellHints.JDBC)
    public String connections() {
        List<JdbcConnection> all = env.connections();
        if (all.isEmpty()) {
            return "Keine Datenbankverbindung konfiguriert – der Nutzer legt sie in der DevTools-App unter Module → "
                    + "Datenbanken (JDBC) an.";
        }
        StringBuilder sb = new StringBuilder();
        for (JdbcConnection c : all) {
            sb.append(c.name()).append("  ").append(c.safeUrl());
            if (!c.username().isEmpty()) {
                sb.append("  Benutzer ").append(c.username());
            }
            sb.append("  [").append(env.accessSummary(c));
            String product = env.product(c);
            if (product != null) {
                sb.append("; ").append(product);
            }
            sb.append(env.isOpen(c) ? "; verbunden" : "").append(']');
            if (!c.description().isEmpty()) {
                sb.append("  – ").append(c.description());
            }
            sb.append('\n');
        }
        if (!env.duplicates().isEmpty()) {
            sb.append("Achtung: mehrfach vergebene Namen ").append(env.duplicates())
                    .append(" – nur der erste Eintrag gilt.\n");
        }
        return sb.toString().strip();
    }

    @Tool(name = "databases", description = "Zeigt für eine Datenbankverbindung Produkt, Version, Treiber, angemeldeten "
            + "Benutzer, aktuellen Katalog und Schema sowie alle Kataloge (bei MySQL/SQL Server: Datenbanken) und "
            + "Schemas." + ShellHints.JDBC)
    public String databases(@ToolParam(required = false, description = CONNECTION) String connection) {
        JdbcConnection c = env.resolve(connection);
        return env.withConnection(c, Mode.READ, con -> {
            DatabaseMetaData m = con.getMetaData();
            String catalog = JdbcMetadata.currentCatalog(con);
            String schema = JdbcMetadata.currentSchema(con);
            StringBuilder sb = new StringBuilder();
            sb.append(Text.firstLine(m.getDatabaseProductName() + " " + m.getDatabaseProductVersion()))
                    .append(" – Treiber ").append(m.getDriverName()).append(' ').append(m.getDriverVersion());
            if (m.getUserName() != null && !m.getUserName().isBlank()) {
                sb.append(", angemeldet als ").append(m.getUserName());
            }
            sb.append("\nAktuell: Katalog ").append(Text.orDash(catalog)).append(", Schema ").append(Text.orDash(schema))
                    .append('\n');
            List<String> catalogs = new ArrayList<>();
            try (ResultSet rs = m.getCatalogs()) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    catalogs.add(Objects.equals(name, catalog) ? name + " (aktuell)" : name);
                }
            }
            List<String> schemas = new ArrayList<>();
            try (ResultSet rs = m.getSchemas()) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_SCHEM");
                    String cat = rs.getString("TABLE_CATALOG");
                    boolean current = Objects.equals(name, schema) && (cat == null || catalog == null || cat.equals(catalog));
                    schemas.add(name + (cat != null && !cat.equals(catalog) ? " (Katalog " + cat + ")" : "")
                            + (current ? " (aktuell)" : ""));
                }
            }
            appendList(sb, "Kataloge", term(m.getCatalogTerm()), catalogs);
            appendList(sb, "Schemas", term(m.getSchemaTerm()), schemas);
            sb.append("\nTabellen eines Schemas: jdbc_tables.");
            return sb.toString();
        });
    }

    @Tool(name = "tables", description = "Listet Tabellen und Views einer Datenbankverbindung (Name, Typ, Kommentar). "
            + "Ohne Angaben: das aktuelle Schema; schema=\"%\" durchsucht alle Schemas, pattern filtert nach Namen "
            + "(SQL-LIKE, z.B. \"order%\")." + ShellHints.JDBC)
    public String tables(
            @ToolParam(required = false, description = CONNECTION) String connection,
            @ToolParam(required = false, description = "Schema; leer = aktuelles, % = alle") String schema,
            @ToolParam(required = false, description = "Namensmuster (SQL-LIKE mit % und _), leer = alle") String pattern,
            @ToolParam(required = false, description = "Tabellentypen, z.B. [\"TABLE\"] oder [\"VIEW\"]; leer = Tabellen "
                    + "und Views, [\"%\"] = alle Typen (auch Systemtabellen, Sequenzen …)") List<String> types,
            @ToolParam(required = false, description = "Katalog (MySQL/SQL Server: Datenbank); leer = aktueller, % = alle")
            String catalog) {
        JdbcConnection c = env.resolve(connection);
        return env.withConnection(c, Mode.READ, con -> {
            DatabaseMetaData m = con.getMetaData();
            String cat = blank(catalog) ? JdbcMetadata.currentCatalog(con) : all(catalog) ? null : catalog.strip();
            boolean defaultSchema = blank(schema);
            String sch = defaultSchema ? JdbcMetadata.currentSchema(con) : all(schema) ? null : schema.strip();
            String pat = blank(pattern) ? "%" : pattern.strip().replace('*', '%');
            String[] typeNames = types(m, types);
            List<TableRef> found = find(m, cat, sch, pat, typeNames);
            String scope = sch == null ? "allen Schemas" : "Schema " + sch;
            if (found.isEmpty() && defaultSchema && sch != null) {
                found = find(m, cat, null, pat, typeNames); // im aktuellen Schema nichts – alle durchsuchen
                scope = "allen Schemas (im aktuellen Schema " + sch + " keine)";
            }
            if (found.isEmpty()) {
                return "Keine Tabellen" + (pat.equals("%") ? "" : " zu '" + pat + "'") + " in " + scope
                        + (cat == null ? "" : ", Katalog " + cat) + ". jdbc_databases zeigt Kataloge und Schemas.";
            }
            StringBuilder sb = new StringBuilder().append(found.size()).append(found.size() == 1 ? " Eintrag" : " Einträge")
                    .append(" in ").append(scope).append(cat == null ? "" : ", Katalog " + cat).append(":\n");
            int limit = env.maxRows();
            for (TableRef t : found.subList(0, Math.min(found.size(), limit))) {
                sb.append(sch == null ? t.display() : t.name()).append("  ").append(t.type());
                if (t.remarks() != null && !t.remarks().isBlank()) {
                    sb.append("  – ").append(Text.firstLine(t.remarks()));
                }
                sb.append('\n');
            }
            if (found.size() > limit) {
                sb.append("… ").append(found.size() - limit).append(" weitere – mit pattern eingrenzen.\n");
            }
            return sb.toString().strip();
        });
    }

    @Tool(name = "describe", description = "Beschreibt eine Tabelle oder View: Spalten (Typ, NULL erlaubt, Standardwert, "
            + "automatisch erzeugt, Kommentar), Primärschlüssel, Fremdschlüssel in beide Richtungen und Indizes. Vor "
            + "dem Schreiben von SQL die echten Spaltennamen und Typen hier nachsehen." + ShellHints.JDBC)
    public String describe(
            @ToolParam(required = false, description = CONNECTION) String connection,
            @ToolParam(description = "Tabelle, optional qualifiziert (schema.tabelle); Groß-/Kleinschreibung wie in der "
                    + "Datenbank oder unquotiert") String table,
            @ToolParam(required = false, description = "Schema, falls nicht in 'table' angegeben; leer = aktuelles, "
                    + "sonst wird überall gesucht") String schema) {
        JdbcConnection c = env.resolve(connection);
        return env.withConnection(c, Mode.READ, con -> describe(con, JdbcMetadata.table(con, null, schema, table)));
    }

    @Tool(name = "disconnect", description = "Schließt die offenen Verbindungen zu einer Datenbank, die die jdbc_*-Tools "
            + "zwischen Aufrufen wiederverwenden (sonst nach 10 Minuten ohne Nutzung). Der nächste Aufruf verbindet neu."
            + ShellHints.JDBC)
    @ToolHints(destructive = false, idempotent = true)
    public String disconnect(@ToolParam(required = false, description = CONNECTION) String connection) {
        JdbcConnection c = env.resolve(connection);
        return env.disconnect(c) ? "Verbindung " + c.name() + " getrennt." : "Verbindung " + c.name() + " war nicht offen.";
    }

    // ------------------------------------------------------------------ intern

    private String describe(Connection con, TableRef t) throws SQLException {
        DatabaseMetaData m = con.getMetaData();
        List<Column> columns = JdbcMetadata.columns(con, t);
        Map<String, String> pk = primaryKey(m, t);
        StringBuilder sb = new StringBuilder().append(t.type()).append(' ').append(t.display());
        if (t.remarks() != null && !t.remarks().isBlank()) {
            sb.append(" – ").append(t.remarks().strip());
        }
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            Column col = columns.get(i);
            List<String> notes = new ArrayList<>();
            if (pk.containsKey(col.name())) {
                notes.add("PK");
            }
            if (col.autoIncrement()) {
                notes.add("automatisch");
            }
            if (col.generated()) {
                notes.add("berechnet");
            }
            if (col.remarks() != null && !col.remarks().isBlank()) {
                notes.add(col.remarks().strip());
            }
            rows.add(new Object[]{i + 1, col.name(), col.displayType(), col.nullable() ? "ja" : "nein",
                    col.defaultValue() == null ? "" : col.defaultValue().strip(), String.join(", ", notes)});
        }
        sb.append("\n\nSpalten (").append(columns.size()).append("):\n").append(JdbcResults.format(
                new JdbcResults.Page(List.of("#", "Spalte", "Typ", "NULL", "Standard", "Hinweise"), rows, false),
                JdbcResults.Format.TABLE, env.maxCellChars()));
        if (!pk.isEmpty()) {
            String name = pk.values().iterator().next();
            sb.append("\n\nPrimärschlüssel").append(name == null || name.isBlank() ? "" : " " + name).append(": (")
                    .append(String.join(", ", pk.keySet())).append(')');
        }
        section(sb, "Fremdschlüssel", () -> foreignKeys(m.getImportedKeys(t.catalog(), t.schema(), t.name()), true));
        section(sb, "Referenziert von", () -> foreignKeys(m.getExportedKeys(t.catalog(), t.schema(), t.name()), false));
        section(sb, "Indizes", () -> indexes(m, t));
        return sb.toString();
    }

    /** Spalten des Primärschlüssels in Schlüsselreihenfolge → Name des Schlüssels. */
    private static Map<String, String> primaryKey(DatabaseMetaData m, TableRef t) {
        Map<Integer, String[]> bySeq = new TreeMap<>();
        try (ResultSet rs = m.getPrimaryKeys(t.catalog(), t.schema(), t.name())) {
            while (rs.next()) {
                bySeq.put(rs.getInt("KEY_SEQ"), new String[]{rs.getString("COLUMN_NAME"), rs.getString("PK_NAME")});
            }
        } catch (SQLException | RuntimeException e) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        bySeq.values().forEach(v -> out.put(v[0], v[1]));
        return out;
    }

    private static List<String> foreignKeys(ResultSet rs, boolean imported) throws SQLException {
        Map<String, List<String[]>> byKey = new LinkedHashMap<>();
        try (rs) {
            while (rs.next()) {
                String fkTable = name(rs.getString("FKTABLE_SCHEM"), rs.getString("FKTABLE_NAME"));
                String pkTable = name(rs.getString("PKTABLE_SCHEM"), rs.getString("PKTABLE_NAME"));
                String fkName = rs.getString("FK_NAME");
                String key = (fkName == null ? "" : fkName) + "|" + fkTable + "|" + pkTable;
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(new String[]{rs.getString("FKCOLUMN_NAME"),
                        rs.getString("PKCOLUMN_NAME"), fkTable, pkTable, fkName == null ? "" : fkName,
                        rule(rs.getShort("DELETE_RULE"), "DELETE"), rule(rs.getShort("UPDATE_RULE"), "UPDATE")});
            }
        }
        List<String> out = new ArrayList<>();
        for (List<String[]> cols : byKey.values()) {
            String[] first = cols.getFirst();
            String fkCols = String.join(", ", cols.stream().map(x -> x[0]).toList());
            String pkCols = String.join(", ", cols.stream().map(x -> x[1]).toList());
            String rules = (first[5] + first[6]).strip();
            String name = first[4].isEmpty() ? "" : " [" + first[4] + "]";
            out.add(imported
                    ? "(" + fkCols + ") → " + first[3] + " (" + pkCols + ")" + (rules.isEmpty() ? "" : " " + rules) + name
                    : first[2] + " (" + fkCols + ") → (" + pkCols + ")" + (rules.isEmpty() ? "" : " " + rules) + name);
        }
        return out;
    }

    private static List<String> indexes(DatabaseMetaData m, TableRef t) throws SQLException {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        Map<String, Boolean> unique = new LinkedHashMap<>();
        // approximate=true: Oracle würde sonst Statistiken neu berechnen
        try (ResultSet rs = m.getIndexInfo(t.catalog(), t.schema(), t.name(), false, true)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                if (name == null || rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                String col = rs.getString("COLUMN_NAME");
                String order = rs.getString("ASC_OR_DESC");
                columns.computeIfAbsent(name, k -> new ArrayList<>())
                        .add((col == null ? "<Ausdruck>" : col) + ("D".equals(order) ? " DESC" : ""));
                unique.put(name, !rs.getBoolean("NON_UNIQUE"));
            }
        }
        List<String> out = new ArrayList<>();
        columns.forEach((name, cols) -> out.add(name + (unique.get(name) ? " UNIQUE" : "") + " (" + String.join(", ", cols) + ")"));
        return out;
    }

    @FunctionalInterface
    private interface Lines {
        List<String> get() throws SQLException;
    }

    private static void section(StringBuilder sb, String title, Lines lines) {
        List<String> list;
        try {
            list = lines.get();
        } catch (SQLException | RuntimeException e) {
            sb.append("\n\n").append(title).append(": vom Treiber nicht ermittelbar (").append(Text.firstLine(e.getMessage()))
                    .append(')');
            return;
        }
        if (list.isEmpty()) {
            return;
        }
        sb.append("\n\n").append(title).append(" (").append(list.size()).append("):");
        list.forEach(l -> sb.append("\n  ").append(l));
    }

    private static String rule(short rule, String event) {
        return switch (rule) {
            case DatabaseMetaData.importedKeyCascade -> " ON " + event + " CASCADE";
            case DatabaseMetaData.importedKeySetNull -> " ON " + event + " SET NULL";
            case DatabaseMetaData.importedKeySetDefault -> " ON " + event + " SET DEFAULT";
            default -> "";
        };
    }

    private static String name(String schema, String table) {
        return schema == null || schema.isBlank() ? table : schema + "." + table;
    }

    /** Tabellen zum Muster; ohne Treffer auch in der Schreibweise, die die Datenbank für unquotierte Namen nutzt. */
    private static List<TableRef> find(DatabaseMetaData m, String catalog, String schema, String pattern, String[] types)
            throws SQLException {
        for (String s : schema == null ? java.util.Collections.<String>singletonList(null) : JdbcMetadata.caseVariants(schema)) {
            for (String p : JdbcMetadata.caseVariants(pattern)) {
                List<TableRef> out = new ArrayList<>();
                try (ResultSet rs = m.getTables(catalog, s, p, types)) {
                    while (rs.next()) {
                        out.add(new TableRef(rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"),
                                rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE"), rs.getString("REMARKS")));
                    }
                }
                if (!out.isEmpty()) {
                    return out;
                }
            }
        }
        return List.of();
    }

    /** Gewünschte Typen; Standard: alle Typen der Datenbank mit TABLE oder VIEW außer System- und Temporärtabellen. */
    private static String[] types(DatabaseMetaData m, List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            if (requested.stream().anyMatch(t -> t != null && all(t))) {
                return null;
            }
            return requested.stream().filter(t -> t != null && !t.isBlank())
                    .map(t -> t.strip().toUpperCase(Locale.ROOT)).toArray(String[]::new);
        }
        List<String> out = new ArrayList<>();
        try (ResultSet rs = m.getTableTypes()) {
            while (rs.next()) {
                String type = rs.getString(1).strip();
                String upper = type.toUpperCase(Locale.ROOT);
                if ((upper.contains("TABLE") || upper.contains("VIEW")) && !upper.startsWith("SYSTEM")
                        && !upper.contains("TEMPORARY")) {
                    out.add(type);
                }
            }
        } catch (SQLException | RuntimeException e) {
            return null;
        }
        return out.isEmpty() ? null : out.toArray(String[]::new);
    }

    private static void appendList(StringBuilder sb, String title, String term, List<String> items) {
        sb.append('\n').append(title).append(term).append(" (").append(items.size()).append("): ");
        sb.append(items.isEmpty() ? "keine" : String.join(", ", items));
    }

    private static String term(String term) {
        return term == null || term.isBlank() ? "" : " [" + term + "]";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static boolean all(String s) {
        return s.strip().equals("%") || s.strip().equals("*");
    }
}
