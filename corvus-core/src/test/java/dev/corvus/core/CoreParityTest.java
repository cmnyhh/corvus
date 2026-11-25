package dev.corvus.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shared parity table for {@code dev.corvus.core}.
 *
 * <p>One test class, compiled and run by <b>both</b> builds (1.21.1/NeoForge and 1.20.1/Forge), so a
 * rule can never be "fixed" on one side only. It deliberately has no Minecraft import: every value on
 * the left of an assertion is a plain number that a caller passes in, so the numbers here are the
 * contract the two entity classes have to honour.</p>
 *
 * <p>Every scenario named in the parity report (§1.1 fuel, §1.2 solar, §1.4/§1.5 power + gauge,
 * §1.11 push split, R4/R5) appears at least once: the historical drift is what the expected values
 * pin down (66.7 % → window 2, 40 FE split evenly, thunder halves output, drain is paid before solar
 * is banked).</p>
 */
class CoreParityTest {

    // ------------------------------------------------------------------ fuel (§1.1, R5)

    @Test
    @DisplayName("fuel: vanilla burn times convert at 2.5 FE/tick")
    void fuelVanillaValues() {
        assertEquals(4_000L, Fuel.fuelToFe(1_600), "coal / charcoal 1,600 t");
        assertEquals(4_000L, Fuel.fuelToFe(1_600), "charcoal shares the coal burn time");
        assertEquals(40_000L, Fuel.fuelToFe(16_000), "coal block 16,000 t");
        assertEquals(6_000L, Fuel.fuelToFe(2_400), "blaze rod 2,400 t");
        assertEquals(50_000L, Fuel.fuelToFe(20_000), "lava bucket 20,000 t");
        assertEquals(2_000L, Fuel.fuelToFe(800), "planks 800 t");
    }

    @Test
    @DisplayName("fuel: non-fuels are worth nothing and never go negative")
    void fuelNonFuel() {
        assertEquals(0L, Fuel.fuelToFe(0), "0 ticks is not a fuel");
        assertEquals(0L, Fuel.fuelToFe(-1), "a negative burn time cannot mint energy");
        assertEquals(0L, Fuel.fuelToFe(Integer.MIN_VALUE), "clamped, not wrapped");
        assertEquals(3L, Fuel.fuelToFe(1), "1 tick is a fuel");
    }

    @Test
    @DisplayName("fuel: half FE rounds up, one item at a time")
    void fuelHalfRoundsUp() {
        assertEquals(3L, Fuel.fuelToFe(1), "2.5 -> 3");
        assertEquals(8L, Fuel.fuelToFe(3), "7.5 -> 8");
        assertEquals(13L, Fuel.fuelToFe(5), "12.5 -> 13");
        assertEquals(3_000L, Fuel.fuelToFe(1_200), "1,200 t is exact");
        assertEquals(4_003L, Fuel.fuelToFe(1_601), "4,002.5 -> 4,003");
        assertEquals(5L, Fuel.fuelToFe(2), "5.0 -> 5 (an even tick count has no half)");
    }

    @Test
    @DisplayName("fuel: the flushed remainder is total minus what was already paid")
    void fuelRemainder() {
        assertEquals(4_000, Fuel.flushRemainder(1_600), "nothing paid yet");
        assertEquals(4_000, Fuel.flushRemainder(1_600, 0L), "explicit zero");
        assertEquals(2_500, Fuel.flushRemainder(1_600, 1_500L), "half burnt");
        assertEquals(0, Fuel.flushRemainder(1_600, 4_000L), "fully paid");
        assertEquals(0, Fuel.flushRemainder(1_600, 9_999L), "overpaid cannot go negative");
        assertEquals(4_000, Fuel.flushRemainder(1_600, -500L), "a negative payout counts as 0, so nothing was paid");
        assertEquals(3, Fuel.flushRemainder(1, 0L), "half-up applies here too");
        assertEquals(1, Fuel.flushRemainder(1, 2L), "3 - 2");
    }

    // ------------------------------------------------------------------ solar (§1.2, R4)

    @Test
    @DisplayName("solar: clear day with open sky is BRIGHT")
    void solarBright() {
        assertEquals(Solar.State.BRIGHT, Solar.state(true, true, false, false));
        assertEquals(2, Solar.state(true, true, false, false).code(), "block-state code is 2");
        assertEquals(60, Solar.ratePerTick(60, Solar.State.BRIGHT), "peak 60");
        assertEquals(1, Solar.ratePerTick(1, Solar.State.BRIGHT), "a 1 FE/t panel still makes 1 FE/t");
    }

    @Test
    @DisplayName("solar: rain or thunder halves it, and thunder counts on its own")
    void solarDim() {
        assertEquals(Solar.State.DIM, Solar.state(true, true, true, false), "rain");
        assertEquals(Solar.State.DIM, Solar.state(true, true, false, true), "thunder without rain");
        assertEquals(Solar.State.DIM, Solar.state(true, true, true, true), "storm");
        assertEquals(1, Solar.state(true, true, true, false).code(), "block-state code is 1");
        assertEquals(30, Solar.ratePerTick(60, Solar.State.DIM), "60 / 2");
    }

