import bitwig.obf.bDS; // a boolean value
import bitwig.obf.dAd; // a toggle button
import bitwig.obf.dND; // a number field
import bitwig.obf.dOu; // a number formatter: number to text and back
import bitwig.obf.dWb; // a layout box: a row or a column
import bitwig.obf.dXp; // a separator line
import bitwig.obf.dtX; // Bitwig's modal dialog
import bitwig.obf.duY; // a label
import bitwig.obf.iok; // a colour
import bml.api.Settings;
import com.bitwig.base.geom.pCj; // a layout axis
import com.bitwig.graphics.SYo; // a graphics context
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The automation tools' dialog: Scale Levels, Normalize, Flip, Smooth and Decimate, after FL
 * Studio's envelope tools. The row of buttons at the top switches tools in place. The preview draws
 * the automation as it is (grey) and as the tool would leave it (orange, removed points red), with
 * Bitwig's own segment curves, redrawn as the settings change; nothing is written until OK, which
 * writes it as one Undo step. The parameter's zero is found automatically (the middle of the lane
 * for a bipolar parameter). Each tool remembers its settings.
 */
final class AutomationsPlusToolsDialog extends dtX {
    private static final long TOOL_WIDTH = 136, TOOL_GAP = 6, PREVIEW_HEIGHT = 300;
    private static final int SLOTS = 4;

    private final AutomationsPlusToolsEdit.Target target;
    private AutomationsPlusTools.Tool tool;
    private final Map<AutomationsPlusTools.Tool, List<Param>> params = new EnumMap<>(AutomationsPlusTools.Tool.class);
    private final List<Radio> toolGroup = new ArrayList<>();
    private final List<Radio> toolButtons = new ArrayList<>();
    private final dND[] slotFields = new dND[SLOTS];
    private final duY[] slotLabels = new duY[SLOTS];
    private final AutomationsPlusPointsDialog.Num blank = new AutomationsPlusPointsDialog.Num(0, 0, 1);
    private final bDS reset;
    private final Preview preview;
    private dWb buttons;
    private boolean applying;

    /** The tool the Automation Tools… entry opens: the last one used (Scale Levels at first). */
    static AutomationsPlusTools.Tool lastTool() {
        int i = AutomationsPlus.store().getInt("tools.last", 0);
        AutomationsPlusTools.Tool[] all = AutomationsPlusTools.Tool.values();
        return i >= 0 && i < all.length ? all[i] : AutomationsPlusTools.Tool.SCALE;
    }

    /** One setting: its value, label, unit, default and where it is remembered. */
    private static final class Param {
        final String key, label;
        final double min, max, def;
        final dOu format;
        final AutomationsPlusPointsDialog.Num value;

        Param(String key, String label, double min, double max, double def, String unit) {
            this.key = key;
            this.label = label;
            this.min = min;
            this.max = max;
            this.def = def;
            this.format = new UnitFormat("", unit);
            Settings s = AutomationsPlus.store();
            this.value = new AutomationsPlusPointsDialog.Num(Math.max(min, Math.min(max, s.getInt(key, (int) Math.round(def)))), min, max);
        }

        double get() {
            return value.getActual();
        }
    }

    AutomationsPlusToolsDialog(AutomationsPlusToolsEdit.Target target, AutomationsPlusTools.Tool tool) {
        super();
        this.target = target;
        this.tool = tool;
        for (AutomationsPlusTools.Tool t : AutomationsPlusTools.Tool.values()) params.put(t, new ArrayList<>());
        params.get(AutomationsPlusTools.Tool.SCALE).add(new Param("tools.scale.center", "Center", -100, 100, 0, "%"));
        params.get(AutomationsPlusTools.Tool.SCALE).add(new Param("tools.scale.tension", "Tension", -100, 100, 0, "%"));
        params.get(AutomationsPlusTools.Tool.SCALE).add(new Param("tools.scale.multiply", "Multiply", 0, 200, 100, "%"));
        params.get(AutomationsPlusTools.Tool.SCALE).add(new Param("tools.scale.offset", "Offset", -100, 100, 0, "%"));
        params.get(AutomationsPlusTools.Tool.SMOOTH).add(new Param("tools.smooth.amount", "Amount", 0, 100, 50, "%"));
        params.get(AutomationsPlusTools.Tool.DECIMATE).add(new Param("tools.decimate.remove", "Remove", 0, 100, 50, "%"));
        for (List<Param> list : params.values()) for (Param p : list) p.value.onChange(this::redraw);
        blank.setSettable(false);
        for (AutomationsPlusTools.Tool each : AutomationsPlusTools.Tool.values()) {
            toolButtons.add(new Radio(toolGroup, each == tool) {
                @Override
                public void setBoolValue(boolean value) {
                    boolean was = getBoolValue();
                    super.setBoolValue(value);
                    if (value && !was) setTool(each);
                }
            });
        }
        reset = new bDS(false) {
            @Override
            public void setBoolValue(boolean value) {
                super.setBoolValue(false); // a push button: it never stays on
                if (value) resetParams();
            }
        };
        List<Param> first = params.get(tool);
        for (int i = 0; i < SLOTS; i++) {
            slotFields[i] = AutomationsPlusPointsDialog.field(blank, AutomationsPlusPointsDialog.PERCENT);
            slotLabels[i] = duY.MIO(i < first.size() ? first.get(i).label : " "); // a label
        }
        preview = new Preview(this, TOOL_WIDTH * 5 + TOOL_GAP * 4, PREVIEW_HEIGHT);
        preview.init();
        bindSlots();
        AutomationsPlus.store().putInt("tools.last", tool.ordinal());
        zqN(); // set the dialog up
        J_();
        MJ("Automation Tools"); // its title
        BDm(true);
        jFL(true);
    }

