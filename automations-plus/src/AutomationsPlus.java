import bitwig.obf.LEn; // an automation lane's document link
import bitwig.obf.Tgk; // an automation lane
import bitwig.obf.aL; // a parameter's value domain: its range, units and formatting
import bitwig.obf.big; // a view's selection
import bitwig.obf.bih; // the context of an automation time selection
import bitwig.obf.bnW; // a lane row, which shows an automation lane
import bitwig.obf.dve; // a widget
import bitwig.obf.eoW; // the modifier keys of a pointer event
import bitwig.obf.lkr; // an automation lane that exists in the document
import bitwig.obf.qiX; // a parameter's description, which holds its value domain
import bitwig.obf.wSr; // the automation editor's time selection
import bitwig.obf.wyB; // a time selection's range
import bitwig.obf.yiy; // an automation lane with its own automation
import bml.actions.ActionContext;
import bml.api.Arg;
import bml.api.At;
import bml.api.Bml;
import bml.api.Hook;
import bml.api.Settings;
import bml.api.This;
import com.bitwig.flt.app.CHR; // the application window controller (shows the transient notification)
import com.bitwig.flt.widget.core.timeline.XzI; // the timeline editor: decides insert vs hold-insert from the modifiers
import com.bitwig.flt.widget.core.timeline.interaction.AUY; // the pencil (draw) tool
import com.bitwig.flt.widget.core.timeline.interaction.brd; // the click-to-insert-point tool
import com.bitwig.ramona.core.AVL; // a document object, which can look up its ancestors
import com.bitwig.windowing_system.RYd; // the windowing system's modifier conventions
import bitwig.obf.dpN; // Bitwig's application: its UI event loop
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Add Automation Points: adds a point at the start and the end of an automation time selection,
 * with the values and the curve chosen in a dialog.
 *
 * Bitwig's time selection is followed through three hooks (its range, whether it is active, and
 * the lane it was made on); the action "Add Automation Points" is declared through the Action and
 * Menu framework ({@link AutomationsPlusActions}) and opens {@link AutomationsPlusPointsDialog}.
 *
 * The mod's classes are in Java's unnamed package, like Bitwig's obfuscated classes: the curve
 * field overrides a package-private method of Bitwig's number field, which only a class in the
 * same package can.
 */
public final class AutomationsPlus {
    static final String ACTION_ID = "automations_plus";
    static final String ACTION_LABEL = "Add Automation Points…"; // the dialog, as Bitwig names Quantize…
    static final String ACTION_MENU_LABEL = "Add Automation Points…";

    /** The action without the dialog: the dialog's last choices, at the automation's own values. */
    static final String DIRECT_ACTION_ID = "automations_plus_direct";
    static final String DIRECT_ACTION_LABEL = "Add Automation Points";

    /** Switches Hold mode for new automation points. */
    static final String TOGGLE_HOLD_ACTION_ID = "automations_plus_toggle_hold";
    static final String TOGGLE_HOLD_ACTION_LABEL = "Toggle Hold for New Automation Points";

    static final Bml.Mod MOD = Bml.mod("automations-plus");
    private static final Settings STORE = MOD.settings();

    /** This mod's settings (settings.properties), for the automation tools' last values. */
    static Settings store() {
        return STORE;
    }

    // ---------------------------------------------------------------------
    // Hold mode: new automation points are hold points
    // ---------------------------------------------------------------------

    private static final String HOLD_MODE = "hold_mode";
    private static volatile boolean holdMode = STORE.getBoolean(HOLD_MODE, false);

    /** Whether Hold mode is on (kept across restarts). */
    static boolean holdMode() {
        return holdMode;
    }

    /** The toggle action: switches Hold mode and says so in Bitwig's transient notification. */
    static void toggleHoldMode(ActionContext context) {
        boolean on = !holdMode;
        holdMode = on;
        STORE.putBoolean(HOLD_MODE, on);
        toast(on ? "New automation points: Hold" : "New automation points: Linear");
    }

    static void toast(String text) {
        try {
            dpN.TVZ().asyncExec(() -> { // the application's UI event loop
                try {
                    CHR app = CHR.Grk(); // the application window controller
                    if (app != null) app.hUR(text); // show the transient notification
                } catch (Throwable t) {
                    MOD.log("showing the notification failed", t);
                }
            });
        } catch (Throwable t) {
            MOD.log("showing the notification failed", t);
        }
    }

