package systems.grebe.devtools.mcp.modules.skills;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import systems.grebe.devtools.mcp.api.MediaTypes;

/**
 * Die Skills aus dem {@link SkillBackend} als Agent Skills (agentskills.io): je Skill ein Verzeichnis
 * {@code skill://<name>/} mit einer {@code SKILL.md} – YAML-Frontmatter ({@code name}, {@code description},
 * {@code metadata}) plus Inhalt – und den Zusatzdateien unter ihrem Pfad. Grundlage der MCP-Extension
 * {@code io.modelcontextprotocol/skills} ({@link SkillsExtension}).
 *
 * <p>Die {@code SKILL.md} wird bei jedem Zugriff gleich aus dem gespeicherten Skill erzeugt, so dass Größe und SHA-256
 * im Manifest zu den gelieferten Bytes passen, solange sich der Skill nicht ändert.
 */
final class SkillCatalog {

    static final String SCHEME = "skill://";
    static final String SKILL_MD = "SKILL.md";
    static final String MARKDOWN = "text/markdown";
    static final String DIRECTORY = "inode/directory";

    private final SkillBackend skills;
    private final Path temp;

    /** @param temp Ordner für das kurzzeitige Auslesen von Anhängen */
    SkillCatalog(SkillBackend skills, Path temp) {
        this.skills = skills;
        this.temp = temp;
    }

    /** Datei im Manifest eines Skills. */
    record Resource(String uri, String path, String mimeType, long size, String digest) {
    }

    /** Skill-Eintrag: URI der {@code SKILL.md}, Frontmatter und vollständiges Manifest (mit {@code SKILL.md}). */
    record Entry(String name, String uri, Map<String, Object> frontmatter, List<Resource> resources,
                 String skillMd) {

        Optional<Resource> resource(String path) {
            return resources.stream().filter(r -> r.path().equals(path)).findFirst();
        }
    }

    /** Gelesene Datei: Text ({@code text}) oder Binäres ({@code bytes}). */
    record Content(String uri, String mimeType, String text, byte[] bytes) {
    }

    /** Eintrag eines Verzeichnisses. */
    record Child(String uri, String name, String mimeType) {
    }

    /** Pfad innerhalb eines Skills; {@code path} leer = Wurzelverzeichnis. */
    record Location(String name, String path) {
    }

    /** Alle sichtbaren Skills (je Name einer – eigene Kopien verdecken die Vorlage). */
    List<Entry> list() {
        Set<String> names = new LinkedHashSet<>();
        skills.overview().forEach(s -> names.add(s.name()));
        List<Entry> out = new ArrayList<>();
        for (String name : names) {
            get(name).ifPresent(out::add);
        }
        return out;
    }

    Optional<Entry> get(String name) {
        return skills.details(name).map(SkillCatalog::entry);
    }

    /** Eintrag zur URI einer {@code SKILL.md}; leer, wenn es den Skill nicht gibt. */
    Optional<Entry> byUri(String uri) {
        Location l = parse(uri);
        if (!SKILL_MD.equals(l.path())) {
            throw new IllegalArgumentException("Keine Skill-URI (…/" + SKILL_MD + "): " + uri);
        }
        return get(l.name());
    }

    /** Inhalt einer Datei des Skills ({@code SKILL.md} oder Zusatzdatei). */
    Content read(String uri) {
        Location l = parse(uri);
        Entry e = get(l.name()).orElseThrow(() -> new IllegalArgumentException("Unbekannter Skill: " + uri));
        Resource r = e.resource(l.path()).orElseThrow(() -> new IllegalArgumentException(
                "Skill '" + l.name() + "' hat keine Datei '" + l.path() + "'."));
        if (SKILL_MD.equals(r.path())) {
            return new Content(r.uri(), MARKDOWN, e.skillMd(), null);
        }
        SkillViews.File f = skills.file(l.name(), r.path()).orElseThrow(() -> new IllegalArgumentException(
                "Skill '" + l.name() + "' hat keine Datei '" + l.path() + "'."));
        if (f.inline()) {
            return new Content(r.uri(), r.mimeType(), f.content(), null);
        }
        byte[] bytes = export(l.name(), r.path());
        String text = MediaTypes.textual(r.mimeType()) ? utf8(bytes) : null;
        return new Content(r.uri(), r.mimeType(), text, text == null ? bytes : null);
    }

