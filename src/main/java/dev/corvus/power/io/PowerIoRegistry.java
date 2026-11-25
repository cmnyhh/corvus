package dev.corvus.power.io;

import dev.corvus.Corvus;
import dev.corvus.ServerOptions;
import dev.corvus.core.PowerIo;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.jetbrains.annotations.Nullable;

/**
 * Registration for the two interface blocks: the {@code power_port} outlet and the {@code power_cable}
 * run.
 *
 * <p>Mirror of the 1.21.1 registry. 1.20.1 publishes energy capabilities from each block entity's
 * {@code getCapability} (see {@link PowerPortEntity} and {@link PowerCableEntity}), so there is no
 * capability listener here — that is the only structural difference between the two builds.</p>
 */
public final class PowerIoRegistry {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Corvus.ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Corvus.ID);
    private static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Corvus.ID);

    // noOcclusion: neither block fills its cell, and a full occluder would cull the face of the block
    // it stands on (the same regression the power blocks and the mill already carry a comment about).
    public static final RegistryObject<PowerPortBlock> PORT = BLOCKS.register("power_port",
            () -> new PowerPortBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops().noOcclusion()));
    public static final RegistryObject<PowerCableBlock> CABLE = BLOCKS.register("power_cable",
            () -> new PowerCableBlock(BlockBehaviour.Properties.of().strength(0.8F).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops().noOcclusion()));

    public static final RegistryObject<Item> PORT_ITEM = ITEMS.register("power_port",
            () -> new PowerIoItem(PORT.get(), new Item.Properties(), PowerIoRegistry::portTooltip));
    public static final RegistryObject<Item> CABLE_ITEM = ITEMS.register("power_cable",
            () -> new PowerIoItem(CABLE.get(), new Item.Properties(), PowerIoRegistry::cableTooltip));

    public static final RegistryObject<BlockEntityType<PowerPortEntity>> PORT_TYPE = TYPES.register("power_port",
            () -> BlockEntityType.Builder.of(PowerPortEntity::new, PORT.get()).build(null));
    public static final RegistryObject<BlockEntityType<PowerCableEntity>> CABLE_TYPE = TYPES.register("power_cable",
            () -> BlockEntityType.Builder.of(PowerCableEntity::new, CABLE.get()).build(null));

    private PowerIoRegistry() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TYPES.register(bus);
    }

    /** The two entries this feature contributes to the creative page. */
    public static void addToTab(CreativeModeTab.Output output) {
        output.accept(new ItemStack(PORT_ITEM.get()));
        output.accept(new ItemStack(CABLE_ITEM.get()));
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
                        amount(PowerIo.CABLE_RATE), PowerIo.CABLE_LENGTH),
                Component.translatable("block.huhu_dronepal.power_cable.tip.use"),
                Component.translatable("block.huhu_dronepal.power_cable.tip.read"));
    }

    private static String amount(int value) { return String.format(Locale.ROOT, "%,d", value); }

    /** Block item whose hover lines come from a supplier, so the numbers stay live. */
    private static final class PowerIoItem extends BlockItem {
        private final Supplier<List<Component>> lines;

        PowerIoItem(Block block, Properties properties, Supplier<List<Component>> lines) {
            super(block, properties);
            this.lines = lines;
        }

        @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                              TooltipFlag flag) {
            super.appendHoverText(stack, level, tooltip, flag);
            for (Component line : lines.get()) tooltip.add(line.copy().withStyle(ChatFormatting.GRAY));
        }
    }
}
