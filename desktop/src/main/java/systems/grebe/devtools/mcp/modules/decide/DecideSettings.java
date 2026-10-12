package systems.grebe.devtools.mcp.modules.decide;

import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;

import systems.grebe.devtools.mcp.config.Home;
import systems.grebe.devtools.mcp.core.ModuleConfig;

/** Einstellungen des Moduls Entscheidungen. */
record DecideSettings(Mode mode, ModelFiles.Laya laya, String layaUrl, String llmUrl, Path modelsDir, boolean autoDownload,
                      double threshold, int threads, int maxLlmTokens) {

    /** Welche Modelle entscheiden. */
    enum Mode {
        /** Laya zuerst; ist es unsicherer als die Schwelle, wird das LLM hinzugenommen und kombiniert. */
        HYBRID,
        /** Nur Laya (Standard). */
        LAYA,
        /** Nur das LLM. */
        LLM;

        static Mode parse(String s) {
            return switch (s == null ? "" : s.toLowerCase(Locale.ROOT).strip()) {
                case "hybrid", "both" -> HYBRID;
                case "llm", "slow" -> LLM;
                default -> LAYA;
            };
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    static DecideSettings of(ModuleConfig c) {
        String dir = c.getString(DecideModule.MODELS_DIR, "").strip();
        Path models = dir.isEmpty() ? Home.defaultHome().resolve("models")
                : Path.of(dir.replaceFirst("^~(?=[/\\\\]|$)", Matcher.quoteReplacement(System.getProperty("user.home"))));
        int threshold = Math.max(0, Math.min(100, c.getInt(DecideModule.THRESHOLD, 80)));
        return new DecideSettings(Mode.parse(c.getString(DecideModule.MODE, "laya")),
                ModelFiles.Laya.parse(c.getString(DecideModule.LAYA_MODEL, "multilingual")),
                c.getString(DecideModule.LAYA_URL, "").strip(), c.getString(DecideModule.LLM_URL, "").strip(), models,
                c.getBoolean(DecideModule.AUTO_DOWNLOAD), threshold / 100.0, c.getInt(DecideModule.THREADS, 0),
                Math.max(256, c.getInt(DecideModule.MAX_LLM_TOKENS, 2048)));
    }

    boolean usesLaya(Mode m) {
        return m != Mode.LLM;
    }

    boolean usesLlm(Mode m) {
        return m != Mode.LAYA;
    }
}
