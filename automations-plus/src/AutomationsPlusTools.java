import java.util.Arrays;

/**
 * The automation tools' maths, after FL Studio's envelope tools: Scale Levels, Normalize, Flip,
 * Smooth and Decimate. Pure functions on the points' times and their values
 * normalized to 0..1 (the lane's height); no Bitwig names, so they are tested outside Bitwig.
 *
 * {@code zero} is where the parameter's zero sits on the lane (0..1): 0 for a unipolar parameter,
 * 0.5 for a bipolar one (pan, pitch). Scaling and normalizing work around it, so a bipolar
 * parameter is scaled towards its middle, not towards the bottom.
 */
final class AutomationsPlusTools {
    private AutomationsPlusTools() {
    }

    enum Tool {
        SCALE("Scale Levels"), NORMALIZE("Normalize"), FLIP("Flip"), SMOOTH("Smooth"), DECIMATE("Decimate");

        final String title;

        Tool(String title) {
            this.title = title;
        }
    }

    /** What a tool does to the points: their new values, and which points are kept. */
    record Result(double[] values, boolean[] keep, double[] curves) {
        /** Values and kept points; segment curves unchanged. */
        Result(double[] values, boolean[] keep) {
            this(values, keep, nanArray(values.length));
        }


        int kept() {
            int n = 0;
            for (boolean k : keep) if (k) n++;
            return n;
        }
    }

    private static double[] nanArray(int n) {
        double[] a = new double[n];
        Arrays.fill(a, Double.NaN);
        return a;
    }

    // ---------------------------------------------------------------------
    // Bitwig's segment curve
    // ---------------------------------------------------------------------

    /**
     * Bitwig's segment shape (its own formula): the fraction of the way from one point's value to the
     * next at the fraction {@code x} of the time between them, for the first point's curve {@code c}
     * (-1..1). 0 is a straight line; positive bends towards the next value early, negative late.
     */
    static double shape(double x, double c) {
        if (c == 0) return x;
        double cc = Math.max(-0.999, Math.min(0.999, c));
        double k = cc + 0.19345 * cc * cc * cc;
        double y1 = -k * x * x + (1 + k) * x;
        double y = -k * y1 * y1 + (1 + k) * y1;
        return clamp01(y);
    }

    /** The value at time {@code t} between two points, as Bitwig draws it. */
    static double segment(double t, double t0, double t1, double v0, double v1, double c, boolean hold) {
        if (hold) return t >= t1 ? v1 : v0;
        if (t >= t1) return v1;
        if (t <= t0 || t1 <= t0) return v0;
        return v0 + shape((t - t0) / (t1 - t0), c) * (v1 - v0);
    }

    /**
     * The curve (-0.999..0.999) whose shape best follows the fractions {@code ys} at {@code xs}
     * (least squares). The shape grows with the curve at every x, so the error has one minimum: a
     * golden-section search finds it.
     */
    static double fitCurve(double[] xs, double[] ys, int n) {
        if (n == 0) return 0;
        double lo = -0.999, hi = 0.999;
        final double g = (Math.sqrt(5) - 1) / 2;
        double a = hi - g * (hi - lo), b = lo + g * (hi - lo);
        double fa = sse(xs, ys, n, a), fb = sse(xs, ys, n, b);
        for (int it = 0; it < 80; it++) {
            if (fa < fb) {
                hi = b; b = a; fb = fa;
                a = hi - g * (hi - lo); fa = sse(xs, ys, n, a);
            } else {
                lo = a; a = b; fa = fb;
                b = lo + g * (hi - lo); fb = sse(xs, ys, n, b);
            }
        }
        double c = (lo + hi) / 2;
        return Math.abs(c) < 1e-4 ? 0 : c;
    }

    private static double sse(double[] xs, double[] ys, int n, double c) {
        double e = 0;
        for (int i = 0; i < n; i++) {
            double d = shape(xs[i], c) - ys[i];
            e += d * d;
        }
        return e;
    }

    static double clamp01(double x) {
        return Math.max(0, Math.min(1, x));
    }

    private static boolean[] all(int n) {
        boolean[] k = new boolean[n];
        Arrays.fill(k, true);
        return k;
    }

    // ---------------------------------------------------------------------
    // Scale Levels
    // ---------------------------------------------------------------------