    @Test
    @DisplayName("solar: dim output floors, and a 0..1 peak stays 0")
    void solarDimRoundsDown() {
        assertEquals(30, Solar.ratePerTick(61, Solar.State.DIM), "30.5 -> 30 (floor, not round)");
        assertEquals(0, Solar.ratePerTick(1, Solar.State.DIM), "0.5 -> 0; no invented FE");
        assertEquals(0, Solar.ratePerTick(0, Solar.State.DIM), "a disabled panel makes nothing");
        assertEquals(29, Solar.ratePerTick(59, Solar.State.DIM), "29.5 -> 29");
    }

    @Test
    @DisplayName("solar: night, fixed time and a covered sky are OFF")
    void solarOff() {
        assertEquals(Solar.State.OFF, Solar.state(false, true, false, false), "night");
        assertEquals(Solar.State.OFF, Solar.state(false, true, true, true), "night wins over weather");
        assertEquals(Solar.State.OFF, Solar.state(true, false, false, false), "roof / overhang");
        assertEquals(Solar.State.OFF, Solar.state(true, false, true, true), "shade wins over weather");
        assertEquals(0, Solar.state(false, true, false, false).code(), "block-state code is 0");
        assertEquals(0, Solar.ratePerTick(60, Solar.State.OFF), "off is always 0 FE/t");
        assertEquals(0, Solar.ratePerTick(60, null), "a missing state is off");
        assertEquals(0, Solar.ratePerTick(-5, Solar.State.BRIGHT), "a negative peak cannot invert");
        assertEquals(Solar.State.BRIGHT, Solar.State.fromCode(2), "code round-trip");
        assertEquals(Solar.State.OFF, Solar.State.fromCode(0), "code round-trip");
        assertThrows(IllegalArgumentException.class, () -> Solar.State.fromCode(3), "unknown code is loud");
    }

    // ------------------------------------------------------------------ gauge (§1.5, R5)

    @Test
    @DisplayName("bank: percentages are rounded, so 2/3 reads 67")
    void bankPercent() {
        assertEquals(0, Bank.percent(0, 500_000), "empty");
        assertEquals(33, Bank.percent(33, 100), "33 %");
        assertEquals(34, Bank.percent(34, 100), "34 %");
        assertEquals(67, Bank.percent(1_334, 2_000), "66.7 % -> 67 (both builds disagree here today)");
        assertEquals(100, Bank.percent(500_000, 500_000), "full");
        assertEquals(100, Bank.percent(600_000, 500_000), "overfull is clamped to 100");
        assertEquals(0, Bank.percent(100, 0), "capacity 0 must not divide by zero");
        assertEquals(0, Bank.percent(-1, 100), "negative charge reads empty");
    }

    @Test
    @DisplayName("bank: the model segment is displayed as 0..3 on both sides, never 1..4")
    void bankWindowDomain() {
        assertEquals(4, Bank.WINDOWS, "four segments");
        assertEquals(3, Bank.LAST_WINDOW, "the display domain ends at 3, not 4 (R5)");
        assertEquals(Bank.LAST_WINDOW, Bank.window(100, 100), "a full bank prints its own segment");
        assertEquals(0, Bank.window(0, 100), "an empty bank prints 0");
        for (int percent = 0; percent <= 100; percent++) {
            int window = Bank.window(percent, 100);
            assertTrue(window >= 0 && window <= Bank.LAST_WINDOW, "segment out of 0..3 at " + percent + "%: " + window);
        }
    }

    @Test
    @DisplayName("bank: the model segment switches at 34/67/100 of the rounded percentage")
    void bankWindow() {
        assertEquals(0, Bank.window(0, 100), "0 %");
        assertEquals(0, Bank.window(33, 100), "33 % is still segment 0");
        assertEquals(1, Bank.window(34, 100), "34 % is segment 1");
        assertEquals(1, Bank.window(66, 100), "66 % is still segment 1");
        assertEquals(2, Bank.window(67, 100), "67 % is segment 2");
        assertEquals(2, Bank.window(99, 100), "99 % is still segment 2");
        assertEquals(3, Bank.window(100, 100), "100 % is segment 3");
        assertEquals(2, Bank.window(2, 3), "66.7 % -> 67 % -> segment 2 (R5's example)");
        assertEquals(3, Bank.window(2_000, 2_000), "a 2,000 cell full is segment 3");
        assertEquals(0, Bank.window(5, 0), "no capacity means no gauge");
        assertEquals(3, Bank.window(4, 4), "4/4");
    }

    // ------------------------------------------------------------------ push split (§1.11)