    /**
     * Bitwig makes a hold point instead of a linear one when its hold modifier is held. In Hold mode
     * that choice is inverted: points are hold points, and the modifier makes a linear one. These
     * hooks invert it where Bitwig's mouse tools read it.
     */

    /** The double-click insert's description ("Insert point" / "Insert hold point") asks this. */
    @Hook(target = XzI.class, method = "MRY(Lcom/bitwig/flt/widget/core/timeline/interaction/pwQ;LeoW;)LdoW;",
          at = At.REPLACE_CALL, callOwner = XzI.class, call = "UNj(LeoW;)Z", occurrence = 1, id = "hold-mode-double-click-description")
    public static boolean holdModifierDescription(eoW modifiers) {
        boolean held = XzI.UNj(modifiers);
        return holdMode ? !held : held;
    }

    /** Click-and-drag on a curve, which inserts a point and moves it, asks the same. */
    @Hook(target = com.bitwig.flt.widget.core.timeline.interaction.Hv3.class, method = "MRY(LdJI;)V",
          at = At.REPLACE_CALL, callOwner = XzI.class, call = "UNj(LeoW;)Z", occurrence = 1, id = "hold-mode-insert-and-move")
    public static boolean holdModifierInsertAndMove(eoW modifiers) {
        boolean held = XzI.UNj(modifiers);
        return holdMode ? !held : held;
    }

    /** The double-click insert's action asks the windowing system's modifier convention. */
    @Hook(target = XzI.class, method = "cfM(Lcom/bitwig/flt/widget/core/timeline/interaction/pwQ;LeoW;LdJH;)V",
          at = At.REPLACE_CALL, callOwner = RYd.class, call = "cfM(LeoW;)Z", occurrence = 1, id = "hold-mode-double-click")
    public static boolean holdModifierDoubleClick(RYd conventions, eoW modifiers) {
        boolean held = conventions.cfM(modifiers);
        return holdMode ? !held : held;
    }

    /** The click-to-insert tool reads the modifier once, when it starts (it also checks the curve has hold points). */
    @Hook(target = brd.class,
          method = "<init>(Lcom/bitwig/flt/widget/core/timeline/XzI;Lcom/bitwig/flt/widget/core/timeline/interaction/pwQ;LeoW;ZZLcom/bitwig/flt/widget/core/timeline/interaction/cuN;Z)V",
          at = At.REPLACE_CALL, callOwner = eoW.class, call = "wGc", occurrence = 1, id = "hold-mode-click-insert")
    public static boolean holdModifierClick(eoW modifiers) {
        boolean held = modifiers.wGc();
        return holdMode ? !held : held;
    }

    /**
     * Click-and-drag on a curve inserts a point and drags it (Bitwig's InsertAndMoveValueEventDragAction):
     * it reads the modifier once, when it starts, and also checks the curve has hold points.
     */
    @Hook(target = com.bitwig.flt.widget.core.timeline.interaction.InsertAndMoveValueEventDragAction.class,
          method = "<init>(Lcom/bitwig/flt/widget/core/timeline/XzI;Lcom/bitwig/flt/widget/core/timeline/interaction/pwQ;Lcom/bitwig/base/geom/DoublePoint;LeoW;Z)V",
          at = At.REPLACE_CALL, callOwner = eoW.class, call = "wGc", occurrence = 1, id = "hold-mode-insert-and-drag")
    public static boolean holdModifierInsertAndDrag(eoW modifiers) {
        boolean held = modifiers.wGc();
        return holdMode ? !held : held;
    }

    /** The pencil: Bitwig picks the hold pencil or the normal one by the modifier; Hold mode swaps them. */
    @Hook(target = AUY.class,
          method = "<init>(Lcom/bitwig/flt/widget/core/timeline/Bfy;Lcom/bitwig/flt/widget/core/timeline/interaction/pwQ;ZLcom/bitwig/flt/widget/core/timeline/interaction/cuN;)V",
          at = At.BEFORE_RETURN, id = "hold-mode-pencil")
    public static void holdModePencil(@This AUY pencil) {
        try {
            if (holdMode) pencil.IwV = !pencil.IwV; // its "draw hold points" flag
        } catch (Throwable t) {
            MOD.log("Hold mode for the pencil failed", t);
        }
    }

    // ---------------------------------------------------------------------
    // The dialog's last choices (settings.properties in this mod's folder of BML's settings
    // folder). The dialog opens with them, and Add Automation Points (no dialog) uses them, as
    // Bitwig's Quantize uses the settings last confirmed in Quantize…
    // ---------------------------------------------------------------------

