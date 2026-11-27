package dev.dronepal.power.io;

import dev.corvus.core.EnergyNet;
import dev.corvus.core.PowerIo;
import dev.dronepal.Dronepal;
import dev.dronepal.ServerOptions;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registration for the two interface blocks: the {@code power_port} outlet and the {@code power_cable}
 * run.
 *
 * <p>Self-contained, like the power and mill registries: {@link Dronepal} gains one call and one
 * creative-tab line at the end of the existing lists, and nothing else in the mod has to know these
 * blocks exist. Nothing here names a model, a texture or a shape — the block ids are the only link to
 * {@code assets/huhu_dronepal/{blockstates,models,textures}}.</p>
 *
 * <p>The energy capability is registered for <b>both</b> block entities on every side
 * ({@code RegisterCapabilitiesEvent}, NeoForge's way — there is no per-entity {@code getCapability}
 * hook in 1.21.1). The port publishes {@code canExtract} only; the cable publishes both so a machine
 * can push its smoothing reserve in.</p>
 */
public final class PowerIoRegistry {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Dronepal.ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Dronepal.ID);
    private static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, Dronepal.ID);

    /**
     * A fresh properties object per block: {@link BlockBehaviour.Properties} is mutable.
     *
     * <p>{@code noOcclusion()} because neither block fills its cell (the port is 14 x 14 x 14 plus a
     * plug, the cable is an 8 x 8 conduit): a block marked as a full occluder would cull the face of
     * the block it stands on and leave a hole in the world.</p>
     */
    private static BlockBehaviour.Properties housing(float strength) {
        return BlockBehaviour.Properties.of().strength(strength).sound(SoundType.METAL)
                .requiresCorrectToolForDrops().noOcclusion();
    }

    public static final DeferredBlock<PowerPortBlock> PORT =
            BLOCKS.register("power_port", () -> new PowerPortBlock(housing(3.5F)));
    public static final DeferredBlock<PowerCableBlock> CABLE =
            BLOCKS.register("power_cable", () -> new PowerCableBlock(housing(0.8F)));

    public static final DeferredItem<PowerIoItem> PORT_ITEM = ITEMS.register("power_port",
            () -> new PowerIoItem(PORT.get(), new Item.Properties(), PowerIoRegistry::portTooltip));
    public static final DeferredItem<PowerIoItem> CABLE_ITEM = ITEMS.register("power_cable",
            () -> new PowerIoItem(CABLE.get(), new Item.Properties(), PowerIoRegistry::cableTooltip));

    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PowerPortEntity>> PORT_TYPE =
            TYPES.register("power_port", () -> BlockEntityType.Builder.of(PowerPortEntity::new, PORT.get()).build(null));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PowerCableEntity>> CABLE_TYPE =
            TYPES.register("power_cable", () -> BlockEntityType.Builder.of(PowerCableEntity::new, CABLE.get()).build(null));

    private PowerIoRegistry() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TYPES.register(bus);
        bus.addListener(PowerIoRegistry::capabilities);
    }

    public static void addToTab(CreativeModeTab.Output output) {
        output.accept(PORT_ITEM.get());
        output.accept(CABLE_ITEM.get());
    }

    private static void capabilities(RegisterCapabilitiesEvent event) {
        // The port answers every face: another mod's cable picks the side it wants.
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, PORT_TYPE.get(), (port, side) -> port);
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, CABLE_TYPE.get(), (cable, side) -> cable);
    }

    // ---- Tooltips: numbers come from the live config and the shared core rules ----

    private static List<Component> portTooltip() {
        return List.of(
                Component.translatable("block.huhu_dronepal.power_port.tip.rate",
                        amount(PowerIo.portOutput(ServerOptions.portOutput())), amount(PowerPortEntity.BUFFER)),
                Component.translatable("block.huhu_dronepal.power_port.tip.sides"),
                Component.translatable("block.huhu_dronepal.power_port.tip.read"));
    }

    private static List<Component> cableTooltip() {
        return List.of(
                Component.translatable("block.huhu_dronepal.power_cable.tip.rate",
                        amount(EnergyNet.clampThroughput(ServerOptions.cableThroughput())),
                        EnergyNet.MAX_LENGTH),
                Component.translatable("block.huhu_dronepal.power_cable.tip.use"),
                Component.translatable("block.huhu_dronepal.power_cable.tip.read"));
    }

    private static String amount(int value) { return String.format(Locale.ROOT, "%,d", value); }
}
