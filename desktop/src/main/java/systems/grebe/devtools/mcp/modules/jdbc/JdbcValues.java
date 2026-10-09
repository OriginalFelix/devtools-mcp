package systems.grebe.devtools.mcp.modules.jdbc;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Struct;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * Werte zwischen JSON (Tool-Parameter, Ausgabe) und JDBC. Parameter werden möglichst mit dem Typ der Zielspalte bzw.
 * des Platzhalters gebunden – so geht {@code "2024-05-01"} in eine DATE-Spalte und {@code "42"} in eine INTEGER-Spalte,
 * auch bei streng typisierten Datenbanken wie PostgreSQL. Was sich nicht umwandeln lässt, geht als Text an die
 * Datenbank, die dann selbst entscheidet.
 */
final class JdbcValues {

    /** Typ unbekannt – die Datenbank soll ihn ableiten. */
    static final int UNKNOWN = Integer.MIN_VALUE;

    private static final JsonMapper JSON = JsonMapper.shared();

    private JdbcValues() {
    }

    /** Bindet Werte an die Platzhalter einer Anweisung; ein Binder je Verbindung (Eigenheiten des Treibers). */
    static final class Binder {
        private final boolean nullViaSetObject;
        private final boolean nullAsVarchar;

        Binder(Connection con) {
            String product = "";
            String driver = "";
            try {
                DatabaseMetaData m = con.getMetaData();
                product = String.valueOf(m.getDatabaseProductName());
                driver = String.valueOf(m.getDriverName());
            } catch (SQLException | RuntimeException e) {
                // Standardverhalten
            }
            // wie Spring JDBC (StatementCreatorUtils): NULL ohne bekannten Typ je nach Treiber
            nullViaSetObject = product.startsWith("Informix") || driver.startsWith("Microsoft") && driver.contains("SQL Server");
            nullAsVarchar = product.startsWith("DB2") || driver.startsWith("jConnect") || driver.startsWith("SQLServer")
                    || driver.startsWith("Apache Derby");
        }

        /** Bindet alle Parameter; die Anzahl muss zu den Platzhaltern passen, soweit der Treiber sie kennt. */
        void bindAll(PreparedStatement ps, List<Object> params) throws SQLException {
            List<Object> values = params == null ? List.of() : params;
            ParameterMetaData meta = parameterMetaData(ps); // einmal je Anweisung: kostet z.B. bei PostgreSQL einen Roundtrip
            int expected = parameterCount(meta);
            if (expected >= 0 && expected != values.size()) {
                throw new IllegalArgumentException("Die Anweisung hat " + expected + " Platzhalter (?), übergeben "
                        + (values.size() == 1 ? "wurde 1 Wert" : "wurden " + values.size() + " Werte") + " in 'params'.");
            }
            for (int i = 0; i < values.size(); i++) {
                bind(ps, i + 1, values.get(i), parameterType(meta, i + 1));
            }
        }

        /** Bindet einen Wert mit dem Zieltyp ({@link java.sql.Types}) oder {@link #UNKNOWN}. */
        void bind(PreparedStatement ps, int i, Object value, int type) throws SQLException {
            if (value == null) {
                if (type != UNKNOWN) {
                    ps.setNull(i, type);
                } else if (nullViaSetObject) {
                    ps.setObject(i, null);
                } else {
                    ps.setNull(i, nullAsVarchar ? Types.VARCHAR : Types.NULL);
                }
            } else if (value instanceof Map<?, ?> || value instanceof List<?> && type != Types.ARRAY) {
                String json = JSON.writeValueAsString(value);
                if (type == Types.OTHER) {
                    ps.setObject(i, json, Types.OTHER); // PostgreSQL json/jsonb
                } else {
                    ps.setString(i, json);
                }
            } else if (value instanceof List<?> list) {
                ps.setObject(i, list.toArray());
            } else if (value instanceof String s) {
                bindString(ps, i, s, type);
            } else if (value instanceof Boolean b) {
                if (numeric(type)) {
                    ps.setInt(i, b ? 1 : 0);
                } else if (text(type)) {
                    ps.setString(i, b.toString());
                } else {
                    ps.setBoolean(i, b);
                }
            } else if (value instanceof Number n) {
                bindNumber(ps, i, n, type);
            } else {
                ps.setObject(i, value);
            }
        }
    }