    @Test
    @DisplayName("pushTo: the first receiver is filled first, so 40 FE across two is 40/0, not 20/20")
    void pushToFillsInOrder() {
        assertArrayEquals(new int[] {40, 0}, Bank.pushTo(40, new int[] {1_000, 1_000}, new int[] {0, 0}),
                "fill in order (the 1.21.1 push used to split evenly, R5)");
        assertArrayEquals(new int[] {10, 30}, Bank.pushTo(40, new int[] {10, 1_000}, new int[] {0, 0}),
                "the first receiver's 10 FE room leaves 30 for the second");
        assertArrayEquals(new int[] {10, 10, 20}, Bank.pushTo(40, new int[] {10, 10, 1_000}, new int[] {0, 0, 0}),
                "three receivers fill left to right");
        assertArrayEquals(new int[] {1, 0, 0}, Bank.pushTo(1, new int[] {10, 10, 10}, new int[] {0, 0, 0}),
                "a 1 FE budget goes to the first receiver");
        assertEquals(40, Bank.planned(Bank.pushTo(40, new int[] {1_000, 1_000}, new int[] {0, 0})),
                "the plan moves the whole budget when there is room");
    }

    @Test
    @DisplayName("pushTo: full receivers are skipped and the budget carries on to the next one")
    void pushToSkipsFullReceivers() {
        assertArrayEquals(new int[] {0, 40}, Bank.pushTo(40, new int[] {1_000, 1_000}, new int[] {1_000, 0}),
                "a full first receiver does not block the second");
        assertArrayEquals(new int[] {0, 0}, Bank.pushTo(40, new int[] {1_000, 1_000}, new int[] {1_000, 1_000}),
                "nobody can take power, so the sender keeps it");
        assertArrayEquals(new int[] {0, 0}, Bank.pushTo(0, new int[] {1_000, 1_000}, new int[] {0, 0}),
                "a zero budget moves nothing");
        assertArrayEquals(new int[] {0, 0}, Bank.pushTo(-5, new int[] {1_000, 1_000}, new int[] {0, 0}),
                "a negative budget moves nothing");
        assertArrayEquals(new int[] {0, 40}, Bank.pushTo(40, new int[] {0, 1_000}, new int[] {0, 0}),
                "a capacity-0 target is skipped, so the second one sees the whole budget");
        assertArrayEquals(new int[] {0, 0}, Bank.pushTo(40, new int[] {5, 0}, new int[] {5, 0}),
                "a full target and a capacity-0 target take nothing between them");
    }

    @Test
    @DisplayName("pushTo: nobody is credited more than its own free room")
    void pushToRoom() {
        assertArrayEquals(new int[] {5, 5}, Bank.pushTo(40, new int[] {5, 5}, new int[] {0, 0}),
                "both capped, the rest stays with the sender");
        assertArrayEquals(new int[] {0, 12}, Bank.pushTo(24, new int[] {100, 100}, new int[] {100, 88}),
                "the full target gets nothing, the second gets its 12 FE of room");
        assertEquals(12, Bank.planned(Bank.pushTo(24, new int[] {100, 100}, new int[] {100, 88})),
                "the plan never invents FE");
        assertThrows(IllegalArgumentException.class,
                () -> Bank.pushTo(10, new int[] {1, 2}, new int[] {0}), "length mismatch is a caller bug");
        assertThrows(IllegalArgumentException.class,
                () -> Bank.pushTo(10, null, new int[] {0}), "null array is a caller bug");
    }

    @Test
    @DisplayName("probe: a burner only lights fuel when a neighbour would accept 1 FE")
    void pushProbe() {
        assertEquals(1, Bank.PROBE_FE, "the probe is exactly one FE");
        assertTrue(Bank.canTakeProbe(1), "an empty neighbour accepts the probe");
        assertTrue(Bank.canTakeProbe(40), "a half-full neighbour accepts the probe");
        assertFalse(Bank.canTakeProbe(0), "a full neighbour answers 0, so no fuel is burnt (R5)");
        assertFalse(Bank.canTakeProbe(-1), "a negative answer is not an acceptance");
    }

    // ------------------------------------------------------------------ settlement (§1.4, R5)

    @Test
    @DisplayName("power: drain is paid before solar is banked, and nothing goes negative")
    void settleDrainFirst() {
        assertEquals(0L, Power.settleSecond(100, 1_000, 10, 0), "10 FE/t for 20 ticks empties 100 FE");
        assertEquals(0L, Power.settleSecond(100, 1_000, 1_000, 0), "a big drain floors at 0, never negative");
        assertEquals(100L, Power.settleSecond(100, 1_000, 10, 5), "100 - 200 floors at 0, then + 100");
        assertEquals(100L, Power.settleSecond(100, 1_000, 5, 5), "100 - 100 + 100");
        assertEquals(250L, Power.settleSecond(250, 1_000, 0, 0), "idle second is unchanged");
    }

