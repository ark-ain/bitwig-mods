# Interface: Keyboard+ (Linux)

> **Linux only.** Keyboard+ works on Linux, in an X11 session or with Bitwig running under Xwayland in a Wayland session. On Windows and macOS it does nothing.

When a plug-in window has the focus, your keyboard still talks to Bitwig, and the plug-in still gets its keys too:

- play notes with the computer keyboard;
- start and stop the transport with Space;
- use Bitwig's shortcuts (Ctrl+S, Ctrl+Z…).

Without it, a plug-in window keeps every key for itself, and nothing happens in Bitwig until you click back into it.

Keyboard+ doesn't take any key away: the plug-in, the desktop and every other program receive their keys exactly as before. Bitwig simply gets a copy of the keys typed in plug-in windows.

## Requirements

- Bitwig Studio 6.x on Linux x64, with [Patchbay](https://github.com/shaboogen-dev/Patchbay).
- An X11 session, or Xwayland under Wayland (Bitwig runs there by default).
- **Framework: Actions and Menus** (comes with Patchbay).

## Install

Download `keyboard-plus-<version>.zip` from the [Releases](https://github.com/ark-ain/bitwig-mods/releases), then in Patchbay: **Mods → Add Mod…**, tick **Interface: Keyboard+ (Linux)** and **Load**.

## How it works

1. Keyboard+ finds the windows of Bitwig's plug-in host processes (`BitwigPluginHost-*`).
2. It listens to their key events alongside the plug-in. X11 lets several programs receive the keys of one window, so the plug-in's own keys are unchanged; Keyboard+ listens only on the windows where the plug-in itself listens.
3. It sends a copy of each key to Bitwig's main window, modifiers included, as if you had pressed it there.

Auto-repeat is filtered out of the copy by default: a held key gives Bitwig one press and one release, like a piano key (the plug-in still gets its auto-repeat). If you release a key after moving the focus away from the plug-in, Keyboard+ notices and releases it in Bitwig too, so no note is left hanging.

## Typing in a plug-in

Since Bitwig gets a copy of every key, typing in a plug-in's text field (a preset name, a search box) also reaches Bitwig: letters may play notes and Space may start the transport. When you need to type in a plug-in:

- run **Toggle Keyboard+** (Settings > Shortcuts, or the Commander) to switch the copy off and on. Give it a key: it works from inside a plug-in window too;
- or leave some plug-ins out with `exclude` / `include` (below);
- or set `keys=space` to copy only Space.

## Settings

In `~/.config/bml/mods/keyboard-plus/settings.properties` (create it if needed), read when Bitwig starts:

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | Whether keys are copied to Bitwig. **Toggle Keyboard+** changes and saves it. |
| `keys` | `all` | `all`: every key is copied to Bitwig. `space`: only unmodified Space. |
| `repeat` | `false` | `true`: a held key's auto-repeat is copied to Bitwig too (handy for arrows or Delete, not for playing notes). |
| `include` | empty (all plug-ins) | Regular expression: only plug-ins whose window title matches, e.g. `ozone|pro-q`. |
| `exclude` | empty | Regular expression: plug-ins whose keys aren't copied, e.g. `kontakt|serum`. |
| `diagnostics` | `false` | `true`: logs every plug-in window found and every key passed on. |

Expressions are case-insensitive. Example:

```properties
keys=all
exclude=kontakt|serum
```

## Log

Messages start with `[keyboard-plus]` and go to `state/bitwig-console.log` in Patchbay's folder. They list the plug-in windows found and whether their keys are copied to Bitwig, excluded, or not copied (`off`).

## Credits

Inspired by *Plug-in Space for Transport* by Mike8040 (Windows). The X11 connection code (socket, `MIT-MAGIC-COOKIE-1` authentication, window properties, X-Resource) is adapted from Patchbay's `X11WindowClose`, MIT licence, (c) 2026 shaboogen.

Unofficial community project, not affiliated with Bitwig GmbH.
