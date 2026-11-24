package dev.dronepal.charging;

import dev.dronepal.Dronepal;
import dev.dronepal.Hardware;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ChargingDockRegistry {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Dronepal.ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Dronepal.ID);
    private static final DeferredRegister<BlockEntityType<?>> TYPES = DeferredRegister.create(BuiltInRegistries.BLOCK_ENTITY_TYPE, Dronepal.ID);
    public static final DeferredBlock<ChargingDockBlock> DOCK = BLOCKS.register("charging_dock", () -> new ChargingDockBlock(
            BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL).noOcclusion()));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ChargingDockEntity>> TYPE = TYPES.register("charging_dock",
            () -> BlockEntityType.Builder.of(ChargingDockEntity::new, DOCK.get()).build(null));

    private ChargingDockRegistry() {}

    /** Exposed so the client can register the hologram renderer for this block entity type. */
    public static BlockEntityType<ChargingDockEntity> type() { return TYPE.get(); }

    public static void register(IEventBus bus) {
        ITEMS.registerSimpleBlockItem(DOCK);
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TYPES.register(bus);
        bus.addListener(ChargingDockRegistry::capabilities);
    }

    private static void capabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(Capabilities.EnergyStorage.BLOCK, TYPE.get(), (dock, side) -> dock);
        for (var battery : Hardware.BATTERIES) {
            event.registerItem(Capabilities.EnergyStorage.ITEM, (stack, context) -> new BatteryPort(() -> stack), battery.get());
        }
        event.registerEntity(Capabilities.EnergyStorage.ENTITY, Dronepal.ENTITY.get(), (drone, side) -> new BatteryPort(drone::controller));
    }

    private record BatteryPort(Supplier<ItemStack> stack) implements IEnergyStorage {
        @Override public int receiveEnergy(int amount, boolean simulate) { return Hardware.charge(stack.get(), amount, simulate); }
        @Override public int getEnergyStored() { return Hardware.energy(stack.get()); }
        @Override public int getMaxEnergyStored() { return Hardware.capacity(stack.get()); }
        @Override public boolean canReceive() { return true; }
        @Override public boolean canExtract() { return false; }
        @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
    }
}
