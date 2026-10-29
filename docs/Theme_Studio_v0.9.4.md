# v0.9.4 — interactive theme studio and reliable inbox dividers

## Release
Version 0.9.4-theme-studio, code 33, Android FossDebug. This is an iterative preview; actual device visual and behavior validation is required.

## User-requested theme features
- **Theme Studio** available from the main inbox overflow menu.
- Two interactive all-at-once demo screens, using synthetic messages only: **Inbox** demonstrates single contacts, read/unread status, group chats, read badges, avatars, dividers, selection colors, search styling, timestamps and the new-message button. **Conversation** demonstrates sent/received bubbles, text, date markers, composer and Send button.
- Tap a preview element to edit its color using a hex field or palette swatches; editable corner-radius sliders on shaped elements. Explicit buttons edit new-message + icon independently from its button and edit Send icon independently from its button. Both bubble backgrounds also have dedicated targets.
- Named themes saved locally via Save As; theme chooser switches instantly among saved themes and immutable presets. Built-ins: **One UI**, **Material You**, **AMOLED**, **Classic**, **Original**. Editing a preset automatically forks into an **Unsaved edits** theme rather than changing the preset; named themes can be deleted. Both light/dark values are preserved in a named theme.
- A **Spacing** panel lets users adjust row height/padding, inbox inset, avatar size, divider thickness/inset, chat bubble padding and gap, composer height, and new-message button size, independent X/Y position
percentages, and upward fine adjustment.
- Extra message geometry controls: distance between incoming/outgoing bubbles and
their respective screen edges, gap from incoming avatar to message, maximum
message width as a percentage of available conversation width, and the spacing
between inbox avatar and text.
- The floating new-message button can be **dragged in either direction** inside
the Inbox preview. A tap still opens its own color/shape editor; the icon color
is separately editable. Both X/Y coordinates are stored as normalized
percentages, so resizing the phone/window keeps placement approximately stable. Unsafe minimums are clamped.
- Values persist in app-private SharedPreferences as compact JSON, without reading or copying SMS data.
- Real inbox, contact rows, unread badges, main toolbar/search, floating compose button, sent/received bubbles and composer read active theme at runtime.

## Missing dividers regression fixed
The previous divider was a 1dp View translated beyond the padded row's bounds, so RecyclerView clipped it. The translated XML View has been removed. A RecyclerView.ItemDecoration now renders dividers on top of children at the real bottom boundary of each visible row, starting at an adjustable inset, with theme-defined width and color. Last conversation receives no divider.

## Scope limits
The theme editor is an early functional implementation, not a complete skinning engine. A few deeper system/system-provided surfaces, media attachment widgets, fonts, keyboard and chat-head overlay still use their preexisting style. The One UI/Material/Classic presets are original approximations, not proprietary Samsung/Google code or artwork. No information in sample previews comes from actual messages. Source code derives from Fossify and remains subject to its GPL licensing requirements.

## Tests
Compile successful. Still validate on OnePlus crDroid: dividers visible while scrolling/selection, applying a preset to inbox and conversation, editing and saving named theme, switching back to the original, dark/light modes, and separate floating + icon/color/shape/sizing changes.

## Geometry controls and safety
The editor provides independent physical-density-aware measurements in dp:
- row height, row vertical padding, row text gap, avatar size, inbox card inset
- divider horizontal inset and thickness
- vertical message spacing, internal bubble padding, received/sent bubble
  edge distances and incoming avatar-to-bubble gap
- bubble max width (45–98% of conversation row)
- compose input height
- new-message button size, horizontal and vertical position (0–100% each),
  and additional upward fine adjustment.
Presets define their own spacing, not only colors. Sizing controls clamp
minimum rows, avatars, and bubble width so controls stay usable.
