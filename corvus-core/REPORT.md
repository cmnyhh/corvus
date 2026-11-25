# Corvus shared core — delivery report

`corvus-core/` is one copy of the pure rules that the 1.21.1/NeoForge project (`../dronepal`) and the
1.20.1/Forge project (`../dronepal-1.20.1`) used to implement twice. It contains no Minecraft, Forge
or NeoForge import (checked below), has no build script, and is compiled by both projects as an extra
source directory.

## 1. Directory

```
corvus-core/
├── TODO.md                                  remaining migration sites (owner-safe list)
├── REPORT.md                                this file
└── src/
    ├── main/java/dev/corvus/core/
    │   ├── Fuel.java                        fuelToFe(burnTicks) x2.5 half-up, flushRemainder(burnTicks, feReleased)
    │   ├── Solar.java                       State OFF/DIM/BRIGHT + ratePerTick(peak, state) floor
    │   ├── Bank.java                        percent + window(charge, capacity) 0..3, pushSplit(budget, caps, stored)
    │   ├── Power.java                       settleSecond(charge, capacity, drainPerTick, solarPerTick)
    │   ├── HardwareRules.java               online, drainPerTick, brownout
    │   └── FrameStats.java                  maxHealth, mitigate, followStep
    └── test/java/dev/corvus/core/
        └── CoreParityTest.java              21 tests / 146 assertions, plain JUnit 5
```

Every method carries Javadoc for its rounding and edge rules; the important ones:

| API | Rule |
|---|---|
| `Fuel.fuelToFe(int ticks)` | `ticks x 2.5`, `Math.round` (half-up): 1 t → 3, 3 t → 8, 1 601 t → 4 003; `<= 0` → 0 |
| `Fuel.flushRemainder(int ticks, long released)` | `fuelToFe - max(0, released)`, clamped to `int`, never negative |
| `Solar.state(day?, sky?, rain?, thunder?)` | night/fixed-time or covered sky → `OFF` first; then rain **or** thunder → `DIM`; else `BRIGHT` |
| `Solar.ratePerTick(peak, state)` | `OFF`/`peak <= 0` → 0; `BRIGHT` → peak; `DIM` → `peak / 2` (floor, no invented minimum) |
| `Bank.window(charge, capacity)` | percent rounded half-up, then `>=100→3, >=67→2, >=34→1`; `capacity <= 0` → 0 |
| `Bank.pushSplit(budget, caps, stored)` | eligible = `free > 0`; `share = budget/n`, remainder one FE at a time in array order; capped by free room |
| `Power.settleSecond(...)` | `clamp(clamp(charge - drain*20, 0, cap) + solar*20, 0, cap)` |
| `HardwareRules.online(u, p)` | `!userDisabled && powered` |
| `HardwareRules.brownout(e, c)` | `c > 0 && e < c / 5` (integer division, strict `<`) |
| `FrameStats.maxHealth(base, mult)` | `base x clamp(mult, 0..10)`, clamped to `[1, 1024]`, NaN mult → 1.0 |
| `FrameStats.mitigate(amount, mult, cap)` | `amount x clamp(mult, 0..1)`, then `min(cap)`; `cap <= 0` uncapped |
| `FrameStats.followStep(cur, target, accel, tick)` | applies `cur += (target-cur) x clamp(0.2 x min(5, accel), 0, 1)` `tick` times |

## 2. Wiring

Both `build.gradle` files end with the same plain-Gradle block (no plugin hook, no copy step):

```groovy
sourceSets {
    main { java.srcDir '../corvus-core/src/main/java' }
    test { java.srcDir '../corvus-core/src/test/java' }
}
```

