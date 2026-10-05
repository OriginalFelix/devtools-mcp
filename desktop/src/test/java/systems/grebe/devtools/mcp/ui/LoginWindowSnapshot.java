package systems.grebe.devtools.mcp.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.stage.Window;
import javax.imageio.ImageIO;
import org.springframework.context.ConfigurableApplicationContext;
import systems.grebe.devtools.mcp.DevToolsMcpApplication;
import systems.grebe.devtools.mcp.backend.account.AccountService;
import systems.grebe.devtools.mcp.backend.account.RoleService;
import systems.grebe.devtools.mcp.config.SettingsStore;
import systems.grebe.devtools.mcp.core.ToolInvocationLog;
import systems.grebe.devtools.mcp.core.ToolRegistry;
import systems.grebe.devtools.mcp.remote.BackendConnection;

/**
 * Manueller Sichttest (kein JUnit): startet die App ohne Anmeldung in einem frischen Ordner und speichert Screenshots
 * des Anmeldefensters (Einrichtung, Anmeldung), des Tabs „Benutzer“ und der Modulliste eines Benutzers mit
 * eingeschränkter Rolle (Argument: PNG-Datei, je Bild mit Suffix).
 */
public final class LoginWindowSnapshot {

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        System.setProperty("devtools.mcp.home", Files.createTempDirectory("login-snapshot").toString());
        ConfigurableApplicationContext ctx = DevToolsMcpApplication.startSpring(new String[] {"--server.port=0",
                "--devtools.local-user.email=snapshot@example.com"});
        BackendConnection backend = ctx.getBean(BackendConnection.class);
        ToolRegistry registry = ctx.getBean(ToolRegistry.class);
        String css = LoginWindowSnapshot.class.getResource("/ui/app.css").toExternalForm();
        CountDownLatch done = new CountDownLatch(1);
        Platform.setImplicitExit(false); // Fenster werden zwischendurch geschlossen
        Platform.startup(() -> {
            LoginWindow.show(backend, css, "", () -> { }, () -> { });
            new Thread(() -> {
                try {
                    sleep(1500);
                    shoot(out, "einrichten");
                    backend.embeddedAccounts().orElseThrow().setup("felix", "Felix", "", "passwort-123");
                    Platform.runLater(LoginWindowSnapshot::closeAll);
                    sleep(300);
                    Platform.runLater(() -> LoginWindow.show(backend, css,
                            "Anmeldung abgelaufen oder widerrufen – bitte neu anmelden.", () -> { }, () -> { }));
                    sleep(1200);
                    shoot(out, "anmelden");
                    Platform.runLater(LoginWindowSnapshot::closeAll);

                    backend.login("felix", "passwort-123");
                    RoleService roles = ctx.getBean(RoleService.class);
                    roles.create("Reviewer", "Nur lesend: Git-Status und Tickets",
                            List.of("module:git", "tool:sonar_issues", "projects.create"));
                    ctx.getBean(AccountService.class).create("rita", "Rita Reviewer", "rita@example.com",
                            List.of("Reviewer"), "passwort-456", true);
                    Platform.runLater(() -> {
                        UsersAdminView users = new UsersAdminView(backend, registry);
                        show(users, 1000, 560);
                        users.refresh();
                    });
                    sleep(1500);
                    shoot(out, "benutzer");
                    Platform.runLater(LoginWindowSnapshot::closeAll);

                    ctx.getBean(AccountService.class).resetPassword(
                            ctx.getBean(AccountService.class).userByName("rita").orElseThrow().id(), "passwort-789",
                            false);
                    backend.login("rita", "passwort-789");
                    Platform.runLater(() -> {
                        Stage stage = new Stage();
                        MainView main = new MainView(registry, ctx.getBean(ToolInvocationLog.class),
                                ctx.getBean(SettingsStore.class), "http://127.0.0.1:0/mcp", stage, List.of());
                        main.setUser("Benutzer: rita");
                        stage.setScene(scene(main, 1180, 700));
                        stage.show();
                    });
                    sleep(1500);
                    shoot(out, "module");
                } catch (RuntimeException e) {
                    e.printStackTrace();
                } finally {
                    Platform.runLater(done::countDown);
                }
            }).start();
        });
        boolean ok = done.await(60, TimeUnit.SECONDS);
        ctx.close();
        Platform.exit();
        System.out.println(ok ? "Screenshots: " + out : "Timeout");
        System.exit(ok ? 0 : 1);
    }

    private static void show(javafx.scene.Parent root, double w, double h) {
        Stage stage = new Stage();
        stage.setScene(scene(root, w, h));
        stage.show();
    }

    private static Scene scene(javafx.scene.Parent root, double w, double h) {
        Scene scene = new Scene(root, w, h);
        scene.getStylesheets().add(LoginWindowSnapshot.class.getResource("/ui/app.css").toExternalForm());
        return scene;
    }

    private static void closeAll() {
        List.copyOf(Window.getWindows()).forEach(Window::hide);
    }

    /** Alle offenen Fenster als PNG. */
    private static void shoot(Path out, String name) {
        CountDownLatch shot = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                int i = 0;
                for (Window w : List.copyOf(Window.getWindows())) {
                    if (w.isShowing() && w.getScene() != null) {
                        ImageIO.write(toAwt(w.getScene().snapshot(null)), "png",
                                new File(out.toString().replace(".png", "-" + name + (i++ == 0 ? "" : "-" + i)
                                        + ".png")));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                shot.countDown();
            }
        });
        try {
            shot.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static java.awt.image.BufferedImage toAwt(WritableImage img) {
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        java.awt.image.BufferedImage b = new java.awt.image.BufferedImage(w, h,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        PixelReader r = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                b.setRGB(x, y, r.getArgb(x, y));
            }
        }
        return b;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
