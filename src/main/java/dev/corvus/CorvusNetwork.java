package dev.corvus;

import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * The single {@code SimpleChannel} for the combat/scan/comms layer, plus the two hooks the hardware
 * layer exposes back into it.
 *
 * <p>1.20.1 has no {@code CustomPacketPayload}/{@code RegisterPayloadHandlersEvent}, so every payload
 * is a plain record with {@code encode}/{@code decode} (see {@link ScanSnapshot},
 * {@link CommsMessage}, {@link DroneVoice}, {@link DroneRemote}) and the protocol version is the
 * channel's own accept-version string.</p>
 *
 * <p>Client-only handlers are reached through {@link DistExecutor} so a dedicated server never loads
 * a GUI class.</p>
 */
public final class CorvusNetwork {
    private static final String VERSION = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Corvus.ID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    private static boolean registered;

    /**
     * Called once from the mod constructor (see {@code PORT-REQUESTS-C.md}). Idempotent: the
     * per-feature {@code register(bus)} shims all funnel here, so any merge order is safe.
     */
    public static synchronized void register() {
        if (registered) return;
        registered = true;
        int id = 0;

        CHANNEL.registerMessage(id++, ScanSnapshot.class, ScanSnapshot::encode, ScanSnapshot::decode,
                (message, context) -> {
                    context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            () -> () -> dev.corvus.client.ScanDisplay.accept(message)));
                    context.get().setPacketHandled(true);
                });

        CHANNEL.registerMessage(id++, CommsMessage.class, CommsMessage::encode, CommsMessage::decode,
                (message, context) -> {
                    context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            () -> () -> dev.corvus.client.CommsSubtitles.accept(
                                    message.speaker(), message.persona(), message.text())));
                    context.get().setPacketHandled(true);
                });

        CHANNEL.registerMessage(id++, DroneVoice.class, DroneVoice::encode, DroneVoice::decode,
                (message, context) -> {
                    context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            () -> () -> dev.corvus.client.DroneVoicePlayer.accept(message)));
                    context.get().setPacketHandled(true);
                });

        CHANNEL.registerMessage(id++, DroneRemote.Query.class, DroneRemote.Query::encode, DroneRemote.Query::decode,
                (message, context) -> {
                    context.get().enqueueWork(() -> {
                        ServerPlayer player = context.get().getSender();
                        DroneRemote.Status answer = DroneRemote.onQuery(player, message);
                        if (answer != null) send(player, answer);
                    });
                    context.get().setPacketHandled(true);
                });

        CHANNEL.registerMessage(id++, DroneRemote.Command.class, DroneRemote.Command::encode, DroneRemote.Command::decode,
                (message, context) -> {
                    context.get().enqueueWork(() -> {
                        ServerPlayer player = context.get().getSender();
                        DroneRemote.Status answer = DroneRemote.onCommand(player, message);
                        if (answer != null) send(player, answer);
                    });
                    context.get().setPacketHandled(true);
                });

        CHANNEL.registerMessage(id++, DroneRemote.Status.class, DroneRemote.Status::encode, DroneRemote.Status::decode,
                (message, context) -> {
                    context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            () -> () -> DroneRemote.receive.accept(message)));
                    context.get().setPacketHandled(true);
                });

        CHANNEL.registerMessage(id++, ModuleSync.Table.class, ModuleSync.Table::encode, ModuleSync.Table::decode,
                (message, context) -> {
                    context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                            () -> () -> ModuleSync.accept(message)));
                    context.get().setPacketHandled(true);
                });

        // The hardware layer owns modules and power but cannot see the intent implementations.
        Hardware.setIntentHandler(DroneSystems::executeIntent);
        Hardware.setGunHandler(GUN_HANDLER);
        DroneCombat.register();
    }

    /** Slot predicates handed to the hardware layer, so the gun slot never links against TaCZ. */
    private static final Hardware.GunHandler GUN_HANDLER = new Hardware.GunHandler() {
        @Override
        public boolean isGun(ItemStack stack) {
            return CombatSlots.isGun(stack);
        }

        @Override
        public int installCount(ItemStack stack) {
            return CombatSlots.installCount(stack);
        }

        @Override
        public String installError(ItemStack controller, ItemStack part, List<ItemStack> parts) {
            return CombatSlots.installError(controller, part, parts);
        }
    };

    public static void send(ServerPlayer player, Object message) {
        if (player == null || message == null) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
    }

    private CorvusNetwork() {}
}
