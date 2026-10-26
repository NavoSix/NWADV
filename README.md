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
- **Earthworks.** Where a road runs across a hillside it is cut into it: a steep bank of bare
  soil on the uphill side (blasted rock cuts in places on maintained roads) and a built-up fill
  slope on the downhill side.
- **Banked corners.** Paved and gravel roads are banked through their bends, more on the faster
  roads.
- **Woods.** In stretches of back road and Class VI the canopy closes overhead: roadside trees
  lean out over the road and the light under them is dappled shade.
- **Unmapped sections.** Any part of your GPX that isn't on a mapped road is ridden as an
  unmaintained trail. Short stretches where the track just cuts a junction corner are ignored.

**Along the road** (`world/SceneryBuilder.kt`, drawn by `render/SceneryMesh.kt`)
- **Intersections.** The junctions where mapped roads meet are found from the map, so the real
  intersections a route passes through look like themselves.
  - Lesser roads get a stop sign and a painted stop line, and the lines on the road break where
    another road comes in.
  - Green street-name blades show both road names ("BEAR NOTCH RD").
  - The GPS names the road you turn onto ("Left onto Bear Notch Road"). Where the route changes
    road at a junction without much of a bend, the GPS tells you too ("Continue onto ...").
- **Buildings.** Real OpenStreetMap building footprints along the route, each with a lawn
  cleared from the woods and a driveway and mailbox: houses in New England colours, red barns
  (some with gambrel roofs), silos, sheds, white steepled churches, and stores with their name
  across the front.
  - Where the map has no buildings, houses and farms are placed where the side roads say people
    live. Farms come with a farmhouse, barn, silo, hay bales, a board or rail fence along the
    road, and a stone wall at the back of the field.
- **Roadside.**
  - Guard rails on high fills and over water, and railings on bridges.
  - Stone walls and fences where fields meet the road, and old stone walls running through the
    woods along back roads.
  - Speed limit signs, curve warnings before tight bends, and "Brake for Moose" signs on the
    main roads.
  - All of this stays off Class VI roads and trails.
- **Landmarks.** Each one is named on the GPS map, and a card names it as you ride past.
  - Viewpoints, with the view cleared below them and a "Scenic view" sign.
  - Named boulders and other big glacial erratics in the woods.
  - Churches, monuments, New Hampshire historical markers, fire towers, waterfalls and other
    attractions.
  - Covered bridges (`bridge=covered`), housed in barn-red walls and a roof you ride through.
  - Peaks on the map, and any waypoints in your GPX file (the club's lunch stop, a gas
    station), with a brown sign by the road.
- **Solid.** Buildings, silos, towers and big boulders stop the bike, just as trees do.
- **Offline after first load.** Everything downloaded is cached, so a ride works without a
  connection the next time.

**GPX and GPS**
- **Collapsible.** Hide the map to a one-line next-turn strip with the Hide button.
- **Loading.** Open GPX tracks or routes from the in-app picker, or straight from email, Drive or
  a file manager with *Open with* or *Share*.
- **GPS display.** A track-up moving map with your route in magenta, roads styled by type (Class
  VI dashed brown), and your breadcrumb trail.
- **Directions and data.** Next turn and its distance (with the road name at real
  intersections), an off-route warning, distance remaining, elevation, heading and odometer.
  Landmarks show on the map.

**Physics** (`core/src/main/kotlin/.../physics`)
- **Tyres.** A combined-slip model with a friction circle, so braking or wheelspin eats cornering
  grip. Each surface has its own grip, peak slip and slide behaviour: pavement lets go suddenly,
  while gravel and sand keep pushing, so you can steer a slide with the throttle.
- **Leaning.** Lean comes from the real moment balance about the contact patches, so turning
  requires countersteering. In the default *lean assist* mode a virtual rider does the
  countersteering for the lean you ask for; *countersteer* mode gives you the bars directly.
  Below 5 mph the bike stays upright and the bars steer directly. Above that, lean fades in by
  10 mph and then grows with speed.
- **Chassis.** Unsprung wheels on tyre springs, plus digressive fork and shock damping. Wheels
  envelope rocks and ledges rather than passing through them, and the contact normal pushes back
  on square edges. That is also why ruts grab the front wheel, off-camber corners push you
  downhill, and climbs cost power.
- **Drivetrain and aids.** Engine torque curve, 6-speed gearbox and auto-clutch. ABS (with
  rear-lift mitigation), traction control and wheelie control all run on the same physics and can
  each be switched off; ABS can be set to front only for off-road riding.
- **Class VI roads** get procedural ruts with a grass crown, embedded rocks, wet and dry ledge,
  water bars, washouts on steep pitches, mud holes in low spots, and fords where they cross
  streams. Fallen trees and large rocks lie across Class VI roads (and only those), at fixed
  spots so you can learn the line. Paved back roads get frost heaves and potholes; gravel roads get washboard and loose
  edges.
- **Rider technique.**
  - Stand on the pegs for better absorption on rough ground.
  - Shift your weight forward or back.
  - In the air, throttle and rear brake pitch the bike, just as they do on a real bike.
  - Hold the rear brake at a standstill with no throttle to paddle backwards with your feet.
- **Water crossings** have a water surface with moving ripples. Riding through throws a wake of
  rings off both wheels and spray off the front.
- **Sound.** The engine, wind and tyres are synthesised from the physics. The tyres sound
  different on each surface (gravel crunch, rock knocks, mud, splashing water) without harsh
  static.