    @Test
    @DisplayName("power: solar refills up to capacity, never past it")
    void settleRefill() {
        assertEquals(1_000L, Power.settleSecond(900, 1_000, 0, 10), "clamped to capacity");
        assertEquals(1_000L, Power.settleSecond(0, 1_000, 0, 10_000), "a huge gain is clamped");
        assertEquals(60L, Power.settleSecond(0, 1_000, 0, 3), "3 FE/t for 20 ticks");
        assertEquals(1L, Power.settleSecond(1, 1_000, 0, 0), "one FE stays one FE");
        assertEquals(500L, Power.settleSecond(900, 1_000, 20, 0), "900 - 400");
        assertEquals(0L, Power.settleSecond(500, 0, 1, 1), "no capacity means no charge");
        assertEquals(0L, Power.settleSecond(-50, 1_000, 0, 0), "negative charge counts as 0");
        assertEquals(500L, Power.settleSecond(500, 1_000, -10, -10), "negative rates are treated as 0");
        assertEquals(1_024L, Power.settleSecond(0, 1_024, 0, 1_000_000), "no overflow, clamped");
    }

    // ------------------------------------------------------------------ hardware (§1.4, R2)

    @Test
    @DisplayName("hardware: online needs both the player switch and power")
    void online() {
        assertTrue(HardwareRules.online(false, true), "enabled and powered");
        assertFalse(HardwareRules.online(true, true), "the wheel switch wins (R2's bug)");
        assertFalse(HardwareRules.online(false, false), "no charge");
        assertFalse(HardwareRules.online(true, false), "neither");
    }

    @Test
    @DisplayName("hardware: drain sums only the online modules")
    void drainPerTick() {
        assertEquals(40, HardwareRules.drainPerTick(new int[] {10, 20, 30}, new int[] {1, 0, 1}),
                "two modules online");
        assertEquals(0, HardwareRules.drainPerTick(new int[] {10, 20}, new int[] {0, 0}), "all switched off");
        assertEquals(60, HardwareRules.drainPerTick(new int[] {10, 20, 30}, new int[] {1, 1, 1}), "all online");
        assertEquals(10, HardwareRules.drainPerTick(new int[] {10, -20}, new int[] {1, 1}),
                "a negative wattage cannot refund power");
        assertEquals(0, HardwareRules.drainPerTick(new int[0], new int[0]), "no modules at all");
        assertThrows(IllegalArgumentException.class,
                () -> HardwareRules.drainPerTick(new int[] {1, 2}, new int[] {1}), "length mismatch");
    }

    @Test
    @DisplayName("hardware: brown-out is strictly below capacity/5 (integer division)")
    void brownout() {
        assertTrue(HardwareRules.brownout(0, 500_000), "empty");
        assertTrue(HardwareRules.brownout(99_999, 500_000), "one FE below the line");
        assertFalse(HardwareRules.brownout(100_000, 500_000), "exactly the line is not a brown-out");
        assertFalse(HardwareRules.brownout(400_000, 500_000), "plenty of charge");
        assertTrue(HardwareRules.brownout(0, 5), "capacity 5 -> threshold 1, 0 < 1");
        assertFalse(HardwareRules.brownout(1, 5), "capacity 5 -> threshold 1, 1 is not < 1");
        assertFalse(HardwareRules.brownout(0, 0), "no battery is not a brown-out");
        assertTrue(HardwareRules.brownout(-1, 100), "a negative reading is below any threshold");
    }

    @Test
    @DisplayName("hardware: a module switched off in the wheel stays off and stops drawing (R2)")
    void disabledModuleStaysOff() {
        int[] watts = {5, 14, 2};
        boolean[] disabled = {true, false, false};
        for (int second = 0; second < 15; second++) {
            int[] flags = new int[watts.length];
            for (int i = 0; i < watts.length; i++) {
                flags[i] = HardwareRules.online(disabled[i], true) ? 1 : 0;
            }
            assertEquals(16, HardwareRules.drainPerTick(watts, flags),
                    "carry is off, so only combat 14 + light 2 draw at second " + second);
            assertFalse(HardwareRules.online(disabled[0], true), "carry is still off at second " + second);
        }
        assertEquals(0, HardwareRules.drainPerTick(new int[] {5}, new int[] {0}),
                "and an all-off drone draws nothing at all");
    }

    // ------------------------------------------------------------------ dialogue intents (R3)

    @Test
    @DisplayName("intents: both builds accept every implemented order, and only those")
    void intentsImplemented() {
        for (String id : new String[] {"scan", "scan.hostile", "scan.ore", "order.heal", "order.fetch",
                "order.supply", "order.light", "order.cover", "order.hold", "order.work", "order.idle"}) {
            assertTrue(IntentRules.implemented(id), "implemented: " + id);
        }
        assertFalse(IntentRules.implemented("order.place"), "order.place is answered by its data line, not run");
        assertFalse(IntentRules.implemented("meta.help"), "chat/lookup intents are not actions");
        assertFalse(IntentRules.implemented(null), "no id is not an action");
        assertEquals(11, IntentRules.IMPLEMENTED.size(), "the whitelist is exactly the eleven orders");
    }

