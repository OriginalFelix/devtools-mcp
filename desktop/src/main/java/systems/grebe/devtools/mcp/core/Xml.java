package systems.grebe.devtools.mcp.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Gehärtetes XML-Parsen (POMs, Maven-Metadaten, JUnit-Berichte) und Hilfen zum Navigieren im DOM: keine
 * DOCTYPE-Deklaration, keine externen Entitäten, sichere Verarbeitung.
 */
public final class Xml {

    private static final DocumentBuilderFactory FACTORY = factory();

    private Xml() {
    }

    private static DocumentBuilderFactory factory() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            f.setNamespaceAware(false);
            return f;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML-Parser nicht absicherbar: " + e.getMessage(), e);
        }
    }

    public static Document parse(byte[] xml) {
        return parse(new ByteArrayInputStream(xml));
    }

    /** Parst den Strom; der Aufrufer schließt ihn. @throws IllegalStateException bei ungültigem XML */
    public static Document parse(InputStream xml) {
        try {
            DocumentBuilder builder;
            synchronized (FACTORY) { // JAXP-Fabriken sind nicht als thread-sicher festgelegt, Builder gehören dem Aufrufer
                builder = FACTORY.newDocumentBuilder();
            }
            builder.setErrorHandler(null);
            return builder.parse(xml);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException("Ungültiges XML: " + e.getMessage(), e);
        }
    }

    /** Erstes direktes Kind-Element mit dem Namen oder {@code null}. */
    public static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (var n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && e.getTagName().equals(name)) {
                return e;
            }
        }
        return null;
    }

    /** Alle direkten Kind-Elemente mit dem Namen. */
    public static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        if (parent != null) {
            for (var n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element e && e.getTagName().equals(name)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    /** Getrimmter Text eines direkten Kind-Elements oder {@code null}. */
    public static String text(Element parent, String name) {
        Element e = child(parent, name);
        if (e == null) {
            return null;
        }
        String t = e.getTextContent().trim();
        return t.isEmpty() ? null : t;
    }
}
