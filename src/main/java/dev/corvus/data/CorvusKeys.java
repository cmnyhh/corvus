package dev.corvus.data;

/** Every NBT key Corvus uses, in one place, so the 1.21 save keys stay stable across versions. */
public final class CorvusKeys {
    /** UUID in NBT; the physical identity of one controller. Same key as the 1.21 build. */
    public static final String SERIAL = "dronepal_serial";
    /** Frame id string: peregrine / owl / bedrock / scrap. */
    public static final String FRAME = "dronepal_frame";
    /** Paint id string, one of {@code Frames.PAINTS}. */
    public static final String PAINT = "dronepal_paint";
    /** Owner UUID, entity only. */
    public static final String OWNER = "dronepal_owner";
    /** Serial of the currently deployed projection, on the player's persistent data. */
    public static final String ACTIVE = "dronepal_active";

    /**
     * Installed hardware: one list of item stacks (battery, function modules, gun, ammo) in install
     * order. The 1.21 build keeps the same list in {@code DataComponents.CONTAINER}; 1.20.1 has no
     * components, so the list lives in plain NBT under this key.
     */
    public static final String PARTS = "dronepal_parts";
    /** Module product id, e.g. {@code huhu_dronepal:combat_mk2}. One shared {@code module} item. */
    public static final String MODULE_PRODUCT = "module_product";
    /** Module family, e.g. {@code combat}. Written next to the product id for older readers. */
    public static final String MODULE_FAMILY = "module_family";
    /** Module tier, 1..3. */
    public static final String MODULE_TIER = "module_tier";
    /** Stored FE on a battery stack; absent means "full", matching the 1.21 behaviour. */
    public static final String ENERGY = "energy";
    /** Game time of the last accepted charge tick, so the receive budget is per tick. */
    public static final String CHARGE_TICK = "charge_tick";
    /** FE already accepted during {@link #CHARGE_TICK}. */
    public static final String CHARGE_RECEIVED = "charge_received";
    /** Prefix for a module fault deadline (game time) on the controller, one key per product. */
    public static final String FAULT_PREFIX = "dronepal_fault_";

    /** Frame ids as older saves spelled them, before the product names were settled. */
    public static final String[] LEGACY_FRAME_KEYS = {"frame", "dronepal:frame", "dronepal_frame"};

    private CorvusKeys() {}
}
