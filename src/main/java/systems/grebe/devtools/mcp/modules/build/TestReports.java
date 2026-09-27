package systems.grebe.devtools.mcp.modules.build;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Liest JUnit-XML-Berichte (Gradle: build/test-results, Maven: target/surefire- und failsafe-reports). */
final class TestReports {

    record Failure(String testClass, String testName, String kind, String message, String stackTrace) { }

    record Summary(int files, int tests, int failures, int errors, int skipped, double seconds, List<Failure> failed) {
        boolean ok() {
            return failures == 0 && errors == 0;
        }
    }

    private TestReports() {
    }

    /** Liest alle Berichte, die neuer als {@code since} sind ({@code null} = alle). */
    static Summary read(Path projectRoot, FileTime since) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(projectRoot, 8)) {
            walk.filter(p -> p.getFileName().toString().startsWith("TEST-") && p.getFileName().toString().endsWith(".xml"))
                    .filter(TestReports::inReportDir)
                    .filter(p -> since == null || modified(p).compareTo(since) >= 0)
                    .forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException("Testberichte nicht lesbar: " + e.getMessage(), e);
        }
        int tests = 0, failures = 0, errors = 0, skipped = 0;
        double seconds = 0;
        List<Failure> failed = new ArrayList<>();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        try {
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // nicht unterstützt
        }
        for (Path f : files) {
            try (InputStream in = Files.newInputStream(f)) {
                Document doc = dbf.newDocumentBuilder().parse(in);
                NodeList suites = doc.getElementsByTagName("testsuite");
                for (int s = 0; s < suites.getLength(); s++) {
                    Element suite = (Element) suites.item(s);
                    tests += intAttr(suite, "tests");
                    failures += intAttr(suite, "failures");
                    errors += intAttr(suite, "errors");
                    skipped += intAttr(suite, "skipped");
                    seconds += doubleAttr(suite, "time");
                }
                NodeList cases = doc.getElementsByTagName("testcase");
                for (int i = 0; i < cases.getLength(); i++) {
                    Element tc = (Element) cases.item(i);
                    for (Node child = tc.getFirstChild(); child != null; child = child.getNextSibling()) {
                        if (child instanceof Element el && (el.getTagName().equals("failure") || el.getTagName().equals("error"))) {
                            failed.add(new Failure(tc.getAttribute("classname"), tc.getAttribute("name"), el.getTagName(),
                                    el.getAttribute("message"), el.getTextContent()));
                        }
                    }
                }
            } catch (Exception e) {
                // defekte/unvollständige Datei überspringen
            }
        }
        return new Summary(files.size(), tests, failures, errors, skipped, seconds, failed);
    }

    private static boolean inReportDir(Path p) {
        String s = p.toString().replace('\\', '/');
        return s.contains("/build/test-results/") || s.contains("/target/surefire-reports/")
                || s.contains("/target/failsafe-reports/");
    }

    private static FileTime modified(Path p) {
        try {
            return Files.getLastModifiedTime(p);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    private static int intAttr(Element e, String name) {
        try {
            return Integer.parseInt(e.getAttribute(name));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static double doubleAttr(Element e, String name) {
        try {
            return Double.parseDouble(e.getAttribute(name).replace(",", ""));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
