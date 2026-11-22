package keyboardplus;

import bml.actions.ActionProvider;
import bml.actions.Actions;

/**
 * Registered with Framework: Actions and Menus. Starts the guard once Bitwig is up, and adds one
 * action (Settings > Shortcuts, Commander) that switches it off and on.
 */
public final class KeyboardPlusActions implements ActionProvider {
    public KeyboardPlusActions() {
    }

    @Override
    public void declare(Actions actions) {
        actions.action("keyboard_plus_toggle", "Toggle Keyboard+")
               .onRun(context -> KeyboardGuard.toggle());
        try {
            KeyboardGuard.start();
        } catch (Throwable t) {
            KeyboardGuard.MOD.log("could not start", t);
        }
    }
}