    /** A row of buttons of which exactly one is on: choosing one unchooses the others. */
    private class Radio extends bDS {
        private final List<Radio> group;

        Radio(List<Radio> group, boolean initial) {
            super(initial);
            this.group = group;
            group.add(this);
        }

        void clear() {
            super.setBoolValue(false);
        }

        @Override
        public void setBoolValue(boolean value) {
            if (!value && getBoolValue()) return; // stays chosen until another is
            boolean changed = value != getBoolValue();
            super.setBoolValue(value);
            if (value && changed) {
                for (Radio r : group) if (r != this && r.getBoolValue()) r.clear();
            }
        }
    }

    /** Another tool, in place: the settings rows show its settings, the preview its result. */
    private void setTool(AutomationsPlusTools.Tool next) {
        if (next == tool) return;
        tool = next;
        AutomationsPlus.store().putInt("tools.last", tool.ordinal());
        bindSlots();
        redraw();
    }

    /** Shows the current tool's settings in the rows; the rows it doesn't use are empty. */
    private void bindSlots() {
        List<Param> list = params.get(tool);
        for (int i = 0; i < SLOTS; i++) {
            Param p = i < list.size() ? list.get(i) : null;
            dND field = slotFields[i];
            try {
                slotLabels[i].Grk(p == null ? " " : p.label); // its text
                if (p == null) {
                    field.Hnt(blank); // its value
                    field.cfM(new AutomationsPlusPointsDialog.BlankCurveParts()); // no text
                    field.LzO(true); // no input
                    field.Txc(0.0); // invisible
                } else {
                    field.Hnt(p.value);
                    field.cfM(new AutomationsPlusPointsDialog.FormatterParts(p.format, p.min, p.max)); // its text
                    field.FzB(p.format.jF_()); // its formatter for typing
                    field.LzO(false);
                    field.Txc(1.0);
                }
                field.jbi(); // repaint
                slotLabels[i].jbi();
            } catch (Throwable t) {
                AutomationsPlus.MOD.log("showing the settings failed", t);
            }
        }
    }

    private void resetParams() {
        for (Param p : params.get(tool)) p.value.setDoubleValue(p.def);
        redraw();
    }

    private double param(String key) {
        for (Param p : params.get(tool)) if (p.key.equals(key)) return p.get();
        throw new IllegalStateException(key);
    }

    /** What the tool would do with the current settings. */
    AutomationsPlusTools.Result compute() {
        AutomationsPlusToolsEdit.Target t = target;
        AutomationsPlusTools.Result r = switch (tool) {
            case SCALE -> AutomationsPlusTools.scale(t.values, t.zero, param("tools.scale.center"),
                    param("tools.scale.tension"), param("tools.scale.multiply"), param("tools.scale.offset"));
            case NORMALIZE -> AutomationsPlusTools.normalize(t.values, t.zero);
            case FLIP -> AutomationsPlusTools.flip(t.values);
            case SMOOTH -> AutomationsPlusTools.smooth(t.times, t.values, t.hold, t.curves, param("tools.smooth.amount"));
            case DECIMATE -> AutomationsPlusTools.decimate(t.times, t.values, t.hold, param("tools.decimate.remove"));
        };
        // a curve changes only where its segment stays in its curve (the lane's, or one clip's)
        double[] curves = r.curves().clone();
        for (int i = 0; i < curves.length; i++) if (Double.isFinite(curves[i]) && !t.curveApplies(i, r.keep())) curves[i] = Double.NaN;
        return new AutomationsPlusTools.Result(r.values(), r.keep(), curves);
    }

