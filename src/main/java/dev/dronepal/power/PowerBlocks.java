package dev.dronepal.power;

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
 * The power chain: burner generator, solar generator, battery bank.
 *
 * <p>No GUI and no model-side state: the geometry comes from the blockstate/model JSON, so artists can
 * replace the resources without touching Java. Each block's collision shape is its model's own
 * bounding box (see the block classes), so a player stands on the housing instead of on an invisible
 * cube around it. The block entity types are what the energy capability is registered against.</p>
 */
public final class PowerBlocks {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Dronepal.ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Dronepal.ID);
    private static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, Dronepal.ID);

    /**
     * A fresh properties object per block: {@link BlockBehaviour.Properties} is mutable.
     *
     * <p>{@code noOcclusion()} is not cosmetic here. None of the three housings fills its cell (the
     * bank is 15.1 x 11.4 x 11.2 units, the solar array 15 x 11.7 x 15, the burner 12 x 16 x 12.2), so
     * a block marked as a full occluder makes the block underneath cull the very face the machine
     * stands on: the stone top under a generator looked like a hole in the world.</p>
     */
    private static BlockBehaviour.Properties housing() {
        return BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL)
                .requiresCorrectToolForDrops().noOcclusion();
    }

    public static final DeferredBlock<BurnerGeneratorBlock> BURNER = BLOCKS.register("burner_generator",
            () -> new BurnerGeneratorBlock(housing()));
    public static final DeferredBlock<SolarGeneratorBlock> SOLAR = BLOCKS.register("solar_generator",
            () -> new SolarGeneratorBlock(housing()));
    public static final DeferredBlock<BatteryBankBlock> BANK = BLOCKS.register("battery_bank",
            () -> new BatteryBankBlock(housing()));

    public static final DeferredItem<PowerBlockItem> BURNER_ITEM = ITEMS.register("burner_generator",
            () -> new PowerBlockItem(BURNER.get(), new Item.Properties(), PowerBlocks::burnerTooltip));
    public static final DeferredItem<PowerBlockItem> SOLAR_ITEM = ITEMS.register("solar_generator",
            () -> new PowerBlockItem(SOLAR.get(), new Item.Properties(), PowerBlocks::solarTooltip));
    public static final DeferredItem<PowerBlockItem> BANK_ITEM = ITEMS.register("battery_bank",
            () -> new PowerBlockItem(BANK.get(), new Item.Properties(), PowerBlocks::bankTooltip));

    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BurnerGeneratorEntity>> BURNER_TYPE = TYPES.register("burner_generator",
            () -> BlockEntityType.Builder.of(BurnerGeneratorEntity::new, BURNER.get()).build(null));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<SolarGeneratorEntity>> SOLAR_TYPE = TYPES.register("solar_generator",
            () -> BlockEntityType.Builder.of(SolarGeneratorEntity::new, SOLAR.get()).build(null));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BatteryBankEntity>> BANK_TYPE = TYPES.register("battery_bank",
            () -> BlockEntityType.Builder.of(BatteryBankEntity::new, BANK.get()).build(null));

    private PowerBlocks() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TYPES.register(bus);
        bus.addListener(PowerBlocks::capabilities);
    }

    public static void addToTab(CreativeModeTab.Output output) {
        output.accept(BURNER_ITEM.get());
        output.accept(SOLAR_ITEM.get());
        output.accept(BANK_ITEM.get());
    }

    private static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BURNER_TYPE.get(), (generator, side) -> generator);
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, SOLAR_TYPE.get(), (generator, side) -> generator);
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, BANK_TYPE.get(), (bank, side) -> bank);
    }

    // ---- Tooltips: numbers come from the live config, so a server change is visible immediately ----

    private static List<Component> burnerTooltip() {
        return List.of(
                Component.translatable("block.huhu_dronepal.burner_generator.tip.output",
                        amount(ServerOptions.burnerOutput()), amount(BurnerGeneratorEntity.BUFFER)),
                Component.translatable("block.huhu_dronepal.burner_generator.tip.fuel"),
                Component.translatable("block.huhu_dronepal.burner_generator.tip.push"));
    }

    private static List<Component> solarTooltip() {
        return List.of(
                Component.translatable("block.huhu_dronepal.solar_generator.tip.output",
                        amount(ServerOptions.solarOutput()), amount(SolarGeneratorEntity.BUFFER)),
                Component.translatable("block.huhu_dronepal.solar_generator.tip.sky"),
                Component.translatable("block.huhu_dronepal.solar_generator.tip.push"),
                Component.translatable("block.huhu_dronepal.solar_generator.tip.state"));
    }

    private static List<Component> bankTooltip() {
        return List.of(
                Component.translatable("block.huhu_dronepal.battery_bank.tip.capacity",
                        amount(ServerOptions.batteryBankCapacity()), amount(BatteryBankEntity.RATE)),
                Component.translatable("block.huhu_dronepal.battery_bank.tip.read"),
                Component.translatable("block.huhu_dronepal.battery_bank.tip.window"));
    }

    private static String amount(int value) { return String.format(Locale.ROOT, "%,d", value); }
}