- `dronepal/build.gradle:63-70`, `dronepal-1.20.1/build.gradle:135-142`.
- **Why this shape**: MDG (NeoForge) and ForgeGradle both build on the stock `java` plugin, so
  `sourceSets.main.java.srcDir` is compiled, jarred and reobfuscated/remapped exactly like project
  sources. `tasks.named('compileJava') { source ... }` was rejected because it would skip the test
  source set and the IDE model; copying into `src/main/java` was rejected because it re-creates the
  duplication the core exists to remove. Both jars contain `dev/corvus/core/*.class` (7 classes each,
  verified with `unzip -l`).
- JUnit 5 (same table on both sides): `mavenLocal()` + pinned 5.10.5 API/engine + 1.10.5 launcher
  (`dronepal/build.gradle:24-44`, `dronepal-1.20.1/build.gradle:62-101`). `~/.m2` already holds those
  artefacts, so the offline 1.21.1 build needs no network; the launcher is pinned because Gradle 8 no
  longer supplies it.

> Note: the two `sourceSets` blocks were already present in the tree when this work started (another
> agent added them at 15:11 while the projects were being edited); they were kept and verified rather
> than duplicated. The JUnit dependencies and `useJUnitPlatform()` are new.

## 3. Migration: solar state + output (the one migrated site)

Both panels now call `dev.corvus.core.Solar`; all local daylight/weather/rounding branches are gone.

**1.21.1** `dronepal/src/main/java/dev/dronepal/power/SolarGeneratorEntity.java`

```java
// before
public static int rate(Level level, BlockPos pos) {
    return switch (sunState(level, pos)) {
        case 2 -> ServerOptions.solarOutput();
        case 1 -> half(ServerOptions.solarOutput());
        default -> 0;
    };
}
public static int sunState(Level level, BlockPos pos) {
    if (!daylight(level)) return 0;
    if (!level.canSeeSky(pos.above())) return 0;
    return level.isRaining() || level.isThundering() ? 1 : 2;
}
private static int half(int peak) { return Math.max(1, (int) Math.round(peak * 0.5D)); }

// after
public static int rate(Level level, BlockPos pos) {
    return Solar.ratePerTick(ServerOptions.solarOutput(), state(level, pos));
}
public static Solar.State state(Level level, BlockPos pos) {
    if (level == null) return Solar.State.OFF;
    return Solar.state(daytimeByWorldClock(level), level.canSeeSky(pos.above()),
            level.isRaining(), level.isThundering());
}
public static int sunState(Level level, BlockPos pos) { return state(level, pos).code(); }
```

**1.20.1** `dronepal-1.20.1/src/main/java/dev/corvus/power/SolarGeneratorEntity.java`

```java
// before
public static int outputNow(Level level, BlockPos pos) {
    int peak = ServerOptions.solarOutput();
    return switch (sunLevel(level, pos)) {
        case 2 -> peak;
        case 1 -> Math.max(1, Mth.floor(peak * 0.5F));
        default -> 0;
    };
}
public static int sunLevel(Level level, BlockPos pos) {
    if (level == null) return 0;
    if (level.isNight()) return 0;                               // rain can read as night
    if (!level.canSeeSky(pos.above())) return 0;
    return level.isRaining() ? 1 : 2;                            // thunder ignored
}

// after
public static int outputNow(Level level, BlockPos pos) {
    return Solar.ratePerTick(ServerOptions.solarOutput(), state(level, pos));
}
public static Solar.State state(Level level, BlockPos pos) {
    if (level == null) return Solar.State.OFF;
    return Solar.state(daytimeByWorldClock(level), level.canSeeSky(pos.above()),
            level.isRaining(), level.isThundering());
}
public static int sunLevel(Level level, BlockPos pos) { return state(level, pos).code(); }
```

Behaviour changes this pins down (R4/§1.2 ③④): the 1.20.1 panel gains thunder halving and reads
daylight from the raw day clock instead of `Level.isNight()`; the dim value is `floor(peak/2)` without
the invented `max(1, …)`; both sides use the same state table, so `sunLevel`/`sunState` and the FE/t
cannot disagree. The 1.20.1 `reasonKey()` now switches on the same enum. Public helpers kept:
`rate`/`sunState`/`sunState()` (1.21.1), `outputNow`/`sunLevel`/`sunLevel()`/`reasonKey` (1.20.1).