    @Test
    @DisplayName("intents: the module gate is pinned per order, and order.supply needs carry (not link)")
    void intentModules() {
        assertEquals("carry", IntentRules.moduleFor("order.supply", "link"),
                "a data pack cannot put supply back on link (R3)");
        assertEquals("carry", IntentRules.moduleFor("order.fetch", null), "fetch is a cargo order");
        assertEquals("medic", IntentRules.moduleFor("order.heal", "carry"), "heal is pinned to medic");
        assertEquals("scan", IntentRules.moduleFor("scan.hostile", ""), "hostile scan is the scan module");
        assertEquals("combat", IntentRules.moduleFor("order.cover", null), "cover is the weapon bus");
        assertEquals("", IntentRules.moduleFor("order.work", null), "work needs a mill, not a module");
        assertEquals("link", IntentRules.moduleFor("custom.order", "link"), "unknown ids keep their data gate");
        assertEquals("", IntentRules.moduleFor("custom.order", null), "and fall back to no gate");
    }

    // ------------------------------------------------------------------ frame stats

    @Test
    @DisplayName("frame: max health scales and never leaves [1, 1024]")
    void maxHealth() {
        assertEquals(144.0D, FrameStats.maxHealth(144, 1.0D), 1e-9, "no multiplier");
        assertEquals(200.0D, FrameStats.maxHealth(80, 2.5D), 1e-9, "2.5x");
        assertEquals(1.0D, FrameStats.maxHealth(80, 0.0D), 1e-9, "a frame is never dead");
        assertEquals(1.0D, FrameStats.maxHealth(0, 1.0D), 1e-9, "a zero base is lifted to 1");
        assertEquals(80.0D, FrameStats.maxHealth(80, Double.NaN), 1e-9, "NaN means no change");
        assertEquals(1_024.0D, FrameStats.maxHealth(1_024, 10.0D), 1e-9, "capped at the validated ceiling");
        assertEquals(800.0D, FrameStats.maxHealth(80, 1_000.0D), 1e-9, "the multiplier is clamped to 10");
    }

    @Test
    @DisplayName("frame: mitigation scales the hit, then applies the per-hit cap")
    void mitigate() {
        assertEquals(20.0D, FrameStats.mitigate(20, 1.0D, 0), 1e-9, "unmitigated and uncapped");
        assertEquals(10.0D, FrameStats.mitigate(20, 0.5D, 0), 1e-9, "half");
        assertEquals(12.0D, FrameStats.mitigate(20, 0.9D, 12), 1e-9, "the cap wins over the multiplier");
        assertEquals(10.0D, FrameStats.mitigate(20, 0.5D, 12), 1e-9, "under the cap");
        assertEquals(5.0D, FrameStats.mitigate(20, 0.25D, 12), 1e-9, "quarter, still under the cap");
        assertEquals(0.0D, FrameStats.mitigate(20, 0.0D, 12), 1e-9, "fully blocked");
        assertEquals(20.0D, FrameStats.mitigate(20, 3.0D, 0), 1e-9, "a multiplier above 1 is clamped");
        assertEquals(0.0D, FrameStats.mitigate(-5, 1.0D, 0), 1e-9, "negative damage is 0");
        assertEquals(0.0D, FrameStats.mitigate(Double.NaN, 1.0D, 0), 1e-9, "NaN damage is 0");
        assertEquals(15.0D, FrameStats.mitigate(20, 0.75D, 0), 1e-9, "three quarters");
    }

    @Test
    @DisplayName("frame: the follow step converges without overshooting")
    void followStep() {
        assertEquals(0.0D, FrameStats.followStep(0, 100, 1.0D, 0), 1e-9, "tick 0 is a no-op");
        assertEquals(20.0D, FrameStats.followStep(0, 100, 1.0D, 1), 1e-9, "one step covers 20 %");
        assertEquals(36.0D, FrameStats.followStep(0, 100, 1.0D, 2), 1e-9, "20 + 16");
        assertEquals(100.0D, FrameStats.followStep(0, 100, 5.0D, 1), 1e-9, "accel 5 snaps in one tick");
        assertEquals(0.0D, FrameStats.followStep(0, 100, 0.0D, 50), 1e-9, "accel 0 never moves");
        assertEquals(0.0D, FrameStats.followStep(0, 100, -3.0D, 10), 1e-9, "negative accel never moves");
        assertTrue(FrameStats.followStep(0, 100, 1.0D, 100) > 99.99D, "converges to the target");
        assertTrue(FrameStats.followStep(0, 100, 1.0D, 100) <= 100.0D, "never overshoots");
        assertTrue(FrameStats.followStep(100, 0, 1.0D, 100) > 0.0D && FrameStats.followStep(100, 0, 1.0D, 100) < 0.01D,
                "converges from above too");
        assertEquals(100.0D, FrameStats.followStep(0, 100, 10.0D, 1), 1e-9,
                "accel is clamped at 5, so the fraction is 1.0 (not 2.0)");
        assertEquals(0.0D, FrameStats.followStep(0, 0, 1.0D, 10), 1e-9, "already at the station");
    }

