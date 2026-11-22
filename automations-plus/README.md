# Arranger: Automations+

**Automations+** is based on shaboogen's [Add Automation Points](https://github.com/shaboogen-dev/Mods) (MIT licence, (c) 2026 shaboogen). It has its own mod ID (`automations-plus`) and action IDs, so it can be installed beside the original; you'll usually want only one of the two.

For Bitwig 6.1.4 (built against the Linux x64 build). Needs **Framework: Action and Menu Registration** 0.5.0 or later.

Compared with Add Automation Points 1.1.0-dev.2, it adds: a **Hold** toggle in the dialog, **Add Automation Points** without the dialog (as Bitwig's Quantize beside Quantize…), **Hold mode** for every new automation point, and **Automation Tools** after FL Studio's envelope tools.

Adds automation points at the start and the end of a time selection on an automation lane, with the values and the curve you choose, as one Undo step.

## If you used Add Automation Points before

Automations+ is a separate mod: untick or remove *Arranger: Add Automation Points* in Patchbay (both would add the same menu entries). Two things don't carry over:

- the dialog's last choices and Hold mode: copy `mods/add-automation-points/settings.properties` to `mods/automations-plus/` in BML's settings folder (`~/.config/bml` on Linux);
- shortcuts given to its actions: give them again in **Settings > Shortcuts** (the action IDs now start with `automations_plus`).

## Using it

Make a time selection on an automation lane (in the arranger, or in a clip's automation in the detail editor), then run **Add Automation Points…** (the dialog) from:

- **the time selection's right-click menu**;
- **the time-selection toolbar's dropdown**;
- **a shortcut** (Settings > Shortcuts) or **the Commander**: it runs on the last time selection made on an automation lane.

The menu entry is shown only while there is such a time selection and its points have one place to go (see below). Its action ID is `automations_plus`.

## Automation clips

On an arranger automation lane, a time selection inside an **automation clip** edits the clip's own points, as Bitwig plays them: the selection is mapped into the clip's time (its position, play start and loop). The clip's content is changed, so a looped clip changes in every pass, and clips that share content change together.

A time selection that is only partly inside an automation clip, covers more than one clip, or runs across the end of a clip's loop pass has no single place for its points, so the entry isn't shown (from a shortcut or the Commander, nothing happens and the reason is logged). Outside clips, the lane's own automation is edited.

## The dialog

- **Point 1** and **Point 2**: each can be switched on or off. Their values are in the automated parameter's own units (dB, Hz, %...) and start at the automation's current values at the start and the end of the selection.
- **Hold**: both added points are hold points: the automation keeps each point's value until the next point, where it jumps. With Hold and Point 1 on, the curve has no effect and is faded. 
- **Curve**: the curve from Point 1 to Point 2, edited like the curve of an ADSR segment: drag it, Ctrl+click to type a value, double-click to reset it to 0%. The preview follows the two values, so a falling range previews a falling curve, and dragging follows the preview: dragging up bends the curve up, whichever way it runs.

On **OK**:

- With **both points** on, the points already inside the selection (its edges included) are replaced by the two new ones.
- With **one point** on, it is added and the existing points stay. With only Point 2, the curve is applied to the last existing point inside the selection.
- With **both off**, nothing changes.

A point is added after any point already at the same time. If the lane has no automation yet, it is created. The whole edit is one Undo step, "Add Automation Points".

The dialog opens with the choices last confirmed with **OK**: Point 1, Point 2 and the curve; Hold starts as Hold mode is (below). The point values always start at the automation's current values.

## Without the dialog: Add Automation Points

As Bitwig's **Quantize** beside **Quantize…**, **Add Automation Points** (no "…") adds the points at once, with no dialog:

- Point 1 and Point 2 take the automation's values at the start and the end of the time selection (the values the dialog opens with);
- which points and the curve are the dialog's last choices confirmed with **OK** (before the dialog has been used: both points, curve 0%);
- the points are hold points when Hold mode is on.

It has no menu entry: give it a key in **Settings > Shortcuts**, or run it from the Commander. Like the dialog's shortcut, it runs on the last time selection made on an automation lane, as one Undo step. **Add Automation Points…** (the dialog) can have a key of its own too.

The last choices are kept in `mods/automations-plus/settings.properties` in BML's settings folder.

## Hold mode

**Toggle Hold for New Automation Points** switches Hold mode on and off; give it a key in **Settings > Shortcuts** (or run it from the Commander). Bitwig's transient notification says "New automation points: Hold" or "New automation points: Linear". Hold mode is kept when Bitwig restarts.

With Hold mode on, new points are hold points (the value stays until the next point, then jumps):

- points made with the mouse: click, double-click, click-and-drag on a curve, and the pencil;
- points from this mod: the dialog's Hold starts on, and Add Automation Points (no dialog) makes hold points.

Bitwig's own hold modifier is inverted: held while clicking or drawing, it makes a linear point. Moving an existing point never changes its type, and recorded automation is not changed. The mouse tools are Bitwig's timeline curve tools, so Hold mode also applies to other curves they edit that have hold points; a curve without hold points is unchanged for a click (Bitwig checks), as with Bitwig's own modifier.

## Automation Tools

After FL Studio's automation clip tools. Right-click **selected automation points** (in the arranger or the detail editor), a **selected clip** in the arranger, or a **time selection** on an automation lane (or use the time-selection toolbar's dropdown), then **Automation Tools…**. The dialog opens on the last tool used; the buttons at its top switch tools in place.

| Tool | Setting | What it does |
|---|---|---|
| **Scale Levels** | Center, Tension, Multiply (0–200 %), Offset | Scales the levels around a center: Center 0 is the parameter's zero, -100 the bottom of the lane, 100 the top. Tension bends the levels away from (positive) or towards (negative) the center; Offset then moves them all up or down. |
| **Normalize** | | Scales the levels around the parameter's zero so the one furthest from it reaches the edge of the lane. |
| **Flip** | | Turns the levels upside down on the lane. |
| **Smooth** | Amount (0–100 %) | A low-pass filter: it removes the automation's fast movements (high frequencies) and keeps its slow ones. The automation is sampled as it plays (Bitwig's own curves), filtered, then each point takes the filtered value and each segment the curve that follows it best. It also simplifies: the points that add little to the smoothed automation are removed (more as the amount grows), the segments that replace them getting the curve that follows them. 0 changes nothing; the higher the amount, the slower the movements it keeps (100 keeps only the slowest movement of the selection). Points don't move in time and none is added; the first and last points stay and keep their values. |
| **Decimate** | Remove (0–100 %) | Removes that share of the points between the first and the last, spread evenly: 50 % removes one point in two, 25 % one in four, 33 % one in three, 100 % all of them (only the first and last stay). Values don't change; each segment that now runs over removed points gets the curve (Bitwig's own shape) that follows them best. |