## 4. Tests

`CoreParityTest` — 21 test methods, **146 assertion calls**, no Minecraft types. Scenarios: fuel
(coal/charcoal/coal block/blaze rod/lava bucket, non-fuels, half-up, remainder), solar (three states,
rain/thunder, floor, night/covered/fixed time, invalid peak), gauge (0/33/34/66/67/99/100 %, 66.7 % →
window 2), pushSplit (20/20, 41→21/20, full targets, room truncation, mismatched lengths), settlement
(drain-first, never negative, refill, clamp, zero capacity), hardware (online truth table, drain sum,
brown-out boundary at `capacity/5`), frame stats (max health clamp, mitigation cap, follow convergence).

Both projects run it from their own `test` task:

```
dronepal/build/test-results/test/TEST-dev.corvus.core.CoreParityTest.xml
  tests="21" skipped="0" failures="0" errors="0"
dronepal-1.20.1/build/test-results/test/TEST-dev.corvus.core.CoreParityTest.xml
  tests="21" skipped="0" failures="0" errors="0"
```

## 5. Verification (all commands really run)

```
# core alone
$ javac -d /tmp/core $(find corvus-core/src/main/java -name '*.java')
javac OK   (7 class files; also clean under --release 17 -Xlint:all -Werror)

# no platform imports
$ grep -rn "net\.minecraft\|net\.neoforged\|net\.minecraftforge" corvus-core/src
none

# 1.21.1 (15:20)
$ GRADLE_USER_HOME=../.dronepal-gradle JAVA_HOME=<corretto-21> ./gradlew --offline build -Djava.net.preferIPv4Stack=true
BUILD SUCCESSFUL in 11s
build/libs/渡鸦-随行无人机-0.1.0.jar            1,058,782 bytes   (7 dev/corvus/core/*.class entries)
test-results: TEST-dev.corvus.core.CoreParityTest.xml  tests="21" failures="0" errors="0"

# 1.20.1 (15:22)
$ GRADLE_USER_HOME=... JAVA_HOME=<jdk17> <gradle 8.14.3> build --no-daemon -Djava.net.preferIPv4Stack=true
BUILD SUCCESSFUL in 1m 2s
build/libs/渡鸦-随行无人机-1.20.1-0.1.0.jar    1,233,855 bytes   (7 dev/corvus/core/*.class entries)
test-results: TEST-dev.corvus.core.CoreParityTest.xml  tests="21" failures="0" errors="0"

# 1.21.1 GameTest (15:21)
$ ./gradlew --offline -PcuriosGameTest runCuriosGameTest
========= 97 GAME TESTS COMPLETE IN 27.47 s ======================
BUILD SUCCESSFUL in 1m 20s          (all 97 green, exit 0)
```

The suite count is 97, not the documented baseline of 90: another agent added the seven
`WaterPumpGameTest` cases at 15:16. That test set was briefly red at 15:17
(`singlesourcedrainsandstops`, because `mill/WaterPumpEntity.java:30` initialised
`private boolean infinite = true;` and never assigned it); the mill agent fixed their pump at
15:19:31 and the rerun at 15:21:08 is green — the solar/power blocks are unaffected either way.

## 6. Remaining TODO

See `TODO.md`. Short version: fuel (`Fuel`), gauge (`Bank.window`), push split (`Bank.pushSplit`),
per-second settlement (`HardwareRules.drainPerTick` + `Power.settleSecond`), online/brown-out
(`HardwareRules`) and frame stats (`FrameStats`) still live in both projects. The 1.21.1
`power/**`/`charging/**` and both `mill/**` trees were off-limits, so only one-line
`TODO(corvus-core)` comments were added next to the shared-rule call sites in `Hardware.java` and
`DroneEntity.java` on both sides; no business logic outside the two solar files was changed.
