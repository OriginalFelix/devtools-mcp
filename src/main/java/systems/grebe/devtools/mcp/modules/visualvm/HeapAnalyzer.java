package systems.grebe.devtools.mcp.modules.visualvm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

import org.graalvm.visualvm.lib.jfluid.heap.ArrayItemValue;
import org.graalvm.visualvm.lib.jfluid.heap.FieldValue;
import org.graalvm.visualvm.lib.jfluid.heap.GCRoot;
import org.graalvm.visualvm.lib.jfluid.heap.Heap;
import org.graalvm.visualvm.lib.jfluid.heap.HeapFactory;
import org.graalvm.visualvm.lib.jfluid.heap.HeapSummary;
import org.graalvm.visualvm.lib.jfluid.heap.Instance;
import org.graalvm.visualvm.lib.jfluid.heap.JavaClass;
import org.graalvm.visualvm.lib.jfluid.heap.ObjectFieldValue;
import org.graalvm.visualvm.lib.jfluid.heap.Value;
import systems.grebe.devtools.mcp.modules.java.ArtifactStore;

/** Heap-Dump-Analyse mit der Heap-Engine von VisualVM (org-graalvm-visualvm-lib-jfluid-heap). */
public final class HeapAnalyzer {

    private final Heap heap;
    private final Path file;

