package dev.corvus.mill;

import dev.corvus.Corvus;
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
 * Registration for the ore mill, the water pump, the five mill items and the menu.
 *
 * <p>Nothing in Java names a model or texture: the registry ids are the only link between the code and
 * {@code assets/huhu_dronepal/{blockstates,models,textures}}. Tooltip text is built from the live
 * constants so a screenshot cannot drift from the code.</p>
 */
public final class OreMillRegistry {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Corvus.ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Corvus.ID);
    private static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Corvus.ID);
    private static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS =
            DeferredRegister.create(ForgeRegistries.MENU_TYPES, Corvus.ID);

    // noOcclusion on the machines: the models are built shells (a mill with feet and a disc pit, a
    // pump with a round body), so the block must not report itself as a full occluder - otherwise a
    // neighbouring block culls the face that touches it and the block below a machine loses its top
    // face. Collision follows each model's own AABB (see the block classes).
    public static final RegistryObject<Block> MILL = BLOCKS.register("ore_mill",
            () -> new OreMillBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops().noOcclusion()));
    public static final RegistryObject<Block> SIDE = BLOCKS.register("ore_mill_side",
            () -> new OreMillSideBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops().noLootTable().noOcclusion()));
    public static final RegistryObject<Block> PUMP = BLOCKS.register("water_pump",
            () -> new WaterPumpBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops().noOcclusion()));
    public static final RegistryObject<Block> PIPE = BLOCKS.register("water_pipe",
            () -> new WaterPipeBlock(BlockBehaviour.Properties.of().strength(0.8F).sound(SoundType.METAL)
                    .requiresCorrectToolForDrops().noOcclusion()));
    public static final RegistryObject<Item> MILL_ITEM = ITEMS.register("ore_mill",
            () -> new LineBlockItem(MILL.get(), new Item.Properties(), OreMillRegistry::millTooltip));
    public static final RegistryObject<Item> PUMP_ITEM = ITEMS.register("water_pump",
            () -> new LineBlockItem(PUMP.get(), new Item.Properties(), WaterPumpEntity::tooltip));
    public static final RegistryObject<Item> PIPE_ITEM = ITEMS.register("water_pipe",
            () -> new LineBlockItem(PIPE.get(), new Item.Properties(), OreMillRegistry::pipeTooltip));
    public static final RegistryObject<BlockEntityType<OreMillEntity>> TYPE = TYPES.register("ore_mill",
            () -> BlockEntityType.Builder.of(OreMillEntity::new, MILL.get()).build(null));
    public static final RegistryObject<BlockEntityType<WaterPumpEntity>> PUMP_TYPE = TYPES.register("water_pump",
            () -> BlockEntityType.Builder.of(WaterPumpEntity::new, PUMP.get()).build(null));
    public static final RegistryObject<net.minecraft.world.inventory.MenuType<OreMillMenu>> MENU = MENUS.register(
            "ore_mill", () -> new net.minecraft.world.inventory.MenuType<OreMillMenu>(OreMillMenu::new,
                    net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS));

    private OreMillRegistry() {}

    public static void register(IEventBus bus) {
        MillItems.register(bus);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TYPES.register(bus);
        MENUS.register(bus);
    }

    /** The seven entries this feature contributes to the creative page: mill, pump and five items. */
    public static void addToTab(CreativeModeTab.Output output) {
        output.accept(new ItemStack(MILL_ITEM.get()));
        output.accept(new ItemStack(PUMP_ITEM.get()));
        output.accept(new ItemStack(PIPE_ITEM.get()));
        MillItems.creative(output);
    }

    /** True for the two upgrade parts, in either upgrade well. */
    public static boolean isUpgrade(ItemStack stack) {
        return MillItems.isSieve(stack) || MillItems.isCooling(stack);
    }

    /** Client code registers the screen against this. */
    public static BlockEntityType<OreMillEntity> type() { return TYPE.get(); }

    private static List<Component> pipeTooltip() {
        return List.of(
                Component.translatable("block.huhu_dronepal.water_pipe.tip.1",
                        OreMillRules.PUMP_RATE, WaterPipeNetwork.MAX_LENGTH).withStyle(ChatFormatting.GRAY),
                Component.translatable("block.huhu_dronepal.water_pipe.tip.2").withStyle(ChatFormatting.GRAY));
    }

    private static List<Component> millTooltip() {
        return List.of(
                Component.translatable("block.huhu_dronepal.ore_mill.tip.rate",
                        OreMillRules.FE_PER_TICK, String.format(Locale.ROOT, "%,d", OreMillRules.BUFFER))
                        .withStyle(ChatFormatting.GRAY),
                Component.translatable("block.huhu_dronepal.ore_mill.tip.work",
                        OreMillRules.MANUAL_TICKS / 20, OreMillRules.WATER_PER_ITEM).withStyle(ChatFormatting.GRAY),
                Component.translatable("block.huhu_dronepal.ore_mill.tip.drone",
                        OreMillRules.DRONE_TICKS / 20, OreMillRules.WATER_PER_ITEM_DRONE).withStyle(ChatFormatting.GRAY));
    }

    /** Block item whose hover lines come from a supplier, so the numbers stay live. */
    private static final class LineBlockItem extends BlockItem {
        private final Supplier<List<Component>> lines;

        LineBlockItem(Block block, Properties properties, Supplier<List<Component>> lines) {
            super(block, properties);
            this.lines = lines;
        }

        @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip,
                                              TooltipFlag flag) {
            super.appendHoverText(stack, level, tooltip, flag);
            tooltip.addAll(lines.get());
        }
    }
}
