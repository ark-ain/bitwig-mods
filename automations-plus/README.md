# Arranger: Automations+

Tools for drawing and reshaping automation in Bitwig Studio:

- **Add Automation Points**: two points over a time selection, with the values and curve you choose, from a dialog or in one keystroke;
- **Hold mode**: every new automation point is a hold point (step automation) until you switch it off;
- **Automation Tools**: Scale Levels, Normalize, Flip, Smooth and Decimate, with a live preview.

Every edit is one Undo step.

## Requirements

- Bitwig Studio 6.x with [Patchbay](https://github.com/shaboogen-dev/Patchbay). The mod is written against Bitwig 6.1.4; Patchbay translates it to the other versions and platforms it supports.
- **Framework: Actions and Menus** (comes with Patchbay).

## Install

Download `automations-plus-<version>.zip` from the [Releases](https://github.com/ark-ain/bitwig-mods/releases), then in Patchbay: **Mods → Add Mod…**, tick **Arranger: Automations+** and **Load**.

## Add Automation Points

Make a time selection on an automation lane (in the arranger, or in a clip's automation in the detail editor), then choose **Add Automation Points…** from:

- the time selection's right-click menu;
- the time-selection toolbar's dropdown;
- a shortcut (Settings > Shortcuts) or the Commander: it runs on the last time selection made on an automation lane.

The entry is shown only while there is such a time selection and its points have one place to go (see *Automation clips*).

### The dialog

- **Point 1** and **Point 2**: each can be switched on or off. Values are in the parameter's own units (dB, Hz, %…) and start at the automation's current values at the start and the end of the selection.
- **Hold**: both points are hold points: the automation keeps each point's value until the next point, where it jumps. With Hold and Point 1 on, the curve has no effect and is greyed out.
- **Curve**: the curve from Point 1 to Point 2, edited like an ADSR segment: drag it, Ctrl+click to type a value, double-click to reset it to 0%. Dragging up always bends the curve up, whichever way it runs.

On **OK**:

- with **both points** on, the points already inside the selection (edges included) are replaced by the two new ones;
- with **one point** on, it is added and existing points stay; with only Point 2, the curve is applied to the last existing point inside the selection;
- with **both off**, nothing changes.

A point is added after any point already at the same time. A lane with no automation yet gets it. The dialog opens with the choices last confirmed with **OK** (Point 1, Point 2, curve); Hold starts as Hold mode is.

### Without the dialog

**Add Automation Points** (no "…") adds the points at once, like Bitwig's Quantize beside Quantize…:

- Point 1 and Point 2 take the automation's values at the start and the end of the time selection;
- which points and the curve are the dialog's last choices (before the dialog is ever used: both points, curve 0%);
- the points are hold points when Hold mode is on.

It has no menu entry: give it a key in **Settings > Shortcuts**, or run it from the Commander.

### Automation clips

On an arranger automation lane, a time selection inside an **automation clip** edits the clip's own points, as Bitwig plays them: the selection is mapped into the clip's time (position, play start, loop). The clip's content changes, so a looped clip changes in every pass, and clips that share content change together.

A time selection only partly inside a clip, covering more than one clip, or crossing the end of a loop pass has no single place for its points: the entry isn't shown (from a shortcut or the Commander, nothing happens and the reason is logged). Outside clips, the lane's own automation is edited.

## Hold mode

**Toggle Hold for New Automation Points** switches Hold mode on and off. Give it a key in **Settings > Shortcuts**, or run it from the Commander. Bitwig's notification shows "New automation points: Hold" or "New automation points: Linear", and the mode is kept when Bitwig restarts.

With Hold mode on, new points are hold points:

- points made with the mouse: click, double-click, click-and-drag on a curve, and the pencil;
- points added by this mod: the dialog's Hold starts on, and Add Automation Points (no dialog) makes hold points.

Bitwig's own hold modifier is inverted: held while clicking or drawing, it makes a linear point. Moving an existing point never changes its type, and recorded automation isn't affected.

## Automation Tools

Inspired by FL Studio's automation clip tools. Right-click **selected automation points** (arranger or detail editor), a **selected clip** in the arranger, or a **time selection** on an automation lane (or use the time-selection toolbar's dropdown), then choose **Automation Tools…**. The dialog opens on the last tool used; the buttons at its top switch tools.

| Tool | Settings | What it does |
|---|---|---|
| **Scale Levels** | Center, Tension, Multiply (0–200 %), Offset | Scales the levels around a center (0 = the parameter's zero, -100 = bottom of the lane, 100 = top). Tension bends them away from (positive) or towards (negative) the center; Offset moves them all up or down. |
| **Normalize** | | Scales the levels around the parameter's zero so the one furthest from it reaches the edge of the lane. |
| **Flip** | | Turns the levels upside down. |
| **Smooth** | Amount (0–100 %) | A low-pass filter: removes fast movements and keeps slow ones, then removes the points that add little. Points don't move in time; the first and last points keep their values. |
| **Decimate** | Remove (0–100 %) | Removes that share of the points between the first and the last, spread evenly (50 % = one in two). Values don't change; each new segment gets the curve that follows the removed points best. |

- **One lane.** Selected points must all be on one lane (its own automation and its automation clips). A selected automation clip works on the part the arranger plays; another clip (audio, notes) works on the automation under it on the track's last-used automation lane. A time selection works on its lane, including the automation clips it crosses.
- **Zero is found automatically**: for a parameter running from -x to +x (pan, pitch…), zero is the middle of the lane, so Scale Levels and Normalize never push levels to the bottom.
- **Live preview**: the automation as it is (grey) and as the tool would leave it (orange, removed points in red). Nothing changes until **OK**; **Cancel** leaves everything as it was.
- **Reset** restores the tool's defaults; each tool remembers its settings.
- Each tool is also an action (**Automation Tools: …** in Settings > Shortcuts or the Commander); from a shortcut it works on the time selection.

## Actions

No action has a key by default: assign them in **Settings > Shortcuts**.

| Action | Also in |
|---|---|
| Add Automation Points… | time-selection menu and toolbar |
| Add Automation Points | Commander only |
| Toggle Hold for New Automation Points | Commander only |
| Automation Tools… | point, clip and time-selection menus |
| Automation Tools: Scale Levels / Normalize / Flip / Smooth / Decimate | Commander only |

Settings and last choices are kept in `mods/automations-plus/settings.properties` in Patchbay's settings folder (`~/.config/bml` on Linux).

## Credits

Based on [Add Automation Points](https://github.com/shaboogen-dev/Mods) by shaboogen (MIT licence). Automations+ has its own mod ID (`automations-plus`), so it can be installed beside the original; enable only one of the two.

Unofficial community project, not affiliated with Bitwig GmbH.
