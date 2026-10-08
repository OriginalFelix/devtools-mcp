package systems.grebe.devtools.mcp.core;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class XmlTest {

    private static Element root(String xml) {
        return Xml.parse(xml.getBytes(StandardCharsets.UTF_8)).getDocumentElement();
    }

    @Test
    void navigatesDirectChildrenOnly() {
        Element project = root("<project><name> Demo </name><deps><dep>a</dep><dep>b</dep></deps><empty> </empty></project>");
        assertThat(Xml.text(project, "name")).isEqualTo("Demo");
        assertThat(Xml.text(project, "empty")).isNull();
        assertThat(Xml.text(project, "fehlt")).isNull();
        assertThat(Xml.children(Xml.child(project, "deps"), "dep")).extracting(Element::getTextContent)
                .containsExactly("a", "b");
        assertThat(Xml.child(null, "x")).isNull();
        assertThat(Xml.children(null, "x")).isEmpty();
        assertThat(Xml.child(project, "dep")).isNull(); // nur direkte Kinder
    }

    @Test
    void rejectsDoctypeAndBrokenXml() {
        assertThatThrownBy(() -> root("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("Ungültiges XML");
        assertThatThrownBy(() -> root("<a><b></a>")).hasMessageStartingWith("Ungültiges XML");
    }

    @Test
    void parsesConcurrently() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var tasks = java.util.stream.IntStream.range(0, 64)
                    .mapToObj(i -> pool.submit(() -> Xml.text(root("<a><b>" + i + "</b></a>"), "b"))).toList();
            for (int i = 0; i < tasks.size(); i++) {
                assertThat(tasks.get(i).get()).isEqualTo(String.valueOf(i));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
