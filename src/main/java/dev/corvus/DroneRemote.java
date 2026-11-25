package dev.corvus;

import dev.corvus.data.CorvusData;
import dev.corvus.data.CorvusKeys;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * The remote-control authority: the radial wheel's query/command protocol plus the personality
 * switches that the terminal also exposes.
 *
 * <p>Every command is authorised against the owner's <em>current</em> controller again at execution
 * time, and a monotonically increasing (nonce, revision) cursor makes replays and out-of-order
 * answers harmless. 1.20.1 carries the packets on {@link CorvusNetwork}'s {@code SimpleChannel}.</p>
 */
public final class DroneRemote {
    public enum Action { FOLLOW, WEAPON, MODE, SCAN, MEDIC, CARRY, LIGHT, LINK, RECALL, DELIVER, SUPPLY, FIREMODE, WORK }

    static final UUID EMPTY = new UUID(0, 0);
    /** NBT switch prefix on the controller: {@code dronepal_off_<family>}. */
    static final String OFF_PREFIX = "dronepal_off_";
    /** The personality master switch: no chip, a silent chip, or this off means total silence. */
    static final String BARKS = "barks";

    private static final Map<ServerPlayer, Long> requests = new WeakHashMap<>();
    private static final Map<ServerPlayer, Long> commands = new WeakHashMap<>();
    private static final Map<ServerPlayer, Cursor> cursors = new WeakHashMap<>();
    /** Client-side sink for wheel state updates; set by {@code client.DroneWheel}. */
    public static Consumer<Status> receive = state -> {};

    private record Cursor(int nonce, int revision) {}

    public record Query(int nonce, int revision) {
        public static void encode(Query query, FriendlyByteBuf buffer) {
            buffer.writeInt(query.nonce);
            buffer.writeInt(query.revision);
        }

        public static Query decode(FriendlyByteBuf buffer) {
            return new Query(buffer.readInt(), buffer.readInt());
        }
    }

    /**
     * One wheel answer.
     *
     * <p>{@code drain} is FE/t the server just measured for the worn controller: the {@code drain_watts}
     * of every fitted module that is online, after its vendor multiplier ({@code -1} when there is no
     * controller at all). {@code solar} is the same kind of figure for the photovoltaic module, in the
     * current weather and daylight, and {@code -1} when no solar module is fitted.</p>
     */
    public record Status(int nonce, int revision, UUID serial, boolean signal, int enabled, int off, int solar,
                         int drain, List<String> labels) {
        public static void encode(Status status, FriendlyByteBuf buffer) {
            buffer.writeInt(status.nonce);
            buffer.writeInt(status.revision);
            buffer.writeUUID(status.serial);
            buffer.writeBoolean(status.signal);
            buffer.writeInt(status.enabled);
            buffer.writeInt(status.off);
            buffer.writeInt(status.solar);
            buffer.writeInt(status.drain);
            for (int slot = 0; slot < 8; slot++) {
                buffer.writeUtf(slot < status.labels.size() ? status.labels.get(slot) : "", 128);
            }
        }

        public static Status decode(FriendlyByteBuf buffer) {
            int nonce = buffer.readInt();
            int revision = buffer.readInt();
            UUID serial = buffer.readUUID();
            boolean signal = buffer.readBoolean();
            int enabled = buffer.readInt();
            int off = buffer.readInt();
            int solar = buffer.readInt();
            int drain = buffer.readInt();
            List<String> labels = new ArrayList<>();
            for (int slot = 0; slot < 8; slot++) labels.add(buffer.readUtf(128));
            return new Status(nonce, revision, serial, signal, enabled, off, solar, drain, List.copyOf(labels));
        }
    }

    public record Command(UUID serial, Action action, int nonce, int revision) {
        public Command(UUID serial, Action action) {
            this(serial, action, 0, 0);
        }

        public static void encode(Command command, FriendlyByteBuf buffer) {
            buffer.writeUUID(command.serial);
            buffer.writeEnum(command.action);
            buffer.writeInt(command.nonce);
            buffer.writeInt(command.revision);
        }

        public static Command decode(FriendlyByteBuf buffer) {
            return new Command(buffer.readUUID(), buffer.readEnum(Action.class), buffer.readInt(), buffer.readInt());
        }
    }

    /** Merge shim: the channel and the wheel packets are registered by {@link CorvusNetwork}. */
    public static void register(net.minecraftforge.eventbus.api.IEventBus bus) {
        CorvusNetwork.register();
    }

    // ------------------------------------------------------------------ personality switches