    // ------------------------------------------------------------------ work cycle (§3, both clients)

    @Test
    @DisplayName("work: the phase table is 0-23 scan, 24-39 drop, 40-59 reset, modulo the period")
    void workPhaseBoundaries() {
        assertEquals(60, WorkCycle.PERIOD, "one cycle is 3 s");
        assertEquals(24, WorkCycle.SCAN_END, "scan ends at 24");
        assertEquals(40, WorkCycle.DROP_END, "drop ends at 40");
        assertEquals(0, WorkCycle.LAMP_SWAP_TICK - WorkCycle.SCAN_END, "the lamp swaps when the drop starts");
        assertEquals(WorkCycle.Phase.SCAN, WorkCycle.phase(0), "tick 0");
        assertEquals(WorkCycle.Phase.SCAN, WorkCycle.phase(23), "last scan tick");
        assertEquals(WorkCycle.Phase.DROP, WorkCycle.phase(24), "first drop tick");
        assertEquals(WorkCycle.Phase.DROP, WorkCycle.phase(39), "last drop tick");
        assertEquals(WorkCycle.Phase.RESET, WorkCycle.phase(40), "first reset tick");
        assertEquals(WorkCycle.Phase.RESET, WorkCycle.phase(59), "last reset tick");
        assertEquals(WorkCycle.Phase.SCAN, WorkCycle.phase(60), "the cycle wraps");
        assertEquals(WorkCycle.Phase.SCAN, WorkCycle.phase(120 + 4), "two cycles and 4 ticks");
        assertEquals(WorkCycle.Phase.RESET, WorkCycle.phase(-1), "a negative tick is the previous cycle's reset");
        assertEquals(WorkCycle.Phase.RESET, WorkCycle.phase(-20), "80 ticks before the wrap is the reset");
        assertEquals(WorkCycle.Phase.DROP, WorkCycle.phase(-30), "90 ticks before the wrap is the drop");
    }

    @Test
    @DisplayName("work: hoverHeight is HOVER outside the dip, DIP at 32, and continuous at 23/24 and 39/40")
    void workHoverHeight() {
        assertEquals(0.35D, WorkCycle.HOVER, 1e-9, "hover constant");
        assertEquals(0.18D, WorkCycle.DIP, 1e-9, "dip constant");
        assertEquals(0.35D, WorkCycle.DIP + 0.17D, 1e-9, "the dip is 0.17 blocks below the hover");
        assertEquals(WorkCycle.HOVER, WorkCycle.hoverHeight(0), 1e-9, "scan hovers");
        assertEquals(WorkCycle.HOVER, WorkCycle.hoverHeight(23), 1e-9, "last scan tick hovers");
        assertEquals(WorkCycle.HOVER, WorkCycle.hoverHeight(24), 1e-9, "the drop starts from the hover height");
        assertEquals(WorkCycle.DIP, WorkCycle.hoverHeight(32), 1e-9, "the bottom of the dip");
        assertEquals(WorkCycle.HOVER, WorkCycle.hoverHeight(39), 1e-9, "back on the hover height");
        assertEquals(WorkCycle.HOVER, WorkCycle.hoverHeight(40), 1e-9, "the reset hovers");
        assertEquals(WorkCycle.HOVER, WorkCycle.hoverHeight(59), 1e-9, "the reset ends on the hover height");
        double down = Math.abs(WorkCycle.hoverHeight(23) - WorkCycle.hoverHeight(24));
        assertTrue(down < 0.01D, "scan -> drop must not step: " + down);
        double up = Math.abs(WorkCycle.hoverHeight(39) - WorkCycle.hoverHeight(40));
        assertTrue(up < 0.01D, "drop -> reset must not step: " + up);
        for (int t = 0; t < WorkCycle.PERIOD; t++) {
            double h = WorkCycle.hoverHeight(t);
            assertTrue(h >= WorkCycle.DIP - 1e-9 && h <= WorkCycle.HOVER + 1e-9, "height out of range at " + t + ": " + h);
            double step = Math.abs(WorkCycle.hoverHeight(t + 1) - h);
            assertTrue(step < 0.05D, "a 0.05 block per-tick step at " + t + " would read as a jump: " + step);
        }
        assertEquals(WorkCycle.hoverHeight(7), WorkCycle.hoverHeight(67), 1e-9, "periodic");
    }