    private static void bindNumber(PreparedStatement ps, int i, Number n, int type) throws SQLException {
        boolean integral = n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
                || n instanceof BigInteger;
        switch (type) {
            case Types.BIT, Types.BOOLEAN -> ps.setBoolean(i, n.doubleValue() != 0);
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> ps.setDouble(i, n.doubleValue());
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB -> ps.setString(i, decimal(n).toPlainString());
            default -> {
                if (integral && n instanceof BigInteger bi) {
                    ps.setBigDecimal(i, new BigDecimal(bi));
                } else if (integral) {
                    ps.setLong(i, n.longValue());
                } else {
                    // Dezimaltext statt double: 0.1 bleibt 0.1 in einer DECIMAL-Spalte
                    ps.setBigDecimal(i, decimal(n));
                }
            }
        }
    }

    private static void bindString(PreparedStatement ps, int i, String s, int type) throws SQLException {
        String t = s.strip();
        try {
            switch (type) {
                case Types.BIT, Types.BOOLEAN -> {
                    Boolean b = parseBoolean(t);
                    if (b == null) {
                        ps.setString(i, s);
                    } else {
                        ps.setBoolean(i, b);
                    }
                }
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT ->
                        ps.setLong(i, new BigDecimal(t).longValueExact());
                case Types.DECIMAL, Types.NUMERIC -> ps.setBigDecimal(i, new BigDecimal(t));
                case Types.REAL, Types.FLOAT, Types.DOUBLE -> ps.setDouble(i, Double.parseDouble(t));
                case Types.DATE -> ps.setDate(i, Date.valueOf(LocalDate.parse(t.length() > 10 ? t.substring(0, 10) : t)));
                case Types.TIME -> ps.setTime(i, Time.valueOf(LocalTime.parse(t)));
                case Types.TIMESTAMP -> ps.setTimestamp(i, Timestamp.valueOf(dateTime(t)));
                case Types.TIMESTAMP_WITH_TIMEZONE -> ps.setObject(i, OffsetDateTime.parse(t.replace(' ', 'T')));
                case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> ps.setBytes(i,
                        t.regionMatches(true, 0, "0x", 0, 2) ? HexFormat.of().parseHex(t.substring(2))
                                : s.getBytes(StandardCharsets.UTF_8));
                case Types.OTHER -> ps.setObject(i, s, Types.OTHER); // PostgreSQL uuid, json, Enums …
                default -> ps.setString(i, s);
            }
        } catch (IllegalArgumentException | ArithmeticException | DateTimeException e) {
            ps.setString(i, s); // die Datenbank soll entscheiden (und ggf. verständlich ablehnen)
        }
    }

    /** {@code 2024-05-01}, {@code 2024-05-01 12:30}, {@code 2024-05-01T12:30:15.123} */
    private static LocalDateTime dateTime(String t) {
        String v = t.replace(' ', 'T');
        if (v.length() == 10) {
            return LocalDate.parse(v).atStartOfDay();
        }
        if (v.endsWith("Z") || v.matches(".*[+-]\\d\\d:?\\d\\d$")) {
            return OffsetDateTime.parse(v).toLocalDateTime();
        }
        return LocalDateTime.parse(v);
    }

    private static Boolean parseBoolean(String t) {
        return switch (t.toLowerCase(Locale.ROOT)) {
            case "true", "t", "1", "yes", "y", "ja", "j", "on" -> Boolean.TRUE;
            case "false", "f", "0", "no", "n", "nein", "off" -> Boolean.FALSE;
            default -> null;
        };
    }