    private static final String LAST_POINT1 = "last.point1";
    private static final String LAST_POINT2 = "last.point2";
    private static final String LAST_CURVE = "last.curve_permille";

    static boolean lastPoint1() { return STORE.getBoolean(LAST_POINT1, true); }
    static boolean lastPoint2() { return STORE.getBoolean(LAST_POINT2, true); }
    /** The curve, -1..1 (stored in thousandths). */
    static double lastCurve() { return Math.max(-1000, Math.min(1000, STORE.getInt(LAST_CURVE, 0))) / 1000.0; }

    /** Called when the dialog's OK adds the points. */
    static void rememberDialog(boolean point1, boolean point2, double curve) {
        STORE.putBoolean(LAST_POINT1, point1);
        STORE.putBoolean(LAST_POINT2, point2);
        STORE.putInt(LAST_CURVE, (int) Math.round(Math.max(-1, Math.min(1, curve)) * 1000));
    }

    private static volatile double from = Double.NaN;
    private static volatile double to = Double.NaN;
    private static volatile boolean active;
    private static volatile WeakReference<Tgk> currentLane = new WeakReference<>(null);

    private AutomationsPlus() {
    }

    // ---------------------------------------------------------------------
    // Bitwig's time selection
    // ---------------------------------------------------------------------

    /** A time selection's start is set. */
    @Hook(target = wyB.class, method = "UNj(D)V", at = At.ENTRY)
    public static void timeSelectionFrom(@Arg(0) double value) {
        from = value;
    }

    /** A time selection's end is set. */
    @Hook(target = wyB.class, method = "hUR(D)V", at = At.ENTRY)
    public static void timeSelectionTo(@Arg(0) double value) {
        to = value;
    }

    /** The automation editor's time selection is switched on or off. */
    @Hook(target = wSr.class, method = "PD3(Z)V", at = At.ENTRY)
    public static void timeSelectionActive(@Arg(0) boolean value) {
        active = value;
    }

    /** A time selection's context changes its range: remember the lane it is on. */
    @Hook(target = bih.class, method = "cfM(DD)V", at = At.ENTRY)
    public static void timeSelectionContext(@This bih context) {
        try {
            Tgk lane = asLane(context.LEd()); // what the time selection is on
            if (lane != null) currentLane = new WeakReference<>(lane);
        } catch (Throwable t) {
            MOD.log("following the time selection's lane failed", t);
        }
    }

    /** The time selection's start and end (arranger time), or null when there is none. */
    static double[] timeRange() {
        if (!hasTimeSelection()) return null;
        return new double[] {Math.min(from, to), Math.max(from, to)};
    }

    static boolean hasTimeSelection() {
        return active && Double.isFinite(from) && Double.isFinite(to) && Math.abs(to - from) > 1e-12;
    }

    // ---------------------------------------------------------------------
    // The action
    // ---------------------------------------------------------------------

    /**
     * Whether the action is shown in a menu: there is a time selection on an automation lane, and
     * its points have one place to go (the lane's own curve or one automation clip).
     */
    static boolean canRun(ActionContext context) {
        if (!hasTimeSelection()) return false;
        Tgk lane = lane(context);
        return lane != null && problem(lane) == null;
    }

    /** Opens the dialog for the time selection. */
    static void run(ActionContext context) {
        Tgk lane = lane(context);
        if (!hasTimeSelection() || lane == null) {
            MOD.log("no time selection on an automation lane; nothing to do");
            return;
        }
        String problem = problem(lane);
        if (problem != null) {
            MOD.log(problem + "; nothing to do");
            return;
        }
        if (!(context.widget() instanceof dve parent)) {
            MOD.log("no window to open the dialog in");
            return;
        }
        currentLane = new WeakReference<>(lane);
        double start = Math.min(from, to), end = Math.max(from, to);
        aL domain = valueDomain(lane);
        double startValue = valueAt(lane, start, 0.25);
        double endValue = valueAt(lane, end, 0.75);
        new AutomationsPlusPointsDialog(lane, domain, start, end, startValue, endValue).FzB(parent); // show it over the window
    }

