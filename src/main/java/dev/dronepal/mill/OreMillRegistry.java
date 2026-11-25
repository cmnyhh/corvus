package dev.dronepal.mill;

import dev.dronepal.Dronepal;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.MenuType;
import dev.dronepal.power.PowerBlockItem;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registry entries for the ore mill, its five items and the energy capability. */
public final class OreMillRegistry {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Dronepal.ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Dronepal.ID);
    private static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, Dronepal.ID);
    private static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(BuiltInRegistries.MENU, Dronepal.ID);

    // noOcclusion on the machines: the models are built shells (a mill with feet and a disc pit, a
    // pump with a round body), so the block must not report itself as a full occluder - otherwise a
    // neighbouring block culls the face that touches it and the block below a generator loses its
    // top face. Their collision shapes are the models' own AABBs (see the block classes).
    public static final DeferredBlock<OreMillBlock> MILL = BLOCKS.register("ore_mill",
            () -> new OreMillBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops().noOcclusion()));
    public static final DeferredBlock<OreMillSideBlock> MILL_SIDE = BLOCKS.register("ore_mill_side",
            () -> new OreMillSideBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops().noLootTable().noOcclusion()));
    public static final DeferredBlock<WaterPumpBlock> WATER_PUMP = BLOCKS.register("water_pump",
            () -> new WaterPumpBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops().noOcclusion()));
    public static final DeferredBlock<WaterPipeBlock> WATER_PIPE = BLOCKS.register("water_pipe",
            () -> new WaterPipeBlock(BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops().noOcclusion()));
    public static final DeferredItem<PowerBlockItem> MILL_ITEM = ITEMS.register("ore_mill",
            () -> new PowerBlockItem(MILL.get(), new Item.Properties(), OreMillRegistry::millTooltip));
    public static final DeferredItem<PowerBlockItem> WATER_PUMP_ITEM = ITEMS.register("water_pump",
            () -> new PowerBlockItem(WATER_PUMP.get(), new Item.Properties(), WaterPumpEntity::tooltip));
    public static final DeferredItem<PowerBlockItem> WATER_PIPE_ITEM = ITEMS.register("water_pipe",
            () -> new PowerBlockItem(WATER_PIPE.get(), new Item.Properties(), OreMillRegistry::pipeTooltip));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<OreMillEntity>> TYPE = TYPES.register("ore_mill",
            () -> BlockEntityType.Builder.of(OreMillEntity::new, MILL.get()).build(null));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WaterPumpEntity>> WATER_PUMP_TYPE = TYPES.register("water_pump",
            () -> BlockEntityType.Builder.of(WaterPumpEntity::new, WATER_PUMP.get()).build(null));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<WaterPipeEntity>> WATER_PIPE_TYPE = TYPES.register("water_pipe",
            () -> BlockEntityType.Builder.of(WaterPipeEntity::new, WATER_PIPE.get()).build(null));
    public static final DeferredHolder<MenuType<?>, MenuType<OreMillMenu>> MENU = MENUS.register("ore_mill",
            () -> new MenuType<>(OreMillMenu::new, FeatureFlags.DEFAULT_FLAGS));

    private OreMillRegistry() {}

    /** Client code registers the screen against this. */
    public static BlockEntityType<OreMillEntity> type() { return TYPE.get(); }

    public static void register(IEventBus bus) {
        MillItems.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TYPES.register(bus);
        MENUS.register(bus);
        bus.addListener(OreMillRegistry::capabilities);
    }

    public static void creative(CreativeModeTab.Output output) {
        output.accept(MILL_ITEM.get());
        output.accept(WATER_PUMP_ITEM.get());
        output.accept(WATER_PIPE_ITEM.get());
        MillItems.creative(output);
    }

    /** The mill body behind a side block, or null when the pair is broken. */
    static OreMillEntity body(Level level, BlockPos pos, BlockState state) {
        BlockPos main = pos.relative(state.getValue(OreMillSideBlock.FACING).getCounterClockWise());
        return level.getBlockEntity(main) instanceof OreMillEntity mill ? mill : null;
    }

    private static List<Component> pipeTooltip() {
        return List.of(
                Component.translatable("block.huhu_dronepal.water_pipe.tip.rate", OreMillRules.PUMP_RATE).withStyle(ChatFormatting.GRAY),
                Component.translatable("block.huhu_dronepal.water_pipe.tip.range", 32).withStyle(ChatFormatting.GRAY),
                Component.translatable("block.huhu_dronepal.water_pipe.tip.read").withStyle(ChatFormatting.GRAY));
    }

    private static List<Component> millTooltip() {
        return List.of(
                Component.translatable("block.huhu_dronepal.ore_mill.tip.rate", OreMillRules.FE_PER_TICK,
                        String.format(java.util.Locale.ROOT, "%,d", OreMillRules.BUFFER)).withStyle(ChatFormatting.GRAY),
                Component.translatable("block.huhu_dronepal.ore_mill.tip.work",
                        OreMillRules.MANUAL_TICKS / 20, OreMillRules.WATER_PER_ITEM).withStyle(ChatFormatting.GRAY),
                Component.translatable("block.huhu_dronepal.ore_mill.tip.drone",
                        OreMillRules.DRONE_TICKS / 20, OreMillRules.WATER_PER_ITEM_DRONE).withStyle(ChatFormatting.GRAY));
    }

    private static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, TYPE.get(), (mill, side) -> mill);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, TYPE.get(), (mill, side) -> mill);
        // Cables and pipes may also land on the side block: proxy both capabilities to the body.
        event.registerBlock(Capabilities.EnergyStorage.BLOCK,
                (level, pos, state, blockEntity, side) -> body(level, pos, state), MILL_SIDE.get());
        event.registerBlock(Capabilities.FluidHandler.BLOCK,
                (level, pos, state, blockEntity, side) -> body(level, pos, state), MILL_SIDE.get());
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, WATER_PUMP_TYPE.get(), (pump, side) -> pump);
        event.registerBlockEntity(Capabilities.FluidHandler.BLOCK, WATER_PIPE_TYPE.get(), (pipe, side) -> pipe);
    }
}
