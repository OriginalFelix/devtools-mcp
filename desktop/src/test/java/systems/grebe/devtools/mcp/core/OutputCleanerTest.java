package systems.grebe.devtools.mcp.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutputCleanerTest {

    @Test
    void stripsAnsiSequencesAndControlCharacters() {
        String colored = "\u001B[1;31mFEHLER\u001B[0m: \u001B]0;titel\u0007kaputt\u0008";
        assertThat(OutputCleaner.clean(colored)).isEqualTo("FEHLER: kaputt");
    }

    @Test
    void keepsOnlyFinalStateOfCarriageReturnProgressLines() {
        String progress = "Download  10%\r Download  50%\rDownload 100%\nfertig\r\n";
        assertThat(OutputCleaner.clean(progress)).isEqualTo("Download 100%\nfertig\n");
    }

    @Test
    void leavesPlainTextUntouched() {
        String plain = "a\n  b  \n\n\nc";
        assertThat(OutputCleaner.clean(plain)).isSameAs(plain);
    }

    @Test
    void compressesFrameworkFramesButKeepsFirstFrameAndApplicationFrames() {
        String trace = """
                java.lang.IllegalStateException: kaputt
                \tat com.example.Service.run(Service.java:42)
                \tat java.base/jdk.internal.reflect.DirectMethodHandleAccessor.invoke(DirectMethodHandleAccessor.java:103)
                \tat java.base/java.lang.reflect.Method.invoke(Method.java:580)
                \tat org.junit.platform.commons.util.ReflectionUtils.invokeMethod(ReflectionUtils.java:767)
                \tat com.example.ServiceTest.runs(ServiceTest.java:12)
                \tat org.gradle.api.internal.tasks.testing.junitplatform.JUnitPlatformTestClassProcessor.execute(X.java:1)
                \t... 42 more""";
        String out = OutputCleaner.compact(trace);
        assertThat(out).contains("com.example.Service.run(Service.java:42)")
                .contains("com.example.ServiceTest.runs(ServiceTest.java:12)")
                .contains("at … (3 Frames jdk.internal, java.lang, org.junit)")
                .contains("at … (1 Frame org.gradle)")
                .contains("... 42 more")
                .doesNotContain("ReflectionUtils");
    }

    @Test
    void firstFrameOfATraceStaysEvenIfItIsFrameworkCode() {
        String trace = "Exception\n\tat java.lang.reflect.Method.invoke(Method.java:1)\n\tat a.B.c(B.java:2)";
        assertThat(OutputCleaner.compact(trace)).contains("java.lang.reflect.Method.invoke");
    }

    @Test
    void collapsesRepeatedLinesAndBlankRuns() {
        String log = "\n\nstart\nretry\nretry\nretry\nretry\n\n \n\nende  \n\n";
        assertThat(OutputCleaner.compact(log)).isEqualTo("start\nretry\n… (vorige Zeile 4× in Folge)\n\nende  ");
    }

    @Test
    void twoEqualLinesStay() {
        assertThat(OutputCleaner.compact("x\nx\ny")).isEqualTo("x\nx\ny");
    }
}