    /**
     * Add Automation Points, without the dialog: Point 1 and Point 2 at the automation's values at
     * the start and the end of the time selection (the values the dialog opens with), with the
     * dialog's last choices (which points, curve, Hold), as one Undo step.
     */
    static void runDirect(ActionContext context) {
        Tgk lane = lane(context);
        if (!hasTimeSelection() || lane == null) {
            MOD.log("no time selection on an automation lane; nothing to do");
            return;
        }
        String problem = problem(lane);
        if (problem != null) {
            MOD.log(problem + "; nothing to do");
            return;
        }
        currentLane = new WeakReference<>(lane);
        double start = Math.min(from, to), end = Math.max(from, to);
        double startValue = valueAt(lane, start, 0.25); // as the dialog opens
        double endValue = valueAt(lane, end, 0.75);
        AutomationsPlusPointsEdit.apply(lane, start, end, lastPoint1(), lastPoint2(),
                startValue, endValue, lastCurve(), holdMode());
    }

    /** Why the time selection's points have no single place to go on a lane, or null when they have. */
    private static String problem(Tgk lane) {
        try {
            if (lane instanceof yiy own && own.LaE() != null) { // its automation
                double start = Math.min(from, to), end = Math.max(from, to);
                return AutomationsPlusPointsEdit.target(own.LaE(), start, end).problem();
            }
            return null; // a lane not created yet: no clips, its own curve
        } catch (Throwable t) {
            MOD.log("checking the time selection against automation clips failed", t);
            return "the automation clips couldn't be read";
        }
    }

    /**
     * The automation lane the action runs on: in a right-click menu, the lane that was clicked;
     * otherwise (and if that can't be found) the lane of the last time selection.
     */
    static Tgk lane(ActionContext context) {
        if (context.selection() != null) {
            Tgk clicked = null;
            if (context.widget() instanceof dve widget) clicked = laneOf(widget);
            if (clicked == null && context.selection() instanceof big selection) clicked = asLane(selection.LEd()); // what it is on
            if (clicked != null) return clicked;
        }
        return currentLane.get();
    }

    /** The automation lane a widget shows, from the models it (or a parent) holds. */
    private static Tgk laneOf(dve widget) {
        for (Class<?> type : List.of(Tgk.class, yiy.class, lkr.class, bnW.class)) {
            try {
                Tgk lane = asLane(widget.TMF(type)); // the widget's model of that type
                if (lane != null) return lane;
            } catch (Throwable ignored) {
                // not one this widget has
            }
        }
        return null;
    }

    private static Tgk asLane(Object o) {
        if (o instanceof Tgk lane) return lane;
        if (o instanceof bnW row) {
            Tgk lane = row.fJR(); // the lane it shows
            return lane != null ? lane : row.E3(); // or the lane it would create
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // The automated parameter
    // ---------------------------------------------------------------------

    /**
     * The automated parameter's value domain (dB, Hz, %...), as the Inspector shows a point's value:
     * from the parameter description of the lane or its automation, else the automation curve's own.
     */
    static aL valueDomain(Tgk lane) {
        List<Object> candidates = new ArrayList<>();
        candidates.add(lane);
        aL curveDomain = null;
        if (lane instanceof yiy own) try {
            candidates.add(own.d3()); // its parameter
            var automation = own.LaE(); // its automation
            if (automation != null) {
                candidates.add(automation);
                var curve = automation.tSS(); // the automation curve
                if (curve != null) {
                    candidates.add(curve);
                    for (Object point : curve.cfM()) { // its points
                        candidates.add(point);
                        if (candidates.size() > 24) break;
                    }
                    if (curve.bU_() instanceof aL d) curveDomain = d; // the curve's own domain
                }
            }
        } catch (Throwable t) {
            MOD.log("reading the lane's parameter failed", t);
        }
        for (Object o : candidates) {
            if (!(o instanceof AVL node)) continue;
            try {
                if (node.hUR(qiX.class) instanceof qiX description) { // itself or its nearest ancestor of that type
                    aL d = description.gfw(); // its value domain
                    if (d != null) return d;
                }
            } catch (Throwable ignored) {
                // try the next one
            }
        }
        return curveDomain;
    }

    /** The automation's value (0..1) at a time, without creating a lane that doesn't exist yet. */
    private static double valueAt(Tgk lane, double time, double fallback) {
        try {
            if (lane instanceof yiy own) {
                double v = own.cfM(time, false); // the automation's value there
                if (Double.isFinite(v)) return Math.max(0, Math.min(1, v));
            }
        } catch (Throwable t) {
            MOD.log("reading the automation's value failed", t);
        }
        return fallback;
    }

    /** The project document an automation lane belongs to (for its Undo step). */
    static Object document(Tgk lane) {
        if (lane instanceof yiy own) {
            LEn link = own.MYI(); // its document link
            if (link != null) return link.ce_(); // the project document
        }
        return null;
    }
}
