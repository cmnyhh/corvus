package dev.corvus.manualshot;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import vazkii.patchouli.api.PatchouliAPI;
import vazkii.patchouli.client.book.BookCategory;
import vazkii.patchouli.client.book.BookContents;
import vazkii.patchouli.client.book.BookEntry;
import vazkii.patchouli.client.book.gui.GuiBookCategory;
import vazkii.patchouli.common.book.Book;
import vazkii.patchouli.common.book.BookRegistry;
import vazkii.patchouli.common.item.ItemModBook;

/**
 * Dev-only verification harness for the Patchouli manual port (no gameplay code is touched).
 *
 * <p>Inactive unless {@code -Dcorvus.manualShot=1}. Compiled only when gradle/patchouli-dev.init.gradle
 * adds {@code gradle/manualshot/java} to the main source set, so a plain {@code gradle build} never sees
 * this class. It exists because the dev client runs without window focus/display access: there is no way
 * to press F2 or script a GUI by hand, so the shots have to be taken from inside the client tick loop.</p>
 *
 * <p>Steps: give the guide book through the real {@code /give} NBT syntax, resolve it back through
 * {@code ItemModBook.getBook} (that is the actual 1.20.1 NBT key check), dump the loaded book
 * (categories/entries/pages), dump the recipe result, then photograph the landing page, a category page,
 * a crafting page and a spotlight page, then stop the game.</p>
 */
@Mod.EventBusSubscriber(modid = "huhu_dronepal", value = Dist.CLIENT)
public final class ManualShot {
    private static final boolean ENABLED = "1".equals(System.getProperty("corvus.manualShot"));
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("corvus-manual-shot");

    private static final ResourceLocation BOOK = new ResourceLocation("huhu_dronepal", "corvus_manual");
    private static final ResourceLocation CATEGORY = new ResourceLocation("huhu_dronepal", "build");
    private static final ResourceLocation ENTRY_CRAFTING = new ResourceLocation("huhu_dronepal", "build/parts");
    private static final ResourceLocation ENTRY_SPOTLIGHT = new ResourceLocation("huhu_dronepal", "start/what");

    /**
     * Ticks to wait after a screen opens before it is photographed. 40 ticks (2 s) is twice the
     * 20-tick floor: it rules out catching a page still being laid out / an animation mid-way, so a
     * capture-timing artefact cannot be mistaken for a typography defect.
     */
    private static final int SETTLE = 40;

    private static final int GIVE_AT = 40;
    private static final int DUMP_AT = 55;
    private static final int LANDING_AT = 70;
    private static final int SHOT_LANDING_AT = LANDING_AT + SETTLE;
    private static final int CATEGORY_AT = SHOT_LANDING_AT + 10;
    private static final int SHOT_CATEGORY_AT = CATEGORY_AT + SETTLE;
    private static final int ENTRY_CRAFT_AT = SHOT_CATEGORY_AT + 10;
    private static final int SHOT_ENTRY_CRAFT_AT = ENTRY_CRAFT_AT + SETTLE;
    private static final int ENTRY_SPOT_AT = SHOT_ENTRY_CRAFT_AT + 10;
    private static final int SHOT_ENTRY_SPOT_AT = ENTRY_SPOT_AT + SETTLE;
    private static final int DONE_AT = SHOT_ENTRY_SPOT_AT + 20;

    private static int ticks;
    private static boolean gave, dumped, landing, shotLanding, category, shotCategory;
    private static boolean entryCraft, shotEntryCraft, entrySpot, shotEntrySpot, done;

    private ManualShot() {}