    @Test
    @DisplayName("work: orbitRadius is 1.2 at the start, 0 from the drop on, and never grows again")
    void workOrbitRadius() {
        assertEquals(1.2D, WorkCycle.ORBIT_RADIUS, 1e-9, "orbit radius constant");
        assertEquals(WorkCycle.ORBIT_RADIUS, WorkCycle.orbitRadius(0), 1e-9, "full radius at tick 0");
        assertEquals(0.0D, WorkCycle.orbitRadius(24), 1e-9, "centred when the drop starts");
        assertEquals(0.0D, WorkCycle.orbitRadius(32), 1e-9, "still centred at the bottom");
        assertEquals(0.0D, WorkCycle.orbitRadius(40), 1e-9, "reset is centred");
        assertEquals(0.0D, WorkCycle.orbitRadius(59), 1e-9, "reset ends centred");
        for (int t = 0; t < WorkCycle.SCAN_END; t++) {
            assertTrue(WorkCycle.orbitRadius(t) >= WorkCycle.orbitRadius(t + 1) - 1e-12,
                    "the spiral must close monotonically at " + t);
        }
        assertTrue(WorkCycle.orbitRadius(23) < 0.01D, "the scan is already over the machine on its last tick");
    }

    @Test
    @DisplayName("work: orbitAngle is one and a half laps at a constant rate, then back on the start bearing")
    void workOrbitAngle() {
        assertEquals(22.5D, WorkCycle.orbitAngle(1) - WorkCycle.orbitAngle(0), 1e-9, "constant 22.5 deg/tick");
        assertEquals(22.5D, WorkCycle.orbitAngle(23) - WorkCycle.orbitAngle(22), 1e-9, "still constant at the end of the scan");
        assertEquals(540.0D, WorkCycle.orbitAngle(24), 1e-9, "one and a half laps by the end of the scan");
        assertEquals(0.0D, WorkCycle.orbitAngle(0), 1e-9, "starts at bearing 0");
        assertEquals(180.0D, Math.floorMod((long) WorkCycle.orbitAngle(24), 360L), 1e-9, "540 degrees is bearing 180");
        assertEquals(720.0D, WorkCycle.orbitAngle(32), 1e-9, "the last half lap closes while the radius is 0");
        assertEquals(720.0D, WorkCycle.orbitAngle(59), 1e-9, "held through the reset");
        assertEquals(0.0D, Math.floorMod((long) WorkCycle.orbitAngle(59), 360L), 1e-9,
                "the seam 59 -> 0 is the same bearing");
        for (int t = 0; t < WorkCycle.PERIOD; t++) {
            assertEquals(WorkCycle.orbitAngle(t), WorkCycle.orbitAngle(t + WorkCycle.PERIOD), 1e-9, "periodic at " + t);
        }
    }

    @Test
    @DisplayName("work: the airframe pitches to 12 deg in the dip and is level at every phase boundary")
    void workPitch() {
        assertEquals(12.0D, WorkCycle.TILT_DEG, 1e-9, "tilt constant");
        for (int boundary : new int[] {0, 23, 24, 39, 40, 59}) {
            assertEquals(0.0D, WorkCycle.pitchDegrees(boundary), 1e-9, "level at boundary " + boundary);
        }
        assertEquals(WorkCycle.TILT_DEG, WorkCycle.pitchDegrees(32), 1e-9, "full nose-down at the bottom of the dip");
        assertTrue(WorkCycle.pitchDegrees(31) > WorkCycle.pitchDegrees(30), "still pitching down before the bottom");
        assertTrue(WorkCycle.pitchDegrees(33) < WorkCycle.pitchDegrees(32), "pitching back up after the bottom");
        for (int t = 0; t < WorkCycle.PERIOD; t++) {
            double pitch = WorkCycle.pitchDegrees(t);
            assertTrue(pitch >= 0.0D && pitch <= WorkCycle.TILT_DEG + 1e-9, "pitch out of range at " + t + ": " + pitch);
            assertEquals(pitch, WorkCycle.pitchDegrees(t + WorkCycle.PERIOD), 1e-9, "periodic at " + t);
        }
    }

    @Test
    @DisplayName("work: lampBlend starts the swap at 24, is 1 through the drop and back to 0 in the reset")
    void workLampBlend() {
        assertEquals(0.0D, WorkCycle.lampBlend(0), 1e-9, "cyan while scanning");
        assertEquals(0.0D, WorkCycle.lampBlend(23), 1e-9, "still cyan on the last scan tick");
        assertEquals(0.0D, WorkCycle.lampBlend(24), 1e-9, "the swap starts on tick 24");
        assertTrue(WorkCycle.lampBlend(28) > 0.4D && WorkCycle.lampBlend(28) < 0.6D, "half way through the swap");
        assertEquals(1.0D, WorkCycle.lampBlend(32), 1e-9, "fully amber at the bottom of the dip");
        assertEquals(1.0D, WorkCycle.lampBlend(39), 1e-9, "still amber at the end of the drop");
        assertTrue(WorkCycle.lampBlend(44) < 1.0D, "the reset fades it back");
        assertEquals(0.0D, WorkCycle.lampBlend(48), 1e-9, "cyan again by 48");
        assertEquals(0.0D, WorkCycle.lampBlend(59), 1e-9, "cyan at the seam");
        for (int t = 0; t < 4 * WorkCycle.PERIOD; t++) {
            double blend = WorkCycle.lampBlend(t);
            assertTrue(blend >= 0.0D && blend <= 1.0D, "lampBlend out of [0, 1] at " + t + ": " + blend);
        }
        assertEquals(WorkCycle.LAMP_CYAN, WorkCycle.lampColor(0), "cyan endpoint");
        assertEquals(WorkCycle.LAMP_AMBER, WorkCycle.lampColor(32), "amber endpoint");
        assertEquals(0x2FE6FF, WorkCycle.LAMP_CYAN, "the cyan the renderers tint with");
        assertEquals(0xFFA41E, WorkCycle.LAMP_AMBER, "the amber the renderers tint with");
        int half = WorkCycle.lampColor(28);
        assertTrue(((half >> 16) & 0xFF) > ((WorkCycle.LAMP_CYAN >> 16) & 0xFF), "half way is warmer than cyan");
        assertTrue(((half >> 16) & 0xFF) < ((WorkCycle.LAMP_AMBER >> 16) & 0xFF), "half way is cooler than amber");
    }

