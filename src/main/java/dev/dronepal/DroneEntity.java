package dev.dronepal;

import dev.corvus.core.FrameStats;
import dev.dronepal.mill.DroneWork;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class DroneEntity extends net.minecraft.world.entity.PathfinderMob {
    @Override protected void dropAllDeathLoot(ServerLevel level, net.minecraft.world.damagesource.DamageSource source) {
        // The controller owns every item. A destroyed projection has no loot or XP.
    }
    @Override public void die(net.minecraft.world.damagesource.DamageSource source) {
        super.die(source);
        if (dead && !isRemoved() && !level().isClientSide()) {
            DroneArchive.lost((ServerLevel) level(), getUUID());
            DroneLifecycle.delay(this, DroneLifecycle.REPAIR);
            discard();
        }
    }
    private static final net.minecraft.network.syncher.EntityDataAccessor<Byte> FLIGHT = SynchedEntityData.defineId(DroneEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BYTE);
    private static final java.util.List<String> PAINTS = PaintCans.PALETTE;
    private static final net.minecraft.network.syncher.EntityDataAccessor<String> FRAME = SynchedEntityData.defineId(DroneEntity.class, net.minecraft.network.syncher.EntityDataSerializers.STRING);
    public String getFrameId() { return entityData.get(FRAME); }
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> FRAME_WIDTH = SynchedEntityData.defineId(DroneEntity.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Float> FRAME_HEIGHT = SynchedEntityData.defineId(DroneEntity.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    private int nextFrameTeleport;
    private float ordinaryHitCap = 10F;
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        if (!source.is(net.minecraft.tags.DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            if (level() instanceof ServerLevel world) {
                var policy = ServerOptions.INVULNERABILITY.get();
                if (policy == ServerOptions.Protection.always) return false;
                if (policy == ServerOptions.Protection.creative_owner && owner != null) {
                    ServerPlayer pilot = world.getServer().getPlayerList().getPlayer(owner);
                    if (pilot != null && pilot.isCreative()) return false;
                }
            }
            // Shared arithmetic (dev.corvus.core.FrameStats#mitigate): same per-hit ceiling rule the
            // 1.20.1 build now uses, so a data-pack hit cap cannot behave differently on the two sides.
            amount = (float) FrameStats.mitigate(amount, 1.0D, ordinaryHitCap);
        }
        return super.hurt(source, amount);
    }
    void setFrame(String id) {
        Frames.Frame frame = Frames.byId(id);
        float healthFraction = getHealth() / getMaxHealth();
        getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH)
                .setBaseValue(FrameStats.maxHealth(Math.round(frame.maxHealth()), 1.0D));
        setHealth(getMaxHealth() * healthFraction);
        ordinaryHitCap = frame.hitCap();
        entityData.set(FRAME, frame.id());
        entityData.set(FRAME_WIDTH, frame.width());
        entityData.set(FRAME_HEIGHT, frame.height());
        var resistance = getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
        if (resistance != null) resistance.setBaseValue(frame.knockbackResistance());
    }
    @Override protected net.minecraft.world.entity.EntityDimensions getDefaultDimensions(net.minecraft.world.entity.Pose pose) {
        return net.minecraft.world.entity.EntityDimensions.scalable(entityData.get(FRAME_WIDTH), entityData.get(FRAME_HEIGHT));
    }
    @Override public void onSyncedDataUpdated(net.minecraft.network.syncher.EntityDataAccessor<?> key) {
        super.onSyncedDataUpdated(key);
        if (key == FRAME_WIDTH || key == FRAME_HEIGHT) refreshDimensions();
    }
    Vec3 followPosition(Vec3 target) {
        // The blend is dev.corvus.core.FrameStats#followStep per axis (the teleport branch stays here,
        // because the cooldown is entity state): both builds now step from the same frame numbers.
        Frames.Frame frame = Frames.byId(getFrameId());
        if (tickCount >= nextFrameTeleport && position().distanceToSqr(target) > frame.teleportThreshold() * frame.teleportThreshold()) {
            nextFrameTeleport = tickCount + frame.teleportCooldown();
            if (frame.teleportFailChance() == 0 || random.nextFloat() >= frame.teleportFailChance()) return target;
        }
        double accel = frame.accelResponse();
        return new Vec3(
                FrameStats.followStep(getX(), target.x, accel, 1),
                FrameStats.followStep(getY(), target.y, accel, 1),
                FrameStats.followStep(getZ(), target.z, accel, 1));
    }
    private static final net.minecraft.network.syncher.EntityDataAccessor<Byte> PAINT = SynchedEntityData.defineId(DroneEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BYTE);
    public String getPaintName() { return PAINTS.get(entityData.get(PAINT)); }
    void setPaint(String name) { entityData.set(PAINT, (byte)Math.max(0, PAINTS.indexOf(name))); }
    static final net.minecraft.network.syncher.EntityDataAccessor<Byte> MOUNT_PITCH = SynchedEntityData.defineId(DroneEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BYTE);
    private static final net.minecraft.network.syncher.EntityDataAccessor<Integer> LAMP = SynchedEntityData.defineId(DroneEntity.class, net.minecraft.network.syncher.EntityDataSerializers.INT);
    public int lightProfile() { return entityData.get(LAMP); }
    void setLightProfile(int profile) { entityData.set(LAMP,profile); }
    int archiveTick;
    DroneTelemetry telemetry;
    final DroneCombat combat = new DroneCombat();
    UUID owner;
    boolean holdingStation;
    public float getFlightBlend() { return entityData.get(FLIGHT) / 15F; }
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> SCANNING = SynchedEntityData.defineId(DroneEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    /** Work-station pose flag: the renderer leans the airframe slowly over the assigned mill. */
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> WORKING = SynchedEntityData.defineId(DroneEntity.class, net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    private net.minecraft.core.BlockPos workTarget;
    private boolean workStalled;
    private final DroneSystems systems = new DroneSystems();
    ScanSnapshot scanSnapshot;
    @Override public void remove(RemovalReason reason) {
        ServerLevel server = level() instanceof ServerLevel world ? world : null;
        ServerPlayer pilot = server == null || owner == null ? null
                : server.getServer().getPlayerList().getPlayer(owner);
        if (scanSnapshot != null && server != null) {
            systems.clearScan(this, pilot);
        }
        // Leaving the world (chunk unload, recall, relog, another dimension): the settlement can only
        // run from this entity's own tick, so no FE will move again. Publish the "off grid" frame now
        // rather than letting the HUD keep the last "charging" line until it times out.
        DroneTelemetry.offline(this, pilot);
        super.remove(reason);
    }
    public boolean isScanActive() { return entityData.get(SCANNING); }
    public boolean isWorking() { return entityData.get(WORKING); }
    public void working(boolean active) { entityData.set(WORKING, active); }
    public net.minecraft.core.BlockPos workTarget() { return workTarget; }
    /** Sticky while below 15% charge; cleared again only at 40% (see DroneWork.second). */
    public boolean workStalled() { return workStalled; }
    public void setWorkStalled(boolean stalled) { workStalled = stalled; }
    public void assignWork(net.minecraft.core.BlockPos pos) { workTarget = pos; setWorkStalled(false); }
    public void clearWork() { workTarget = null; workStalled = false; working(false); }
    /** The flight blend is the same byte the follow AI drives; the work hover reuses it. */
    public void setFlight(int value) { entityData.set(FLIGHT, (byte)Math.clamp(value, 0, 15)); }
    void scanPatrolChanged(ServerPlayer player, boolean enabled) { systems.patrolChanged(this, player, enabled); }
    void scanning(boolean active) { entityData.set(SCANNING, active); }
    boolean execute(ServerPlayer player, String intent) { return systems.execute(this, player, controller(), intent); }
    public DroneEntity(EntityType<? extends DroneEntity> type, Level level) {
        super(type, level);
        noPhysics = true;
        setNoGravity(true);
    }
    static Vec3 station(ServerPlayer player) {
        return station(player.position(), player.getYRot());
    }
    private static Vec3 station(Vec3 origin, float heading) {
        double yaw = Math.toRadians(heading);
        return origin.add(1.25 * Math.cos(yaw) + .75 * Math.sin(yaw), 1.55,
                1.25 * Math.sin(yaw) - .75 * Math.cos(yaw));
    }
    private Vec3 lastOwnerPosition;
    private float followHeading;
    float followHeading() { return followHeading; }
    static final net.minecraft.network.syncher.EntityDataAccessor<Float> AIM_YAW = SynchedEntityData.defineId(DroneEntity.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    static final net.minecraft.network.syncher.EntityDataAccessor<Float> AIM_PITCH = SynchedEntityData.defineId(DroneEntity.class, net.minecraft.network.syncher.EntityDataSerializers.FLOAT);
    private void updateFollowHeading(ServerPlayer player) {
        Vec3 current = player.position();
        if (lastOwnerPosition == null) {
            // Recover the deployed shoulder offset even if the owner already turned.
            followHeading = (float)Math.toDegrees(Math.atan2(getZ()-current.z, getX()-current.x) + Math.atan2(.75, 1.25));
            lastOwnerPosition = current;
            return;
        }
        Vec3 delta = current.subtract(lastOwnerPosition);
        if (delta.horizontalDistanceSqr() >= .04 * .04) {
            followHeading = (float)Math.toDegrees(Math.atan2(-delta.x, delta.z));
            lastOwnerPosition = current;
        }
    }
    /**
     * Families that did a one-shot job recently, i.e. a carry pickup or a link hand-off.
     *
     * <p>Those two are not continuous states (unlike a lit lamp or an engagement), so the drain cannot
     * read them off the entity. Marking the airframe for a little over one settlement window means a
     * pickup that happens just after a settlement is still billed at the in-use rate on the next one
     * instead of slipping between the two.</p>
     */
    private final java.util.Map<String, Long> activeUntil = new java.util.HashMap<>();
    private static final long ACTIVITY_WINDOW = 25L;

    /** Records that {@code family} did work now, so the next settlement bills its in-use rate. */
    void markActive(String family) {
        if (family == null || family.isEmpty()) return;
        activeUntil.put(family, level().getGameTime() + ACTIVITY_WINDOW);
    }

    /** True while {@code family} has work inside the current settlement window. */
    boolean activeWithin(String family) {
        return activeUntil.getOrDefault(family, Long.MIN_VALUE) >= level().getGameTime();
    }

    /** Fallback for mods that interact with the entity directly rather than firing the use event. */    @Override public net.minecraft.world.InteractionResult mobInteract(net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand) {
        if (player.isShiftKeyDown() && player.getItemInHand(hand).isEmpty()
                && player instanceof ServerPlayer owner && owner.containerMenu instanceof TerminalMenu == false
                && TerminalMenus.openTerminal(owner, this)) return net.minecraft.world.InteractionResult.SUCCESS;
        return super.mobInteract(player, hand);
    }

    @Override public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel world)) return;
        ServerPlayer p = owner == null ? null : world.getServer().getPlayerList().getPlayer(owner);
        if (p == null || !p.isAlive() || p.level() != world || !p.getPersistentData().hasUUID(DroneItem.ACTIVE)
                || !getUUID().equals(p.getPersistentData().getUUID(DroneItem.ACTIVE))) { discard(); return; }
        if (tickCount % 20 == 0) {
            var terminal = controller(p);
            if (terminal.isEmpty()) { p.getPersistentData().remove(DroneItem.ACTIVE); discard(); return; }
            DroneArchive.checkpoint(world, this);
            systems.second(this, p, terminal);
        }
        systems.tick(this, p);
        if (distanceToSqr(p) > 64 * 64) {
            DroneLifecycle.delay(this, DroneLifecycle.UNLOAD);
            discard();
            return;
        }
        updateFollowHeading(p);
        boolean interacting = (p.isShiftKeyDown() || p.containerMenu instanceof TerminalMenu) && p.canInteractWithEntity(this, 0);
        if (holdingStation || interacting) {
            setDeltaMovement(Vec3.ZERO);
            entityData.set(FLIGHT, (byte)0);
            return;
        }
        if (workTarget != null && DroneWork.chase(this, p)) return;
        Vec3 target = station(p.position(), followHeading);
        Vec3 next = followPosition(target);
        double speed = next.subtract(position()).horizontalDistance();
        entityData.set(FLIGHT, (byte)Math.round(15 * Math.min(speed / 0.22, 1)));
        setPos(next);
        if (getTarget() == null) faceHeading(followHeading);
    }
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

    /** Aim body, head and view at {@code heading}; used by the follow AI and by the work orbit. */
    public void faceHeading(float heading) {
        setYRot(heading);
        setYHeadRot(heading);
        setYBodyRot(heading);
    }
    public net.minecraft.world.item.ItemStack controller() {
        if (owner != null && level() instanceof ServerLevel world && world.getServer().getPlayerList().getPlayer(owner) instanceof ServerPlayer player) return controller(player);
        return net.minecraft.world.item.ItemStack.EMPTY;
    }
    private net.minecraft.world.item.ItemStack controller(ServerPlayer player) {
        var equipped = CuriosDrones.equipped(player);
        return getUUID().equals(DroneItem.serial(equipped)) ? equipped : net.minecraft.world.item.ItemStack.EMPTY;
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { super.defineSynchedData(builder); builder.define(FRAME, "peregrine"); builder.define(FRAME_WIDTH, 0.7f); builder.define(FRAME_HEIGHT, 0.7f); builder.define(MOUNT_PITCH, (byte)85); builder.define(AIM_YAW, 0f); builder.define(AIM_PITCH, 0f); builder.define(FLIGHT, (byte)0); builder.define(PAINT, (byte)0); builder.define(SCANNING, false); builder.define(WORKING, false); builder.define(LAMP,0); }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.hasUUID("Owner")) owner = tag.getUUID("Owner");
        if (tag.contains("WorkTarget")) workTarget = net.minecraft.core.BlockPos.of(tag.getLong("WorkTarget"));
        workStalled = tag.getBoolean("WorkStalled");
    }
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (owner != null) tag.putUUID("Owner", owner);
        if (workTarget != null) tag.putLong("WorkTarget", workTarget.asLong());
        tag.putBoolean("WorkStalled", workStalled);
    }
}