- **Crashes.** Lowsides, tip-overs, looping a wheelie, going over the bars, trees, buildings and boulders, casing a
  landing, and drowning the bike in deep water. After a crash, or any time from the pause
  menu with **Reset position**, you're put back on the route a few metres behind.

## Bikes

Pick a bike in the **Garage** (main menu, *Bike*). Each one has its own physics, engine sound
and 3D model, and the garage shows it on a turntable you can spin with a drag. Bikes carry their
owner's name; the garage model is finely detailed (cables, bolts, chain links, gauge needles),
while a lighter version of the same model is used on the road. Club members'
bikes are added one at a time in `core/src/main/kotlin/.../bikes/Bikes.kt`.

- **Donovan's Royal Enfield Himalayan 411**, modelled on the real bike as Donovan has it set up:
  - Rock Red, with a tall screen, phone mount, Tusk handguards, crash bars, tank guards, skid
    plate, pannier frames and rear rack.
  - Three more factory paint schemes to earn: Lake Blue (blue over white), White Flame from the
    Scram 411 (white and red), and Pine Green (camouflage on the tank, side panels and fenders).
    Each is unlocked by an achievement on the road; what it takes stays a secret until you've
    done it, when a notice tells you. Pick unlocked paints in the garage; the choice is
    remembered for each bike. Only riding the Himalayan itself counts (not auto-ride). Future
    bikes get their own paints, earned only on that bike.
  - 411 cc air/oil-cooled single (24 hp at 6,500 rpm, 32 N m at 4,000-4,500), 5-speed.
  - 207 kg with the accessories.
  - 21"/17" spoked wheels on knobbies, 200/180 mm travel, 26.5 deg rake.
  - The engine sound is the real bike: Donovan recorded it holding idle, 1,900, 2,850, 4,000
    and 4,800 rpm. The game plays one recorded firing cycle per firing, blended between the two
    recordings nearest the current rpm, with the single's natural cycle-to-cycle lope.
  - Ridden with a 90 kg rider, it does 0-100 km/h in about 15 s and tops out near 125 km/h.
- **Mid-weight ADV twin**: the original generic 100 hp stand-in.

### Recording a bike's engine

Record the bike in neutral holding steady revs (idle, then roughly 2k, 3k, 4k and 5k) for about
20 s each, phone close to the exhaust. Then build a grain bank:

```
cd tools/engine-samples
python3 make_grains.py ../../assets/sounds/<bike>.grains <bike>/idle.mp3 <bike>/2k.mp3 ...
```

The tool measures each take's real rpm from its firing rate, so the labels don't need to be
exact. Point the bike's `EngineSound` at the new asset (`grainAsset`).

### Navigation screen

In the helmet view the moving map is shown on the bike's own phone or GPS rather than the HUD
(the speed and rpm move to the top right). Each bike's look declares where its screen is with a
`NavScreen`: centre, lit-area width and height in metres, tilt toward the rider, and whether it
sits on the bars (and steers) or on the frame. The map is rendered to a texture of that shape,
and the helmet camera tilts down as far as needed to keep the screen in frame, seated or standing,
so a new bike only needs its device's position and size. A bike without a `NavScreen` keeps the
map on the HUD.

## Difficulty

The **Difficulty** setting (menu) defaults to *Easy*:

- **Easy** adds a stabiliser that holds the lean you ask for and shrugs off knocks from rocks and
  ruts, keeps the bike tracking where it's pointed over bumps, keeps the chassis level over
  obstacles, gives 40% more tyre grip (80% more sideways) and a riding buddy who pushes you out
  when you're stuck.
- **Normal** gives a lighter stabiliser and 20-35% more grip, and still has the buddy push.
- **Realistic** is pure physics.

The suspension is set up plush for trail riding: 270/260 mm of travel (rally-style), about 35%
sag, soft high-speed damping that soaks up sharp hits, a progressive bottoming cushion, and
aired-down tyres.

## Controls

| | Phone | Keyboard |
|---|---|---|
| Lean / steer | Joystick (left thumb, appears where you touch) | A / D or arrows |
| Weight fore / aft | Joystick up / down | R / V |
| Throttle | GAS slider (right edge) | W / Up (Shift = gentle) |
| Front brake | FRONT BRAKE slider | S / Down |
| Rear brake (hold at a stop to reverse) | REAR button | Space |
| Stand on pegs | Stand button | F |
| Hide / show GPS map | Hide / Map button | |
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

Desktop development flags include `--osm-file <saved Overpass JSON>` (use a saved map
download instead of the network) and `--dump-scenery` (print what was placed along the route).

The Android module is only included when an Android SDK is found, so the game code can still be
built and tested without one.

### Layout

- `core/`: all game code (Kotlin, libGDX): map data, terrain, physics, rendering, HUD
- `android/`: Android launcher (GPX file picker, "Open with", vibration)
- `desktop/`: desktop launcher for development
- `assets/routes/`: two sample routes (an approximate Kancamagus Highway and a generated Class
  VI practice trail; see `tools/sample-routes`). Ride your own club GPX files for the real thing.

## Data sources

- Roads, water, land cover, buildings and landmarks: (c) OpenStreetMap contributors, ODbL.
  Downloaded at runtime through the Overpass API.
- Elevation: USGS 3D Elevation Program, via the AWS Terrain Tiles open dataset.
