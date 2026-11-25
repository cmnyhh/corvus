package dev.dronepal.power.grid;

import dev.dronepal.Dronepal;
import dev.dronepal.ServerOptions;
import dev.dronepal.power.PowerBlockItem;
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
 * Registration for the wireless power tower: bottom (block entity), middle and top segments, plus the
 * bottom's block item. The upper two segments have no item and no loot table — the bottom owns both,
 * which is what makes "break any segment, one drop" true by construction.
 */
public final class TowerRegistry {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Dronepal.ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Dronepal.ID);
    private static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, Dronepal.ID);

    private static BlockBehaviour.Properties housing() {
        return BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL)
                .requiresCorrectToolForDrops().noOcclusion();
    }

    public static final DeferredBlock<PowerTowerBlock> TOWER = BLOCKS.register("power_tower",
            () -> new PowerTowerBlock(housing()));
    public static final DeferredBlock<PowerTowerPartBlock> MID = BLOCKS.register("power_tower_mid",
            () -> new PowerTowerPartBlock(housing(), PowerTowerPartBlock.Part.MID));
    public static final DeferredBlock<PowerTowerPartBlock> TOP = BLOCKS.register("power_tower_top",
            () -> new PowerTowerPartBlock(housing(), PowerTowerPartBlock.Part.TOP));

    public static final DeferredItem<PowerBlockItem> ITEM = ITEMS.register("power_tower",
            () -> new PowerBlockItem(TOWER.get(), new Item.Properties(), TowerRegistry::tooltip));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PowerTowerEntity>> TYPE =
            TYPES.register("power_tower", () -> BlockEntityType.Builder.of(PowerTowerEntity::new, TOWER.get()).build(null));

    private TowerRegistry() {}

    public static PowerTowerPartBlock part(PowerTowerPartBlock.Part part) {
        return part == PowerTowerPartBlock.Part.TOP ? TOP.get() : MID.get();
    }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TYPES.register(bus);
        bus.addListener(TowerRegistry::capabilities);
    }

    public static void addToTab(CreativeModeTab.Output output) { output.accept(ITEM.get()); }

    private static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, TYPE.get(), (tower, side) -> tower);
    }

    /** Tooltip is read live so a server config edit is visible without restarting the client. */
    private static List<Component> tooltip() {
        return List.of(
                Component.translatable("block.huhu_dronepal.power_tower.tip.buffer",
                        amount(PowerTowerEntity.CAPACITY), amount(ServerOptions.towerThroughput())),
                Component.translatable("block.huhu_dronepal.power_tower.tip.radius",
                        ServerOptions.towerRadius(), amount(ServerOptions.towerPullRate())),
                Component.translatable("block.huhu_dronepal.power_tower.tip.efficiency",
                        percent(ServerOptions.towerEfficiency())),
                Component.translatable("block.huhu_dronepal.power_tower.tip.priority"));
    }

    private static String amount(int value) { return String.format(Locale.ROOT, "%,d", value); }
    private static String percent(double value) { return String.format(Locale.ROOT, "%.2f", value); }
}
