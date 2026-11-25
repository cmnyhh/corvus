package dev.corvus.power;

import dev.corvus.Corvus;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registration for the three power blocks: burner generator, solar generator, battery bank.
 *
 * <p>Deliberately separate from {@code dev.corvus.charging.ChargingDockRegistry} so the dock keeps its
 * own file untouched by the power-grid work. Only the merge points in {@link Corvus} call into here.</p>
 *
 * <p>Nothing in Java names a model, texture or shape: the block id is the only link between the code
 * and {@code assets/huhu_dronepal/{blockstates,models,textures}}.</p>
 */
public final class PowerRegistry {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Corvus.ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Corvus.ID);
    private static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Corvus.ID);

    // noOcclusion: none of the three housings fills its cell (12 x 16 x 12.2, 15 x 11.7 x 15 and
    // 15.1 x 11.4 x 11.2 units), and a block marked as a full occluder makes the block underneath cull
    // the very face the machine stands on - the stone top used to look like a hole in the world.
    public static final RegistryObject<Block> BURNER = BLOCKS.register("burner_generator",
            () -> new BurnerGeneratorBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL)
                    .noOcclusion()));
    public static final RegistryObject<Item> BURNER_ITEM = ITEMS.register("burner_generator",
            () -> new PowerBlockItem(BURNER.get(), new Item.Properties(), "burner_generator"));
    public static final RegistryObject<BlockEntityType<BurnerGeneratorEntity>> BURNER_TYPE = TYPES.register("burner_generator",
            () -> BlockEntityType.Builder.of(BurnerGeneratorEntity::new, BURNER.get()).build(null));

    public static final RegistryObject<Block> SOLAR = BLOCKS.register("solar_generator",
            () -> new SolarGeneratorBlock(BlockBehaviour.Properties.of().strength(3.0F).sound(SoundType.METAL)
                    .noOcclusion()));
    public static final RegistryObject<Item> SOLAR_ITEM = ITEMS.register("solar_generator",
            () -> new PowerBlockItem(SOLAR.get(), new Item.Properties(), "solar_generator"));
    public static final RegistryObject<BlockEntityType<SolarGeneratorEntity>> SOLAR_TYPE = TYPES.register("solar_generator",
            () -> BlockEntityType.Builder.of(SolarGeneratorEntity::new, SOLAR.get()).build(null));

    public static final RegistryObject<Block> BANK = BLOCKS.register("battery_bank",
            () -> new BatteryBankBlock(BlockBehaviour.Properties.of().strength(4.0F).sound(SoundType.METAL)
                    .noOcclusion()));
    public static final RegistryObject<Item> BANK_ITEM = ITEMS.register("battery_bank",
            () -> new PowerBlockItem(BANK.get(), new Item.Properties(), "battery_bank"));
    public static final RegistryObject<BlockEntityType<BatteryBankEntity>> BANK_TYPE = TYPES.register("battery_bank",
            () -> BlockEntityType.Builder.of(BatteryBankEntity::new, BANK.get()).build(null));

    private PowerRegistry() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TYPES.register(bus);
        // TODO(grid): wireless power tower registration (power/grid).
        dev.corvus.power.grid.TowerRegistry.register(bus);
    }

    public static void addToTab(CreativeModeTab.Output output) {
        output.accept(BURNER_ITEM.get());
        output.accept(SOLAR_ITEM.get());
        output.accept(BANK_ITEM.get());
        dev.corvus.power.grid.TowerRegistry.addToTab(output);
    }
}
