package dev.corvus.power.grid;

import dev.corvus.Corvus;
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

import java.util.List;

/**
 * Forge registration for the wireless power tower. The upper two segments have no item and no loot
 * table, which is what makes "break any segment, one drop" true by construction.
 */
public final class TowerRegistry {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Corvus.ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Corvus.ID);
    private static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Corvus.ID);

    private static BlockBehaviour.Properties housing() {
        return BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL)
                .requiresCorrectToolForDrops().noOcclusion();
    }

    public static final RegistryObject<Block> TOWER = BLOCKS.register("power_tower",
            () -> new PowerTowerBlock(housing().noLootTable()));
    public static final RegistryObject<Block> MID = BLOCKS.register("power_tower_mid",
            () -> new PowerTowerPartBlock(housing().noLootTable(), PowerTowerPartBlock.Part.MID));
    public static final RegistryObject<Block> TOP = BLOCKS.register("power_tower_top",
            () -> new PowerTowerPartBlock(housing().noLootTable(), PowerTowerPartBlock.Part.TOP));

    public static final RegistryObject<Item> ITEM = ITEMS.register("power_tower",
            () -> new TowerItem(TOWER.get(), new Item.Properties()));

    public static final RegistryObject<BlockEntityType<PowerTowerEntity>> TYPE =
            TYPES.register("power_tower", () -> BlockEntityType.Builder.of(PowerTowerEntity::new, TOWER.get()).build(null));

    private TowerRegistry() {}

    public static PowerTowerPartBlock part(PowerTowerPartBlock.Part part) {
        return (PowerTowerPartBlock) (part == PowerTowerPartBlock.Part.TOP ? TOP.get() : MID.get());
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TYPES.register(bus);
    }

    public static void addToTab(CreativeModeTab.Output output) { output.accept(ITEM.get()); }

    /** Three grey hover lines, matching the other power blocks. */
    private static final class TowerItem extends BlockItem {
        private static final String PREFIX = "tooltip." + Corvus.ID + ".power_tower.";

        TowerItem(Block block, Properties properties) { super(block, properties); }

        @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
            super.appendHoverText(stack, level, lines, flag);
            for (int line = 1; line <= 3; line++) {
                lines.add(Component.translatable(PREFIX + line).withStyle(ChatFormatting.GRAY));
            }
        }
    }
}
