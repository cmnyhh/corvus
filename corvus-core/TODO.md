# corvus-core — the shared cross-version rule table

One copy of the rules both builds used to spell out twice. Plain Java 17, **no** Minecraft, Forge or
NeoForge import, no build script of its own: each project adds these directories to its own source
sets (see the `Corvus shared core` block at the bottom of both `build.gradle` files) and the parity
test runs in each project's `test` task.

```
corvus-core/
  src/main/java/dev/corvus/core/
    Fuel.java          fuel burn ticks -> FE (x2.5, half-up) + flushed remainder
    Solar.java         OFF/DIM/BRIGHT + FE/t (rain/thunder x0.5, floor)
    Bank.java          rounded 0..3 gauge window + even push split with index-ordered remainder
    Power.java         once-per-second settle: drain first, then solar, clamped to [0, capacity]
    HardwareRules.java online (player switch AND power), drain sum, brown-out (< capacity/5)
    FrameStats.java    max health, per-hit mitigation with cap, follow-loop step
  src/test/java/dev/corvus/core/
    CoreParityTest.java   21 tests / 146 assertions, plain JUnit 5
```

## Wiring (why this shape)

- `sourceSets { main { java.srcDir '../corvus-core/src/main/java' } test { ... } }` in each project.
  No copy step, no second artefact to publish: the compiler reads the same files from both builds,
  so a rule cannot be fixed on one side only. Both plugins (MDG for NeoForge, ForgeGradle for Forge)
  accept extra source directories; this is the ordinary Gradle Java mechanism and needs no plugin
  hook, which is why it was preferred over `tasks.named('compileJava') { source ... }` (that would
  skip the test source set and the IDE model).
- JUnit 5 is resolved from `mavenLocal()` (`~/.m2` already holds 5.10.5), so the offline 1.21.1
  build needs no network; the launcher is pinned explicitly because Gradle 8 no longer ships it.
- The core jar entries land in the mod jar as `dev/corvus/core/*.class` (verified in
  `dronepal/build/libs/*-0.1.0.jar`).

## Migrated (proof it works)

- **Solar state + rate, both sides** — `dronepal/src/main/java/dev/dronepal/power/SolarGeneratorEntity.java`
  and `dronepal-1.20.1/src/main/java/dev/corvus/power/SolarGeneratorEntity.java` now call
  `Solar.state(...)` / `Solar.ratePerTick(...)`. Both deleted their own daylight/weather/rounding
  branches. The 1.20.1 panel therefore gains thunder-halving and clock-based daylight (it used
  `Level.isNight()` and `isRaining()` only) and the half output is floored without an invented
  minimum.

## Remaining TODO (deliberately not migrated yet)

The 1.21.1 `power/**`, `charging/**` and both `mill/**` trees are being edited by other agents; the
1.20.1 `power/**` files are owned by that project's data/hardware agent. The migration below is
mechanical, but doing it now would collide with those edits.

| Rule | Call site today | Replace with |
|---|---|---|
| fuel value + container remainder | 1.21.1 `power/FuelValues#feFromTicks`, `power/FuelBuffer#ignite`; 1.20.1 `power/FuelPower#fe`, `power/BurnerGeneratorEntity`, `charging/ChargingDockEntity` (1.20.1 eats the bucket: R5) | `Fuel.fuelToFe`, `Fuel.flushRemainder` + a platform `craftingRemainingItem` drop on the 1.20.1 side |
| battery gauge | 1.21.1 `power/BatteryBankEntity#percent` (truncates) + `chargeWindow`; 1.20.1 `BatteryBankEntity#bucket`/`percent` (rounds) | `Bank.window(charge, capacity)` (rounds; 66.7 % -> window 2 on both sides) |
| push split | 1.21.1 `power/PowerPush#push` (already even, keep the call); 1.20.1 `power/EnergyLink#push` (feeds face order: R5) | `Bank.pushSplit(budget, capacities, stored)` |
| per-second settlement | 1.21.1 `DroneSystems` tick (rounds each module then sums); 1.20.1 `Hardware#drainPerTick` + `ModuleRuntime#settle` | `HardwareRules.drainPerTick` + `Power.settleSecond` |
| module online | 1.21.1 `Hardware#online` (correct); 1.20.1 `Hardware#online` drops the player switch (R2) | `HardwareRules.online(userDisabled, powered)` |
| brown-out cut | 1.21.1 `Hardware#powered`; 1.20.1 `Hardware#powered` | `HardwareRules.brownout(energy, capacity)` |
| frame HP / hit cap / follow step | 1.21.1 `Frames` (`max_damage_per_hit` default) + `DroneEntity#followPosition` (0.2 x accel, clamp); 1.20.1 `Frames` + `DroneEntity#followPosition` (fixed 0.35, does not read `accel_response`) | `FrameStats.maxHealth`, `FrameStats.mitigate`, `FrameStats.followStep` |

Each row also has a one-line `TODO(corvus-core)` comment next to its rule in `Hardware.java` and
`DroneEntity.java` on both sides.
