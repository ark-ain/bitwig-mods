import bitwig.obf.DEl; // an automation point with a value and a curve
import bitwig.obf.GVR; // a lane's automation: its own curve and its automation clips
import bitwig.obf.Jsq; // an arranger clip
import bitwig.obf.PQT; // an automation curve: its points
import bitwig.obf.Tgk; // an automation lane
import bitwig.obf.YvI; // a point as the curve inserts it
import bitwig.obf.aL; // a value domain: plain value <-> 0..1
import bitwig.obf.big; // a view's selection
import bitwig.obf.hMP; // a point the curve holds
import bitwig.obf.jRs; // a point's kind (HOLD / LINEAR)
import bitwig.obf.la1; // an automation clip
import bitwig.obf.mdl; // a selected point as the detail editor shows it (wraps the point)
import bitwig.obf.qae; // a point as the curve removes and indexes it
import bitwig.obf.yiy; // an automation lane with its own automation
import bml.actions.ActionContext;
import com.bitwig.flt.document.core.master.IOJ; // an undoable change
import com.bitwig.flt.document.core.master.zwV; // a track
import com.bitwig.ramona.core.oDc; // a document object that undoable changes belong to
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which points the automation tools work on, and writing their result as one Undo step.
 *
 * Always one lane. The points can be: the selected points (in the arranger or the detail editor),
 * every point of one selected automation clip, or the points inside the time selection on the lane
 * it was made on, the lane's own and those of the automation clips the time selection crosses. Each
 * point keeps the curve it belongs to (the lane's or a clip's), its value is read and written through
 * that curve's value domain (so the tools see 0..1 of the lane), and its time is where it plays in
 * the arranger, so points of the lane and of its clips line up.
 */
final class AutomationsPlusToolsEdit {
    private static final double EPS = 1e-9;
    /** The right-click menus of selected automation points. */
    static final String ARRANGER_POINTS = "arranger_automation";
    static final String CLIP_POINTS = "clip_automation";
    /** The right-click menu of selected arranger clips (automation clips among them). */
    static final String ARRANGER_CLIPS = "arranger_clip";

    private AutomationsPlusToolsEdit() {
    }

    /** One point to work on: the point, the curve that holds it, and where it plays. */
    private record Entry(DEl point, PQT owner, double time) {
    }

    /**
     * The points a tool works on, sorted by time; or, when there are none it can work on, why
     * ({@code points} null).
     */
    static final class Target {
        final Tgk lane;
        final List<DEl> points;
        final List<PQT> owners;
        final aL[] storage; // each point's curve's value domain (plain <-> 0..1), or null
        final double[] times;
        final double[] values; // 0..1 of the lane
        final boolean[] hold;
        final double[] curves; // each point's curve: the shape of the segment after it (-1..1)
        final double start, end; // the span to show
        final double zero; // 0..1: the parameter's zero when it is bipolar, else 0
        final String problem;

