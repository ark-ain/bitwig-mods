import bml.actions.ActionProvider;
import bml.actions.Actions;
import bml.actions.Menu;

/** The action, declared through the Action and Menu framework. */
public final class AutomationsPlusActions implements ActionProvider {
    /** Bitwig's two kinds of time selection on automation lanes: in the arranger and in a clip. */
    private static final String ARRANGER = "arranger_automation_time_selection";
    private static final String CLIP = "clip_automation_time_selection";

    @Override
    public void declare(Actions actions) {
        actions.action(AutomationsPlus.ACTION_ID, AutomationsPlus.ACTION_LABEL)
               .menuLabel(AutomationsPlus.ACTION_MENU_LABEL)
               .in(Menu.selection(ARRANGER), Menu.selection(CLIP),
                   Menu.selectionToolbar(ARRANGER), Menu.selectionToolbar(CLIP))
               .visibleWhen(AutomationsPlus::canRun)
               .onRun(AutomationsPlus::run);
        // Without the dialog, as Bitwig's Quantize beside Quantize…: no menu entry; a shortcut
        // (Settings > Shortcuts) or the Commander. It runs on the last time selection made on an
        // automation lane, with the dialog's last choices at the automation's own values.
        actions.action(AutomationsPlus.DIRECT_ACTION_ID, AutomationsPlus.DIRECT_ACTION_LABEL)
               .onRun(AutomationsPlus::runDirect);
        // Hold mode: new automation points (mouse tools and this mod) are hold points.
        actions.action(AutomationsPlus.TOGGLE_HOLD_ACTION_ID, AutomationsPlus.TOGGLE_HOLD_ACTION_LABEL)
               .onRun(AutomationsPlus::toggleHoldMode);

        // Automation Tools (after FL Studio's envelope tools): one entry in the context menu, on the
        // selected automation points of one lane or on the points in the time selection. It opens the
        // last tool used; the dialog's top row switches tools.
        actions.action("automations_plus_tools", "Automation Tools…")
               .in(Menu.selection(AutomationsPlusToolsEdit.ARRANGER_POINTS), Menu.selection(AutomationsPlusToolsEdit.CLIP_POINTS),
                   Menu.selection(ARRANGER), Menu.selection(CLIP),
                   Menu.selectionToolbar(ARRANGER), Menu.selectionToolbar(CLIP),
                   Menu.selection(AutomationsPlusToolsEdit.ARRANGER_CLIPS))
               // in the clip menu when a clip is selected: the points under it
               .visibleWhen(context -> !AutomationsPlusToolsEdit.ARRANGER_CLIPS.equals(context.selectionKind())
                       || !AutomationsPlusToolsEdit.clips(context.selection()).isEmpty())
               .onRun(context -> openTool(context, AutomationsPlusToolsDialog.lastTool()));
        // Each tool as its own action, for a shortcut (Settings > Shortcuts) or the Commander.
        for (AutomationsPlusTools.Tool tool : AutomationsPlusTools.Tool.values()) {
            actions.action("automations_plus_tool_" + tool.name().toLowerCase(java.util.Locale.ROOT),
                           "Automation Tools: " + tool.title + "…")
                   .onRun(context -> openTool(context, tool));
        }
    }

    /** Opens a tool's dialog on the points the action finds, or logs why there are none. */
    private static void openTool(bml.actions.ActionContext context, AutomationsPlusTools.Tool tool) {
        try {
            AutomationsPlusToolsEdit.Target target = AutomationsPlusToolsEdit.resolve(context);
            if (target.points == null) {
                AutomationsPlus.MOD.log(tool.title + ": " + target.problem + "; nothing to do ("
                        + AutomationsPlusToolsEdit.describe(context) + ")");
                AutomationsPlus.toast(tool.title + ": " + target.problem);
                return;
            }
            if (!(context.widget() instanceof bitwig.obf.dve parent)) {
                AutomationsPlus.MOD.log("no window to open the dialog in");
                return;
            }
            new AutomationsPlusToolsDialog(target, tool).FzB(parent); // show it over the window
        } catch (Throwable t) {
            AutomationsPlus.MOD.log(tool.title + " failed", t);
        }
    }
}
