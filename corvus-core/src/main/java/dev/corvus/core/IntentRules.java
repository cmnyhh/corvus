package dev.corvus.core;

import java.util.Map;
import java.util.Set;

/**
 * Which spoken orders the firmware can actually execute, and which module family each one needs.
 *
 * <p>The two builds kept two hand-written whitelists in {@code LocalDialogue} and they drifted apart
 * in both directions: the 1.21.1 list rejected {@code order.fetch/order.supply/order.light} (orders
 * that build does implement) and the 1.20.1 list rejected {@code order.cover/order.hold} (orders that
 * build implements too), so the same sentence answered "指令已执行" on one side and "尚未实现" on the
 * other. {@code order.supply}'s module gate had also drifted ({@code mod_link} vs {@code mod_carry}).</p>
 *
 * <p>Both {@code LocalDialogue}s now ask this table instead of switching on literals, so the set of
 * runnable orders and their module gates cannot disagree again. The dialogue <em>resources</em>
 * ({@code data/huhu_dronepal/intents/*.json}) still own aliases, patterns and the refusal line; this
 * class owns the contract the code has to honour.</p>
 *
 * <p>Pure data, no Minecraft types.</p>
 */
public final class IntentRules {
    /**
     * The orders a dialogue line may dispatch. Every id here has a live handler in both builds:
     * {@code scan*}/{@code order.heal} in {@code DroneSystems}, {@code order.fetch}/{@code order.supply}
     * in the cargo runtime, {@code order.light} in the lamp runtime, {@code order.cover}/{@code order.hold}
     * in the weapon bus and {@code order.work}/{@code order.idle} in the mill runtime.
     */
    public static final Set<String> IMPLEMENTED = Set.of(
            "scan", "scan.hostile", "scan.ore",
            "order.heal", "order.fetch", "order.supply", "order.light",
            "order.cover", "order.hold", "order.work", "order.idle");

    /**
     * Module family per order, keyed by the same ids. An empty string means "no module required"
     * (work/idle are gated by a nearby mill instead).
     *
     * <p>{@code order.supply} needs {@code carry}: handing a stack over is a cargo action, and the link
     * module neither holds nor moves items. That is the one gate that used to differ between the builds
     * ({@code mod_link} on 1.21.1), so it is pinned here in addition to the data files.</p>
     */
    private static final Map<String, String> MODULE = Map.ofEntries(
            Map.entry("scan", "scan"),
            Map.entry("scan.hostile", "scan"),
            Map.entry("scan.ore", "scan"),
            Map.entry("order.heal", "medic"),
            Map.entry("order.fetch", "carry"),
            Map.entry("order.supply", "carry"),
            Map.entry("order.light", "light"),
            Map.entry("order.cover", "combat"),
            Map.entry("order.hold", "combat"),
            Map.entry("order.work", ""),
            Map.entry("order.idle", ""));

    private IntentRules() {}

    /**
     * Can the firmware run this order?
     *
     * @param intentId the intent id from the dialogue data, e.g. {@code order.supply}
     * @return true for every id in {@link #IMPLEMENTED}; false for unknown ids, lookup/chat intents and
     *         anything the mod does not handle (the caller answers "尚未实现" for those)
     */
    public static boolean implemented(String intentId) {
        return intentId != null && IMPLEMENTED.contains(intentId);
    }

    /**
     * Module family an order needs before it may run.
     *
     * <p>The built-in orders are pinned by this table, so a data file cannot re-gate them (that is how
     * {@code order.supply} ended up on {@code link} in one build). Orders the mod does not know about
     * keep whatever their data file declared, so a data pack can still add its own gated order.</p>
     *
     * @param intentId the intent id
     * @param declared the family the data file asked for ({@code requires_module} without its
     *                 {@code mod_} prefix); {@code null} counts as "none"
     * @return the family that must be fitted and online, or {@code ""} when the order needs no module
     */
    public static String moduleFor(String intentId, String declared) {
        String pinned = intentId == null ? null : MODULE.get(intentId);
        if (pinned != null) return pinned;
        return declared == null ? "" : declared;
    }
}