    private void redraw() {
        if (preview != null) preview.redraw();
    }

    /** Lays out the dialog's content: the tools, the preview, then the settings. */
    @Override
    public void Hnt(dWb root) {
        dWb outer = dWb.UNj(0); // a column
        dWb tools = dWb.IwV((int) TOOL_GAP); // a row: one button per tool
        AutomationsPlusTools.Tool[] all = AutomationsPlusTools.Tool.values();
        for (int i = 0; i < all.length; i++) tools.HL(toggle(toolButtons.get(i), all[i].title, TOOL_WIDTH));
        outer.HL(tools);
        outer.Hnt(8);
        outer.cfM(preview, 0.0f);
        outer.Hnt(8);
        dWb settings = dWb.IwV((int) TOOL_GAP); // a row under the preview: the settings, then Reset
        for (int i = 0; i < SLOTS; i++) {
            dWb cell = dWb.UNj(2); // a column: the setting's name above its field
            cell.HL(slotLabels[i]);
            cell.HL(slotFields[i]);
            settings.HL(new AutomationsPlusPointsDialog.FixedWidthBox(TOOL_WIDTH, cell));
        }
        dWb resetCell = dWb.UNj(2);
        resetCell.HL(duY.MIO(" "));
        resetCell.HL(toggle(reset, "Reset", TOOL_WIDTH));
        settings.HL(new AutomationsPlusPointsDialog.FixedWidthBox(TOOL_WIDTH, resetCell));
        outer.HL(settings);
        outer.Hnt(10);
        outer.HL(dXp.cfM(AutomationsPlusPointsDialog.ruleColour())); // a horizontal separator
        outer.Hnt(10);
        buttons = dWb.IwV(8);
        outer.HL(buttons);
        root.HL(outer);
    }

    private static dWb toggle(bDS value, String text, long width) {
        dAd button = dAd.cfM(value); // Bitwig's text toggle button
        button.IwV(text); // its text
        return new AutomationsPlusPointsDialog.FixedWidthBox(width, button);
    }

    /** Adds the buttons: right-aligned OK and Cancel, as in Bitwig's dialogs. */
    @Override
    public void cfM(dWb root) {
        dWb row = buttons != null ? buttons : root;
        row.JJY(); // stretchable space
        E3(row); // Bitwig's standard dialog buttons
        lYc(row);
        hUR(row);
        TMF(row);
        UNj(row);
        IwV(row);
        wGc(row);
        FzB(row);
    }

    /** OK: writes the result; the dialog closes only if that worked. */
    @Override
    public void kg_() {
        if (applying) return;
        applying = true;
        boolean ok = false;
        try {
            Settings s = AutomationsPlus.store();
            for (Param p : params.get(tool)) s.putInt(p.key, (int) Math.round(p.get()));
            s.putInt("tools.last", tool.ordinal());
            ok = AutomationsPlusToolsEdit.apply(target, compute(), "Automation: " + tool.title);
        } catch (Throwable t) {
            AutomationsPlus.MOD.log(tool.title + " failed", t);
        } finally {
            applying = false;
        }
        if (ok) super.kg_(); // close
    }

    /** A setting's text: a whole number and its unit ("25 %"). Typing takes the number. */
    static final class UnitFormat implements dOu {
        private static final java.util.regex.Pattern NUMBER = java.util.regex.Pattern.compile("-?\\d+(?:[.,]\\d+)?");
        private final String name, unit;

        UnitFormat(String name, String unit) {
            this.name = name;
            this.unit = unit;
        }

        @Override
        public String cfM(double v) { // format
            String n = String.format(Locale.ROOT, "%.0f", v);
            String text = n + (unit.isEmpty() ? "" : " " + unit);
            return name.isEmpty() ? text : name + "  " + text;
        }

        @Override
        public String cfM(double v, boolean a, boolean b) {
            return cfM(v);
        }

        @Override
        public double cfM(String s, double old) { // parse
            try {
                java.util.regex.Matcher m = NUMBER.matcher(s);
                return m.find() ? Double.parseDouble(m.group().replace(',', '.')) : old;
            } catch (Throwable e) {
                return old;
            }
        }
    }

    /**
     * The preview: the parameter's zero (dashed), the automation as it is (grey) and as the tool
     * would leave it (orange; a removed point is a red square), drawn with Bitwig's own segment
     * curves (hold points as steps).
     */
    static final class Preview extends dND {
        private static final iok CELL = iok.cfM(28, 28, 28);
        private static final iok GRID = iok.cfM(60, 60, 60);
        private static final iok BEFORE = iok.cfM(120, 120, 120);
        private static final iok AFTER = iok.cfM(255, 135, 19);
        private static final iok REMOVED = iok.cfM(220, 60, 60);
        private static final iok FRAME = iok.cfM(150, 150, 150);
        private final AutomationsPlusToolsDialog dialog;
        private final long width, height;

