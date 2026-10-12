package systems.grebe.devtools.mcp.modules.decide;

import java.util.List;

/**
 * Wahrscheinlichkeiten aus Logits und Temperatur-Kalibrierung (Guo et al. 2017): eine Temperatur je Feld und Modell,
 * gefittet auf die Logits früherer Entscheidungen mit bekannter richtiger Antwort (Feedback), durch Minimieren der
 * negativen Log-Likelihood.
 */
final class Calibration {

    /** Ab so vielen Rückmeldungen wird eine eigene Temperatur gefittet. */
    static final int MIN_RECORDS = 6;
    static final double T_MIN = 0.25;
    static final double T_MAX = 10.0;

    /** Eine Rückmeldung: Roh-Logits des Modells und Index der richtigen Option. */
    record Sample(float[] logits, int correct) {
    }

    private Calibration() {
    }

    static double[] softmax(float[] logits, double temperature) {
        double max = Double.NEGATIVE_INFINITY;
        for (float l : logits) {
            max = Math.max(max, l / temperature);
        }
        double[] p = new double[logits.length];
        double sum = 0;
        for (int i = 0; i < p.length; i++) {
            p[i] = Math.exp(logits[i] / temperature - max);
            sum += p[i];
        }
        for (int i = 0; i < p.length; i++) {
            p[i] /= sum;
        }
        return p;
    }

    static int argmax(double[] p) {
        int best = 0;
        for (int i = 1; i < p.length; i++) {
            if (p[i] > p[best]) {
                best = i;
            }
        }
        return best;
    }

    /** 1 − H(p)/ln k: wie eindeutig die ganze Verteilung ist (1 = eindeutig, 0 = gleichverteilt). */
    static double certainty(double[] p) {
        if (p.length < 2) {
            return 1.0;
        }
        double h = 0;
        for (double v : p) {
            h -= v * Math.log(Math.max(1e-12, v));
        }
        return Math.max(0, Math.min(1, 1 - h / Math.log(p.length)));
    }

    static double nll(List<Sample> samples, double t) {
        double sum = 0;
        for (Sample s : samples) {
            double[] p = softmax(s.logits(), t);
            sum -= Math.log(Math.max(1e-12, p[s.correct()]));
        }
        return sum;
    }

    /** Gefittete Temperatur oder {@code NaN}, wenn es zu wenige Rückmeldungen gibt. */
    static double fit(List<Sample> samples) {
        if (samples.size() < MIN_RECORDS) {
            return Double.NaN;
        }
        // Goldener Schnitt über log T – die NLL ist in T unimodal
        double a = Math.log(T_MIN);
        double b = Math.log(T_MAX);
        double g = (Math.sqrt(5) - 1) / 2;
        double c = b - g * (b - a);
        double d = a + g * (b - a);
        double fc = nll(samples, Math.exp(c));
        double fd = nll(samples, Math.exp(d));
        for (int i = 0; i < 60; i++) {
            if (fc < fd) {
                b = d;
                d = c;
                fd = fc;
                c = b - g * (b - a);
                fc = nll(samples, Math.exp(c));
            } else {
                a = c;
                c = d;
                fc = fd;
                d = a + g * (b - a);
                fd = nll(samples, Math.exp(d));
            }
        }
        return Math.exp((a + b) / 2);
    }

    /** Rückmeldung mit den Logits beider Modelle zur selben Entscheidung. */
    record Pair(float[] laya, float[] llm, int correct) {
    }

    /** Normiertes gewichtetes geometrisches Mittel: p ∝ laya^(1−w) · llm^w. */
    static double[] combine(double[] laya, double[] llm, double w) {
        double[] p = new double[laya.length];
        double sum = 0;
        for (int i = 0; i < p.length; i++) {
            p[i] = Math.exp((1 - w) * Math.log(Math.max(1e-12, laya[i])) + w * Math.log(Math.max(1e-12, llm[i])));
            sum += p[i];
        }
        for (int i = 0; i < p.length; i++) {
            p[i] /= sum;
        }
        return p;
    }

    /** Gewicht des LLM in [0, 1] mit kleinster NLL der Kombination, oder {@code NaN} bei zu wenigen Paaren. */
    static double fitWeight(List<Pair> pairs, double tLaya, double tLlm) {
        if (pairs.size() < MIN_RECORDS) {
            return Double.NaN;
        }
        double best = 0.5;
        double bestNll = Double.POSITIVE_INFINITY;
        for (int step = 0; step <= 20; step++) {
            double w = step / 20.0;
            double nll = 0;
            for (Pair p : pairs) {
                double[] q = combine(softmax(p.laya(), tLaya), softmax(p.llm(), tLlm), w);
                nll -= Math.log(Math.max(1e-12, q[p.correct()]));
            }
            if (nll < bestNll - 1e-9) {
                bestNll = nll;
                best = w;
            }
        }
        return best;
    }

    /** Anteil richtiger Antworten (argmax) unter den Rückmeldungen. */
    static double accuracy(List<Sample> samples) {
        if (samples.isEmpty()) {
            return Double.NaN;
        }
        int ok = 0;
        for (Sample s : samples) {
            if (argmax(softmax(s.logits(), 1)) == s.correct()) {
                ok++;
            }
        }
        return (double) ok / samples.size();
    }

    static double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
