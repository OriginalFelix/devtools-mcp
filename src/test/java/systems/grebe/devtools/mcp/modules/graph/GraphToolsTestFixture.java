package systems.grebe.devtools.mcp.modules.graph;

import java.nio.file.Files;
import java.nio.file.Path;

/** Beispielprojekt der Graph-Tests (Quellen unter src/main/java). */
final class GraphToolsTestFixture {

    private GraphToolsTestFixture() {
    }

    static void write(Path project) throws Exception {
        writeSource(project, "com/acme/shop/OrderService.java", """
                package com.acme.shop;

                import com.acme.shop.repo.OrderRepository;
                import java.util.List;

                /** Service für Aufträge. Speichert und lädt sie. */
                public class OrderService implements Service {
                    private final OrderRepository repo;
                    private final Printer printer = new Printer();

                    public OrderService(OrderRepository repo) {
                        this.repo = repo;
                    }

                    @Override
                    public void start() {
                        load(1L);
                    }

                    public Order load(long id) {
                        return repo.findById(id);
                    }

                    public void save(Order o) {
                        repo.save(o);
                        o.total().add(1);
                        validate(o);
                        Util.check(o);
                        printer.print(o);
                    }

                    private void validate(Order o) {
                    }

                    void chain(List<String> names) {
                        load(2).total();
                        names.get(0).frobnicate();
                        Runnable r = this::start;
                    }
                }

                interface Service {
                    void start();
                }
                """);
        writeSource(project, "com/acme/shop/Order.java", """
                package com.acme.shop;

                /** Ein Auftrag. */
                public record Order(int amount) {
                    Money total() {
                        return new Money();
                    }
                }

                class Money {
                    void add(int x) {
                    }
                }

                class Util {
                    static void check(Object o) {
                    }
                }

                class Printer {
                    void print(Order o) {
                    }

                    void print(String s) {
                    }
                }

                class Weird {
                    void frobnicate() {
                    }
                }
                """);
        writeSource(project, "com/acme/shop/repo/OrderRepository.java", """
                package com.acme.shop.repo;

                import com.acme.shop.Order;

                public interface OrderRepository {
                    Order findById(long id);

                    void save(Order o);
                }
                """);
        writeSource(project, "com/acme/shop/repo/JpaOrderRepository.java", """
                package com.acme.shop.repo;

                import com.acme.shop.Order;
                import jakarta.persistence.Entity;

                @Entity
                public class JpaOrderRepository implements OrderRepository {
                    public Order findById(long id) {
                        return null;
                    }

                    @Override
                    public void save(Order o) {
                        helper();
                    }

                    private void helper() {
                    }
                }
                """);
    }

    static void writeSource(Path project, String rel, String content) throws Exception {
        Path file = project.resolve("src/main/java").resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