    @SubscribeEvent
    public static void clientTick(TickEvent.ClientTickEvent event) {
        if (!ENABLED || event.phase != TickEvent.Phase.END) return;
        Minecraft game = Minecraft.getInstance();
        if (game.player == null || game.level == null) return;
        ticks++;
        if (ticks == 1) {
            game.options.pauseOnLostFocus = false;
            LOGGER.info("[manual-shot] pauseOnLostFocus=false, waiting for the world");
        }
        if (ticks == GIVE_AT && !gave) {
            gave = true;
            onServer(game, ManualShot::give);
        }
        if (ticks == DUMP_AT && !dumped) {
            dumped = true;
            dumpBook();
        }
        if (ticks == LANDING_AT && !landing) {
            landing = true;
            LOGGER.info("[manual-shot] openBookGUI({})", BOOK);
            PatchouliAPI.get().openBookGUI(BOOK);
            logScreen(game, "after openBookGUI");
        }
        if (ticks == SHOT_LANDING_AT && !shotLanding) {
            shotLanding = true;
            grab(game, "30-manual-1-landing.png");
        }
        if (ticks == CATEGORY_AT && !category) {
            category = true;
            openCategory(game);
        }
        if (ticks == SHOT_CATEGORY_AT && !shotCategory) {
            shotCategory = true;
            grab(game, "30-manual-2-category.png");
        }
        if (ticks == ENTRY_CRAFT_AT && !entryCraft) {
            entryCraft = true;
            LOGGER.info("[manual-shot] openBookEntry({})", ENTRY_CRAFTING);
            PatchouliAPI.get().openBookEntry(BOOK, ENTRY_CRAFTING, 0);
            logScreen(game, "after openBookEntry(crafting)");
        }
        if (ticks == SHOT_ENTRY_CRAFT_AT && !shotEntryCraft) {
            shotEntryCraft = true;
            grab(game, "30-manual-3-entry-crafting.png");
        }
        if (ticks == ENTRY_SPOT_AT && !entrySpot) {
            entrySpot = true;
            LOGGER.info("[manual-shot] openBookEntry({})", ENTRY_SPOTLIGHT);
            PatchouliAPI.get().openBookEntry(BOOK, ENTRY_SPOTLIGHT, 0);
            logScreen(game, "after openBookEntry(spotlight)");
        }
        if (ticks == SHOT_ENTRY_SPOT_AT && !shotEntrySpot) {
            shotEntrySpot = true;
            grab(game, "30-manual-4-entry-spotlight.png");
        }
        if (ticks == DONE_AT && !done) {
            done = true;
            LOGGER.info("[manual-shot] done, stopping the client");
            game.stop();
        }
    }

    private static void onServer(Minecraft game, java.util.function.Consumer<MinecraftServer> action) {
        MinecraftServer integrated = game.getSingleplayerServer();
        if (integrated == null) {
            LOGGER.error("[manual-shot] no integrated server");
            return;
        }
        integrated.execute(() -> action.accept(integrated));
    }