    /** All spoken lines live behind the chip: no chip, a silent chip, or the switch off means silence. */
    public static boolean barksEnabled(ItemStack controller) {
        return controller != null && !controller.isEmpty() && Hardware.hasChip(controller)
                && !silentChip(controller) && !disabled(controller, BARKS);
    }

    /** The switch alone, independent of whether a chip is fitted (terminal button state). */
    public static boolean barksOn(ItemStack controller) {
        return controller != null && !controller.isEmpty() && !disabled(controller, BARKS);
    }

    /** Flips only {@code dronepal_off_barks}; module and link switches keep their own keys. */
    public static void toggleBarks(ItemStack controller) {
        if (controller != null && !controller.isEmpty()) toggle(controller, BARKS);
    }

    /** True when the fitted chip never speaks, so a barks switch would be misleading. */
    public static boolean silentChip(ItemStack controller) {
        return "silent".equals(Hardware.chip(controller));
    }

    public static boolean disabled(ItemStack stack, String family) {
        return stack != null && !stack.isEmpty() && CorvusData.of(stack).getBoolean(OFF_PREFIX + family);
    }

    static void enable(ItemStack stack, String family) {
        if (disabled(stack, family)) toggle(stack, family);
    }

    /** Flips one switch; package-private so the acceptance run drives the same key the wheel writes. */
    static boolean toggle(ItemStack stack, String family) {
        CorvusData data = CorvusData.of(stack);
        String key = OFF_PREFIX + family;
        data.setBoolean(key, !data.getBoolean(key));
        return true;
    }

    // ------------------------------------------------------------------ protocol

    static Status onQuery(ServerPlayer player, Query query) {
        if (player == null || query.revision <= 0 || !allow(requests, player)) return null;
        Cursor previous = cursors.get(player);
        if (previous != null && (query.nonce < previous.nonce()
                || query.nonce == previous.nonce() && query.revision <= previous.revision)) return null;
        cursors.put(player, new Cursor(query.nonce, query.revision));
        return status(player, query.nonce, query.revision);
    }

    static Status onCommand(ServerPlayer player, Command command) {
        if (player == null) return null;
        Cursor cursor = cursors.get(player);
        if (cursor == null || cursor.nonce() != command.nonce || command.revision <= cursor.revision) return null;
        cursors.put(player, new Cursor(command.nonce, command.revision));
        if (!allow(commands, player)) return null;
        execute(player, command);
        return status(player, command.nonce, command.revision);
    }

    private static boolean allow(Map<ServerPlayer, Long> map, ServerPlayer player) {
        long now = player.serverLevel().getGameTime();
        if (map.getOrDefault(player, -1L) > now) return false;
        map.put(player, now + 3);
        return true;
    }

    private static ItemStack controller(ServerPlayer player, UUID id) {
        var state = player.getPersistentData();
        if (!player.isAlive() || player.isSpectator() || !state.hasUUID(CorvusKeys.ACTIVE)
                || !id.equals(state.getUUID(CorvusKeys.ACTIVE))) return ItemStack.EMPTY;
        ItemStack equipped = DroneCurios.equipped(player);
        return id.equals(DroneItem.serial(equipped)) ? equipped : ItemStack.EMPTY;
    }

    private static DroneEntity entity(ServerPlayer player, UUID id) {
        return player.serverLevel().getEntity(id) instanceof DroneEntity drone && drone.isAlive()
                && player.getUUID().equals(drone.ownerId()) ? drone : null;
    }

    static Status status(ServerPlayer player, int nonce) {
        return status(player, nonce, 0);
    }

