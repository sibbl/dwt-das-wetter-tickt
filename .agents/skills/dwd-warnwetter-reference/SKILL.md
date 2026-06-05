---
name: dwd-warnwetter-reference
description: Use historical DWD WarnWetter reverse-engineering notes when changing DWT radar backends, payload parsing, rendering, coloring, or Wear OS architecture.
---

# DWD WarnWetter Reference

These notes document observations from a decompiled WarnWetter APK and live
backend samples. Treat them as historical reference, not as guaranteed current
DWD API documentation.

Read only the reference relevant to the task:

- Backend endpoints, overview payloads, formats, and loading behavior:
  [references/backend-and-loading.md](references/backend-and-loading.md)
- Decompiled APK layout, native rendering boundaries, and color assets:
  [references/rendering-and-assets.md](references/rendering-and-assets.md)
- Original Wear OS implementation options and tradeoffs:
  [references/wear-os-porting.md](references/wear-os-porting.md)

Verify live backend behavior before changing parsers or publishing claims about
DWD formats. Keep DWD attribution and permission status aligned with
`DATA_SOURCES.md`.