    private static BigDecimal decimal(Number n) {
        return n instanceof BigDecimal bd ? bd : new BigDecimal(n.toString());
    }

    private static boolean numeric(int type) {
        return switch (type) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT, Types.DECIMAL, Types.NUMERIC, Types.REAL,
                 Types.FLOAT, Types.DOUBLE -> true;
            default -> false;
        };
    }

    private static boolean text(int type) {
        return switch (type) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB -> true;
            default -> false;
        };
    }

    private static ParameterMetaData parameterMetaData(PreparedStatement ps) {
        try {
            return ps.getParameterMetaData();
        } catch (SQLException | RuntimeException | AbstractMethodError e) {
            return null;
        }
    }

    /** Anzahl der Platzhalter laut Treiber oder -1, wenn er es nicht sagt. */
    private static int parameterCount(ParameterMetaData m) {
        try {
            return m == null ? -1 : m.getParameterCount();
        } catch (SQLException | RuntimeException e) {
            return -1;
        }
    }

    /** Typ eines Platzhalters laut Treiber oder {@link #UNKNOWN} (MySQL z.B. kennt ihn nur mit Server-Prepare). */
    private static int parameterType(ParameterMetaData m, int i) {
        try {
            return m == null ? UNKNOWN : m.getParameterType(i);
        } catch (SQLException | RuntimeException e) {
            return UNKNOWN;
        }
    }

    // ------------------------------------------------------------------ Lesen

    /** Binärwert für die Ausgabe: Anfang als Hex und Gesamtlänge. */
    record Binary(byte[] head, long length) {
        @Override
        public String toString() {
            return "0x" + HexFormat.of().formatHex(head) + (length > head.length ? "…" : "") + " (" + length + " Bytes)";
        }
    }

    /**
     * Wert einer Spalte für die Ausgabe: {@code null}, {@link Number}, {@link Boolean}, {@link Binary}, Liste (Array)
     * oder Text. Große Texte werden schon beim Lesen auf {@code maxChars + 1} Zeichen begrenzt.
     */
    static Object read(ResultSet rs, int col, int type, int maxChars) throws SQLException {
        Object value = switch (type) {
            case Types.CLOB, Types.NCLOB -> {
                Clob clob = rs.getClob(col);
                yield clob == null ? null : clob.getSubString(1, (int) Math.min(clob.length(), maxChars + 1L));
            }
            case Types.BLOB -> {
                Blob blob = rs.getBlob(col);
                yield blob == null ? null : new Binary(blob.getBytes(1, (int) Math.min(blob.length(), 32)), blob.length());
            }
            default -> rs.getObject(col);
        };
        return normalize(value, maxChars);
    }

    private static Object normalize(Object value, int maxChars) throws SQLException {
        return switch (value) {
            case null -> null;
            case Number n -> n;
            case Boolean b -> b;
            case String s -> s;
            case Binary b -> b;
            case byte[] bytes -> new Binary(java.util.Arrays.copyOf(bytes, Math.min(bytes.length, 32)), bytes.length);
            case Timestamp ts -> {
                String s = ts.toString();
                yield s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
            }
            case Clob clob -> clob.getSubString(1, (int) Math.min(clob.length(), maxChars + 1L));
            case Blob blob -> new Binary(blob.getBytes(1, (int) Math.min(blob.length(), 32)), blob.length());
            case SQLXML xml -> xml.getString();
            case Array array -> {
                Object raw = array.getArray();
                List<Object> out = new ArrayList<>();
                if (raw instanceof Object[] items) {
                    for (Object item : items) {
                        out.add(normalize(item, maxChars));
                    }
                } else if (raw != null && raw.getClass().isArray()) {
                    for (int i = 0; i < java.lang.reflect.Array.getLength(raw); i++) {
                        out.add(java.lang.reflect.Array.get(raw, i));
                    }
                }
                yield out;
            }
            case Struct struct -> java.util.Arrays.toString(struct.getAttributes());
            default -> value.toString();
        };
    }
}