    /**
     * Scale Levels. {@code center} (-100..100) moves the point the levels are scaled around: 0 is the
     * parameter's zero, -100 the bottom of the lane, 100 the top. {@code tension} (-100..100) bends
     * the levels around it (positive pushes them away from it, negative pulls them towards it, as a
     * logarithmic / exponential rescale). {@code multiply} (0..200 %) scales them around it.
     * {@code offset} (-100..100 %) then moves them all up or down by that share of the lane.
     */
    static Result scale(double[] values, double zero, double center, double tension, double multiply, double offset) {
        double z = clamp01(zero);
        double c = Math.max(-1, Math.min(1, center / 100.0));
        double pivot = c < 0 ? z + c * z : z + c * (1 - z);
        double exponent = Math.pow(2, -2 * Math.max(-1, Math.min(1, tension / 100.0)));
        double m = Math.max(0, Math.min(2, multiply / 100.0));
        double o = Math.max(-1, Math.min(1, offset / 100.0));
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            double d = clamp01(values[i]) - pivot;
            double span = d >= 0 ? 1 - pivot : pivot; // room on that side of the pivot
            if (span > 1e-12) {
                double r = Math.min(1, Math.abs(d) / span);
                d = Math.signum(d) * Math.pow(r, exponent) * span;
            }
            out[i] = clamp01(pivot + d * m + o);
        }
        return new Result(out, all(values.length));
    }

    // ---------------------------------------------------------------------
    // Normalize Levels
    // ---------------------------------------------------------------------

    /**
     * Normalize Levels: scales the levels around the parameter's zero so the one furthest from it
     * reaches the edge of the lane on its side (the top, or for a bipolar parameter the top or the
     * bottom). Levels all at zero are left as they are.
     */
    static Result normalize(double[] values, double zero) {
        double z = clamp01(zero);
        double reach = 0;
        for (double v : values) {
            double d = clamp01(v) - z;
            double span = d >= 0 ? 1 - z : z;
            if (span > 1e-12) reach = Math.max(reach, Math.abs(d) / span);
        }
        double[] out = new double[values.length];
        double factor = reach > 1e-9 ? 1 / reach : 1;
        for (int i = 0; i < values.length; i++) out[i] = clamp01(z + (clamp01(values[i]) - z) * factor);
        return new Result(out, all(values.length));
    }

    // ---------------------------------------------------------------------
    // Flip
    // ---------------------------------------------------------------------

    /**
     * Flip: turns the levels upside down on the lane (FL Studio's Flip vertically): the top becomes
     * the bottom, and a bipolar parameter's +x becomes -x. Curves stay: the shape of each segment is
     * relative to its two points, so it flips with them.
     */
    static Result flip(double[] values) {
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) out[i] = clamp01(1 - clamp01(values[i]));
        return new Result(out, all(values.length));
    }

    // ---------------------------------------------------------------------
    // Smooth
    // ---------------------------------------------------------------------

    /**
     * Smooth, with one setting, {@code amount} (0..100): a low-pass filter on the automation as it
     * plays. The automation is sampled finely in time (with Bitwig's own segment curves and hold
     * steps), its high frequencies are removed with a Gaussian filter (the higher the amount, the
     * lower the frequencies it keeps: 100 keeps only the slowest movement of the selection), then
     * each point takes the filtered value at its time and each segment the curve that best follows
     * the filtered automation between its points. Then the points that add little to it are removed
     * (within 4 % of the lane at 100, less below; the segments that replace them get the curve that
     * follows them). Points don't move in time and none is added; the first and last points stay and
     * keep their values, so the automation still joins what is before and after. Hold points stay
     * hold points.
     */
    static Result smooth(double[] times, double[] values, boolean[] hold, double[] curves, double amount) {
        double a = Math.max(0, Math.min(1, amount / 100.0));
        int n = values.length;
        if (a <= 0 || n < 3) return new Result(values.clone(), all(n));
        double t0 = times[0], t1 = times[n - 1], span = t1 - t0;
        if (!(span > 1e-9)) return new Result(values.clone(), all(n));
        final int samples = 4096;
        double dt = span / (samples - 1);
        double[] signal = new double[samples];
        int seg = 0;
        for (int k = 0; k < samples; k++) {
            double t = t0 + k * dt;
            while (seg < n - 2 && t >= times[seg + 1]) seg++;
            signal[k] = segment(t, times[seg], times[seg + 1], values[seg], values[seg + 1], curves[seg], hold[seg]);
        }
        // Gaussian low-pass. Its width (in time) grows with the amount on a log scale: from a third of
        // the points' average spacing (only the fastest wiggles go) to a sixth of the selection.
        double spacing = span / (n - 1);
        double narrow = spacing / 3, wide = Math.max(narrow, span / 6);
        double sigma = Math.max(0.5, narrow * Math.pow(wide / narrow, a) / dt);
        int radius = (int) Math.min(samples - 1, Math.ceil(3 * sigma));
        double[] kernel = new double[radius + 1];
        double sum = 0;
        for (int r = 0; r <= radius; r++) {
            kernel[r] = Math.exp(-0.5 * (r / sigma) * (r / sigma));
            sum += r == 0 ? kernel[r] : 2 * kernel[r];
        }
        double[] filtered = new double[samples];
        for (int k = 0; k < samples; k++) {
            double acc = kernel[0] * signal[k];
            for (int r = 1; r <= radius; r++) {
                acc += kernel[r] * (signal[Math.max(0, k - r)] + signal[Math.min(samples - 1, k + r)]); // ends held
            }
            filtered[k] = acc / sum;
        }
        double[] out = values.clone();
        for (int i = 1; i < n - 1; i++) out[i] = clamp01(sampleAt(filtered, t0, dt, times[i]));
        double[] newCurves = nanArray(n);
        final int fit = 16;
        double[] xs = new double[fit], ys = new double[fit];
        for (int i = 0; i < n - 1; i++) {
            double ta = times[i], tb = times[i + 1], dv = out[i + 1] - out[i];
            if (hold[i] || tb - ta <= 1e-9) continue;
            if (Math.abs(dv) < 1e-6) {
                newCurves[i] = 0;
                continue;
            }
            for (int s = 0; s < fit; s++) {
                double x = (s + 1.0) / (fit + 1.0);
                xs[s] = x;
                ys[s] = clamp01((sampleAt(filtered, t0, dt, ta + x * (tb - ta)) - out[i]) / dv);
            }
            newCurves[i] = fitCurve(xs, ys, fit);
        }
        // Simplify: drop the points that add little to the smoothed automation (the higher the
        // amount, the more go); the segments that replace them get the curve that follows them.
        Result simple = simplify(times, out, hold, 4 * a);
        double[] merged = newCurves.clone();
        for (int i = 0; i < n; i++) if (Double.isFinite(simple.curves()[i])) merged[i] = simple.curves()[i];
        return new Result(out, simple.keep(), merged);
    }

    /** The filtered automation at time t (linear between samples). */
    private static double sampleAt(double[] f, double t0, double dt, double t) {
        double k = (t - t0) / dt;
        if (k <= 0) return f[0];
        if (k >= f.length - 1) return f[f.length - 1];
        int i = (int) Math.floor(k);
        double frac = k - i;
        return f[i] + (f[i + 1] - f[i]) * frac;
    }

    // ---------------------------------------------------------------------
    // Decimate Points
    // ---------------------------------------------------------------------

    /**
     * Simplify (used by Smooth): removes the points the automation can do without. Between two points that
     * stay, the removed ones are followed by the segment's curve, fitted to them with Bitwig's own
     * curve shape; a point stays when no curve gets within {@code tolerance} (0..100 % of the lane: at 100 only the first and last points stay) of
     * it (Ramer-Douglas-Peucker, measured vertically). The first and last points always stay, values
     * don't change; the kept segments that replace removed points get their fitted curve.
     */
    static Result simplify(double[] times, double[] values, boolean[] hold, double tolerance) {
        int n = values.length;
        boolean[] keep = new boolean[n];
        double[] curves = nanArray(n);
        if (n == 0) return new Result(values.clone(), keep, curves);
        keep[0] = true;
        keep[n - 1] = true;
        double tol = Math.max(0, tolerance / 100.0);
        int[] stack = new int[2 * Math.max(2, n)];
        int sp = 0;
        stack[sp++] = 0;
        stack[sp++] = n - 1;
        double[] xs = new double[n], ys = new double[n];
        while (sp > 0) {
            int hi = stack[--sp], lo = stack[--sp];
            if (hi - lo < 2) continue;
            // the segment lo..hi replacing the points between: its curve, and its worst miss
            double t0 = times[lo], t1 = times[hi], v0 = values[lo], v1 = values[hi], dv = v1 - v0;
            boolean step = hold[lo];
            double c = 0;
            if (!step && t1 > t0 && Math.abs(dv) > 1e-9) {
                int m = 0;
                for (int i = lo + 1; i < hi; i++) {
                    xs[m] = (times[i] - t0) / (t1 - t0);
                    ys[m] = (values[i] - v0) / dv;
                    m++;
                }
                c = fitCurve(xs, ys, m);
            }
            double worst = -1;
            int at = -1;
            for (int i = lo + 1; i < hi; i++) {
                double model = segment(times[i], t0, t1, v0, v1, c, step);
                if (t1 <= t0) model = v0;
                double err = Math.abs(values[i] - model);
                if (err > worst) {
                    worst = err;
                    at = i;
                }
            }
            if (at >= 0 && worst > tol) {
                keep[at] = true;
                stack[sp++] = lo;
                stack[sp++] = at;
                stack[sp++] = at;
                stack[sp++] = hi;
            } else if (!step) {
                curves[lo] = c; // the curve of the segment that now runs from lo to hi
            }
        }
        return new Result(values.clone(), keep, curves);
    }

    /**
     * Decimate: removes {@code percent} (0..100) of the points between the first and the last, spread
     * evenly: 50 removes one point in two, 25 one in four, 33 one in three, 100 all of them (only the
     * first and last stay). Values don't change; each segment that now runs over removed points gets
     * the curve (Bitwig's own shape) that follows them best. A segment from a hold point stays a step.
     */
    static Result decimate(double[] times, double[] values, boolean[] hold, double percent) {
        int n = values.length;
        boolean[] keep = all(n);
        double[] curves = nanArray(n);
        if (n < 3) return new Result(values.clone(), keep, curves);
        double p = Math.max(0, Math.min(1, percent / 100.0));
        for (int k = 1; k <= n - 2; k++) {
            if (Math.floor(k * p + 1e-9) > Math.floor((k - 1) * p + 1e-9)) keep[k] = false; // one in 1/p
        }
        double[] xs = new double[n], ys = new double[n];
        int lo = 0;
        for (int hi = 1; hi < n; hi++) {
            if (!keep[hi]) continue;
            double t0 = times[lo], t1 = times[hi], v0 = values[lo], v1 = values[hi], dv = v1 - v0;
            if (hi - lo >= 2 && !hold[lo] && t1 > t0) {
                if (Math.abs(dv) < 1e-9) {
                    curves[lo] = 0;
                } else {
                    int m = 0;
                    for (int i = lo + 1; i < hi; i++) {
                        xs[m] = (times[i] - t0) / (t1 - t0);
                        ys[m] = (values[i] - v0) / dv;
                        m++;
                    }
                    curves[lo] = fitCurve(xs, ys, m);
                }
            }
            lo = hi;
        }
        return new Result(values.clone(), keep, curves);
    }

    // ---------------------------------------------------------------------
    // Filter
    // ---------------------------------------------------------------------

    /**
     * Filter: smooths the levels, each point becoming a weighted average of itself and the
     * {@code width} points on each side (closer points weigh more). {@code mix} (0..100 %) blends
     * the smoothed levels with the original ones. The first and last points don't move, so the
     * automation still joins what comes before and after.
     */
    static Result filter(double[] values, int width, double mix) {
        int n = values.length;
        double[] out = values.clone();
        int w = Math.max(0, width);
        double k = Math.max(0, Math.min(1, mix / 100.0));
        for (int i = 1; i < n - 1; i++) {
            double sum = 0, weight = 0;
            for (int j = Math.max(0, i - w); j <= Math.min(n - 1, i + w); j++) {
                double wt = w + 1 - Math.abs(i - j);
                sum += clamp01(values[j]) * wt;
                weight += wt;
            }
            double smooth = weight > 0 ? sum / weight : values[i];
            out[i] = clamp01(values[i] + (smooth - values[i]) * k);
        }
        return new Result(out, all(n));
    }

    /**
     * Smooth curves: the points stay where they are; each segment gets the curve that best follows
     * a smooth line through the points (a monotone cubic spline, so it never overshoots a point), so
     * the automation turns round its points instead of meeting them at an angle. {@code mix}
     * (0..100 %) blends the new curves with the old ones. Hold points and flat segments keep their
     * curve; so does the last point, whose curve shapes the segment after the selection.
     */
    static Result smoothCurves(double[] times, double[] values, boolean[] hold, double[] curves, double mix) {
        int n = values.length;
        double[] out = nanArray(n);
        if (n < 2) return new Result(values.clone(), all(n), out);
        double k = Math.max(0, Math.min(1, mix / 100.0));
        double[] slope = monotoneSlopes(times, values);
        final int samples = 24;
        double[] xs = new double[samples], ys = new double[samples];
        for (int i = 0; i < n - 1; i++) {
            double t0 = times[i], t1 = times[i + 1], v0 = values[i], v1 = values[i + 1], dv = v1 - v0, dt = t1 - t0;
            if (hold[i] || dt <= 1e-12 || Math.abs(dv) <= 1e-9) continue;
            for (int s = 0; s < samples; s++) {
                double x = (s + 1.0) / (samples + 1.0);
                double h00 = 2 * x * x * x - 3 * x * x + 1, h10 = x * x * x - 2 * x * x + x;
                double h01 = -2 * x * x * x + 3 * x * x, h11 = x * x * x - x * x;
                double v = h00 * v0 + h10 * dt * slope[i] + h01 * v1 + h11 * dt * slope[i + 1];
                xs[s] = x;
                ys[s] = clamp01((v - v0) / dv);
            }
            double fitted = fitCurve(xs, ys, samples);
            double old = Double.isFinite(curves[i]) ? curves[i] : 0;
            out[i] = old + (fitted - old) * k;
        }
        return new Result(values.clone(), all(n), out);
    }

    /** Fritsch-Carlson slopes: a cubic through the points with them never overshoots. */
    static double[] monotoneSlopes(double[] t, double[] v) {
        int n = v.length;
        double[] m = new double[n];
        if (n < 2) return m;
        double[] d = new double[n - 1];
        for (int i = 0; i < n - 1; i++) {
            double dt = t[i + 1] - t[i];
            d[i] = dt > 1e-12 ? (v[i + 1] - v[i]) / dt : 0;
        }
        m[0] = d[0];
        m[n - 1] = d[n - 2];
        for (int i = 1; i < n - 1; i++) m[i] = d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2;
        for (int i = 0; i < n - 1; i++) {
            if (Math.abs(d[i]) < 1e-12) {
                m[i] = 0;
                m[i + 1] = 0;
                continue;
            }
            double a = m[i] / d[i], b = m[i + 1] / d[i];
            if (a < 0) m[i] = 0;
            if (b < 0) m[i + 1] = 0;
            double r = a * a + b * b;
            if (r > 9) {
                double tau = 3 / Math.sqrt(r);
                m[i] = tau * a * d[i];
                m[i + 1] = tau * b * d[i];
            }
        }
        return m;
    }

    // ---------------------------------------------------------------------
    // Smooth Up
    // ---------------------------------------------------------------------

    /**
     * Smooth Up: removes abrupt spikes. A point further than {@code threshold} (0..100 % of the lane)
     * from the median of itself and the {@code width} points on each side is moved to that median;
     * the other points don't move. The first and last points don't move.
     */
    static Result smoothUp(double[] values, int width, double threshold) {
        int n = values.length;
        double[] out = values.clone();
        int w = Math.max(1, width);
        double th = Math.max(0, threshold / 100.0);
        for (int i = 1; i < n - 1; i++) {
            int lo = Math.max(0, i - w), hi = Math.min(n - 1, i + w);
            double[] window = Arrays.copyOfRange(values, lo, hi + 1);
            Arrays.sort(window);
            double median = window.length % 2 == 1 ? window[window.length / 2]
                    : (window[window.length / 2 - 1] + window[window.length / 2]) / 2;
            if (Math.abs(values[i] - median) > th) out[i] = clamp01(median);
        }
        return new Result(out, all(n));
    }
}