    static Status status(ServerPlayer player, int nonce, int revision) {
        var state = player.getPersistentData();
        UUID id = state.hasUUID(CorvusKeys.ACTIVE) ? state.getUUID(CorvusKeys.ACTIVE) : EMPTY;
        ItemStack stack = controller(player, id);
        List<String> labels = new ArrayList<>(Collections.nCopies(8, ""));
        boolean signal = !stack.isEmpty() && entity(player, id) != null;
        int enabled = 0;
        int off = 0;
        int solar = -1;
        int drain = -1;
        boolean lightFitted = false;
        if (!stack.isEmpty()) {
            enabled = 1;
            drain = Hardware.drainPerTick(stack);
            labels.set(0, "dronepal.wheel.follow");
            if (disabled(stack, "follow")) off |= 1;
            for (Hardware.Module module : Hardware.installed(stack)) {
                int slot = switch (module.family()) {
                    case "combat" -> 1;
                    case "scan" -> 3;
                    case "medic" -> 4;
                    case "carry" -> 5;
                    case "light" -> 6;
                    case "link" -> 7;
                    default -> -1;
                };
                if (slot >= 0) {
                    if (slot == 6) lightFitted = true;
                    enabled |= 1 << slot;
                    labels.set(slot, module.wheelLabel());
                    if (disabled(stack, module.family())) off |= 1 << slot;
                    if (slot == 3) {
                        String scanState = disabled(stack, "scan") ? "scan_off"
                                : Hardware.powered(stack, "scan") ? "scan_on" : "scan_standby";
                        labels.set(slot, "dronepal.wheel." + scanState);
                    }
                    if (slot == 1) {
                        labels.set(1, DroneCombat.automatic(stack)
                                ? "dronepal.wheel.weapon_auto" : "dronepal.wheel.weapon_safe");
                        enabled |= 4;
                        labels.set(2, "dronepal.engage." + Engagement.mode(stack));
                    }
                } else if (module.family().equals("solar")) {
                    // Real output for the weather and hour the projection is actually standing in.
                    DroneEntity drone = entity(player, id);
                    var level = player.serverLevel();
                    boolean sky = drone != null && level.canSeeSky(drone.blockPosition());
                    solar = Hardware.solarOutput(module, sky, level.isDay(), level.isRaining());
                }
            }
            // The mill's work sector is the Shift variant of the lamp sector. It lights up whenever a
            // mill is within 32 blocks, even on a drone without a lamp; the label says "work" then.
            if (dev.corvus.mill.DroneWork.nearest(player.serverLevel(), player.blockPosition(),
                    dev.corvus.mill.OreMillRules.WORK_RANGE) != null) {
                enabled |= 1 << 6;
                if (!lightFitted) labels.set(6, "dronepal.wheel.work");
            }
        }
        return new Status(nonce, revision, id, signal, enabled, off, solar, drain, labels);
    }

    static boolean execute(ServerPlayer player, Command command) {
        ItemStack stack = controller(player, command.serial);
        if (stack.isEmpty()) return false;
        DroneEntity drone = entity(player, command.serial);
        if (command.action == Action.RECALL) return rescue(player, stack, command.serial, drone);
        if (drone == null) return feedback(player, "signal");
        boolean ok = switch (command.action) {
            case FOLLOW -> {
                toggle(stack, "follow");
                drone.holdingStation = disabled(stack, "follow");
                if (drone.holdingStation) dev.corvus.mill.DroneWork.stop(drone);
                yield true;
            }
            case WEAPON -> DroneCombat.toggle(drone);
            case MODE -> Engagement.cycle(drone);
            case SCAN -> {
                if (Hardware.tier(stack, "scan") == 0) yield false;
                toggle(stack, "scan");
                drone.scanPatrolChanged(player, !disabled(stack, "scan"));
                yield true;
            }
            case FIREMODE -> DroneCombat.cycleMode(drone);
            case WORK -> Hardware.execute(player, "order.work");
            case DELIVER, SUPPLY -> Hardware.execute(player, command.action == Action.DELIVER ? "order.fetch" : "order.supply");
            case MEDIC, CARRY, LIGHT, LINK -> {
                String family = command.action.name().toLowerCase(Locale.ROOT);
                yield Hardware.tier(stack, family) > 0
                        && (Hardware.online(stack, family) || (disabled(stack, family) && Hardware.energy(stack) > 0))
                        && toggle(stack, family);
            }
            default -> false;
        };
        if (!ok) return feedback(player, "unavailable");
        player.displayClientMessage(Component.translatable("dronepal.wheel.done"), true);
        return true;
    }

    private static boolean rescue(ServerPlayer player, ItemStack stack, UUID id, DroneEntity drone) {
        long now = player.serverLevel().getServer().overworld().getGameTime();
        CorvusData data = CorvusData.of(stack);
        long wait = data.getInt("dronepal_recall") - now;
        if (wait > 0) {
            player.displayClientMessage(Component.translatable("dronepal.wheel.cooldown", (wait + 19) / 20), true);
            return false;
        }
        if (drone == null) {
            if (!DroneItem.deploy(player, stack, id)) return feedback(player, "repair");
            drone = entity(player, id);
        }
        if (drone == null) return false;
        dev.corvus.mill.DroneWork.stop(drone);
        drone.holdingStation = false;
        drone.setTarget(null);
        drone.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
        drone.setPos(DroneEntity.station(player));
        data.setInt("dronepal_recall", (int) Math.min(Integer.MAX_VALUE, now + 200));
        data.setBoolean(OFF_PREFIX + "follow", false);
        player.displayClientMessage(Component.translatable("dronepal.wheel.rescued"), true);
        return true;
    }

    private static boolean feedback(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable("dronepal.wheel." + key), true);
        return false;
    }

    private DroneRemote() {}
}