        private Target(Tgk lane, List<Entry> entries, double start, double end, String problem) {
            this.lane = lane;
            this.problem = problem;
            int n = entries == null ? 0 : entries.size();
            points = entries == null ? null : new ArrayList<>();
            owners = new ArrayList<>();
            storage = new aL[n];
            times = new double[n];
            values = new double[n];
            hold = new boolean[n];
            curves = new double[n];
            Map<PQT, aL> domains = new IdentityHashMap<>();
            for (int i = 0; i < n; i++) {
                Entry e = entries.get(i);
                points.add(e.point());
                owners.add(e.owner());
                storage[i] = domains.computeIfAbsent(e.owner(), c -> {
                    try {
                        return c.bU_() instanceof aL d ? d : null; // the curve's point factory is its value domain
                    } catch (Throwable t) {
                        return null;
                    }
                });
                times[i] = e.time();
                values[i] = AutomationsPlusTools.clamp01(toNormalized(i, e.point().cfM())); // its value
                try {
                    curves[i] = e.point().dU_(); // its curve
                } catch (Throwable t) {
                    curves[i] = 0;
                }
                try {
                    hold[i] = e.point().FzB() == jRs.cfM; // its kind: HOLD
                } catch (Throwable t) {
                    hold[i] = false;
                }
            }
            double s = start, en = end;
            if (n > 0 && !(Double.isFinite(s) && Double.isFinite(en) && en > s)) {
                s = times[0];
                en = times[n - 1];
            }
            if (!(en > s)) en = s + 1; // a single point, or all at one time
            this.start = s;
            this.end = en;
            double z = 0;
            try {
                aL d = lane != null ? AutomationsPlus.valueDomain(lane) : null;
                if (d == null && n > 0) d = storage[0];
                if (d != null) {
                    double lo = Math.min(d.FzB(), d.UNj()), hi = Math.max(d.FzB(), d.UNj()); // its range
                    // Bipolar: the range runs from -x to +x (pan, pitch, bipolar modulation amounts).
                    if (lo < 0 && hi > 0 && Math.abs(lo + hi) <= 0.02 * (hi - lo)) z = AutomationsPlusTools.clamp01(d.cfM(0.0)); // where 0 sits
                }
            } catch (Throwable t) {
                AutomationsPlus.MOD.log("reading the parameter's range failed", t);
            }
            zero = z;
        }

        static Target refused(String problem) {
            return new Target(null, null, Double.NaN, Double.NaN, problem);
        }

        double toNormalized(int i, double plain) {
            if (storage[i] == null) return plain;
            try {
                return storage[i].cfM(plain); // plain value to 0..1
            } catch (Throwable t) {
                return plain;
            }
        }

        double toPlain(int i, double normalized) {
            if (storage[i] == null) return normalized;
            return storage[i].MRY(normalized); // 0..1 to plain value
        }

        /**
         * A curve may change only where the segment after the point stays in its curve: the next
         * kept point is in the same curve (the lane's, or the same clip's).
         */
        boolean curveApplies(int i, boolean[] keep) {
            for (int j = i + 1; j < points.size(); j++) {
                if (keep[j]) return owners.get(j) == owners.get(i);
            }
            return false; // the last point: its curve shapes what comes after the selection
        }
    }

    // ---------------------------------------------------------------------
    // Finding the points
    // ---------------------------------------------------------------------

    /** The points the action works on: the selected points or clip of the menu, else the time selection's. */
    static Target resolve(ActionContext context) {
        try {
            String kind = context.selectionKind();
            Object sel = context.selection();
            if ((ARRANGER_POINTS.equals(kind) || CLIP_POINTS.equals(kind)) && sel instanceof big selection)
                return fromSelectedPoints(selection);
            if (ARRANGER_CLIPS.equals(kind))
                return sel instanceof big selection ? fromSelectedClip(selection, context) : Target.refused("no clip is selected");
            return fromTimeSelection(context);
        } catch (Throwable t) {
            AutomationsPlus.MOD.log("finding the automation points failed", t);
            return Target.refused("the automation points couldn't be read");
        }
    }

    /** The curve a point belongs to: its automation clip's, else its lane's own. */
    static PQT curveOf(DEl point) {
        try {
            if (point.hUR(la1.class) instanceof la1 clip && clip.SVQ() != null) { // the clip's content
                PQT c = clip.SVQ().TD2(); // its curve
                if (holds(c, point)) return c;
            }
        } catch (Throwable ignored) {
            // try the lane
        }
        try {
            if (point.hUR(yiy.class) instanceof yiy own && own.LaE() != null) { // its lane's automation
                PQT c = own.LaE().tSS(); // the lane's own curve
                if (holds(c, point)) return c;
            }
        } catch (Throwable ignored) {
            // try its parents
        }
        try {
            if (point.hUR(PQT.class) instanceof PQT c) return c;
        } catch (Throwable ignored) {
            // none
        }
        return null;
    }

