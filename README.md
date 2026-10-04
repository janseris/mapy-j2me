# Mapy 9300

A map app for the **Nokia 9300 / 9500 Communicator** (Series 80 v2, Symbian 7.0s, J2ME MIDP 2.0),
inspired by the Mapy.com Android app.

**Demo 0.2:**

- **Map:** OpenStreetMap tiles (`tile.openstreetmap.org`), panning and zoom, up to 24 tiles in memory.
- **Points of interest overlay:** named shops, restaurants, hotels, museums... from OpenStreetMap
  (Overpass API), shown as coloured dots from zoom 16. Select one to see its name and type, open its
  details, and look it up in Mapy.com.
- **Search:** Mapy.com suggestions (`vectmap.mapy.cz/rpc`, FastRPC `suggest`) near the map view;
  the chosen result gets a red pin.
- **Place detail:** Mapy.com `getDetail` (address, rating, description, facts).
- **What's here:** Mapy.com detail of the map centre.

Routes and Bluetooth GPS (position from an Android phone) come next.

## Controls

The map has a mouse-style cursor. The 9300's Java has no pointer events, so the arrows drive it.

| Key | Action |
|---|---|
| Arrows | move the cursor (faster when held); at the edge of the map the map scrolls |
| Chr + Up / Chr + Down (the blue + / -) | zoom in / out around the cursor (also `+` `-` `3` `1`) |
| Enter / joystick press | click: open the object under the cursor, or "what's here" at the cursor |
| `N` / space | jump the cursor to the next object on screen |
| letters | start a search with that letter |
| Side buttons | Hledat, Otevřít, Přiblížit, Oddálit; more in the menu |

Objects under the cursor are highlighted like a link (blue ring, underlined label, name in the
bottom strip). Unknown keys are written to the log with their key code.

## How it talks to the network (Nokia 9300 rules)

- HTTPS through `HttpConnection` and the patched `SSLADAPTOR.dll`
  ([janseris/symbian-tls](https://github.com/janseris/symbian-tls) `v20-fix10`); no signing needed.
- `Net.java`: one request at a time, each attempt on its own thread, abandoned (never closed from
  another thread) after 15 s without progress, 3 attempts, a short pause after each request so the
  phone keeps answering the PC's USB keep-alive. Progress is shown in the bottom strip, and only that
  strip (or the arriving tile) is repainted while loading.
- Our own `User-Agent` (`Mapy9300/0.2 (+https://github.com/janseris/mapy-j2me)`), as OSM's tile and
  Overpass policies require; tiles only for the visible area, no prefetching.
- Mapy.com calls: the FastRPC requests captured from the Android app (encoder verified byte for byte
  against the capture), without key or login.

Credits: map data and POIs © OpenStreetMap contributors (ODbL). Search and details: Mapy.com (Seznam.cz).
This is a non-commercial hobby project, not affiliated with Seznam.cz.

## Build

`build.bat` (uses the JDK 8, stub jars and ProGuard bundled in pubtran-j2me, cloned next to this repo).
Raise `MIDlet-Version` in `manifest.mf` first. Output: `bin/mapy9300.jar` + `.jad`.
`run_kemulator.bat` starts it in KEmulator. Request log: menu *Log* → *Odeslat log na PC*
(`ota_server.js` from android-to-j2me-kit or pubtran-j2me on the PC, port 8000).
