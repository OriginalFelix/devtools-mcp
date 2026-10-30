package systems.grebe.devtools.mcp.intellij;

/** Geänderter Bereich zwischen zwei Fassungen eines Textes. */
final class Changes {

    private Changes() {
    }

    /**
     * Bereich {@code [start, end)} im neuen Text, der sich gegenüber dem alten geändert hat (gemeinsamer Anfang und
     * gemeinsames Ende abgezogen), {@code null} bei gleichem Text. Mehrere Änderungen ergeben einen Bereich von der
     * ersten bis zur letzten.
     */
    static int[] range(CharSequence before, CharSequence after) {
        int n = Math.min(before.length(), after.length());
        int prefix = 0;
        while (prefix < n && before.charAt(prefix) == after.charAt(prefix)) {
            prefix++;
        }
        if (prefix == before.length() && prefix == after.length()) {
            return null;
        }
        int suffix = 0;
        while (suffix < n - prefix
                && before.charAt(before.length() - 1 - suffix) == after.charAt(after.length() - 1 - suffix)) {
            suffix++;
        }
        return new int[]{prefix, after.length() - suffix};
    }
}