    private static boolean holds(PQT curve, Object point) {
        if (curve == null) return false;
        for (Object o : curve.cfM()) if (o == point) return true; // its points
        return false;
    }

    /** Where a clip plays content time {@code ct} first, between {@code a} and {@code b} (arranger time), or NaN. */
    static double firstPlay(la1 clip, double ct, double a, double b) {
        double start = clip.x3t(), end = clip.QWG(); // the clip's start and end
        double lo = Math.max(a, start), hi = Math.min(b, end);
        if (hi < lo - EPS) return Double.NaN;
        double base = start + (ct - clip.dE_()); // its play start
        double len = clip.sut() ? clip.LIK().nfq() : 0; // loop on: loop length
        int passes = len > EPS ? (int) Math.ceil((end - start) / len) + 2 : 0;
        for (int k = -1; k <= passes; k++) {
            if (len <= EPS && k != 0) continue;
            double t = base + k * len;
            if (t < lo - EPS || t > hi + EPS || t > end - EPS && end > start) continue;
            if (Math.abs(AutomationsPlusPointsEdit.clipTime(clip, t) - ct) < 1e-6) return t;
        }
        return Double.NaN;
    }

    /** The selected points, on one lane: the lane's own and its automation clips', where they play. */
    private static Target fromSelectedPoints(big selection) {
        List<Entry> entries = new ArrayList<>();
        Object lane = null;
        boolean several = false;
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
        for (Object o : selection.UNj().FzB()) { // the selected objects
            DEl point = null;
            Object itsLane = null;
            if (o instanceof mdl shown) { // the detail editor wraps each point
                if (shown.ODA() instanceof DEl d) point = d; // the point
                itsLane = shown.UNj(); // its lane
            } else if (o instanceof DEl d) {
                point = d;
            }
            if (point == null || seen.put(point, Boolean.TRUE) != null) continue;
            if (itsLane == null) itsLane = point.hUR(yiy.class);
            if (lane == null) lane = itsLane;
            else if (itsLane != null && itsLane != lane) several = true;
            PQT owner = curveOf(point);
            if (owner == null) continue;
            double t = point.x3t(); // its time in its curve
            if (point.hUR(la1.class) instanceof la1 clip && owner != laneCurve(itsLane)) {
                double at = firstPlay(clip, t, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY);
                t = Double.isFinite(at) ? at : clip.x3t() + (t - clip.dE_());
            }
            entries.add(new Entry(point, owner, t));
        }
        if (several) return Target.refused("the selected points are on more than one lane: select points on one lane");
        if (entries.isEmpty()) return Target.refused("no automation points are selected");
        entries.sort(Comparator.comparingDouble(Entry::time));
        return new Target(lane instanceof Tgk l ? l : null, entries, Double.NaN, Double.NaN, null);
    }

