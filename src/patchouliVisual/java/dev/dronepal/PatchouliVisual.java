package dev.dronepal;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import vazkii.patchouli.api.IVariable;
import vazkii.patchouli.client.book.BookCategory;
import vazkii.patchouli.client.book.BookContents;
import vazkii.patchouli.client.book.BookEntry;
import vazkii.patchouli.client.book.BookPage;
import vazkii.patchouli.client.book.gui.GuiBook;
import vazkii.patchouli.client.book.gui.GuiBookCategory;
import vazkii.patchouli.client.book.gui.GuiBookEntry;
import vazkii.patchouli.client.book.gui.GuiBookLanding;
import vazkii.patchouli.common.book.Book;
import vazkii.patchouli.common.book.BookRegistry;

/**
 * Manual (Patchouli book) acceptance harness. Runs only through
 * {@code gradle/patchouli-visual.init.gradle}; never part of a normal build.
 *
 * It loads the shipped book through Patchouli's own loader, asserts that every
 * category / entry / page resolves and that every language key produces text,
 * then screenshots the landing page, a category index and a Chinese entry page.
 */
@EventBusSubscriber(modid = "huhu_dronepal", value = Dist.CLIENT)
public final class PatchouliVisual {
    private static final ResourceLocation BOOK_ID = ResourceLocation.parse("huhu_dronepal:corvus_manual");
    private static final ResourceLocation ENTRY_ID = ResourceLocation.parse("huhu_dronepal:start/what");
    private static final String PREFIX = "dronepal.manual.";
    private static int phase;
    private static int waited;
    private static final List<String> badNames = new ArrayList<>();
    private static final List<String> badTexts = new ArrayList<>();
    private static final List<String> recipeFailures = new ArrayList<>();
    private static int pageCount;
    private static int craftPages;
    private static int textKeys;

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getOverlay() != null) return;
        if (mc.level == null || mc.player == null) {
            // quickPlaySingleplayer can refuse a copied world; fail loudly instead of hanging.
            if (mc.screen instanceof TitleScreen && ++waited > 1200) {
                fail("no singleplayer world: quickPlaySingleplayer inspection never reached a level");
            }
            return;
        }
        if (mc.level.getGameTime() < 20) return;
        switch (phase) {
            case 0 -> open(mc);
            case 1 -> { if (settled(mc, 6)) { shot(mc, "patchouli-landing-zh.png"); phase = 2; waited = 0; } }
            case 2 -> { if (settled(mc, 6)) { openCategory(mc); phase = 3; waited = 0; } }
            case 3 -> { if (settled(mc, 6)) { shot(mc, "patchouli-category-zh.png"); phase = 4; waited = 0; } }
            case 4 -> { if (settled(mc, 6)) { openEntry(mc); phase = 5; waited = 0; } }
            case 5 -> { if (settled(mc, 8)) { shot(mc, "patchouli-entry-zh.png"); report(mc); mc.execute(mc::stop); phase = 6; } }
            default -> { }
        }
    }

    private static boolean settled(Minecraft mc, int frames) {
        if (!(mc.screen instanceof GuiBook book)) return false;
        return book.ticksInBook >= frames;
    }

    private static Book readyBook(Minecraft mc) {
        Book book = BookRegistry.INSTANCE.books.get(BOOK_ID);
        if (book == null) fail("Patchouli did not register " + BOOK_ID + " (books=" + BookRegistry.INSTANCE.books.keySet() + ")");
        BookContents contents = book.getContents();
        if (contents == null || contents.entries.isEmpty()) {
            book.reloadContents(mc.level, false);
            contents = book.getContents();
        }
        if (contents == null) fail("book contents are null for " + BOOK_ID);
        if (contents.isErrored()) fail("book contents errored: " + contents.getException());
        return book;
    }

    private static void open(Minecraft mc) {
        Book book = readyBook(mc);
        BookContents contents = book.getContents();
        verify(book, contents);
        mc.setScreen(new GuiBookLanding(book));
        phase = 1;
        waited = 0;
    }

    private static void openCategory(Minecraft mc) {
        Book book = readyBook(mc);
        BookCategory category = book.getContents().categories.get(ResourceLocation.parse("huhu_dronepal:start"));
        if (category == null) fail("category huhu_dronepal:start missing");
        mc.setScreen(new GuiBookCategory(book, category));
    }

    private static void openEntry(Minecraft mc) {
        Book book = readyBook(mc);
        BookEntry entry = book.getContents().entries.get(ENTRY_ID);
        if (entry == null) fail("entry " + ENTRY_ID + " missing");
        mc.setScreen(new GuiBookEntry(book, entry, 0));
    }

    private static void verify(Book book, BookContents contents) {
        String language = Minecraft.getInstance().getLanguageManager().getSelected();
        if (!"zh_cn".equals(language)) badNames.add("client language is not zh_cn: " + language);
        String title = book.getBookItem().getHoverName().getString();
        if (title.startsWith(PREFIX) || !title.equals(I18n.get(PREFIX + "name"))) badNames.add("book title: " + title);
        if (contents.categories.size() != 7) badNames.add("category count " + contents.categories.size());
        if (contents.entries.size() != 16) badNames.add("entry count " + contents.entries.size());
        for (BookCategory category : contents.categories.values()) {
            String name = category.getName().getString();
            if (name.startsWith(PREFIX)) badNames.add("category name unresolved: " + name);
        }
        for (BookEntry entry : contents.entries.values()) {
            String name = entry.getName().getString();
            if (name.startsWith(PREFIX)) badNames.add("entry name unresolved: " + entry.getId() + " -> " + name);
            List<BookPage> pages = entry.getPages();
            pageCount += pages.size();
            int pageIndex = 0;
            for (BookPage page : pages) {
                pageIndex++;
                String key = stringField(page, IVariable.class, "text");
                if (key != null && !key.isEmpty()) {
                    textKeys++;
                    checkKey(key, entry.getId() + " text");
                }
                String titleKey = stringField(page, String.class, "title");
                if (titleKey != null && !titleKey.isEmpty()) checkKey(titleKey, entry.getId() + " title");
                if (page.getClass().getSimpleName().contains("Crafting")) {
                    craftPages++;
                    Object one = field(page, "recipe1");
                    Object two = field(page, "recipe2");
                    Object twoId = field(page, "recipe2Id");
                    if (one == null) recipeFailures.add(entry.getId() + " page " + pageIndex + ": recipe missing");
                    if (twoId != null && two == null) recipeFailures.add(entry.getId() + " page " + pageIndex + ": recipe2 missing");
                }
            }
        }
        if (!recipeFailures.isEmpty()) fail("recipe pages failed: " + recipeFailures);
        if (!badNames.isEmpty()) fail("unresolved names: " + badNames);
        if (!badTexts.isEmpty()) fail("unresolved text keys: " + badTexts);
        System.out.println("DRONEPAL_MANUAL_OK categories=" + contents.categories.size() + " entries=" + contents.entries.size()
                + " pages=" + pageCount + " textKeys=" + textKeys + " craftPages=" + craftPages + " title=" + title);
    }

    private static void checkKey(String key, String where) {
        if (!I18n.exists(key)) { badTexts.add(where + " missing " + key); return; }
        String rendered = I18n.get(key, new Object[0]);
        if (rendered.equals(key) || rendered.startsWith("Format error:")) badTexts.add(where + " -> " + rendered);
    }

    private static String stringField(BookPage page, Class<?> type, String name) {
        Object value = field(page, name);
        return value instanceof String s ? s : value instanceof IVariable v ? v.asString() : null;
    }

    private static Object field(Object instance, String name) {
        for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(instance);
            } catch (NoSuchFieldException ignored) {
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException(error);
            }
        }
        return null;
    }

    private static void shot(Minecraft mc, String name) {
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            Path dir = Path.of(System.getProperty("dronepal.patchouli.dir", "artifacts/client"));
            Files.createDirectories(dir);
            image.writeToFile(dir.resolve(name));
            System.out.println("DRONEPAL_MANUAL_CAPTURE " + dir.resolve(name));
        } catch (Exception error) {
            fail("screenshot " + name + " failed: " + error);
        }
    }

    private static void report(Minecraft mc) {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("book", BOOK_ID.toString());
            json.addProperty("language", I18n.get("language.name"));
            json.addProperty("title", BookRegistry.INSTANCE.books.get(BOOK_ID).getBookItem().getHoverName().getString());
            json.addProperty("categories", BookRegistry.INSTANCE.books.get(BOOK_ID).getContents().categories.size());
            json.addProperty("entries", BookRegistry.INSTANCE.books.get(BOOK_ID).getContents().entries.size());
            json.addProperty("pages", pageCount);
            json.addProperty("textKeys", textKeys);
            json.addProperty("craftPages", craftPages);
            JsonArray bad = new JsonArray();
            badNames.forEach(bad::add);
            badTexts.forEach(bad::add);
            recipeFailures.forEach(bad::add);
            json.add("problems", bad);
            Path path = Path.of(System.getProperty("dronepal.patchouli.report", "artifacts/client/patchouli-report.json"));
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(json) + "\n");
            System.out.println("DRONEPAL_MANUAL_REPORT " + path);
        } catch (Exception error) {
            fail("report failed: " + error);
        }
    }

    private static void fail(String message) {
        System.out.println("DRONEPAL_MANUAL_FAIL " + message);
        Minecraft.getInstance().execute(() -> {
            throw new AssertionError(message);
        });
    }

    private PatchouliVisual() {}
}
