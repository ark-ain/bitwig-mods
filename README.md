# Bitwig Mods: Automations+ and Keyboard+

Two mods by ark-ain for **Bitwig Studio** running on [Patchbay](https://github.com/shaboogen-dev/Patchbay).

> Unofficial community project. Not affiliated with, endorsed by or supported by Bitwig GmbH.

| Mod | Platform | What it does |
|---|---|---|
| [Arranger: Automations+](automations-plus/README.md) | Windows, macOS, Linux | Adds points over an automation time selection (dialog or direct), a Hold mode for new automation points, and Automation Tools after FL Studio's (Scale Levels, Normalize, Flip, Smooth, Decimate) with a live preview. Based on shaboogen's *Add Automation Points*. |
| [Interface: Keyboard+ (Linux)](keyboard-plus/README.md) | **Linux only** | While a plug-in window has the focus, the keyboard still goes to Bitwig: play notes with the computer keyboard, Space for the transport, Bitwig's shortcuts. Works in an X11 session and under Wayland with Bitwig running in Xwayland. Does nothing on Windows or macOS. |

Both need **Framework: Actions and Menus**, which comes with Patchbay.

## Install

1. Download the mod ZIPs from [Releases](../../releases).
2. In Patchbay: **Mods → Add Mod…**, pick the ZIP, tick the mod (and Framework: Actions and Menus), then **Load**.

## Tested with

- Bitwig Studio 6.1.4 on Linux x64 (both mods are built against the Linux 6.1.4 names; Patchbay's maps translate Automations+ to the other platforms and versions).
- Keyboard+ was tested on a virtual X server; on real hardware, please report what you find.

## Français

Deux mods pour Bitwig Studio avec Patchbay :

- **Automations+** : ajout de points sur une sélection de temps d'automation, mode Hold, outils d'automation façon FL Studio. Basée sur *Add Automation Points* de shaboogen.
- **Keyboard+** (**Linux uniquement**, X11 ou Xwayland) : quand une fenêtre de plug-in a le focus, le clavier va quand même à Bitwig (notes au clavier, Espace, raccourcis).

Téléchargement dans [Releases](../../releases), puis *Add Mod…* dans Patchbay.

## Licence

MIT, see [LICENSE](LICENSE). Automations+ keeps shaboogen's copyright notice (`automations-plus/LICENSE`).