    private static PQT laneCurve(Object lane) {
        try {
            return lane instanceof yiy own && own.LaE() != null ? own.LaE().tSS() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The selected arranger clips. */
    static List<Jsq> clips(Object selection) {
        List<Jsq> clips = new ArrayList<>();
        try {
            if (selection instanceof big b) {
                for (Object o : b.UNj().FzB()) if (o instanceof Jsq clip) clips.add(clip); // the selected objects
            }
        } catch (Throwable t) {
            AutomationsPlus.MOD.log("reading the selected clips failed", t);
        }
        return clips;
    }

    /**
     * The points under one selected clip, as they play in the arranger between its start and end: for
     * an automation clip, the part of it the arranger plays, on its lane; for another clip (audio,
     * notes), the automation under it on the track's automation lane last used for a time selection.
     */
    private static Target fromSelectedClip(big selection, ActionContext context) {
        List<Jsq> clips = clips(selection);
        if (clips.isEmpty()) return Target.refused("no clip is selected");
        if (clips.size() > 1) return Target.refused("several clips are selected: select one");
        Jsq clip = clips.get(0);
        double a = clip.x3t(), b = clip.QWG(); // its start and end in the arranger
        if (!(b > a)) return Target.refused("the clip has no length");
        Tgk lane = null;
        if (clip instanceof la1 automationClip && automationClip.hUR(yiy.class) instanceof yiy own) lane = own; // its lane
        if (lane == null) {
            Tgk last = AutomationsPlus.lane(context);
            try {
                Object track = clip.hUR(zwV.class), laneTrack = last instanceof yiy l ? l.hUR(zwV.class) : null; // their tracks
                if (last != null && track != null && track == laneTrack) lane = last;
            } catch (Throwable ignored) {
                // no lane
            }
        }
        if (lane == null) return Target.refused("make a time selection on one of the track's automation lanes first, so the tools know which lane is under the clip");
        return fromRange(lane, a, b);
    }

    /**
     * The points inside the time selection on its lane, as they play: the lane's own points outside
     * automation clips, and the points of each automation clip the time selection crosses (a looped
     * clip's point once, where it first plays in the time selection).
     */
    private static Target fromTimeSelection(ActionContext context) {
        double[] range = AutomationsPlus.timeRange();
        Tgk lane = AutomationsPlus.lane(context);
        if (range == null || lane == null) return Target.refused("no points are selected and there is no time selection on an automation lane");
        return fromRange(lane, range[0], range[1]);
    }

    /** The points that play between a and b (arranger time) on a lane: its own and its clips'. */
    private static Target fromRange(Tgk lane, double a, double b) {
        if (!(lane instanceof yiy own) || own.LaE() == null) return Target.refused("the lane has no automation yet"); // its automation
        GVR automation = own.LaE();
        List<la1> clips = new ArrayList<>();
        for (Object event : automation.cfM()) { // its events: the automation clips
            if (event instanceof la1 clip && clip.x3t() < b + EPS && clip.QWG() > a - EPS) clips.add(clip); // start, end
        }
        List<Entry> entries = new ArrayList<>();
        PQT own_ = automation.tSS(); // the lane's own curve
        if (own_ != null) {
            for (Object o : own_.cfM()) {
                if (!(o instanceof DEl p)) continue;
                double t = p.x3t();
                if (t < a - EPS || t > b + EPS) continue;
                boolean underClip = false;
                for (la1 clip : clips) if (t >= clip.x3t() - EPS && t < clip.QWG() - EPS) underClip = true; // a clip plays there
                if (!underClip) entries.add(new Entry(p, own_, t));
            }
        }
        for (la1 clip : clips) {
            PQT curve = clip.SVQ() != null ? clip.SVQ().TD2() : null; // the clip's curve
            if (curve == null) continue;
            for (Object o : curve.cfM()) {
                if (!(o instanceof DEl p)) continue;
                double t = firstPlay(clip, p.x3t(), a, b);
                if (Double.isFinite(t)) entries.add(new Entry(p, curve, t));
            }
        }
        if (entries.isEmpty()) return Target.refused("there are no automation points in the time selection");
        entries.sort(Comparator.comparingDouble(Entry::time));
        return new Target(lane, entries, a, b, null);
    }

    // ---------------------------------------------------------------------
    // Writing the result
    // ---------------------------------------------------------------------

    /** Writes a tool's result into the points as one Undo step named after the tool. */
    static boolean apply(Target target, AutomationsPlusTools.Result result, String undoName) {
        int n = target.points == null ? 0 : target.points.size();
        if (n == 0 || result.values().length != n) return false;
        IOJ change = null;
        List<DEl> changed = new ArrayList<>();
        List<Double> oldPlain = new ArrayList<>();
        List<DEl> curved = new ArrayList<>();
        List<Double> oldCurve = new ArrayList<>();
        List<Object[]> removed = new ArrayList<>(); // point, curve, index
        try {
            if (!(AutomationsPlus.document(target.lane) instanceof oDc document))
                throw new IllegalStateException("the lane's document can't be found");
            change = new IOJ();
            if (!change.cfM(document, undoName)) { // begin
                change = null;
                throw new IllegalStateException("the Undo step couldn't be started");
            }
            for (int i = 0; i < n; i++) {
                if (!result.keep()[i]) continue;
                double now = result.values()[i];
                if (Math.abs(now - target.values[i]) < 1e-9) continue;
                DEl p = target.points.get(i);
                double before = p.cfM();
                p.cfM(target.toPlain(i, AutomationsPlusTools.clamp01(now))); // set its value
                changed.add(p);
                oldPlain.add(before);
            }
            for (int i = 0; i < n; i++) {
                if (!result.keep()[i] || !Double.isFinite(result.curves()[i]) || !target.curveApplies(i, result.keep())) continue;
                double c = Math.max(-1, Math.min(1, result.curves()[i]));
                if (Math.abs(c - target.curves[i]) < 1e-6) continue;
                DEl p = target.points.get(i);
                curved.add(p);
                oldCurve.add(target.curves[i]);
                p.MRY(c); // set its curve
            }
            for (int i = n - 1; i >= 0; i--) {
                if (result.keep()[i]) continue;
                DEl p = target.points.get(i);
                PQT curve = target.owners.get(i);
                int index = indexOf(curve, p);
                curve.cfM((qae) p); // remove
                removed.add(new Object[] {p, curve, index});
            }
            return true;
        } catch (Throwable t) {
            AutomationsPlus.MOD.log(undoName + " failed; undoing", t);
            for (int i = 0; i < changed.size(); i++) try { changed.get(i).cfM(oldPlain.get(i)); } catch (Throwable ignored) { }
            for (int i = 0; i < curved.size(); i++) try { curved.get(i).MRY(oldCurve.get(i)); } catch (Throwable ignored) { }
            for (int i = removed.size() - 1; i >= 0; i--) {
                try {
                    hMP p = (hMP) removed.get(i)[0];
                    PQT curve = (PQT) removed.get(i)[1];
                    int index = Math.max(0, Math.min((Integer) removed.get(i)[2], curve.cfM().size()));
                    curve.Hnt((YvI) p, index); // insert
                    curve.MRY((qae) p); // index it
                } catch (Throwable ignored) {
                    // logged above
                }
            }
            return false;
        } finally {
            if (change != null) {
                try {
                    change.MRY(); // finish the Undo step
                } catch (Throwable t) {
                    AutomationsPlus.MOD.log("finishing the Undo step failed", t);
                }
            }
        }
    }

    private static int indexOf(PQT curve, Object point) {
        List<?> all = curve.cfM();
        for (int i = 0; i < all.size(); i++) if (all.get(i) == point) return i;
        return all.size();
    }

    /** What the action was run on, for the log when it finds no points. */
    static String describe(ActionContext context) {
        StringBuilder b = new StringBuilder();
        try {
            b.append("menu ").append(context.menu()).append(", kind ").append(context.selectionKind());
            Object sel = context.selection();
            b.append(", selection ").append(sel == null ? "none" : sel.getClass().getName());
            if (sel instanceof big selection) {
                List<?> items = selection.UNj().FzB(); // the selected objects
                b.append(", ").append(items.size()).append(" selected:");
                for (int i = 0; i < Math.min(5, items.size()); i++) {
                    Object o = items.get(i);
                    b.append(' ').append(o == null ? "null" : o.getClass().getName());
                    DEl p = o instanceof DEl d ? d : o instanceof mdl m && m.ODA() instanceof DEl d2 ? d2 : null;
                    if (p != null) b.append(curveOf(p) != null ? "(curve found)" : "(no curve found)");
                }
            }
            double[] range = AutomationsPlus.timeRange();
            b.append(", time selection ").append(range == null ? "none" : range[0] + ".." + range[1]);
            b.append(", lane ").append(AutomationsPlus.lane(context) == null ? "none" : "found");
        } catch (Throwable t) {
            b.append(" (").append(t).append(')');
        }
        return b.toString();
    }
}