    @Test
    @DisplayName("work: the disc keeps one lap per 80 ticks and the scan gimbal tracks it exactly")
    void workDiscAndGimbal() {
        assertEquals(80, WorkCycle.DISC_PERIOD, "one disc lap is 4 s");
        assertEquals(4.5D, WorkCycle.discAngle(1) - WorkCycle.discAngle(0), 1e-9, "4.5 deg/tick");
        assertEquals(WorkCycle.discAngle(0), WorkCycle.discAngle(80), 1e-9, "the disc is periodic");
        assertEquals(180.0D, WorkCycle.discAngle(40), 1e-9, "half a lap at 40");
        for (int t = 0; t < WorkCycle.SCAN_END; t++) {
            assertEquals(WorkCycle.discAngle(t), WorkCycle.gimbalYaw(t), 1e-9,
                    "during the scan the head points where the disc points, at " + t);
            assertEquals(-60.0D, WorkCycle.gimbalPitch(t), 1e-9, "and looks down at it, at " + t);
        }
        assertEquals(0.0D, WorkCycle.releaseBlend(23), 1e-9, "still locked on the last scan tick");
        assertEquals(1.0D, WorkCycle.releaseBlend(40), 1e-9, "released for the reset");
        assertEquals(0.0D, WorkCycle.releaseBlend(59), 1e-9, "locked again by the seam");
        assertEquals(WorkCycle.discAngle(59), WorkCycle.gimbalYaw(59), 1e-9, "the seam hands back to the disc");
        assertEquals(WorkCycle.discAngle(60), WorkCycle.gimbalYaw(60), 1e-9, "and the next scan starts on it");
        for (int t = 0; t < WorkCycle.PERIOD; t++) {
            double pan = WorkCycle.sweepYaw(t);
            assertTrue(Math.abs(pan) <= WorkCycle.GIMBAL_SWEEP + 1e-9, "the sweep stays inside +-20 deg at " + t);
            assertTrue(Math.abs(WorkCycle.gimbalPitch(t)) <= 90.0D, "the head never passes straight down at " + t);
            assertEquals(WorkCycle.gimbalPitch(t), WorkCycle.gimbalPitch(t + WorkCycle.PERIOD), 1e-9,
                    "the head pose is periodic at " + t);
        }
        assertTrue(Math.abs(WorkCycle.gimbalPitch(50)) < 21.0D, "the released head is inside the +-20 deg band");
        assertEquals(0.5D, WorkCycle.ease(0.5D), 1e-9, "ease midpoint");
        assertEquals(0.0D, WorkCycle.ease(-3.0D), 1e-9, "ease clamps low");
        assertEquals(1.0D, WorkCycle.ease(3.0D), 1e-9, "ease clamps high");
        assertEquals(1.0D, WorkCycle.mix(0.0D, 1.0D, 5.0D), 1e-9, "mix clamps high");
        assertEquals(0.0D, WorkCycle.mix(0.0D, 1.0D, -5.0D), 1e-9, "mix clamps low");
    }

    @Test
    @DisplayName("work: the enter/leave transition and the orbit radius keep the drone around the machine")
    void workEnterAndFootprint() {
        assertEquals(8, WorkCycle.ENTER_TICKS, "the recall/enter pose takes 8 ticks");
        assertTrue(WorkCycle.ORBIT_RADIUS > 1.0D, "the orbit is wide enough to clear the two-block machine");
        for (int t = 0; t < 3 * WorkCycle.PERIOD; t++) {
            double radius = WorkCycle.orbitRadius(t);
            assertTrue(radius >= 0.0D && radius <= WorkCycle.ORBIT_RADIUS + 1e-9, "radius out of range at " + t);
            double height = WorkCycle.hoverHeight(t);
            assertTrue(height >= WorkCycle.DIP && height <= WorkCycle.HOVER, "height out of range at " + t);
        }
    }
}