    /** Direkte Kinder eines Verzeichnisses ({@code skill://<name>} oder ein Unterverzeichnis). */
    List<Child> directory(String uri) {
        Location l = parse(uri);
        Entry e = get(l.name()).orElseThrow(() -> new IllegalArgumentException("Unbekannter Skill: " + uri));
        String prefix = l.path().isEmpty() ? "" : l.path() + "/";
        Map<String, Child> children = new LinkedHashMap<>();
        for (Resource r : e.resources()) {
            if (!r.path().startsWith(prefix)) {
                continue;
            }
            String rest = r.path().substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                children.put(rest, new Child(r.uri(), rest, r.mimeType()));
            } else {
                String dir = rest.substring(0, slash);
                children.putIfAbsent(dir + "/", new Child(uri(l.name(), prefix + dir), dir, DIRECTORY));
            }
        }
        if (children.isEmpty()) {
            throw new IllegalArgumentException("Kein Verzeichnis: " + uri);
        }
        return List.copyOf(children.values());
    }

    // ------------------------------------------------------------------ SKILL.md und Manifest

    static Entry entry(SkillViews.Details d) {
        SkillViews.Summary s = d.summary();
        Map<String, Object> frontmatter = frontmatter(s);
        List<SkillViews.File> files = d.files().stream().filter(f -> !SKILL_MD.equals(f.path())).toList();
        String skillMd = render(frontmatter, d.content(), files);
        List<Resource> resources = new ArrayList<>();
        byte[] md = skillMd.getBytes(StandardCharsets.UTF_8);
        resources.add(new Resource(uri(s.name(), SKILL_MD), SKILL_MD, MARKDOWN, md.length, digest(md)));
        for (SkillViews.File f : files) {
            String type = f.mediaType() == null ? MediaTypes.guess(f.path()) : f.mediaType();
            if (f.inline()) {
                byte[] b = (f.content() == null ? "" : f.content()).getBytes(StandardCharsets.UTF_8);
                resources.add(new Resource(uri(s.name(), f.path()), f.path(), type, b.length, digest(b)));
            } else {
                resources.add(new Resource(uri(s.name(), f.path()), f.path(), type, f.size(),
                        "sha256:" + f.blob().toLowerCase(Locale.ROOT)));
            }
        }
        return new Entry(s.name(), uri(s.name(), SKILL_MD), frontmatter, List.copyOf(resources), skillMd);
    }

    /** {@code name}, {@code description} und unter {@code metadata} (nur Zeichenketten) die Angaben der Datenbank. */
    static Map<String, Object> frontmatter(SkillViews.Summary s) {
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("name", s.name());
        fm.put("description", s.description() == null ? "" : s.description());
        Map<String, String> metadata = new LinkedHashMap<>();
        if (s.category() != null && !s.category().isBlank()) {
            metadata.put("category", s.category());
        }
        if (s.tags() != null && !s.tags().isEmpty()) {
            metadata.put("tags", String.join(", ", s.tags()));
        }
        if (!s.triggers().isEmpty()) {
            metadata.put("triggers", String.join(", ", s.triggers()));
        }
        metadata.put("revision", String.valueOf(s.revision()));
        metadata.put("scope", s.scope().name().toLowerCase(Locale.ROOT));
        if (s.owner() != null && !s.owner().isBlank()) {
            metadata.put("owner", s.owner());
        }
        fm.put("metadata", metadata);
        return fm;
    }

    static String render(Map<String, Object> frontmatter, String content, List<SkillViews.File> files) {
        StringBuilder sb = new StringBuilder("---\n");
        for (Map.Entry<String, Object> e : frontmatter.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> map) {
                sb.append(e.getKey()).append(":\n");
                map.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(yaml(String.valueOf(v)))
                        .append('\n'));
            } else {
                sb.append(e.getKey()).append(": ").append(yaml(String.valueOf(e.getValue()))).append('\n');
            }
        }
        sb.append("---\n\n").append(content == null ? "" : content.strip()).append('\n');
        if (!files.isEmpty()) {
            sb.append("\n## Zusatzdateien\n\n");
            files.forEach(f -> sb.append("- [").append(f.path()).append("](").append(encodePath(f.path()))
                    .append(")\n"));
        }
        return sb.toString();
    }

    /** YAML-Skalar in doppelten Anführungszeichen – Zahlen, {@code true}, {@code :} usw. bleiben Text. */
    static String yaml(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || (c >= 0x7f && c <= 0x9f) || c == '﻿') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ URIs

    /** {@code skill://<name>/<pfad>}; {@code path} leer = Verzeichnis des Skills. */
    static String uri(String name, String path) {
        return SCHEME + name + (path == null || path.isEmpty() ? "" : "/" + encodePath(path));
    }

    static Location parse(String uri) {
        if (uri == null || !uri.startsWith(SCHEME)) {
            throw new IllegalArgumentException("Keine Skill-URI (skill://…): " + uri);
        }
        String rest = uri.substring(SCHEME.length());
        while (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);
        String path = slash < 0 ? "" : decodePath(rest.substring(slash + 1));
        if (name.isEmpty() || name.contains("%") || ("/" + path + "/").contains("/../")
                || ("/" + path + "/").contains("/./") || path.contains("//")) {
            throw new IllegalArgumentException("Ungültige Skill-URI: " + uri);
        }
        return new Location(name, path);
    }

    private static final String UNRESERVED = "-._~!$&'()*+,;=:@";

    static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (byte b : path.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if (c < 0x80 && (Character.isLetterOrDigit(c) || c == '/' || UNRESERVED.indexOf(c) >= 0)) {
                sb.append((char) c);
            } else {
                sb.append('%').append(String.format("%02X", c));
            }
        }
        return sb.toString();
    }

    static String decodePath(String path) {
        if (path.indexOf('%') < 0) {
            return path;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '%' && i + 2 < path.length()) {
                out.write(HexFormat.fromHexDigits(path, i + 1, i + 3));
                i += 2;
            } else if (c == '%') {
                throw new IllegalArgumentException("Ungültige Prozent-Kodierung in '" + path + "'.");
            } else {
                out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            }
        }
        return utf8OrFail(out.toByteArray(), path);
    }

    // ------------------------------------------------------------------ Anhänge

    private byte[] export(String name, String path) {
        Path file = null;
        try {
            Files.createDirectories(temp);
            file = Files.createTempFile(temp, "skill-", ".bin");
            skills.exportFile(name, path, file);
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UncheckedIOException("Anhang '" + path + "' aus Skill '" + name + "' nicht lesbar: "
                    + e.getMessage(), e);
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // temporäre Datei – bleibt notfalls liegen
                }
            }
        }
    }

    /** Text, wenn die Bytes gültiges UTF-8 sind, sonst {@code null}. */
    static String utf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String utf8OrFail(byte[] bytes, String original) {
        String s = utf8(bytes);
        if (s == null) {
            throw new IllegalArgumentException("Ungültige Prozent-Kodierung in '" + original + "'.");
        }
        return s;
    }
}
