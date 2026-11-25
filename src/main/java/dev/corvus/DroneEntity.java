package dev.corvus;

import dev.corvus.core.FrameStats;
import dev.corvus.data.CorvusKeys;
import dev.corvus.mill.DroneWork;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The flying projection. It follows the owner, reports its frame/paint, and carries the combat/scan
 * layer from the 1.21 build: the gimbal pose ({@code mountPitch/aimYaw/aimPitch}), the sensor sweep
 * flag, the server-side scan runtime ({@link DroneSystems}) and the weapon bus
 * ({@link DroneCombat}). Cargo, lamps and the terminal panels are owned by other files.
 *
 * <p>The projection has no life of its own: it exists only while the owner is online, in the same
 * dimension, and still wearing the controller that carries its serial. Otherwise it removes itself
 * and {@link DroneDeployment} decides whether to bring it back.</p>
 */
public final class DroneEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> DATA_FRAME =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Byte> DATA_PAINT =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    /** Gun-mount fold, 0 = deployed, {@value #FOLD_LIMIT} = stowed. Written by DroneCombat. */
    private static final EntityDataAccessor<Byte> DATA_MOUNT_PITCH =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    /** Gimbal yaw relative to the body heading, clamped by the mount to ±75°. */
    private static final EntityDataAccessor<Float> DATA_AIM_YAW =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_AIM_PITCH =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.FLOAT);
    /** Sensor sweep flag: the renderer plays the scan clip only while a scan is live. */
    private static final EntityDataAccessor<Boolean> DATA_SCANNING =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
    /** 0..15 flight-blend sample, feeding the boost clip. */
    private static final EntityDataAccessor<Byte> DATA_FLIGHT =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BYTE);
    /** True while the projection is hovering over its ore mill; the renderer adds a small work bob. */
    private static final EntityDataAccessor<Boolean> DATA_WORKING =
            SynchedEntityData.defineId(DroneEntity.class, EntityDataSerializers.BOOLEAN);
    public static final int FOLD_LIMIT = 85;

    /** Offset used at deploy time and by the follow loop: right shoulder, slightly behind. */
    private static final double STATION_RIGHT = 1.25D;
    private static final double STATION_BACK = 0.75D;
    private static final double STATION_HEIGHT = 1.55D;
    /**
     * Beyond this many blocks the projection is dropped instead of flown after the owner (the same 64
     * blocks the 1.21.1 build uses); {@link DroneDeployment} rebuilds it at the station on the next
     * player tick, so a long teleport costs a re-deploy, not a minute of chasing.
     */
    public static final double UNLOAD_DISTANCE = 64.0D;
    /** Blend a frame without an {@code accel_response} falls back to; matches {@link FrameStats}. */
    private static final double LEGACY_BLEND = 0.35D;

    private UUID owner;
    private Vec3 lastOwnerPosition;
    private float followHeading;
    /** Tick before which the frame may not teleport again ({@code teleport_cooldown_ticks}). */
    private int nextFrameTeleport;
    /** Per-hit damage ceiling from the frame's {@code max_damage_per_hit}; see {@link #hurt}. */
    private float ordinaryHitCap = 10F;

    // ---- combat / scan layer (port phase C) -------------------------------------------------
    /** Server-side scan runtime; ticks with the entity and never runs on the client. */
    private final DroneSystems systems = new DroneSystems();
    /** Weapon bus: pose, target selection and the (currently degraded) firing schedule. */
    public final DroneCombat combat = new DroneCombat();
    /** Last snapshot sent to this projection's owner; null when no scan is live. */
    ScanSnapshot scanSnapshot;

    /** The live snapshot, for tooling/tests and the dialogue layer. */
    public ScanSnapshot scanSnapshot() {
        return scanSnapshot;
    }
    /** Follow switch: park at the station instead of chasing the owner. */
    public boolean holdingStation;
    /**
     * Ore mill this projection is working at. Non-null wins over the follow station: the drone flies to
     * the mill, hovers, and the mill resolves the projection through this field on every tick. Cleared by
     * recall, the follow switch and {@code order.idle}.
     */
    private BlockPos workTarget;
    /** Sticky while below 15 % charge; cleared again only at 40 % (see {@link DroneWork#second}). */
    private boolean workStalled;
    /** Dev-tool seam: a controller supplied for headless acceptance runs instead of the worn one. */
    private ItemStack testController = ItemStack.EMPTY;
    /**
     * Dev-tool seam: the pilot this projection follows. A dedicated acceptance run has no connected
     * player, and {@code PlayerList#getPlayer} returns null for a {@code FakePlayer}, so without this
     * seam {@link #tick} can only be driven by a real login.
     */
    private ServerPlayer testPilot;
    /** Flight-log accounting mark, shared with {@link DroneArchive}. */
    int archiveTick;
    /** Hitbox size of the current frame, in blocks. See {@link #getDimensions}. */
    private float frameWidth;
    private float frameHeight;

    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
        this.setPersistenceRequired();
    }

    public String getFrameId() {
        return entityData.get(DATA_FRAME);
    }

    /**
     * Applies a frame: hitbox, hit points, per-hit cap and knockback resistance.
     *
     * <p>Every number comes from {@code data/huhu_dronepal/frames/*.json} through
     * {@link Frames.Frame}; the health arithmetic is {@link FrameStats#maxHealth}. Before R1 this build
     * stored the frame id and the hitbox only, so every airframe kept the vanilla 20 HP and an uncapped
     * hit. The current health <em>fraction</em> is preserved, so swapping a frame in the terminal cannot
     * heal a damaged drone for free (and a 10 % drone stays at 10 %).</p>
     */
    public void setFrame(String frameId) {
        entityData.set(DATA_FRAME, Frames.canonical(frameId));
        Frames.Frame frame = Frames.byId(getFrameId());
        if (frame == null) return;
        applyFrameStats(frame);
        applyFrameDimensions();
    }

    /**
     * Writes the frame's combat numbers onto this entity: max health (keeping the current fraction),
     * the {@code max_damage_per_hit} ceiling used by {@link #hurt} and the base knockback resistance.
     *
     * <p>The battery-mass modifier is a separate, transient modifier ({@code Batteries.tickDeployed}),
     * so the base value here is the frame's own {@code knockback_resistance} and the two add up, exactly
     * like the 1.21.1 build.</p>
     */
    private void applyFrameStats(Frames.Frame frame) {
        float fraction = getMaxHealth() <= 0F ? 1F : getHealth() / getMaxHealth();
        var maxHealth = getAttribute(Attributes.MAX_HEALTH);
        if (maxHealth != null) {
            maxHealth.setBaseValue(FrameStats.maxHealth(Math.round(frame.maxHealth()), 1.0D));
            setHealth(Math.max(0F, (float) (getMaxHealth() * fraction)));
        }
        ordinaryHitCap = frame.hitCap() > 0F ? frame.hitCap() : 0F;
        var resistance = getAttribute(Attributes.KNOCKBACK_RESISTANCE);
        if (resistance != null) resistance.setBaseValue(frame.knockbackResistance());
    }

    /** The live per-hit damage ceiling, for tooling and the smoke harness. */
    public float hitCap() {
        return ordinaryHitCap;
    }

    /**
     * Damage gate: the configured invulnerability policy, then the frame's per-hit ceiling.
     *
     * <p>The policy is the same one the 1.21.1 build reads ({@link ServerOptions#protection()}):
     * {@code always} = untouchable, {@code creative_owner} = untouchable while the owner is in creative,
     * {@code never} = the ceiling is the only protection. Damage sources flagged
     * {@link DamageTypeTags#BYPASSES_INVULNERABILITY} (the void, {@code /kill}) skip the gate, so a
     * projection can always be removed. The ceiling is applied through
     * {@link FrameStats#mitigate(amount, multiplier, cap)} — the shared arithmetic, not a local
     * {@code Math.min}.</p>
     */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean bypasses = source.is(DamageTypeTags.BYPASSES_INVULNERABILITY);
        if (!bypasses) amount = (float) FrameStats.mitigate(amount, 1.0D, ordinaryHitCap);
        // The policy is a server decision (same guard as the 1.21.1 build); the client still plays the
        // hit so the animation/sound stay in sync.
        if (level() instanceof ServerLevel
                && protectedFrom(ServerOptions.protection(), ownerCreative(), bypasses)) return false;
        return super.hurt(source, amount);
    }

    /** True when the flying owner is a creative player; false when the owner is offline or unknown. */
    boolean ownerCreative() {
        if (owner == null || !(level() instanceof ServerLevel serverLevel)) return false;
        ServerPlayer pilot = serverLevel.getServer().getPlayerList().getPlayer(owner);
        return pilot != null && pilot.isCreative();
    }

    /**
     * The invulnerability decision for one hit: {@code always} = untouchable, {@code creative_owner} =
     * untouchable while the owner is creative, {@code never} = the per-hit ceiling is the only
     * protection. A source that bypasses invulnerability (the void, {@code /kill}) always lands.
     *
     * <p>Pure, so the acceptance run can drive all three policies (and both owner game modes) without
     * needing a second connected player; {@link #hurt} is the only production caller.</p>
     *
     * @param policy         the configured policy, never {@code null}
     * @param ownerCreative  whether the owner is a creative player
     * @param bypasses       whether the damage source is tagged {@code bypasses_invulnerability}
     * @return true when the hit must be refused
     */
    public static boolean protectedFrom(ServerOptions.Protection policy, boolean ownerCreative, boolean bypasses) {
        if (bypasses) return false;
        return policy == ServerOptions.Protection.always
                || (policy == ServerOptions.Protection.creative_owner && ownerCreative);
    }

    /**
     * True when the owner is too far away to keep flying after them ({@link #UNLOAD_DISTANCE}).
     *
     * <p>Separate from {@link #tick} so the rule is one line both the tick and the acceptance run read.</p>
     */
    boolean tooFarToFollow(net.minecraft.world.entity.Entity pilot) {
        return pilot != null && distanceToSqr(pilot) > UNLOAD_DISTANCE * UNLOAD_DISTANCE;
    }

    /**
     * One follow step towards the station, frame-driven.
     *
     * <p>Two rules from the frame file: {@code follow_teleport_threshold} (beyond it, and once
     * {@code teleport_cooldown_ticks} have elapsed, the projection arrives in one step unless
     * {@code teleport_fail_chance} rolls) and {@code accel_response} (the per-tick easing fraction,
     * {@link FrameStats#followStep}). This build used a fixed {@code lerp(target, 0.35)} that ignored the
     * frame entirely, which is why a 磐石 and a 夜枭 followed at the same speed.</p>
     */
    Vec3 followPosition(Vec3 target) {
        Frames.Frame frame = Frames.byId(getFrameId());
        if (frame == null) return position().lerp(target, LEGACY_BLEND);
        double threshold = frame.teleportThreshold();
        if (tickCount >= nextFrameTeleport && position().distanceToSqr(target) > threshold * threshold) {
            nextFrameTeleport = tickCount + frame.teleportCooldown();
            if (frame.teleportFailChance() <= 0F || getRandom().nextFloat() >= frame.teleportFailChance()) {
                return target;
            }
        }
        double accel = frame.accelResponse();
        return new Vec3(
                FrameStats.followStep(getX(), target.x, accel, 1),
                FrameStats.followStep(getY(), target.y, accel, 1),
                FrameStats.followStep(getZ(), target.z, accel, 1));
    }

    /**
     * The hitbox follows the frame definition ({@code collision}/{@code width}/{@code height} in
     * {@code data/huhu_dronepal/frames/*.json}): a 磐石 is a significantly wider target than a 游隼,
     * and the box also decides where hits and the terminal interaction land.
     *
     * <p>The values live in plain fields rather than synched data on purpose: {@link #getDimensions}
     * is called from the entity constructor before synched data exists, so reading it there would
     * either throw or force a zero-size box.</p>
     */
    private void applyFrameDimensions() {
        Frames.Frame frame = Frames.byId(getFrameId());
        if (frame == null) return;
        frameWidth = frame.width();
        frameHeight = frame.height();
        refreshDimensions();
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        // Before the constructor reaches the field initialisers these are 0; fall back to the type.
        if (frameWidth <= 0F || frameHeight <= 0F) return super.getDimensions(pose);
        return EntityDimensions.scalable(frameWidth, frameHeight);
    }

    @Override
    public void onSyncedDataUpdated(EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key == DATA_FRAME) {
            // The frame id arrives from the server after spawn; resize once it does.
            Frames.Frame frame = Frames.byId(getFrameId());
            if (frame != null) {
                frameWidth = frame.width();
                frameHeight = frame.height();
                refreshDimensions();
            }
        }
    }

    public String getPaintName() {
        int index = entityData.get(DATA_PAINT);
        return Frames.PAINTS.get(Mth.clamp(index, 0, Frames.PAINTS.size() - 1));
    }

    public void setPaint(String paint) {
        int index = Frames.PAINTS.indexOf(paint);
        entityData.set(DATA_PAINT, (byte) Math.max(0, index));
    }

    public UUID ownerId() {
        return owner;
    }

    // ---- combat / scan accessors -----------------------------------------------------------

    public int mountPitch() {
        return entityData.get(DATA_MOUNT_PITCH);
    }

    public void setMountPitch(int pitch) {
        entityData.set(DATA_MOUNT_PITCH, (byte) Mth.clamp(pitch, 0, FOLD_LIMIT));
    }

    public float aimYaw() {
        return entityData.get(DATA_AIM_YAW);
    }

    public void setAimYaw(float yaw) {
        entityData.set(DATA_AIM_YAW, yaw);
    }

    public float aimPitch() {
        return entityData.get(DATA_AIM_PITCH);
    }

    public void setAimPitch(float pitch) {
        entityData.set(DATA_AIM_PITCH, pitch);
    }

    public boolean isScanActive() {
        return entityData.get(DATA_SCANNING);
    }

    /** Toggled by {@link DroneSystems}; the renderer keys the sensor sweep off it. */
    public void scanning(boolean active) {
        entityData.set(DATA_SCANNING, active);
    }

    /** 0..1 sample of the current horizontal speed, driving the boost clip. */
    public float getFlightBlend() {
        return entityData.get(DATA_FLIGHT) / 15F;
    }

    DroneSystems systems() {
        return systems;
    }

    void scanPatrolChanged(ServerPlayer player, boolean enabled) {
        systems.patrolChanged(this, player, enabled);
    }

    public boolean execute(ServerPlayer player, String intent) {
        return systems.execute(this, player, controller(), intent);
    }

    /** Dev-tool seam: makes {@link #controller()} answer with a supplied stack. */
    public void setTestController(ItemStack stack) { testController = stack == null ? ItemStack.EMPTY : stack; }

    /** Dev-tool seam: makes {@link #tick()} follow this pilot instead of a player-list lookup. */
    public void setTestPilot(ServerPlayer pilot) { testPilot = pilot; }

    /** The controller that carries this projection's serial, or EMPTY when it is not worn. */
    public ItemStack controller() {
        if (!testController.isEmpty()) return testController;
        if (owner == null || !(level() instanceof ServerLevel serverLevel)) return ItemStack.EMPTY;
        ServerPlayer pilot = serverLevel.getServer().getPlayerList().getPlayer(owner);
        return pilot == null ? ItemStack.EMPTY : controller(pilot);
    }

    private ItemStack controller(ServerPlayer pilot) {
        ItemStack equipped = DroneCurios.equipped(pilot);
        return getUUID().equals(DroneItem.serial(equipped)) ? equipped : ItemStack.EMPTY;
    }

    public void setOwner(UUID ownerId) {
        this.owner = ownerId;
    }

    public static Vec3 station(ServerPlayer player) {
        return station(player.position(), player.getYRot());
    }

    static Vec3 station(Vec3 origin, float heading) {
        double yaw = Math.toRadians(heading);
        return origin.add(
                STATION_RIGHT * Math.cos(yaw) + STATION_BACK * Math.sin(yaw),
                STATION_HEIGHT,
                STATION_RIGHT * Math.sin(yaw) - STATION_BACK * Math.cos(yaw));
    }

    /** The last movement direction of the owner, so the body keeps a sensible heading while idle. */
    public float followHeading() {
        return followHeading;
    }

    private void updateFollowHeading(ServerPlayer player) {
        Vec3 current = player.position();
        if (lastOwnerPosition == null) {
            followHeading = player.getYRot();
            lastOwnerPosition = current;
            return;
        }
        Vec3 delta = current.subtract(lastOwnerPosition);
        if (delta.horizontalDistanceSqr() >= 0.04D * 0.04D) {
            followHeading = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
            lastOwnerPosition = current;
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel serverLevel)) return;
        ServerPlayer pilot = testPilot != null ? testPilot
                : owner == null ? null
                : serverLevel.getServer().getPlayerList().getPlayer(owner);
        CompoundTag state = pilot == null ? null : pilot.getPersistentData();
        boolean active = state != null
                && state.hasUUID(CorvusKeys.ACTIVE)
                && getUUID().equals(state.getUUID(CorvusKeys.ACTIVE));
        if (pilot == null || !pilot.isAlive() || pilot.level() != serverLevel || !active) {
            discard();
            return;
        }
        updateFollowHeading(pilot);
        // Batteries leak and the knockback modifier refreshes from the hardware layer (self-throttled).
        Hardware.tickDeployed(this);
        if (tickCount % 20 == 0) {
            ItemStack terminal = controller(pilot);
            if (!terminal.isEmpty()) {
                DroneArchive.checkpoint(serverLevel, this);
                // Power transaction, lamp profile and cargo pickup, once per second (see ModuleRuntime).
                ModuleRuntime.second(this, pilot, terminal);
                DroneWork.second(this, terminal);
                systems.second(this, pilot, terminal);
            }
        }
        systems.tick(this, pilot);
        combat.tick(this);
        if (tooFarToFollow(pilot)) {
            // Out of follow range: drop the body. DroneDeployment.reconcile() rebuilds it at the
            // station on the owner's next tick, which is the 1.21.1 "unload then redeploy" behaviour.
            discard();
            return;
        }
        if (workTarget != null && DroneWork.chase(this, pilot)) return;
        working(false);
        if (holdingStation) {
            setDeltaMovement(Vec3.ZERO);
            entityData.set(DATA_FLIGHT, (byte) 0);
            return;
        }
        Vec3 target = station(pilot.position(), followHeading);
        setDeltaMovement(Vec3.ZERO);
        Vec3 previous = position();
        // The blend and the teleport branch are the frame's (dev.corvus.core.FrameStats), so the two
        // builds step identically for the same frame JSON.
        setPos(followPosition(target));
        double speed = position().subtract(previous).horizontalDistance();
        entityData.set(DATA_FLIGHT, (byte) Math.round(15 * Math.min(speed / 0.22D, 1D)));
        if (getTarget() == null) faceHeading(followHeading);
    }

    @Override
    public void remove(RemovalReason reason) {
        if (scanSnapshot != null && level() instanceof ServerLevel serverLevel) {
            ServerPlayer pilot = owner == null ? null
                    : serverLevel.getServer().getPlayerList().getPlayer(owner);
            systems.clearScan(this, pilot);
        }
        super.remove(reason);
    }

    /** The mill this projection is stationed at, or {@code null}. */
    public BlockPos workTarget() {
        return workTarget;
    }

    /** Assign or clear the work station; the mill re-checks this field itself. */
    public void setWorkTarget(BlockPos target) {
        workTarget = target;
    }

    public boolean workStalled() { return workStalled; }

    public void setWorkStalled(boolean stalled) { workStalled = stalled; }

    /**
     * The work orbit turns the hull with the shared cycle (22.5 degrees per tick). That is faster than
     * the vanilla body follower re-aims a mob that is not moving horizontally - during the drop and the
     * reset the drone hangs still - so while a work order runs the hull simply follows the synced
     * heading. Every other state (idle, follow, combat) keeps the vanilla rule, where the body turns
     * toward the direction of travel and a parked drone looks wherever it last looked.
     */
    @Override protected float tickHeadTurn(float bodyYaw, float radius) {
        float turn = super.tickHeadTurn(bodyYaw, radius);
        if (isWorking()) setYBodyRot(getYRot());
        return turn;
    }

    /** Work-order entry point: point at a mill and clear any sticky battery stall. */
    public void assignWork(BlockPos pos) {
        workTarget = pos;
        workStalled = false;
    }

    public void clearWork() {
        workTarget = null;
        workStalled = false;
        working(false);
    }

    public boolean isWorking() { return entityData.get(DATA_WORKING); }

    public void working(boolean active) { entityData.set(DATA_WORKING, active); }

    public void setFlight(int value) { entityData.set(DATA_FLIGHT, (byte) Mth.clamp(value, 0, 15)); }

    public void faceHeading(float heading) {
        setYRot(heading);
        setYHeadRot(heading);
        setYBodyRot(heading);
    }

    @Override
    protected void defineSynchedData() {
        // 1.20.1 has no SynchedEntityData.Builder: the parent must define its own data
        // (health, ...) first, otherwise LivingEntity's constructor NPEs in setHealth.
        super.defineSynchedData();
        entityData.define(DATA_FRAME, "peregrine");
        entityData.define(DATA_PAINT, (byte) 0);
        entityData.define(DATA_MOUNT_PITCH, (byte) FOLD_LIMIT);
        entityData.define(DATA_AIM_YAW, 0F);
        entityData.define(DATA_AIM_PITCH, 0F);
        entityData.define(DATA_SCANNING, false);
        entityData.define(DATA_FLIGHT, (byte) 0);
        entityData.define(DATA_WORKING, false);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID(CorvusKeys.OWNER)) owner = tag.getUUID(CorvusKeys.OWNER);
        // Always re-apply: a save written before R1 has no frame stats, and an explicit id must update
        // max health / hit cap / knockback even when the synched default already matches.
        setFrame(tag.contains(CorvusKeys.FRAME) ? tag.getString(CorvusKeys.FRAME) : getFrameId());
        if (tag.contains(CorvusKeys.PAINT)) setPaint(tag.getString(CorvusKeys.PAINT));
        workTarget = tag.contains("WorkTarget") ? BlockPos.of(tag.getLong("WorkTarget")) : null;
        workStalled = tag.getBoolean("WorkStalled");
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (owner != null) tag.putUUID(CorvusKeys.OWNER, owner);
        tag.putString(CorvusKeys.FRAME, getFrameId());
        tag.putString(CorvusKeys.PAINT, getPaintName());
        if (workTarget != null) tag.putLong("WorkTarget", workTarget.asLong());
        tag.putBoolean("WorkStalled", workStalled);
    }
}
