# Mapy 9300

> **Superseded by [Mapy 9300 native](../mapy9300-native)** (C++), since 2026-10-10. This Java version
> works but is too slow to use on the Nokia 9300. The limits are in the phone's Java (MIDP), not in
> this code, and no workaround got past them:
>
> | Per map tile | Java (Mapy 4.19 + Net Helper 0.12) | Native C++ (Mapy native 0.3) |
> |---|---|---|
> | Request from Java to Net Helper on the same phone | ~1.7 s | — (same process) |
> | Net Helper fetching it (kept-open connection) | 0.1–0.7 s | 0.1–0.25 s (same code) |
> | Answer from Net Helper back into Java | 3–4 s | — |
> | Decoding a 256×256 PNG | 0.3–0.9 s | median 0.47 s, 1.6 s on average while downloading (the phone's own decoders) |
> | Saving a 20 KB tile | ~4.3 s (record store, any size of store) | in the background, after it's shown |
> | Whole tile, typically | **5–11 s**, often 15–50 s stalls | **0.5–2 s**, and the map stays responsive meanwhile |
>
> What was tried and measured (details in the kit's `nokia9300/NOTES.md`, Probe 3.x results):
> - **HTTP from Java** costs 0.5–1.6 s per tile even with a direct connection; Java's comms layer
>   allows one connection at a time and can't keep a connection open. Net Helper (native, kept-open
>   connections) was fast itself, but handing data between Java and it on the same phone cost seconds.
> - **Bluetooth GPS from Java** next to HTTP made both slow and crashed `jes-java-comms`
>   (E32USER-CBase 40, KERN-EXEC 3); the GPS had to move into Net Helper.
> - **Storage:** Mapy used the record store, which never prompts but writes at about 5–6 KB/s
>   (4.3 s per 20 KB, even when empty). Files (JSR-75 FileConnection) were tried only in Probe's tests,
>   never in Mapy: denied on C: for an unsigned MIDlet, and an "Allow?" prompt on almost every access
>   on the card, so not an option. Signing isn't possible (closed root certificates).
> - **Java Personal Profile** (IBM J9, the phone's second Java) has fast http, but AWT crashes
>   (KERN-EXEC 3), image decoding and `java.io.File` never return, and there's no https.
> - **The UI thread** shares the VM with decoding and network threads; with tiles loading the app
>   lagged by seconds and answered keys late.
>
> Installing an update also took ~11 minutes, because the installer copies the 8.8 MB record store.

A map app for the **Nokia 9300 / 9500 Communicator** (Series 80 v2, Symbian 7.0s, J2ME MIDP 2.0),
inspired by the Mapy.com Android app.

**Demo 0.9:**

- **Map:** OpenStreetMap tiles (`tile.openstreetmap.org`), panning and zoom, up to 24 tiles in memory.
- **Points of interest overlay** (from zoom 16): restaurants, cafés, pubs, accommodation, parking,
  drinking water, playgrounds, monuments, churches, bells, museums, galleries, theatres, shelters,
  bunkers, city gates, parks, shops and services. Positions and types come from OpenStreetMap
  (Overpass API), loaded by cells of the z15 tile grid (~0.8 km): the view's missing cells in one query,
  each cell then kept in the phone's cache (30 days), so panning back or revisiting needs no request
  (*Reload* fetches the view's cells again); they are drawn with the **Mapy.com map icons** (`res/poi_icons16.png`, made from the
  icons the Mapy.com app loads from `api.mapy.cz/poiimg/icon`). A click opens the same place in
  Mapy.com (search by name nearby, then `getDetail`); without a match it shows the OSM data.
- **Photos:** the detail shows the place's main photo; "Fotky (N)" opens a full-screen gallery
  (arrows = next). Photos come from Seznam's image servers in a size that fits the screen
  (`?fl=res,,<height>,3`).
- **Hover preview:** when the cursor rests on an object for a second, the panel shows its Mapy.com
  rating and a small photo (can be switched off in Settings; costs a few requests per object).
- **Caching:** map tiles and photos are kept in an LRU cache in the phone's record store (RMS, no
  permission prompts), 16 MB by default, set in Settings (0–48 MB, "Clear cache"). Entries
  older than 30 days are fetched again. In memory: the last 24 tiles, 12 photos and 40 Mapy.com details.
- **Search:** Mapy.com suggestions (`vectmap.mapy.cz/rpc`, FastRPC `suggest`) near the map view;
  the chosen result gets a red pin.
- **Place detail:** Mapy.com `getDetail` (address, rating, description, facts).
- **What's here:** Mapy.com detail of the map centre.

- **Bluetooth GPS:** position from a Bluetooth GPS receiver or an Android phone sharing its GPS as
  NMEA over Bluetooth (address in Settings; Menu → GPS connect). Blue dot with heading; "My
  position" follows it.
- **Routes and navigation (walking and car):** detail of a place → "Route here on foot / by car", from the
  GPS position (or, without GPS, from the map cursor). The route comes from OSRM on the FOSSGIS server
  (`routing.openstreetmap.de`, OpenStreetMap data: full line + turn steps; max 1 request/s, no heavy
  use). Menu → "Navigation start": the map follows the GPS, the panel shows the next instruction
  ("In 80 m: Turn left onto Křížkovského"), the remaining distance and time; off the route (30 m on
  foot, 50 m by car, 3 fixes in a row) it recalculates, at most every 15 s. Mapy.com's own routes can't
  be used for this yet: their full line is in an encoded format; see `../mapy/ANALYSIS.md`.
  **With your own Mapy.com API key** (Settings → Mapy.com API key; *Load from the PC* reads
  `mapy_api.key` next to the kit's `ota_server.js`, gitignored) routes come from the official Mapy.com
  REST API instead (`api.mapy.com/v1/routing/route`, 4 credits each, free plan 250,000 credits a
  month): cars use `car_fast_traffic` (current traffic in Czechia), "no toll roads" is `avoidToll`. That
  API gives no turn instructions, so they are made from the line's bends (no street names). If the
  Mapy.com request fails, OSRM is used as before.

## Map types: measured speed

Network time per tile on the Nokia 9300 (Probe 3.4 *Map servers* test, average of 4 runs, 2026-10-06,
internet over USB from a PC; the first tile of each series left out). The map type list shows these.

| Map type | Direct | Through Net Helper |
|---|---|---|
| OpenStreetMap (HTTPS) | 0.7 s | 0.2 s |
| OpenTopoMap (HTTP) | 0.5 s | 0.2 s |
| ČÚZK base map (HTTP, 30–40 KB JPEG) | 0.6 s | 0.4 s |
| ČÚZK aerial (HTTP) | 0.6 s | 0.4 s |
| Mapy.com outdoor (HTTPS, API key) | 1.7 s | 0.3 s |
| Mapy.com aerial (HTTPS, API key) | 1.6 s | 0.3 s |
| Mapy.com standard (HTTPS, API key) | 1.5 s | 0.3 s |

Without Net Helper every HTTPS tile pays a new TLS handshake (Mapy.com ~1 s); the servers themselves
answer in 60–220 ms. Decoding on the phone comes on top (JPEG ~200 ms, PNG 300–500 ms).

## Controls

Full screen: an info panel on the left (progress, status, the object under the cursor, zoom,
credits; width set in Settings), the map, and a thin icon bar on the right that labels the four
side buttons (in full screen the phone doesn't draw their labels). The map has a Windows-XP-style mouse cursor. The 9300's Java has no
pointer events, so the navigation key drives it: it moves while held (time-based speed that
accelerates, so the 9300's coarse timer doesn't make it jerky), diagonally when two directions are
held, and at the edge of the map the map scrolls.

| Key | Action |
|---|---|
| Navigation key / arrows | move the cursor; the map scrolls at the edge |
| Side buttons (top to bottom) | Search, Zoom in, Zoom out, Menu (set up once: Menu → Set up side buttons) |
| `+` / `-` (also `3` / `1`) | zoom in / out around the cursor |
| Enter / navigation key press | click: open the object under the cursor, or "what's here" at the cursor |
| `N` / space | jump the cursor to the next object on screen |
| `0` | full screen on/off |
| letters | shortcuts (H search, T route, P my position, M map type, S follow, G GPS, V navigation, …); Tab opens the menu, which lists them |

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
- Our own `User-Agent` (`Mapy9300/0.9 (+https://github.com/janseris/mapy-j2me)`), as OSM's tile and
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
