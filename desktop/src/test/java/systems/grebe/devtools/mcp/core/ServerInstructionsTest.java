package systems.grebe.devtools.mcp.core;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import static org.assertj.core.api.Assertions.assertThat;

class ServerInstructionsTest {

    @Test
    void combinesPreambleBaseTextAndModuleSectionsInModuleOrder() {
        String text = new ServerInstructions(List.of(
                module("zeta", "Zeta", 100, "Z-Hinweis"),
                module("alpha", "Alpha", 100, "A-Hinweis"),
                module("first", "First", 10, "F-Hinweis"),
                module("silent", "Silent", 50, "  ")),
                "Zusatz aus Properties").build();

        assertThat(text).startsWith(ServerInstructions.PREAMBLE.strip())
                .contains("Zusatz aus Properties", "## First – Tools `first_*`\nF-Hinweis")
                .doesNotContain("Silent");
        assertThat(text.indexOf("Zusatz")).isLessThan(text.indexOf("## First"));
        assertThat(text.indexOf("## First")).isLessThan(text.indexOf("## Alpha"));
        assertThat(text.indexOf("## Alpha")).isLessThan(text.indexOf("## Zeta"));
    }

    @Test
    void failingModuleIsSkippedWithoutBreakingTheOthers() {
        ToolModule broken = new StubModule("broken", "Broken", 1, null) {
            @Override
            public String instructions() {
                throw new IllegalStateException("kaputt");
            }
        };
        String text = new ServerInstructions(List.of(broken, module("ok", "Ok", 2, "läuft")), "").build();

        assertThat(text).contains("## Ok – Tools `ok_*`").doesNotContain("Broken", "kaputt");
    }

    private static ContextSettings context(boolean compact, boolean shellHintsOnce, boolean lazy) {
        return ContextSettings.of(true, ModuleConfig.of(List.of(), java.util.Map.of(
                ContextSettings.COMPACT_INSTRUCTIONS, String.valueOf(compact),
                ContextSettings.SHELL_HINTS_ONCE, String.valueOf(shellHintsOnce),
                ContextSettings.LAZY_TOOLS, String.valueOf(lazy))));
    }

    @Test
    void compactModeKeepsHeadingsWithBriefTextAndShellHintOnce() {
        ToolModule git = new StubModule("git", "Git", 10, "Lange Git-Anleitung mit Tabelle …") {
            @Override
            public String briefInstructions() {
                return "Alles über git_*.";
            }
        };
        ToolModule other = new StubModule("other", "Other", 20, "Langer Text. ".repeat(40)) {
            @Override
            public String description() {
                return "Macht z.B. Dinge. Und noch mehr.";
            }
        };
        ToolModule plugin = module("echo", "Echo", 30, "Kurzer Plugin-Hinweis.");
        String text = new ServerInstructions(List.of(git, other, plugin), List::of, () -> context(true, true, false),
                "").build();

        assertThat(text).contains(ServerInstructions.CONTEXT_RULES.strip())
                .contains("## Git – Tools `git_*`\nAlles über git_*. " + ShellHints.forModule("git"))
                .contains("## Other – Tools `other_*`\nMacht z.B. Dinge.\n")
                .contains("## Echo – Tools `echo_*`\nKurzer Plugin-Hinweis.")
                .doesNotContain("Lange Git-Anleitung", "Und noch mehr", "Langer Text",
                        ServerInstructions.LAZY_RULES.strip());
    }

    @Test
    void fullModeAppendsShellHintAfterModuleTextWhenHintsMoveOutOfDescriptions() {
        String text = new ServerInstructions(List.of(module("git", "Git", 10, "Git-Text")), List::of,
                () -> context(false, true, true), "").build();
        assertThat(text).contains("## Git – Tools `git_*`\nGit-Text\n" + ShellHints.forModule("git"))
                .contains(ServerInstructions.LAZY_RULES.strip());
    }

    @Test
    void guideReturnsFullModuleTextWithShellHint() {
        ServerInstructions si = new ServerInstructions(List.of(module("git", "Git", 10, "Git-Text")), List::of,
                () -> context(true, true, false), "");
        assertThat(si.guide("git")).contains("## Git – Tools `git_*`\nGit-Text\n" + ShellHints.forModule("git"));
        assertThat(si.guide("nope")).isEmpty();
    }

    @Test
    void firstSentenceIgnoresAbbreviations() {
        assertThat(ServerInstructions.firstSentenceOf("Liest z.B. Logs bzw. Dateien. Rest")).isEqualTo(
                "Liest z.B. Logs bzw. Dateien.");
    }

    @Test
    void shellHintsStripRemovesOnlyHintText() {
        assertThat(ShellHints.strip("Zeigt den Status." + ShellHints.GIT)).isEqualTo("Zeigt den Status.");
    }

    private static ToolModule module(String id, String name, int order, String instructions) {
        return new StubModule(id, name, order, instructions);
    }

    private static class StubModule implements ToolModule {
        private final String id;
        private final String name;
        private final int order;
        private final String instructions;

        StubModule(String id, String name, int order, String instructions) {
            this.id = id;
            this.name = name;
            this.order = order;
            this.instructions = instructions;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String displayName() {
            return name;
        }

        @Override
        public String description() {
            return "";
        }

        @Override
        public String instructions() {
            return instructions;
        }

        @Override
        public int order() {
            return order;
        }

        @Override
        public List<ToolCallback> createTools(ModuleConfig config) {
            return List.of();
        }
    }
}