    /** The exact 1.20.1 form: NBT tag {@code patchouli:book} on patchouli:guide_book. */
    private static void give(MinecraftServer server) {
        // Recipe side first: the 1.20.1 result block has to carry the same tag as NBT, quoted key
        // included, otherwise the SNBT parser rejects the recipe at load time.
        Recipe<?> recipe = server.getRecipeManager()
                .byKey(new ResourceLocation("huhu_dronepal", "corvus_manual")).orElse(null);
        if (recipe == null) {
            LOGGER.error("[manual-shot] recipe huhu_dronepal:corvus_manual is NOT loaded");
        } else {
            ItemStack result = recipe.getResultItem(server.registryAccess());
            Book fromRecipe = ItemModBook.getBook(result);
            LOGGER.info("[manual-shot] recipe {} -> {} nbt={} book={}", recipe.getId(), result,
                    result.getTag(), fromRecipe == null ? "null" : fromRecipe.id);
        }

        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            LOGGER.error("[manual-shot] no player on the integrated server");
            return;
        }
        ServerPlayer player = players.get(0);
        String command = "give @s patchouli:guide_book{\"patchouli:book\":\"huhu_dronepal:corvus_manual\"}";
        LOGGER.info("[manual-shot] running /{}", command);
        server.getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(4), command);

        Item guideBook = BuiltInRegistries.ITEM.get(new ResourceLocation("patchouli", "guide_book"));
        ItemStack found = ItemStack.EMPTY;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack candidate = player.getInventory().getItem(slot);
            if (!candidate.isEmpty() && candidate.is(guideBook)) {
                found = candidate;
                break;
            }
        }
        if (found.isEmpty()) {
            LOGGER.error("[manual-shot] the /give result is not in the player inventory");
            return;
        }
        Book resolved = ItemModBook.getBook(found);
        LOGGER.info("[manual-shot] stack={} nbt={}", found, found.getTag());
        LOGGER.info("[manual-shot] ItemModBook.getBook -> {}", resolved == null ? "null" : resolved.id);
    }

    private static void dumpBook() {
        Book book = BookRegistry.INSTANCE.books.get(BOOK);
        if (book == null) {
            LOGGER.error("[manual-shot] book {} is not registered (book.json/json location)", BOOK);
            return;
        }
        LOGGER.info("[manual-shot] book name={} subtitle={} landing={} version={} i18n={} "
                        + "useResourcePack={} creativeTab={} noBook={}",
                book.name, book.subtitle, book.landingText, book.version, book.i18n,
                book.isExternal, book.creativeTab, book.noBook);
        int pageCount = 0;
        BookContents contents = book.getContents();
        if (contents == null) {
            LOGGER.error("[manual-shot] book contents are null");
            return;
        }
        for (BookEntry entry : contents.entries.values()) pageCount += entry.getPages().size();
        LOGGER.info("[manual-shot] pages(actual, authoritative)={}", pageCount);
        LOGGER.info("[manual-shot] contents: categories={} entries={} pages={} errored={}",
                contents.categories.size(), contents.entries.size(), pageCount, contents.isErrored());
        for (BookCategory category : contents.categories.values()) {
            LOGGER.info("[manual-shot]   category {} name={} entries={}", category.getId(),
                    category.getName().getString(), category.getEntries().size());
        }
        for (BookEntry entry : contents.entries.values()) {
            LOGGER.info("[manual-shot]   entry {} category={} pages={} icon={}", entry.getId(),
                    entry.getCategory() == null ? "null" : entry.getCategory().getId(),
                    entry.getPages().size(), entry.getIcon());
        }
    }

    private static void openCategory(Minecraft game) {
        Book book = BookRegistry.INSTANCE.books.get(BOOK);
        if (book == null || book.getContents() == null) {
            LOGGER.error("[manual-shot] cannot open the category page without contents");
            return;
        }
        BookCategory category = book.getContents().categories.get(CATEGORY);
        if (category == null) {
            LOGGER.error("[manual-shot] category {} missing", CATEGORY);
            return;
        }
        game.setScreen(new GuiBookCategory(book, category));
        logScreen(game, "after GuiBookCategory");
    }

    private static void logScreen(Minecraft game, String when) {
        LOGGER.info("[manual-shot] screen {}: {}", when,
                game.screen == null ? "null" : game.screen.getClass().getName());
    }

    private static void grab(Minecraft game, String name) {
        File directory = new File(System.getProperty("corvus.artifacts", "artifacts/client"));
        if (!directory.isDirectory() && !directory.mkdirs()) {
            LOGGER.error("[manual-shot] cannot create screenshot directory {}", directory.getAbsolutePath());
            return;
        }
        if (game.screen == null) {
            LOGGER.error("[manual-shot] no screen open at the screenshot tick ({})", name);
            return;
        }
        String state = "";
        if (game.screen instanceof vazkii.patchouli.client.book.gui.GuiBook guiBook) {
            state = " ticksInBook=" + guiBook.ticksInBook;
        }
        LOGGER.info("[manual-shot] screenshot with screen={}{}", game.screen.getClass().getName(), state);
        NativeImage image = Screenshot.takeScreenshot(game.getMainRenderTarget());
        File target = new File(directory, name);
        try {
            image.writeToFile(target);
            LOGGER.info("[manual-shot] screenshot written: {} ({}x{})", target.getAbsolutePath(),
                    image.getWidth(), image.getHeight());
        } catch (IOException failure) {
            LOGGER.error("[manual-shot] screenshot failed: {}", failure.toString());
        } finally {
            image.close();
        }
    }
}
