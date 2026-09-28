package systems.grebe.devtools.mcp.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import systems.grebe.devtools.mcp.modules.skills.SkillService;
import systems.grebe.devtools.mcp.modules.skills.SkillTestContext;
import systems.grebe.devtools.mcp.modules.skills.SkillViews;
import systems.grebe.devtools.mcp.modules.skills.SkillsPersistenceConfig;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Echte JavaFX-Ansicht gegen die echte H2-Datenbank: Änderungen über den Service (wie vom LLM) erscheinen ohne
 * Klick in der Übersicht. Wird übersprungen, wo kein JavaFX-Toolkit starten kann (z.B. CI ohne Display).
 */
class SkillsViewLiveUpdateTest {

    @TempDir
    static Path home;

    static boolean toolkit;

    @BeforeAll
    static void startToolkit() throws Exception {
        CountDownLatch up = new CountDownLatch(1);
        try {
            Platform.startup(up::countDown);
            toolkit = up.await(10, TimeUnit.SECONDS);
        } catch (IllegalStateException alreadyRunning) {
            toolkit = true;
        } catch (UnsupportedOperationException | Error noDisplay) {
            toolkit = false;
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void viewFollowsChangesMadeThroughTheService() throws Exception {
        Assumptions.assumeTrue(toolkit, "kein JavaFX-Toolkit verfügbar");
        try (var ctx = SkillTestContext.start(home)) {
            SkillService service = ctx.getBean(SkillService.class);
            AtomicReference<SkillsView> view = new AtomicReference<>();
            onFx(() -> {
                SkillsView v = new SkillsView(service, ctx.getBean(SkillsPersistenceConfig.Status.class));
                Stage stage = new Stage();
                stage.setScene(new Scene(v, 900, 500));
                stage.show();
                view.set(v);
            });
            TableView<SkillViews.Summary> table = (TableView<SkillViews.Summary>) view.get().lookup(".table-view");

            awaitRows(table, List.of());
            service.create("live-a", "Erster Skill.", "a", "testing", null, 5_000);
            awaitRows(table, List.of("live-a"));
            service.create("live-b", "Zweiter Skill.", "b", null, null, 5_000);
            awaitRows(table, List.of("live-b", "live-a")); // ohne Kategorie zuerst, wie skills_list
            service.patch("live-a", "a", "a2", null, null, null, null, 5_000);
            awaitRevision(table, "live-a", 2);
            service.delete("live-b");
            awaitRows(table, List.of("live-a"));
        }
    }

    private static void awaitRows(TableView<SkillViews.Summary> table, List<String> names) throws Exception {
        long end = System.currentTimeMillis() + 5_000;
        List<String> current = List.of();
        while (System.currentTimeMillis() < end) {
            current = fx(() -> table.getItems().stream().map(SkillViews.Summary::name).toList());
            if (current.equals(names)) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(current).as("Zeilen der Übersicht").containsExactlyElementsOf(names);
    }

    private static void awaitRevision(TableView<SkillViews.Summary> table, String name, int revision) throws Exception {
        long end = System.currentTimeMillis() + 5_000;
        int current = -1;
        while (System.currentTimeMillis() < end) {
            current = fx(() -> table.getItems().stream().filter(s -> s.name().equals(name))
                    .mapToInt(SkillViews.Summary::revision).findFirst().orElse(-1));
            if (current == revision) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(current).as("Revision von " + name).isEqualTo(revision);
    }

    private static void onFx(Runnable r) throws Exception {
        fx(() -> {
            r.run();
            return null;
        });
    }

    private static <T> T fx(java.util.concurrent.Callable<T> c) throws Exception {
        java.util.concurrent.FutureTask<T> task = new java.util.concurrent.FutureTask<>(c);
        Platform.runLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }
}
