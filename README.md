# Northwoods ADV

A motorcycle riding game for Android that recreates the rides the Northwoods ADV club takes around
New Hampshire. Load a real `.gpx` file and ride it on real roads and terrain, following the route
on a handlebar GPS. The focus is the riding: how a big adventure bike behaves on pavement, and on
New Hampshire's unmaintained Class VI roads.

## What's in this first version

**Real maps**
- **Roads** come from OpenStreetMap (Overpass API), fetched along your route: highways, back
  roads, gravel roads, logging and jeep tracks, trails, plus streams, lakes, fields and wetlands.
  Surfaces and widths come from OSM tags.
- **Class VI detection.** Roads count as unmaintained when any of these is true: tagged
  `highway=track`; an explicit `class`/`legal` tag with value `VI`/`6`; maintenance tags; poor
  `tracktype`/`smoothness`; or "Class VI" in the name or notes.
- **Terrain** comes from USGS 3DEP elevation (AWS Terrain Tiles): roughly 7 m detail along your
  route and coarser tiles around it. Roads are graded into the terrain, with ditches, crowns and
  bridges.
- **Unmapped sections.** Any part of your GPX that isn't on a mapped road is ridden as an
  unmaintained trail.
- **Offline after first load.** Everything downloaded is cached, so a ride works without a
  connection the next time.

**GPX and GPS**
- **Loading.** Open GPX tracks or routes from the in-app picker, or straight from email, Drive or
  a file manager with *Open with* or *Share*.
- **GPS display.** A track-up moving map with your route in magenta, roads styled by type (Class
  VI dashed brown), and your breadcrumb trail.
- **Directions and data.** Next-turn distance, an off-route warning, distance remaining,
  elevation, heading and odometer.

**Physics** (`core/src/main/kotlin/.../physics`)
- **Tyres.** A combined-slip model with a friction circle, so braking or wheelspin eats cornering
  grip. Each surface has its own grip, peak slip and slide behaviour: pavement lets go suddenly,
  while gravel and sand keep pushing, so you can steer a slide with the throttle.
- **Leaning.** Lean comes from the real moment balance about the contact patches, so turning
  requires countersteering. In the default *lean assist* mode a virtual rider does the
  countersteering for the lean you ask for; *countersteer* mode gives you the bars directly.
- **Chassis.** Unsprung wheels on tyre springs, plus digressive fork and shock damping. Wheels
  envelope rocks and ledges rather than passing through them, and the contact normal pushes back
  on square edges. That is also why ruts grab the front wheel, off-camber corners push you
  downhill, and climbs cost power.
- **Drivetrain and aids.** Engine torque curve, 6-speed gearbox and auto-clutch. ABS (with
  rear-lift mitigation), traction control and wheelie control all run on the same physics and can
  each be switched off; ABS can be set to front only for off-road riding.
- **Class VI roads** get procedural ruts with a grass crown, embedded rocks, wet and dry ledge,
  water bars, washouts on steep pitches, mud holes in low spots, and fords where they cross
  streams. Paved back roads get frost heaves and potholes; gravel roads get washboard and loose
  edges.
- **Rider technique.**
  - Stand on the pegs for better absorption on rough ground.
  - Shift your weight forward or back.
  - In the air, throttle and rear brake pitch the bike, just as they do on a real bike.
- **Crashes.** Lowsides, tip-overs, looping a wheelie, going over the bars, trees, casing a
  landing, and drowning the bike in deep water.

## Controls

| | Phone | Keyboard |
|---|---|---|
| Lean / steer | Tilt the phone like handlebars (or the on-screen strip) | A / D or arrows |
| Throttle | Right slider | W / Up (Shift = gentle) |
| Front brake | Left slider | S / Down |
| Rear brake | REAR button | Space |
| Stand on pegs | Stand button | F |
| Weight fore / aft | | R / V |
| Gears (manual mode) | Gear buttons | Q / E |
| Camera | Camera button | C |
| Map zoom | + / - | M |
| Auto-ride demo | Auto button | P |

## Building

The Android APK is built by GitHub Actions on every push (*Actions → Build → artifacts →
nwadv-android*). To build locally you need JDK 17+ and the Android SDK:

    ./gradlew :core:test                 # physics and data tests
    ./gradlew :android:assembleDebug     # APK in android/build/outputs/apk/debug
    ./gradlew :desktop:run --args="path/to/ride.gpx"   # desktop version for development

The Android module is only included when an Android SDK is found, so the game code can still be
built and tested without one.

### Layout

- `core/`: all game code (Kotlin, libGDX): map data, terrain, physics, rendering, HUD
- `android/`: Android launcher (GPX file picker, "Open with", vibration)
- `desktop/`: desktop launcher for development
- `assets/routes/`: two sample routes (an approximate Kancamagus Highway and a generated Class
  VI practice trail; see `tools/sample-routes`). Ride your own club GPX files for the real thing.

## Data sources

- Roads, water and land cover: (c) OpenStreetMap contributors, ODbL. Downloaded at runtime
  through the Overpass API.
- Elevation: USGS 3D Elevation Program, via the AWS Terrain Tiles open dataset.
