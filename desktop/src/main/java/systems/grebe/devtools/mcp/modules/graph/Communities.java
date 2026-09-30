package systems.grebe.devtools.mcp.modules.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Community-Erkennung nach Louvain (Modularität, gewichteter ungerichteter Graph), deterministisch: Knoten werden
 * in fester Reihenfolge besucht, Gleichstände zugunsten der kleineren Community-ID entschieden.
 *
 * <p>graphify verwendet Leiden; Louvain liefert auf Code-Graphen vergleichbare Gruppen und kommt ohne Zusatzbibliothek
 * aus. Gruppiert wird rein über die Kantendichte, ohne Embeddings.
 */
final class Communities {

    private static final int MAX_LEVELS = 10;
    private static final int MAX_PASSES = 20;

    private Communities() {
    }

    /**
     * @param n       Anzahl Knoten (0..n-1)
     * @param weights ungerichtete Kanten als {@code a<<32|b} (a &lt; b) → Gewicht
     * @return Community je Knoten, nach Größe absteigend durchnummeriert (0 = größte)
     */
    static int[] detect(int n, Map<Long, Double> weights) {
        int[] membership = new int[n];
        for (int i = 0; i < n; i++) {
            membership[i] = i;
        }
        if (n == 0) {
            return membership;
        }
        int size = n;
        Map<Long, Double> w = weights;
        for (int level = 0; level < MAX_LEVELS; level++) {
            int[] local = oneLevel(size, w);
            int groups = 0;
            for (int c : local) {
                groups = Math.max(groups, c + 1);
            }
            for (int i = 0; i < n; i++) {
                membership[i] = local[membership[i]];
            }
            if (groups == size) {
                break;
            }
            Map<Long, Double> agg = new HashMap<>();
            for (var e : w.entrySet()) {
                int a = local[(int) (e.getKey() >>> 32)];
                int b = local[(int) (e.getKey() & 0xffffffffL)];
                long key = a <= b ? ((long) a << 32) | b : ((long) b << 32) | a;
                agg.merge(key, e.getValue(), Double::sum);
            }
            w = agg;
            size = groups;
        }
        return renumberBySize(membership);
    }

    /** Eine Louvain-Phase: Knoten wandern in die Nachbar-Community mit dem größten Modularitätsgewinn. */
    private static int[] oneLevel(int n, Map<Long, Double> weights) {
        List<List<int[]>> adjIdx = new ArrayList<>(n);
        List<List<Double>> adjW = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            adjIdx.add(new ArrayList<>());
            adjW.add(new ArrayList<>());
        }
        double[] degree = new double[n];
        double[] self = new double[n];
        double total = 0;
        for (var e : weights.entrySet()) {
            int a = (int) (e.getKey() >>> 32);
            int b = (int) (e.getKey() & 0xffffffffL);
            double wt = e.getValue();
            if (a == b) {
                self[a] += wt;
                degree[a] += 2 * wt;
            } else {
                adjIdx.get(a).add(new int[] {b});
                adjW.get(a).add(wt);
                adjIdx.get(b).add(new int[] {a});
                adjW.get(b).add(wt);
                degree[a] += wt;
                degree[b] += wt;
            }
            total += wt;
        }
        int[] comm = new int[n];
        double[] commTotal = new double[n];
        for (int i = 0; i < n; i++) {
            comm[i] = i;
            commTotal[i] = degree[i];
        }
        if (total == 0) {
            return compact(comm);
        }
        double m2 = 2 * total;
        Map<Integer, Double> linksTo = new HashMap<>();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean moved = false;
            for (int i = 0; i < n; i++) {
                linksTo.clear();
                List<int[]> nb = adjIdx.get(i);
                List<Double> nw = adjW.get(i);
                for (int k = 0; k < nb.size(); k++) {
                    linksTo.merge(comm[nb.get(k)[0]], nw.get(k), Double::sum);
                }
                int own = comm[i];
                commTotal[own] -= degree[i];
                double bestGain = linksTo.getOrDefault(own, 0.0) - commTotal[own] * degree[i] / m2;
                int best = own;
                for (var e : linksTo.entrySet()) {
                    double gain = e.getValue() - commTotal[e.getKey()] * degree[i] / m2;
                    if (gain > bestGain + 1e-12 || (Math.abs(gain - bestGain) <= 1e-12 && e.getKey() < best)) {
                        bestGain = gain;
                        best = e.getKey();
                    }
                }
                commTotal[best] += degree[i];
                if (best != own) {
                    comm[i] = best;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
        return compact(comm);
    }

    private static int[] compact(int[] comm) {
        Map<Integer, Integer> ids = new HashMap<>();
        int[] out = new int[comm.length];
        for (int i = 0; i < comm.length; i++) {
            out[i] = ids.computeIfAbsent(comm[i], k -> ids.size());
        }
        return out;
    }

    private static int[] renumberBySize(int[] membership) {
        Map<Integer, Integer> sizes = new HashMap<>();
        Map<Integer, Integer> firstSeen = new HashMap<>();
        for (int i = 0; i < membership.length; i++) {
            sizes.merge(membership[i], 1, Integer::sum);
            firstSeen.putIfAbsent(membership[i], i);
        }
        List<Integer> order = new ArrayList<>(sizes.keySet());
        order.sort((a, b) -> sizes.get(b).equals(sizes.get(a))
                ? Integer.compare(firstSeen.get(a), firstSeen.get(b))
                : Integer.compare(sizes.get(b), sizes.get(a)));
        Map<Integer, Integer> rank = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            rank.put(order.get(i), i);
        }
        int[] out = new int[membership.length];
        for (int i = 0; i < membership.length; i++) {
            out[i] = rank.get(membership[i]);
        }
        return out;
    }
}