        Preview(AutomationsPlusToolsDialog dialog, long width, long height) {
            super();
            this.dialog = dialog;
            this.width = width;
            this.height = height;
        }

        void init() {
            ztx(); // initialise, as Bitwig's factory does for a plain number field
            J_();
            AutomationsPlusPointsDialog.Num dummy = new AutomationsPlusPointsDialog.Num(0, 0, 1);
            dummy.setSettable(false);
            Hnt(dummy); // its value (not used)
            cfM(new AutomationsPlusPointsDialog.BlankCurveParts()); // no text
            FzB(AutomationsPlusPointsDialog.PERCENT.jF_());
            try {
                LzO(true); // no input: it only shows
            } catch (Throwable ignored) {
                // cosmetic
            }
        }

        @Override
        public long TtY(pCj axis) { // minimum size
            return axis == pCj.cfM ? width : height;
        }

        @Override
        public long iHb(pCj axis) { // maximum size
            return axis == pCj.cfM ? width : height;
        }

        void redraw() {
            try {
                jbi(); // repaint
            } catch (Throwable ignored) {
                // cosmetic
            }
        }

        @Override
        public void cfM(SYo g) { // paint
            super.cfM(g); // Bitwig's border
            try {
                final double w = Sxo(), h = yiX(); // width, height
                if (w < 20 || h < 20) return;
                g.cfM(0.0, 0.0, w, h, FRAME); // a clearly visible frame...
                g.cfM(2.0, 2.0, w - 4.0, h - 4.0, CELL); // ...around the dark display
                final double pad = 12, x0 = pad, x1 = w - pad, top = pad, bottom = h - pad;
                AutomationsPlusToolsEdit.Target t = dialog.target;
                AutomationsPlusTools.Result r = dialog.compute();
                double zy = bottom - (bottom - top) * t.zero; // the parameter's zero
                for (double x = x0; x < x1; x += 8) g.cfM(x, zy, Math.min(4, x1 - x), 1, GRID);
                double[] afterCurves = new double[t.curves.length];
                for (int i = 0; i < afterCurves.length; i++) afterCurves[i] = Double.isFinite(r.curves()[i]) ? r.curves()[i] : t.curves[i];
                curve(g, t, t.values, t.curves, null, top, bottom, x0, x1, BEFORE, 1.5);
                curve(g, t, r.values(), afterCurves, r.keep(), top, bottom, x0, x1, AFTER, 2.0);
                double span = t.end - t.start;
                for (int i = 0; i < t.times.length; i++) {
                    double x = x0 + (x1 - x0) * ((t.times[i] - t.start) / span);
                    if (r.keep()[i]) g.cfM(x - 3, bottom - (bottom - top) * r.values()[i] - 3, 6, 6, AFTER);
                    else g.cfM(x - 3, bottom - (bottom - top) * t.values[i] - 3, 6, 6, REMOVED);
                }
            } catch (Throwable ignored) {
                // cosmetic
            }
        }

        /** The automation through the kept points, drawn with Bitwig's own segment shapes. */
        private static void curve(SYo g, AutomationsPlusToolsEdit.Target t, double[] v, double[] curves, boolean[] keep,
                                  double top, double bottom, double x0, double x1, iok colour, double width) {
            double span = t.end - t.start;
            g.nVd(); // new path
            int prev = -1;
            for (int i = 0; i < v.length; i++) {
                if (keep != null && !keep[i]) continue;
                double x = x0 + (x1 - x0) * ((t.times[i] - t.start) / span);
                double y = bottom - (bottom - top) * v[i];
                if (prev < 0) {
                    g.MRY(x0, y); // move to: the lane before the first point
                    g.cfM(x, y); // line to
                } else {
                    final int steps = 24;
                    for (int s = 1; s <= steps; s++) {
                        double tt = t.times[prev] + (t.times[i] - t.times[prev]) * s / steps;
                        double vv = AutomationsPlusTools.segment(tt, t.times[prev], t.times[i], v[prev], v[i], curves[prev], t.hold[prev]);
                        g.cfM(x0 + (x1 - x0) * ((tt - t.start) / span), bottom - (bottom - top) * vv);
                    }
                }
                prev = i;
            }
            if (prev < 0) return;
            g.cfM(x1, bottom - (bottom - top) * v[prev]);
            g.MRY(colour); // stroke colour
            g.IwV(width); // line width
            g.hbP(); // stroke
        }
    }
}
