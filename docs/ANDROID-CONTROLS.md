# Android touch controls

The Android shell reuses the browser client's `web/input.js` touch layer. The
layout follows the Bedrock touch UI rather than inventing a new scheme, so
players who know Bedrock can play without relearning the screen.

Reference resources:

- `Mojang/bedrock-samples` — `resource_pack/ui/` (touch control layouts,
  joystick textures, button hit areas).
- GeyserMC mappings — Bedrock button IDs to Java actions, used as the
  behavioural reference for jump/sneak/sprint/attack/use.

## Mapping

| Bedrock control | Java action |
| --- | --- |
| Left virtual joystick | Move / strafe |
| Drag on the world (right side) | Look |
| Jump button | Jump (double-tap toggles fly in creative) |
| Sneak button | Sneak / descend |
| Sprint button | Sprint |
| Attack / mine button | Attack or break the targeted block |
| Use / place button | Use item / place block |
| Hotbar tap | Select hotbar slot |
| Hotbar long-press | Drop one item (Bedrock-style) |
| Inventory button | Open inventory |
| Pause button | Open the Java pause menu |
| Back gesture | Close the current screen |

The touch layer sends the same key/mouse events the desktop client uses, so
the Java client's own key bindings, inventory handling and GUI scaling remain
authoritative. The shell only chooses the layout and hit areas.

## Fullscreen and safe areas

The WebView is edge-to-edge. `input.js` applies `env(safe-area-inset-*)` to the
hotbar and joystick so notches and gesture bars do not cover controls. The
page requests fullscreen on the first pointer down, which also hides the
Android status bar on devices that allow immersive mode.

## WebGPU requirements

Android System WebView 121+ on Android 12+ with a Qualcomm or ARM GPU is
required. WebGPU is not exposed on every vendor WebView; if the client reports
a missing adapter, update Android System WebView from the Play Store.
