package dev.corvus.charging;

import dev.corvus.Corvus;
import dev.corvus.Hardware;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Charging pad registration plus the energy capabilities of everything that can hold a charge.
 *
 * <p>1.20.1 capabilities are attached per instance: the pad publishes {@link IEnergyStorage} from
 * {@link ChargingDockEntity#getCapability}, while batteries and the projected drone get one through
 * {@code AttachCapabilitiesEvent}, which Forge posts lazily the first time a stack or entity is asked
 * for a capability.</p>
 */
public final class ChargingDockRegistry {
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, Corvus.ID);
    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Corvus.ID);
    private static final DeferredRegister<BlockEntityType<?>> TYPES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, Corvus.ID);

    public static final RegistryObject<Block> DOCK = BLOCKS.register("charging_dock",
            () -> new ChargingDockBlock(BlockBehaviour.Properties.of().strength(3.5F).sound(SoundType.METAL).noOcclusion()));
    public static final RegistryObject<Item> DOCK_ITEM = ITEMS.register("charging_dock",
            () -> new BlockItem(DOCK.get(), new Item.Properties()));
    private static final RegistryObject<BlockEntityType<ChargingDockEntity>> TYPE = TYPES.register("charging_dock",
            () -> BlockEntityType.Builder.of(ChargingDockEntity::new, DOCK.get()).build(null));

    private static final ResourceLocation BATTERY_CAP = new ResourceLocation(Corvus.ID, "battery_energy");
    private static final ResourceLocation DRONE_CAP = new ResourceLocation(Corvus.ID, "drone_energy");
    private static boolean wired;

    private ChargingDockRegistry() {}

    /** Exposed so the client can register the hologram renderer for this block entity type. */
    public static BlockEntityType<ChargingDockEntity> type() { return TYPE.get(); }

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
        TYPES.register(bus);
        if (wired) return;
        wired = true;
        MinecraftForge.EVENT_BUS.addGenericListener(ItemStack.class, ChargingDockRegistry::attachItemStack);
        MinecraftForge.EVENT_BUS.addGenericListener(net.minecraft.world.entity.Entity.class, ChargingDockRegistry::attachEntity);
    }

    public static void addToTab(CreativeModeTab.Output output) {
        output.accept(DOCK_ITEM.get());
    }

    private static void attachItemStack(AttachCapabilitiesEvent<ItemStack> event) {
        ItemStack stack = event.getObject();
        if (!isBattery(stack)) return;
        event.addCapability(BATTERY_CAP, new Provider<>(ForgeCapabilities.ENERGY, LazyOptional.of(() -> new BatteryPort(stack))));
    }

    private static void attachEntity(AttachCapabilitiesEvent<net.minecraft.world.entity.Entity> event) {
        if (!(event.getObject() instanceof dev.corvus.DroneEntity drone)) return;
        event.addCapability(DRONE_CAP, new Provider<>(ForgeCapabilities.ENERGY, LazyOptional.of(() -> new DronePort(drone))));
    }

    private static boolean isBattery(ItemStack stack) {
        if (stack.isEmpty()) return false;
        for (RegistryObject<Item> battery : Hardware.BATTERIES) if (stack.is(battery.get())) return true;
        return false;
    }

    /** One capability, one instance: enough for everything the pad and cables need. */
    private record Provider<T>(Capability<T> capability, LazyOptional<T> storage) implements ICapabilityProvider {
        @Override public <R> LazyOptional<R> getCapability(Capability<R> asked, Direction side) {
            return asked == capability ? storage.cast() : LazyOptional.empty();
        }
    }

    /** Cell view: charging only, and the receive rate is the battery model's own limit. */
    private record BatteryPort(ItemStack stack) implements IEnergyStorage {
        @Override public int receiveEnergy(int amount, boolean simulate) { return Hardware.charge(stack, amount, simulate); }
        @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return Hardware.energy(stack); }
        @Override public int getMaxEnergyStored() { return Hardware.capacity(stack); }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return Hardware.capacity(stack) > 0; }
    }

    /** The projection has no cell of its own: it charges the owner's controller stack. */
    private record DronePort(dev.corvus.DroneEntity drone) implements IEnergyStorage {
        private ItemStack controller() { return ChargingDockEntity.controllerOf(drone); }
        @Override public int receiveEnergy(int amount, boolean simulate) { return Hardware.charge(controller(), amount, simulate); }
        @Override public int extractEnergy(int amount, boolean simulate) { return 0; }
        @Override public int getEnergyStored() { return Hardware.energy(controller()); }
        @Override public int getMaxEnergyStored() { return Hardware.capacity(controller()); }
        @Override public boolean canExtract() { return false; }
        @Override public boolean canReceive() { return Hardware.capacity(controller()) > 0; }
    }
}