    public HeapAnalyzer(Path file) {
        this.file = file;
        try {
            this.heap = HeapFactory.createHeap(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Heap-Dump nicht lesbar: " + e.getMessage(), e);
        }
    }

    /** Übersicht, Histogramm und – wenn gewünscht – größte Objekte mit Pfad zur GC-Wurzel. */
    public String overview(int topClasses, int topObjects, String classFilter) {
        HeapSummary s = heap.getSummary();
        StringBuilder sb = new StringBuilder("Heap-Dump ").append(file.getFileName()).append('\n');
        sb.append("Lebende Objekte: ").append(s.getTotalLiveInstances()).append(", Größe (flach): ")
                .append(ArtifactStore.humanSize(s.getTotalLiveBytes())).append('\n');
        String jvm = heap.getSystemProperties() == null ? null : heap.getSystemProperties().getProperty("java.version");
        if (jvm != null) {
            sb.append("Java: ").append(jvm).append('\n');
        }
        Pattern filter = classFilter == null || classFilter.isBlank() ? null : Pattern.compile(classFilter);
        List<JavaClass> classes = new ArrayList<>(heap.getAllClasses());
        classes.removeIf(c -> c.getInstancesCount() == 0 || (filter != null && !filter.matcher(c.getName()).find()));
        classes.sort(Comparator.comparingLong(JavaClass::getAllInstancesSize).reversed());
        sb.append("\nKlassen nach flacher Größe (Anzahl, Bytes):\n");
        for (JavaClass c : classes.subList(0, Math.min(topClasses, classes.size()))) {
            sb.append(String.format("  %10d  %10s  %s\n", c.getInstancesCount(), ArtifactStore.humanSize(c.getAllInstancesSize()), c.getName()));
        }
        if (topObjects > 0) {
            sb.append("\nGrößte Objekte nach zurückgehaltenem Speicher (retained) mit Pfad zur GC-Wurzel:\n");
            List<Instance> biggest = heap.getBiggestObjectsByRetainedSize(topObjects * 3);
            int shown = 0;
            for (Instance i : biggest) {
                if (i.getJavaClass().getName().equals("java.lang.Class") && shown > 0) {
                    continue; // Klassenobjekte doppeln ihre statischen Felder
                }
                if (shown++ >= topObjects) {
                    break;
                }
                sb.append(String.format("\n  %s  %s#%d\n", ArtifactStore.humanSize(i.getRetainedSize()),
                        i.getJavaClass().getName(), i.getInstanceNumber()));
                sb.append(pathToRoot(i, 12));
            }
        }
        sb.append("\nDetails: visualvm_heap_analyze mit instance=<Klasse#Nr> · Klassenfilter mit classFilter");
        return sb.toString();
    }

    /** Pfad vom Objekt zur nächsten GC-Wurzel, inkl. Feldnamen. */
    String pathToRoot(Instance start, int maxDepth) {
        StringBuilder sb = new StringBuilder();
        Instance cur = start;
        for (int d = 0; cur != null && d < maxDepth; d++) {
            if (cur.isGCRoot()) {
                String kind = heap.getGCRoots(cur).stream().map(GCRoot::getKind).findFirst().orElse("?");
                sb.append("      ← GC-Wurzel (").append(kind).append(')');
                if (cur.getJavaClass().getName().equals("java.lang.Class")) {
                    sb.append(" Klasse ").append(classNameOf(cur));
                }
                sb.append('\n');
                return sb.toString();
            }
            Instance parent = cur.getNearestGCRootPointer();
            if (parent == null) {
                break;
            }
            sb.append("      ← ").append(describeRef(parent, cur)).append('\n');
            cur = parent;
        }
        return sb.append("      ← …\n").toString();
    }

    private String describeRef(Instance parent, Instance child) {
        String via = "?";
        if (parent.getJavaClass().getName().equals("java.lang.Class")) {
            JavaClass jc = heap.getJavaClassByID(parent.getInstanceId());
            if (jc != null) {
                for (Object o : jc.getStaticFieldValues()) {
                    if (o instanceof ObjectFieldValue ofv && ofv.getInstance() != null
                            && ofv.getInstance().getInstanceId() == child.getInstanceId()) {
                        return "static " + jc.getName() + "." + ofv.getField().getName();
                    }
                }
            }
        }
        for (FieldValue fv : parent.getFieldValues()) {
            if (fv instanceof ObjectFieldValue ofv && ofv.getInstance() != null
                    && ofv.getInstance().getInstanceId() == child.getInstanceId()) {
                via = "." + fv.getField().getName();
                break;
            }
        }
        if ("?".equals(via)) {
            for (Value v : child.getReferences()) {
                if (v instanceof ArrayItemValue a && a.getDefiningInstance().getInstanceId() == parent.getInstanceId()) {
                    via = "[" + a.getIndex() + "]";
                    break;
                }
            }
        }
        return parent.getJavaClass().getName() + "#" + parent.getInstanceNumber() + " " + via;
    }

    private String classNameOf(Instance classInstance) {
        JavaClass jc = heap.getJavaClassByID(classInstance.getInstanceId());
        return jc == null ? "?" : jc.getName();
    }

    /** Details einer Instanz: Felder, zurückgehaltene Größe, Pfad zur Wurzel. Format {@code Klasse#Nummer}. */
    public String instance(String spec) {
        int hash = spec.lastIndexOf('#');
        if (hash < 0) {
            throw new IllegalArgumentException("Format: Klasse#Nummer, z.B. java.util.HashMap#196");
        }
        JavaClass jc = heap.getJavaClassByName(spec.substring(0, hash).trim());
        if (jc == null) {
            throw new IllegalArgumentException("Klasse nicht im Heap: " + spec.substring(0, hash));
        }
        int nr = Integer.parseInt(spec.substring(hash + 1).trim());
        Instance inst = null;
        var it = jc.getInstancesIterator();
        while (it.hasNext()) {
            Instance i = (Instance) it.next();
            if (i.getInstanceNumber() == nr) {
                inst = i;
                break;
            }
        }
        if (inst == null) {
            throw new IllegalArgumentException("Instanz #" + nr + " von " + jc.getName() + " nicht gefunden.");
        }
        StringBuilder sb = new StringBuilder(jc.getName()).append('#').append(nr).append('\n');
        sb.append("Größe flach: ").append(inst.getSize()).append(" B, zurückgehalten: ")
                .append(ArtifactStore.humanSize(inst.getRetainedSize())).append('\n');
        sb.append("\nFelder:\n");
        int n = 0;
        for (FieldValue fv : inst.getFieldValues()) {
            if (n++ >= 60) {
                sb.append("  …\n");
                break;
            }
            String value;
            if (fv instanceof ObjectFieldValue ofv) {
                Instance ref = ofv.getInstance();
                value = ref == null ? "null" : ref.getJavaClass().getName() + "#" + ref.getInstanceNumber()
                        + " (" + ArtifactStore.humanSize(ref.getRetainedSize()) + ")" + stringValue(ref);
            } else {
                value = fv.getValue();
            }
            sb.append("  ").append(fv.getField().getName()).append(" = ").append(value).append('\n');
        }
        sb.append("\nPfad zur GC-Wurzel:\n").append(pathToRoot(inst, 20));
        return sb.toString();
    }

    private static String stringValue(Instance i) {
        if (!i.getJavaClass().getName().equals("java.lang.String")) {
            return "";
        }
        try {
            Object v = i.getValueOfField("value");
            if (v instanceof Instance arr && arr.getJavaClass().getName().equals("byte[]")) {
                List<?> vals = ((org.graalvm.visualvm.lib.jfluid.heap.PrimitiveArrayInstance) arr).getValues();
                StringBuilder s = new StringBuilder();
                for (Object o : vals.subList(0, Math.min(80, vals.size()))) {
                    s.append((char) Byte.parseByte(o.toString()));
                }
                return " \"" + s + (vals.size() > 80 ? "…" : "") + "\"";
            }
        } catch (RuntimeException ignored) {
            // anderes Layout
        }
        return "";
    }
}
