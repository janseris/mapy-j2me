# Mapy 9300

A map app for the **Nokia 9300 / 9500 Communicator** (Series 80 v2, Symbian 7.0s, J2ME MIDP 2.0),
inspired by the Mapy.com Android app.

**Demo 0.8:**

- **Map:** OpenStreetMap tiles (`tile.openstreetmap.org`), panning and zoom, up to 24 tiles in memory.
- **Points of interest overlay** (from zoom 16): restaurants, cafés, pubs, accommodation, parking,
  drinking water, playgrounds, monuments, churches, bells, museums, galleries, theatres, shelters,
  bunkers, city gates, parks, shops and services. Positions and types come from OpenStreetMap
  (Overpass API); they are drawn with the **Mapy.com map icons** (`res/poi_icons16.png`, made from the
  icons the Mapy.com app loads from `api.mapy.cz/poiimg/icon`). A click opens the same place in
  Mapy.com (search by name nearby, then `getDetail`); without a match it shows the OSM data.
- **Photos:** the detail shows the place's main photo; "Fotky (N)" opens a full-screen gallery
  (arrows = next). Photos come from Seznam's image servers in a size that fits the screen
  (`?fl=res,,<height>,3`).
- **Hover preview:** when the cursor rests on an object for a second, the panel shows its Mapy.com
  rating and a small photo (can be switched off in Nastavení; costs a few requests per object).
- **Caching:** map tiles and photos are kept in an LRU cache in the phone's record store (RMS, no
  permission prompts), 16 MB by default, set in Nastavení (0–48 MB, "Smazat mezipaměť"). Entries
  older than 30 days are fetched again. In memory: the last 24 tiles, 12 photos and 40 Mapy.com details.
- **Search:** Mapy.com suggestions (`vectmap.mapy.cz/rpc`, FastRPC `suggest`) near the map view;
  the chosen result gets a red pin.
- **Place detail:** Mapy.com `getDetail` (address, rating, description, facts).
- **What's here:** Mapy.com detail of the map centre.

- **Bluetooth GPS:** position from a Bluetooth GPS receiver or an Android phone sharing its GPS as
  NMEA over Bluetooth (address in Nastavení; Menu → GPS připojit). Blue dot with heading; "Moje
  poloha" follows it.
- **Routes and navigation (walking and car):** detail of a place → "Trasa sem pěšky / autem", from the
  GPS position (or, without GPS, from the map cursor). The route comes from OSRM on the FOSSGIS server
  (`routing.openstreetmap.de`, OpenStreetMap data: full line + turn steps; max 1 request/s, no heavy
  use). Menu → "Navigace start": the map follows the GPS, the panel shows the next instruction in Czech
  ("Za 80 m: Odbočte vlevo na Křížkovského"), the remaining distance and time; off the route (30 m on
  foot, 50 m by car, 3 fixes in a row) it recalculates, at most every 15 s. Mapy.com's own routes can't
  be used for this yet: their full line is in an encoded format; see `../mapy/ANALYSIS.md`.

## Controls

Full screen: an info panel on the left (progress, status, the object under the cursor, zoom,
credits; width set in Nastavení), the map, and a thin icon bar on the right that labels the four
side buttons (in full screen the phone doesn't draw their labels). The map has a Windows-XP-style mouse cursor. The 9300's Java has no
pointer events, so the navigation key drives it: it moves while held (time-based speed that
accelerates, so the 9300's coarse timer doesn't make it jerky), diagonally when two directions are
held, and at the edge of the map the map scrolls.

| Key | Action |
|---|---|
| Navigation key / arrows | move the cursor; the map scrolls at the edge |
| Side buttons (top to bottom) | Hledat, Přiblížit, Oddálit, Otevřít; more in the menu |
| `+` / `-` (also `3` / `1`) | zoom in / out around the cursor |
| Enter / navigation key press | click: open the object under the cursor, or "what's here" at the cursor |
| `N` / space | jump the cursor to the next object on screen |
| `0` | full screen on/off |
| letters | start a search with that letter |

Objects under the cursor are highlighted like a link (blue ring, underlined label, name in the
panel). The panel shows the code of the last key pressed, and every key is logged. Chr + Up/Down can't
zoom: the 9300 doesn't tell Java about the Chr key (Chr alone sends nothing, Chr+Up arrives as a plain
Up, -1), so zoom is on the side buttons and `+` / `-`.

## How it talks to the network (Nokia 9300 rules)

- HTTPS through `HttpConnection` and the patched `SSLADAPTOR.dll`
  ([janseris/symbian-tls](https://github.com/janseris/symbian-tls) `v20-fix10`); no signing needed.
- `Net.java`: one request at a time, each attempt on its own thread, abandoned (never closed from
  another thread) after 15 s without progress, 3 attempts, a short pause after each request so the
  phone keeps answering the PC's USB keep-alive. Progress is shown in the bottom strip, and only that
  strip (or the arriving tile) is repainted while loading.
- Our own `User-Agent` (`Mapy9300/0.8 (+https://github.com/janseris/mapy-j2me)`), as OSM's tile and
  Overpass policies require; tiles only for the visible area, no prefetching.
- Mapy.com calls: the FastRPC requests captured from the Android app (encoder verified byte for byte
  against the capture), without key or login.

Credits: map data and POIs © OpenStreetMap contributors (ODbL). Search, details and the POI icons:
Mapy.com (Seznam.cz).
This is a non-commercial hobby project, not affiliated with Seznam.cz.

## Build

`build.bat` (uses the JDK 8, stub jars and ProGuard bundled in pubtran-j2me, cloned next to this repo).
Raise `MIDlet-Version` in `manifest.mf` first. Output: `bin/mapy9300.jar` + `.jad`.
`run_kemulator.bat` starts it in KEmulator. Request log: menu *Log* → *Odeslat log na PC*
(`ota_server.js` from android-to-j2me-kit or pubtran-j2me on the PC, port 8000).
