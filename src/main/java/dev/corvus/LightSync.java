package dev.corvus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Lamp-state transport.
 *
 * <p>1.20.1's {@code DroneEntity} is a plain tracked entity, and this layer deliberately does not add a
 * synched-data channel for the lamp: the module runtime already recomputes the lamp once per second, so a
 * small packet sent only when the state <em>changes</em> is both cheaper and independent of the entity's
 * own data serialiser choices (which the combat layer also edits).</p>
 *
 * <p>The payload is one integer per projection — the same packing the 1.21 build synched:
 * {@code radius << 4 | brightness | 512·flicker} — plus the lamp tint, and it goes to exactly the
 * clients tracking that projection ({@link PacketDistributor#TRACKING_ENTITY}). The receiving side is a
 * cache keyed by entity id; {@link dev.corvus.client.DroneLamps} prunes ids whose entity has gone, so a
 * recalled drone cannot leave a lamp burning in the client's memory.</p>
 */
public final class LightSync {
    private static final String VERSION = "1";
    /** Its own channel: the combat/scan channel is owned by another file of this port. */
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Corvus.ID, "lamp"), () -> VERSION, VERSION::equals, VERSION::equals);
    private static final Map<Integer, Lamp> CLIENT = new ConcurrentHashMap<>();
    private static boolean registered;

    /**
     * One lamp as the client needs it.
     *
     * @param profile packed {@code radius << 4 | brightness | 512·flicker}; 0 = dark
     * @param rgb     tint, {@code 0xRRGGBB}
     */
    public record Lamp(int profile, int rgb) {
        public int radius() {
            return (profile >> 4) & 31;
        }

        public int brightness() {
            return profile & 15;
        }

        public boolean flicker() {
            return (profile & ModuleRuntime.LIGHT_FLICKER) != 0;
        }

        public boolean lit() {
            return profile != 0 && brightness() > 0;
        }
    }

    /** Wire form: which projection, and its lamp. */
    public record State(int entityId, int profile, int rgb) {
        public static void encode(State state, FriendlyByteBuf buffer) {
            buffer.writeVarInt(state.entityId);
            buffer.writeInt(state.profile);
            buffer.writeInt(state.rgb);
        }

        public static State decode(FriendlyByteBuf buffer) {
            return new State(buffer.readVarInt(), buffer.readInt(), buffer.readInt());
        }
    }

    private LightSync() {}

    /** Called once from {@link ModuleRuntime}'s common setup; idempotent. */
    static void register() {
        if (registered) return;
        registered = true;
        CHANNEL.registerMessage(0, State.class, State::encode, State::decode, (message, context) -> {
            context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> accept(message)));
            context.get().setPacketHandled(true);
        });
    }

    /** Sends one lamp to the clients tracking {@code drone}. */
    public static void publish(DroneEntity drone, int profile, int rgb) {
        if (!registered || drone == null || drone.level().isClientSide()) return;
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> drone),
                new State(drone.getId(), profile, rgb));
    }

    /** Client sink: profile 0 means "dark", and drops the entry instead of storing a lit-looking zero. */
    private static void accept(State state) {
        if (state.profile() == 0) CLIENT.remove(state.entityId());
        else CLIENT.put(state.entityId(), new Lamp(state.profile(), state.rgb()));
    }

    /** The client-side lamp of an entity id, or null when it carries none. */
    public static Lamp lamp(int entityId) {
        return CLIENT.get(entityId);
    }

    /** Forgets entries whose entity no longer exists (a recalled projection, a dimension change). */
    public static void prune(IntPredicate exists) {
        CLIENT.keySet().removeIf(id -> !exists.test(id));
    }

    /** Number of lamps the client currently remembers; used by the client smoke harness. */
    public static int cached() {
        return CLIENT.size();
    }

    /** Test hook: installs a lamp without a live packet. */
    public static void remember(int entityId, int profile, int rgb) {
        accept(new State(entityId, profile, rgb));
    }

    public static void forgetAll() {
        CLIENT.clear();
    }
}
