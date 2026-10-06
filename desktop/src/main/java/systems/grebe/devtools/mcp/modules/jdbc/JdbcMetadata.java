package systems.grebe.devtools.mcp.modules.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Tabellen und Spalten über {@link DatabaseMetaData} finden – trotz der Unterschiede zwischen Datenbanken: Oracle und
 * H2 speichern unquotierte Namen groß, PostgreSQL klein; MySQL nennt Datenbanken Kataloge, PostgreSQL Schemas.
 * Muster-Parameter der Metadaten behandeln {@code _} und {@code %} als Platzhalter; für exakte Namen werden sie
 * maskiert und die Treffer zusätzlich exakt verglichen.
 */
final class JdbcMetadata {

    /** Eine gefundene Tabelle (oder View …) mit ihren echten Namen. */
    record TableRef(String catalog, String schema, String name, String type, String remarks) {

        /** {@code schema.name}, ohne Schema {@code katalog.name}. */
        String display() {
            if (schema != null && !schema.isEmpty()) {
                return schema + "." + name;
            }
            return catalog != null && !catalog.isEmpty() ? catalog + "." + name : name;
        }
    }

    /** Eine Spalte. */
    record Column(String name, int type, String typeName, int size, int scale, boolean nullable, String defaultValue,
                  boolean autoIncrement, boolean generated, String remarks) {

        /** Typ zur Anzeige, z.B. {@code varchar(200)} oder {@code numeric(10,2)}. */
        String displayType() {
            if (typeName.contains("(")) {
                return typeName;
            }
            return switch (type) {
                case Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR, Types.BINARY, Types.VARBINARY ->
                        size > 0 && size < Integer.MAX_VALUE ? typeName + "(" + size + ")" : typeName;
                case Types.DECIMAL, Types.NUMERIC -> size > 0 ? typeName + "(" + size + "," + scale + ")" : typeName;
                default -> typeName;
            };
        }
    }

    private record Part(String text, boolean quoted) {
    }

    private JdbcMetadata() {
    }

    static String currentCatalog(Connection con) {
        try {
            return con.getCatalog();
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            return null;
        }
    }

    static String currentSchema(Connection con) {
        try {
            return con.getSchema();
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            return null;
        }
    }

