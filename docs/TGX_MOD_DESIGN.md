# TGX Mod design ("Swiss")

Toggle: **Settings → Themes → TGX Mod design → Use TGX Mod design** (default ON).
Turning it off swaps Swiss Light/Dark back to Classic/Night Blue in the account's day/night slots,
restores the default wallpaper and disables the layout tweaks (header title, chat-list hairlines,
avatar override, new video controls). Header title size applies after an app restart.

## Palette

| Role | Swiss Light | Swiss Dark |
|---|---|---|
| Surface (filling, header) | `#FCFCFB` | `#121212` |
| Page background | `#F1F1EE` | `#0A0A0A` |
| Chat background (plain, no pattern) | `#F3F3F0` | `#0D0D0D` |
| Incoming bubble | `#FFFFFF` | `#1C1C1C` |
| Outgoing bubble (neutral tint) | `#E7E7E3` | `#2A2A28` |
| Primary text / graphite | `#1A1A1A` | `#EDEDEB` |
| Secondary ink (read ticks, quote lines) | `#3A3A3A` | `#C9C9C6` |
| Secondary text | `#6E6E6C` | `#9B9B98` |
| Tertiary (time, muted) | `#8C8C89` | `#7C7C79` |
| Hairline divider | `#E4E4E0` | `#232323` |
| **Accent – signal red** | `#D0312D` | `#EF5A4F` (badge `#D9443A`) |
| Positive / Negative | `#3E7F52` / `#B3261E` | `#6FB383` / `#F2685E` |

The accent is used only for: unread badges, links, selection controls (toggles, checkboxes,
sliders, progress), tab indicator, primary filled buttons and the send button.
The floating action button, snackbars and attach-menu circles are graphite, not red.
Avatar / member-name colours are a muted, equal-lightness set.

## Geometry

* 8 dp grid; header title 20 sp Roboto Medium, left keyline 16 dp (72 dp with back button).
* Bubble corner 8 dp, merged corner 3 dp, image corner 2 dp, date/service pill corner 4 dp.
* Shadows replaced by 0.5 dp hairlines (`replaceShadowsWithSeparators`, `shadowDepth 0`), flat
  bubbles with 1 px outline, flat status bar (light status bar icons in Swiss Light).
* Chat list: hairline row divider aligned with the text column.
* Avatars: circle by default (forums: rounded square 0.3), optional "Rounded square" for all.

## Video player

Flat translucent graphite bar (`#B80F0F0F`), 1.5 dp seek line with 4 dp thumb (tap anywhere on
the line to seek), play/pause, `0:12 / 3:45` with tabular figures, speed (1 → 1.25 → 1.5 → 2 →
0.5 → 0.75×), mute, rotate (landscape lock), double tap on the left / right 30 % of a video to seek
∓10 s with a small indicator (centre double tap still zooms). PiP stays in the header.

Regenerate themes: `python3 scripts/tgxmod-gen-swiss-themes.py`.
