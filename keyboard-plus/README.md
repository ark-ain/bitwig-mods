# Interface: Keyboard+ (Linux)

> **Linux uniquement.** Keyboard+ fonctionne sous Linux, en session X11 ou avec Bitwig sous Xwayland dans une session Wayland. Sous Windows et macOS, elle ne fait rien.

Les touches pressées pendant qu'une fenêtre de plug-in a le focus vont à Bitwig, comme si cette fenêtre n'était pas là :

- jouer des notes avec le clavier d'ordinateur ;
- Espace pour le transport ;
- les raccourcis de Bitwig (Ctrl+S, Ctrl+Z…).

Inspirée de *Plug-in Space for Transport* de Mike8040 (Windows, Espace seulement), réécrite pour Linux et étendue à tout le clavier.

- Mod ID : `keyboard-plus`
- Nécessite **Framework: Actions and Menus** 0.8.0-dev.1 ou plus récent (fourni avec Patchbay).
- N'utilise aucun nom interne de Bitwig : prête pour Bitwig 6.0 à 6.1.x sous Linux.

## Fonctionnement

1. La mod repère les fenêtres des processus `BitwigPluginHost-*` de Bitwig (les fenêtres de plug-ins).
2. Elle prend le clavier sur chacune : quand une fenêtre de plug-in a le focus, les touches arrivent à la mod au lieu du plug-in.
3. Elle les renvoie à la fenêtre principale de Bitwig, modificateurs compris.

Tant qu'une touche est enfoncée, la mod garde tout le clavier. Chaque relâchement arrive ainsi à Bitwig, même pour un accord dont on relâche les touches dans le désordre : aucune note ne reste bloquée.

Par défaut, l'auto-répétition est filtrée : une touche tenue donne un seul appui et un seul relâchement, comme une touche de piano.

## Limite : le plug-in ne reçoit plus le clavier

Tant que la mod est active, le plug-in ne reçoit plus aucune touche. On ne peut donc plus taper dans ses champs de texte (nom de preset, recherche, valeur saisie au clavier). Trois solutions :

- l'action **Toggle Keyboard+** (Settings > Shortcuts, ou le Commander) coupe et rallume la mod. Donne-lui un raccourci : il marche aussi depuis une fenêtre de plug-in, puisque la touche part à Bitwig ;
- `exclude` / `include` (ci-dessous) écartent certains plug-ins par le titre de leur fenêtre ;
- `keys=space` revient au comportement d'origine : seulement Espace.

## Réglages

Dans `~/.config/bml/mods/keyboard-plus/settings.properties`, lus au démarrage de Bitwig :

| Clé | Défaut | Rôle |
|---|---|---|
| `enabled` | `true` | Mod active. L'action la change et l'enregistre ici. |
| `keys` | `all` | `all` : tout le clavier va à Bitwig. `space` : seulement Espace sans modificateur. |
| `repeat` | `false` | `true` : l'auto-répétition d'une touche tenue va aussi à Bitwig (utile pour les flèches, Suppr…). |
| `include` | (vide = tous) | Expression régulière : seuls les plug-ins dont le titre de fenêtre correspond sont concernés, ex. `ozone|uadx|pro-q`. |
| `exclude` | (vide) | Expression régulière : plug-ins à laisser tranquilles, ex. `kontakt|serum`. |
| `diagnostics` | `false` | `true` : écrit chaque fenêtre trouvée et chaque premier appui de touche renvoyé. |

Les expressions ne tiennent pas compte des majuscules.

## Si tu avais « Plug-in Space for Transport (Linux) »

Keyboard+ en est la suite, avec un autre ID. Désinstalle l'ancienne dans Patchbay (les deux prendraient les mêmes touches). Deux choses ne suivent pas :

- les réglages : copie `~/.config/bml/mods/plugin-space-for-transport-linux/settings.properties` dans `~/.config/bml/mods/keyboard-plus/` ;
- le raccourci de l'action : redonne une touche à **Toggle Keyboard+** dans Settings > Shortcuts.

## Journal

Les messages (préfixe `[keyboard-plus]`) vont dans `state/bitwig-console.log`, dans le dossier de Patchbay. On y voit les fenêtres de plug-ins trouvées et si leurs touches sont prises (`keys taken`), exclues ou laissées (`off`).

## Crédits et licence

La connexion X11 (ouverture du socket, authentification `MIT-MAGIC-COOKIE-1`, propriétés et X-Resource) reprend `X11WindowClose` de Patchbay, licence MIT, © 2026 shaboogen.

Projet communautaire non officiel, sans lien avec Bitwig GmbH.