    /**
     * Sucht eine Tabelle. {@code table} darf qualifiziert sein ({@code schema.tabelle}, {@code katalog.schema.tabelle},
     * quotiert mit {@code "…"}, {@code `…`} oder {@code […]}). Ohne Schema: zuerst im aktuellen Schema, dann im
     * aktuellen Katalog, dann überall – mehrere Treffer sind ein Fehler.
     */
    static TableRef table(Connection con, String catalog, String schema, String table) throws SQLException {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("'table' fehlt.");
        }
        List<Part> parts = split(table.strip());
        if (parts.isEmpty() || parts.size() > 3) {
            throw new IllegalArgumentException("Ungültiger Tabellenname '" + table + "'.");
        }
        DatabaseMetaData m = con.getMetaData();
        Part name = parts.getLast();
        Part schemaPart = parts.size() >= 2 ? parts.get(parts.size() - 2) : plain(schema);
        Part catalogPart = parts.size() == 3 ? parts.getFirst() : plain(catalog);
        List<TableRef> found;
        if (schemaPart != null) {
            found = lookup(m, catalogPart, schemaPart, name);
            if (found.isEmpty() && parts.size() == 2 && catalogPart == null) {
                found = lookup(m, schemaPart, null, name); // MySQL: datenbank.tabelle = Katalog
            }
        } else {
            Part currentCatalog = catalogPart != null ? catalogPart : plain(currentCatalog(con));
            Part currentSchema = plain(currentSchema(con));
            found = currentSchema == null ? List.of() : lookup(m, currentCatalog, currentSchema, name);
            if (found.isEmpty()) {
                found = lookup(m, currentCatalog, null, name);
            }
            if (found.isEmpty() && catalogPart == null) {
                found = lookup(m, null, null, name);
            }
        }
        if (found.isEmpty()) {
            throw new IllegalArgumentException("Tabelle oder View '" + table + "' nicht gefunden"
                    + (schemaPart != null ? " (Schema/Datenbank " + schemaPart.text() + ")" : "")
                    + " – jdbc_tables listet die vorhandenen.");
        }
        if (found.size() > 1) {
            throw new IllegalArgumentException("'" + table + "' ist mehrdeutig: " + found.stream()
                    .map(TableRef::display).toList() + " – qualifiziert angeben (schema.tabelle) oder 'schema' setzen.");
        }
        return found.getFirst();
    }

    /** Erste Schreibweise (wie angegeben, groß, klein), unter der es Treffer gibt. */
    private static List<TableRef> lookup(DatabaseMetaData m, Part catalog, Part schema, Part name) throws SQLException {
        for (String c : variants(catalog)) {
            for (String s : variants(schema)) {
                for (String n : variants(name)) {
                    List<TableRef> out = new ArrayList<>();
                    try (ResultSet rs = m.getTables(c, s == null ? null : escape(m, s), escape(m, n), null)) {
                        while (rs.next()) {
                            TableRef t = new TableRef(rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"),
                                    rs.getString("TABLE_NAME"), rs.getString("TABLE_TYPE"), rs.getString("REMARKS"));
                            if (t.name().equals(n) && (s == null || s.equals(t.schema())) && !out.contains(t)) {
                                out.add(t);
                            }
                        }
                    }
                    if (!out.isEmpty()) {
                        return out;
                    }
                }
            }
        }
        return List.of();
    }

    /** Spalten in Tabellenreihenfolge. */
    static List<Column> columns(Connection con, TableRef t) throws SQLException {
        DatabaseMetaData m = con.getMetaData();
        List<Column> out = new ArrayList<>();
        try (ResultSet rs = m.getColumns(t.catalog(), t.schema() == null ? null : escape(m, t.schema()),
                escape(m, t.name()), "%")) {
            while (rs.next()) {
                if (!t.name().equals(rs.getString("TABLE_NAME"))
                        || t.schema() != null && !t.schema().equals(rs.getString("TABLE_SCHEM"))) {
                    continue;
                }
                String typeName = Objects.requireNonNullElse(rs.getString("TYPE_NAME"), "?");
                out.add(new Column(rs.getString("COLUMN_NAME"), rs.getInt("DATA_TYPE"), typeName,
                        rs.getInt("COLUMN_SIZE"), rs.getInt("DECIMAL_DIGITS"),
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls, rs.getString("COLUMN_DEF"),
                        "YES".equals(optional(rs, "IS_AUTOINCREMENT")), "YES".equals(optional(rs, "IS_GENERATEDCOLUMN")),
                        rs.getString("REMARKS")));
            }
        }
        return out;
    }

    /** Spalte nach Name: exakt, sonst ohne Groß-/Kleinschreibung (eindeutig). */
    static Column column(List<Column> columns, String name, TableRef t) {
        for (Column c : columns) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        List<Column> matches = columns.stream().filter(c -> c.name().equalsIgnoreCase(name)).toList();
        if (matches.size() == 1) {
            return matches.getFirst();
        }
        throw new IllegalArgumentException((matches.isEmpty() ? "Unbekannte Spalte '" : "Mehrdeutige Spalte '") + name
                + "' in " + t.display() + ". Spalten: " + columns.stream().map(Column::name).toList());
    }

    /** Bezeichner quotiert, z.B. {@code "Name"} oder {@code `Name`}; eingebettete Quotes verdoppelt. */
    static String quote(DatabaseMetaData m, String identifier) throws SQLException {
        String q = m.getIdentifierQuoteString();
        if (q == null || q.isBlank()) {
            return identifier;
        }
        q = q.strip();
        return q + identifier.replace(q, q + q) + q;
    }

    /** Qualifizierter, quotierter Name für DML; den Katalog nur, wenn er nicht der aktuelle ist. */
    static String qualified(Connection con, TableRef t) throws SQLException {
        DatabaseMetaData m = con.getMetaData();
        String catalog = t.catalog();
        boolean withCatalog = catalog != null && !catalog.isEmpty() && !catalog.equals(currentCatalog(con))
                && supports(m::supportsCatalogsInDataManipulation);
        boolean withSchema = t.schema() != null && !t.schema().isEmpty() && supports(m::supportsSchemasInDataManipulation);
        String separator = withCatalog ? Objects.requireNonNullElse(m.getCatalogSeparator(), ".") : ".";
        boolean catalogAtStart = !withCatalog || supports(m::isCatalogAtStart);
        StringBuilder sb = new StringBuilder();
        if (withCatalog && catalogAtStart) {
            sb.append(quote(m, catalog)).append(separator);
        }
        if (withSchema) {
            sb.append(quote(m, t.schema())).append('.');
        }
        sb.append(quote(m, t.name()));
        if (withCatalog && !catalogAtStart) {
            sb.append(separator).append(quote(m, catalog));
        }
        return sb.toString();
    }

    /** Maskiert die Platzhalter {@code _} und {@code %} für Muster-Parameter. */
    static String escape(DatabaseMetaData m, String s) throws SQLException {
        String esc = m.getSearchStringEscape();
        if (esc == null || esc.isEmpty()) {
            return s;
        }
        return s.replace(esc, esc + esc).replace("_", esc + "_").replace("%", esc + "%");
    }

    /** Groß-/Kleinschreibung, die die Datenbank für unquotierte Namen verwendet (Ausgangsform zuerst). */
    static List<String> caseVariants(String s) {
        Set<String> out = new LinkedHashSet<>();
        out.add(s);
        out.add(s.toUpperCase(Locale.ROOT));
        out.add(s.toLowerCase(Locale.ROOT));
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ intern

    private static List<String> variants(Part p) {
        if (p == null) {
            return java.util.Collections.singletonList(null);
        }
        return p.quoted() ? List.of(p.text()) : caseVariants(p.text());
    }

    private static Part plain(String s) {
        return s == null || s.isBlank() ? null : new Part(s.strip(), false);
    }

    /** Teilt {@code a.b.c} an Punkten außerhalb von Quotes; quotierte Teile behalten ihre Schreibweise. */
    private static List<Part> split(String name) {
        List<Part> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (i < name.length()) {
            char ch = name.charAt(i);
            char close = switch (ch) {
                case '"' -> '"';
                case '`' -> '`';
                case '[' -> ']';
                default -> 0;
            };
            if (close != 0 && cur.isEmpty()) {
                int j = i + 1;
                while (j < name.length()) {
                    if (name.charAt(j) == close) {
                        if (j + 1 < name.length() && name.charAt(j + 1) == close) {
                            cur.append(close);
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    cur.append(name.charAt(j++));
                }
                quoted = true;
                i = j + 1;
            } else if (ch == '.') {
                out.add(new Part(cur.toString().strip(), quoted));
                cur.setLength(0);
                quoted = false;
                i++;
            } else {
                cur.append(ch);
                i++;
            }
        }
        out.add(new Part(cur.toString().strip(), quoted));
        return out.stream().anyMatch(p -> p.text().isEmpty()) ? List.of() : out;
    }

    private static String optional(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null; // ältere Treiber kennen die Spalte nicht
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean test() throws SQLException;
    }

    private static boolean supports(Check check) {
        try {
            return check.test();
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            return true;
        }
    }
}
