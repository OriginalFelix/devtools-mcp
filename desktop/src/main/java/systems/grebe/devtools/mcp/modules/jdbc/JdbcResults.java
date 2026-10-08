package systems.grebe.devtools.mcp.modules.jdbc;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.json.JsonMapper;

/** Liest Ergebnismengen begrenzt ein und formatiert sie für das LLM: als Tabelle, CSV oder JSON. */
final class JdbcResults {

    enum Format {
        TABLE, CSV, JSON;

        static Format of(String value) {
            if (value == null || value.isBlank()) {
                return TABLE;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unbekanntes Format '" + value + "' – table, csv oder json.");
            }
        }
    }

    /**
     * Eingelesene Zeilen.
     *
     * @param more ob es weitere Zeilen gibt, die nicht gelesen wurden
     */
    record Page(List<String> columns, List<Object[]> rows, boolean more) {
    }

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private JdbcResults() {
    }

    /** Liest höchstens {@code maxRows} Zeilen; Werte siehe {@link JdbcValues#read}. */
    static Page read(ResultSet rs, int maxRows, int maxChars) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int count = meta.getColumnCount();
        List<String> columns = new ArrayList<>(count);
        int[] types = new int[count];
        for (int i = 1; i <= count; i++) {
            String label = meta.getColumnLabel(i);
            columns.add(label == null || label.isBlank() ? meta.getColumnName(i) : label);
            types[i - 1] = meta.getColumnType(i);
        }
        List<Object[]> rows = new ArrayList<>();
        boolean more = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                more = true;
                break;
            }
            Object[] row = new Object[count];
            for (int i = 0; i < count; i++) {
                row[i] = JdbcValues.read(rs, i + 1, types[i], maxChars);
            }
            rows.add(row);
        }
        return new Page(columns, rows, more);
    }

    static String format(Page page, Format format, int maxChars) {
        return switch (format) {
            case TABLE -> table(page, maxChars);
            case CSV -> csv(page, maxChars);
            case JSON -> json(page, maxChars);
        };
    }

    /** Spalten mit {@code |} getrennt und ausgerichtet; Zahlen rechtsbündig, NULL als {@code NULL}. */
    private static String table(Page page, int maxChars) {
        int n = page.columns().size();
        List<String[]> cells = new ArrayList<>();
        int[] width = new int[n];
        for (int i = 0; i < n; i++) {
            width[i] = page.columns().get(i).length();
        }
        for (Object[] row : page.rows()) {
            String[] line = new String[n];
            for (int i = 0; i < n; i++) {
                line[i] = row[i] == null ? "NULL" : oneLine(text(row[i], maxChars));
                width[i] = Math.max(width[i], line[i].length());
            }
            cells.add(line);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i > 0 ? " | " : "").append(pad(page.columns().get(i), width[i], false));
        }
        sb.append('\n');
        for (int i = 0; i < n; i++) {
            sb.append(i > 0 ? "-+-" : "").append("-".repeat(width[i]));
        }
        for (int r = 0; r < cells.size(); r++) {
            sb.append('\n');
            for (int i = 0; i < n; i++) {
                sb.append(i > 0 ? " | " : "").append(pad(cells.get(r)[i], width[i], page.rows().get(r)[i] instanceof Number));
            }
        }
        return stripTrailing(sb.toString());
    }

    private static String csv(Page page, int maxChars) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", page.columns().stream().map(JdbcResults::csvField).toList()));
        for (Object[] row : page.rows()) {
            sb.append('\n');
            for (int i = 0; i < row.length; i++) {
                sb.append(i > 0 ? "," : "").append(row[i] == null ? "" : csvField(text(row[i], maxChars)));
            }
        }
        return sb.toString();
    }

    private static String json(Page page, int maxChars) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] row : page.rows()) {
            Map<String, Object> obj = new LinkedHashMap<>();
            for (int i = 0; i < row.length; i++) {
                obj.put(page.columns().get(i), jsonValue(row[i], maxChars));
            }
            out.add(obj);
        }
        return JSON.writeValueAsString(out);
    }

    private static Object jsonValue(Object v, int maxChars) {
        if (v == null || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof List<?> list) {
            return list.stream().map(x -> jsonValue(x, maxChars)).toList();
        }
        return text(v, maxChars);
    }

    /** Text eines Werts, gekürzt auf {@code maxChars} Zeichen. */
    static String text(Object v, int maxChars) {
        String s = v instanceof BigDecimal bd ? bd.toPlainString() : String.valueOf(v);
        return s.length() > maxChars ? s.substring(0, maxChars) + "…" : s;
    }

    private static String oneLine(String s) {
        return s.replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String csvField(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String pad(String s, int width, boolean right) {
        String fill = " ".repeat(Math.max(0, width - s.length()));
        return right ? fill + s : s + fill;
    }

    private static String stripTrailing(String table) {
        return table.lines().map(String::stripTrailing).reduce((a, b) -> a + "\n" + b).orElse("");
    }
}