- **One lane.** Selected points must all be on one lane (its own automation and its automation clips together); a selected clip works on the points that play between its start and end in the arranger: for an automation clip, only the part of it the arranger plays, on its lane; for another clip (audio, notes), the automation under it on the track's automation lane last used for a time selection (select one clip; clips that share content change together). A time selection works on its lane: the lane's own points and the points of the automation clips it crosses, even partly (a looped clip's point once, where it first plays in the time selection). Each point is written back into its own curve, the lane's or its clip's; a segment's curve is changed only where it stays in one curve.
- **Zero** is found automatically: for a parameter whose range runs from -x to +x (pan, pitch…), zero is in the middle of the lane and Scale Levels and Normalize work around it, so levels are never pushed to the bottom.
- **Live preview.** The automation as it is (grey) and as the tool would leave it (orange; a removed point in red), with Bitwig's own segment curves, redrawn as you change the setting. Nothing changes until **OK**, which writes it as one Undo step; **Cancel** leaves it as it was.
- **Reset** puts the tool's settings back to their defaults; each tool remembers its settings.
- When there are no points to work on, Bitwig's notification says why (and the mod log says what was right-clicked).
- Each tool is also an action of its own (**Automation Tools: …** in Settings > Shortcuts, or the Commander); from a shortcut, it works on the time selection.
