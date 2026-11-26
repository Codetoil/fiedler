package fiedler.client;

import fiedler.Fiedler;
import fiedler.connection.CatSitOnCushionGoal;
import fiedler.connection.CushionCats;
import fiedler.gametest.ClientTests;
import fiedler.gametest.FiedlerGameTests;
import fiedler.graph.FeatureGraph;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.decoration.Cushion;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Instrument;
import net.minecraft.world.item.InstrumentItem;
import net.minecraft.world.item.Instruments;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.EnchantingTableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import org.jspecify.annotations.Nullable;

/**
 * GameTests that drive the real game client. The test bodies run on the integrated server's thread like any GameTest,
 * but every player action goes through the client: the local player uses items, right-clicks entities, holds the
 * use key and types commands, and the packets travel client→server as in real play. Each test then checks both what
 * the server did and what the client shows.
 *
 * <p>The tests need a client-controlled player, so they are registered only on the client. Each runs in its own
 * environment, which puts it in its own batch, because they share that one player.
 */
public final class FiedlerClientGameTests {
    private FiedlerClientGameTests() {}

    public static void registerTests(RegisterGameTestsEvent event) {
        ClientTests.TESTS.forEach((name, maxTicks) -> {
            Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(Fiedler.id(name));
            event.registerTest(Fiedler.id(name), new FunctionGameTestInstance(
                    ResourceKey.create(Registries.TEST_FUNCTION, Fiedler.id(name)),
                    new TestData<>(environment, FiedlerGameTests.ARENA, maxTicks, 0, true)));
        });
    }

    public static void run(String name, GameTestHelper helper) {
        switch (name) {
            case "client_horn_alarm" -> hornAlarm(helper);
            case "client_cat_cushion" -> catCushion(helper);
            case "client_brush_moss" -> brushMoss(helper);
            case "client_graph_command" -> graphCommand(helper);
            case "client_enchanting_glyphs" -> enchantingGlyphs(helper);
            case "client_gui_enchanting" -> guiEnchanting(helper);
            case "client_gui_crafting" -> guiCrafting(helper);
            case "client_gui_chat" -> guiChat(helper);
            default -> helper.fail("unknown client test " + name);
        }
    }

    // ---- plumbing ---------------------------------------------------------------------------------------------------

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    /** Runs a player action on the client thread. */
    private static void onClient(Runnable action) {
        mc().execute(action);
    }

    /** A wait step that keeps asking the client thread until {@code probe} holds, without ever blocking the server. */
    private static Runnable untilClient(GameTestHelper helper, Supplier<Boolean> probe, String message) {
        AtomicReference<CompletableFuture<Boolean>> pending = new AtomicReference<>();
        return () -> {
            CompletableFuture<Boolean> answer = pending.get();
            if (answer != null && answer.isDone() && Boolean.TRUE.equals(answer.getNow(Boolean.FALSE))) return;
            if (answer == null || answer.isDone()) pending.set(CompletableFuture.supplyAsync(() -> safely(probe), mc()));
            helper.assertTrue(false, Component.literal(message));
        };
    }

    private static boolean safely(Supplier<Boolean> probe) {
        try {
            return Boolean.TRUE.equals(probe.get());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, Component.literal(message));
    }

    /** The player the game client controls, as the server sees it. Mock players from other tests are not this one. */
    private static ServerPlayer realPlayer(GameTestHelper helper) {
        LocalPlayer local = mc().player;
        ServerPlayer player = local == null ? null : helper.getLevel().getServer().getPlayerList().getPlayer(local.getUUID());
        if (player == null) {
            helper.fail("no client-controlled player is in the world");
            throw new IllegalStateException("unreachable");
        }
        return player;
    }

    /** Moves the real player to an arena-relative spot and empties their hand. The client follows via the usual packets. */
    private static void place(GameTestHelper helper, ServerPlayer player, Vec3 relativeFeet, float yaw, float pitch) {
        onClient(() -> mc().options.keyUse.setDown(false));
        player.stopRiding();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        Vec3 at = helper.absoluteVec(relativeFeet);
        player.teleportTo(helper.getLevel(), at.x, at.y, at.z, Set.of(), yaw, pitch, true);
        player.setDeltaMovement(Vec3.ZERO);
    }

    /** {yaw, pitch} that make an eye at {@code eye} look at {@code target}. */
    private static float[] aim(Vec3 eye, Vec3 target) {
        Vec3 d = target.subtract(eye);
        return new float[]{(float) -Math.toDegrees(Math.atan2(d.x, d.z)), (float) -Math.toDegrees(Math.atan2(d.y, d.horizontalDistance()))};
    }

    private static void rightClick(int entityId) {
        Entity target = mc().level.getEntity(entityId);
        if (target != null) mc().gameMode.interact(mc().player, target, new EntityHitResult(target), InteractionHand.MAIN_HAND);
    }

    /** Right-clicks a block from the client (the top face, like looking down at it). */
    private static void useBlock(BlockPos pos) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).add(0.0, 0.5, 0.0), Direction.UP, pos, false);
        mc().gameMode.useItemOn(mc().player, InteractionHand.MAIN_HAND, hit);
    }

    /** Presses and releases the left mouse button at screen coordinates, the way the mouse handler feeds a screen. */
    private static void click(Screen screen, double x, double y, int modifiers) {
        MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(InputConstants.MOUSE_BUTTON_LEFT, modifiers));
        screen.mouseClicked(event, false);
        screen.mouseReleased(event);
    }

    /** Clicks the centre of a slot as drawn on screen; the screen itself works out which slot is under the cursor. */
    private static void clickSlot(AbstractContainerScreen<?> screen, Slot slot, boolean shift) {
        click(screen, screen.leftPos + slot.x + 8, screen.topPos + slot.y + 8, shift ? InputConstants.MOD_SHIFT : 0);
    }

    /** Picks a stack up from the player's inventory part of the menu and drops it into {@code target}. */
    private static void moveIntoSlot(AbstractContainerScreen<?> screen, Item item, Slot target) {
        Slot from = inventorySlotWith(screen.getMenu(), item);
        if (from == null) throw new IllegalStateException("no " + item + " in the inventory part of the menu");
        clickSlot(screen, from, false);
        clickSlot(screen, target, false);
    }

    private static @Nullable Slot inventorySlotWith(AbstractContainerMenu menu, Item item) {
        for (Slot slot : menu.slots) {
            if (slot.container instanceof Inventory && slot.getItem().is(item)) return slot;
        }
        return null;
    }

    /** Puts stacks into the real player's main inventory (not the hotbar, so the hand stays empty for right-clicks). */
    private static void stock(ServerPlayer player, ItemStack... stacks) {
        Inventory inventory = player.getInventory();
        inventory.clearContent();
        for (int i = 0; i < stacks.length; i++) inventory.setItem(9 + i, stacks[i]);
    }

    // ---- tests ------------------------------------------------------------------------------------------------------

    /** The client blows a Call horn: the server's villager takes cover, and the client sees the raider glow. */
    private static void hornAlarm(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Villager villager = helper.spawn(EntityTypes.VILLAGER, new BlockPos(1, 1, 1));
        Pillager pillager = helper.spawn(EntityTypes.PILLAGER, new BlockPos(7, 1, 7));
        pillager.setNoAi(true);
        int pillagerId = pillager.getId();
        ServerPlayer player = realPlayer(helper);
        place(helper, player, new Vec3(4.5, 1.0, 4.5), 0.0F, 0.0F);
        Holder<Instrument> call = level.registryAccess().lookupOrThrow(Registries.INSTRUMENT).getOrThrow(Instruments.CALL_GOAT_HORN);
        player.setItemInHand(InteractionHand.MAIN_HAND, InstrumentItem.create(Items.GOAT_HORN, call));

        helper.startSequence()
                .thenWaitUntil(untilClient(helper, () -> mc().player.getMainHandItem().is(Items.GOAT_HORN), "the client never received the goat horn"))
                .thenExecute(() -> onClient(() -> mc().gameMode.useItem(mc().player, InteractionHand.MAIN_HAND)))
                .thenWaitUntil(() -> {
                    check(helper, villager.getBrain().getMemory(MemoryModuleType.HEARD_BELL_TIME).isPresent(), "the villager never heard the horn the client blew");
                    check(helper, pillager.hasEffect(MobEffects.GLOWING), "the raider does not glow on the server");
                })
                .thenWaitUntil(untilClient(helper, () -> {
                    Entity seen = mc().level.getEntity(pillagerId);
                    return seen != null && seen.isCurrentlyGlowing();
                }, "the client never showed the raider glowing"))
                .thenExecute(() -> {
                    onClient(() -> mc().gameMode.releaseUsingItem(mc().player));
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                })
                .thenSucceed();
    }

    /**
     * The client sees the tame cat sit on the cushion. One right-click from the client shoos it (without seating the
     * player, and the cat stays off), and a second right-click seats the player.
     */
    private static void catCushion(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = realPlayer(helper);
        place(helper, player, new Vec3(5.5, 1.0, 2.5), 0.0F, 20.0F);
        Cushion cushion = EntityTypes.CUSHION.create(level, EntitySpawnReason.TRIGGERED);
        check(helper, cushion != null, "could not create a cushion");
        BlockPos seat = helper.absolutePos(new BlockPos(5, 1, 4));
        cushion.snapTo(Vec3.atCenterOfWithY(seat, seat.getY()), 0.0F, 0.0F);
        level.addFreshEntity(cushion);
        Cat cat = helper.spawn(EntityTypes.CAT, new BlockPos(2, 1, 4));
        cat.tame(player);
        CatSitOnCushionGoal goal = CushionCats.goalOf(cat);
        check(helper, goal != null, "cats should get the cushion goal when they join the level");
        goal.searchNow();
        int catId = cat.getId(), cushionId = cushion.getId();

        helper.startSequence()
                .thenWaitUntil(() -> check(helper, cat.getVehicle() == cushion, "the cat has not sat on the cushion"))
                .thenWaitUntil(untilClient(helper, () -> mc().level.getEntity(catId) instanceof Cat seen
                        && seen.getVehicle() != null && seen.getVehicle().getId() == cushionId && seen.isInSittingPose(),
                        "the client never showed the cat sitting on the cushion"))
                .thenExecute(() -> onClient(() -> rightClick(cushionId)))
                .thenWaitUntil(() -> {
                    check(helper, !cat.isPassenger(), "the client's right-click did not shoo the cat");
                    check(helper, !player.isPassenger(), "the first right-click should only shoo the cat, not seat the player");
                })
                .thenWaitUntil(untilClient(helper, () -> mc().level.getEntity(catId) instanceof Cat seen && !seen.isPassenger(),
                        "the client still shows the cat on the cushion"))
                .thenExecuteAfter(40, () -> check(helper, !cat.isPassenger(), "the shooed cat climbed back onto the cushion"))
                .thenExecute(() -> onClient(() -> rightClick(cushionId)))
                .thenWaitUntil(() -> check(helper, player.getVehicle() == cushion, "the second right-click should seat the player"))
                .thenWaitUntil(untilClient(helper, () -> mc().player.getVehicle() != null && mc().player.getVehicle().getId() == cushionId,
                        "the client does not show the player seated"))
                .thenExecute(player::stopRiding)
                .thenSucceed();
    }

    /** The client looks at a mossy block and holds right-click with a brush, like a player; the moss comes off. */
    private static void brushMoss(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos rel = new BlockPos(4, 1, 6);
        helper.setBlock(rel, Blocks.MOSSY_COBBLESTONE);
        BlockPos abs = helper.absolutePos(rel);
        ServerPlayer player = realPlayer(helper);
        Vec3 feet = new Vec3(4.5, 1.0, 4.5);
        float[] angles = aim(helper.absoluteVec(feet).add(0.0, player.getEyeHeight(), 0.0), helper.absoluteVec(new Vec3(4.5, 1.5, 6.5)));
        place(helper, player, feet, angles[0], angles[1]);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BRUSH));

        helper.startSequence()
                .thenWaitUntil(untilClient(helper, () -> mc().player.getMainHandItem().is(Items.BRUSH)
                        && mc().hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(abs),
                        "the client's crosshair never settled on the mossy block"))
                .thenExecute(() -> onClient(() -> mc().options.keyUse.setDown(true)))
                .thenWaitUntil(() -> check(helper, level.getBlockState(abs).is(Blocks.COBBLESTONE), "holding right-click with the brush has not scrubbed the block"))
                .thenExecute(() -> onClient(() -> mc().options.keyUse.setDown(false)))
                .thenWaitUntil(untilClient(helper, () -> mc().level.getBlockState(abs).is(Blocks.COBBLESTONE), "the client never showed the clean block"))
                .thenExecute(() -> {
                    helper.assertItemEntityPresent(Items.MOSS_CARPET, rel, 2.5);
                    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                })
                .thenSucceed();
    }

    /** The client types /fiedler graph and /fiedler connections and receives the reports in chat. */
    private static void graphCommand(GameTestHelper helper) {
        ClientChatLog.clear();
        int connections = FeatureGraph.load().fiedlerEdges().size();
        helper.startSequence()
                .thenExecute(() -> onClient(() -> mc().player.connection.sendCommand("fiedler graph")))
                .thenWaitUntil(() -> check(helper, ClientChatLog.snapshot().stream().anyMatch(m -> m.contains("with Fiedler:") && m.contains("β₀=1")),
                        "the client never received the /fiedler graph report"))
                .thenExecute(() -> onClient(() -> mc().player.connection.sendCommand("fiedler connections")))
                .thenWaitUntil(() -> check(helper, ClientChatLog.snapshot().stream().filter(m -> m.startsWith("• ")).count() == connections,
                        "the client should receive one line per connection (" + connections + ")"))
                .thenSucceed();
    }

    /** On the client, chiseled shelves with books feed the enchanting table (that is what draws the glyph particles). */
    private static void enchantingGlyphs(GameTestHelper helper) {
        BlockPos table = new BlockPos(4, 1, 4);
        helper.setBlock(table, Blocks.ENCHANTING_TABLE);
        List<BlockPos> booked = EnchantingTableBlock.BOOKSHELF_OFFSETS.subList(0, 15);
        BlockPos bare = EnchantingTableBlock.BOOKSHELF_OFFSETS.get(15);
        BlockState full = Blocks.CHISELED_BOOKSHELF.defaultBlockState();
        for (BooleanProperty slot : ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES) full = full.setValue(slot, true);
        BlockState fullShelf = full;
        booked.forEach(offset -> helper.setBlock(table.offset(offset), fullShelf));
        helper.setBlock(table.offset(bare), Blocks.CHISELED_BOOKSHELF.defaultBlockState());
        BlockPos absTable = helper.absolutePos(table);

        helper.startSequence()
                .thenWaitUntil(untilClient(helper, () -> booked.stream().allMatch(o -> EnchantingTableBlock.isValidBookShelf(mc().level, absTable, o))
                        && !EnchantingTableBlock.isValidBookShelf(mc().level, absTable, bare),
                        "on the client, shelves with books should feed the enchanting table and the empty shelf should not"))
                .thenSucceed();
    }

    // ---- GUI tests: real screens, real mouse and keyboard events -------------------------------------------------------

    /**
     * The player opens an enchanting table surrounded by 15 full chiseled bookshelves by right-clicking it from the
     * client. In the EnchantmentScreen they drag a pickaxe and lapis into the slots with mouse clicks, see the level-30
     * offer (synced to the client menu), and click the third offer button.
     */
    private static void guiEnchanting(GameTestHelper helper) {
        BlockPos table = new BlockPos(4, 1, 4);
        helper.setBlock(table, Blocks.ENCHANTING_TABLE);
        BlockState full = Blocks.CHISELED_BOOKSHELF.defaultBlockState();
        for (BooleanProperty slot : ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES) full = full.setValue(slot, true);
        BlockState fullShelf = full;
        EnchantingTableBlock.BOOKSHELF_OFFSETS.subList(0, 15).forEach(offset -> helper.setBlock(table.offset(offset), fullShelf));
        BlockPos absTable = helper.absolutePos(table);
        ServerPlayer player = realPlayer(helper);
        place(helper, player, new Vec3(4.5, 1.0, 0.5), 0.0F, 20.0F);
        stock(player, new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.LAPIS_LAZULI, 10));
        player.setExperienceLevels(30);

        helper.startSequence()
                .thenWaitUntil(untilClient(helper, () -> mc().player.experienceLevel == 30 && mc().player.getInventory().getItem(10).is(Items.LAPIS_LAZULI),
                        "the client never received the pickaxe, lapis and levels"))
                .thenExecute(() -> onClient(() -> useBlock(absTable)))
                .thenWaitUntil(untilClient(helper, () -> mc().gui.screen() instanceof EnchantmentScreen, "right-clicking the table did not open the enchanting screen"))
                .thenExecute(() -> onClient(() -> {
                    EnchantmentScreen screen = (EnchantmentScreen) mc().gui.screen();
                    moveIntoSlot(screen, Items.IRON_PICKAXE, screen.getMenu().getSlot(0));
                    moveIntoSlot(screen, Items.LAPIS_LAZULI, screen.getMenu().getSlot(1));
                }))
                .thenWaitUntil(untilClient(helper, () -> mc().player.containerMenu instanceof EnchantmentMenu menu
                        && menu.getSlot(0).getItem().is(Items.IRON_PICKAXE) && menu.getSlot(1).getItem().getCount() == 10 && menu.costs[2] == 30,
                        "the enchanting screen should show the pickaxe, 10 lapis and a level-30 top offer"))
                .thenExecute(() -> onClient(() -> {
                    EnchantmentScreen screen = (EnchantmentScreen) mc().gui.screen();
                    click(screen, screen.leftPos + 60 + 54, screen.topPos + 14 + 19 * 2 + 9, 0);    // third offer button
                }))
                .thenWaitUntil(() -> {
                    check(helper, player.containerMenu instanceof EnchantmentMenu, "the server closed the enchanting menu early");
                    EnchantmentMenu menu = (EnchantmentMenu) player.containerMenu;
                    check(helper, menu.getSlot(0).getItem().isEnchanted(), "clicking the offer did not enchant the pickaxe");
                    check(helper, menu.getSlot(1).getItem().getCount() == 7, "the third offer should cost 3 lapis");
                    check(helper, player.experienceLevel == 27, "the third offer should cost 3 levels, now at " + player.experienceLevel);
                })
                .thenWaitUntil(untilClient(helper, () -> mc().player.containerMenu.getSlot(0).getItem().isEnchanted(),
                        "the client screen never showed the enchanted pickaxe"))
                .thenExecute(() -> onClient(() -> mc().player.closeContainer()))
                .thenWaitUntil(() -> check(helper, player.containerMenu == player.inventoryMenu, "closing the screen should close the menu on the server"))
                .thenExecute(() -> player.getInventory().clearContent())
                .thenSucceed();
    }

    /**
     * The player opens a crafting table from the client and crafts both Fiedler recipes through the crafting screen:
     * they click the ingredients into the grid, see the result appear, and shift-click it into the inventory.
     */
    private static void guiCrafting(GameTestHelper helper) {
        BlockPos table = new BlockPos(4, 1, 6);
        helper.setBlock(table, Blocks.CRAFTING_TABLE);
        BlockPos absTable = helper.absolutePos(table);
        ServerPlayer player = realPlayer(helper);
        place(helper, player, new Vec3(4.5, 1.0, 4.5), 0.0F, 30.0F);
        stock(player, new ItemStack(Items.RESIN_CLUMP), new ItemStack(Items.PISTON),
                new ItemStack(Items.SULFUR_SPIKE), new ItemStack(Items.CHARCOAL), new ItemStack(Items.BONE_MEAL));

        helper.startSequence()
                .thenWaitUntil(untilClient(helper, () -> mc().player.getInventory().getItem(13).is(Items.BONE_MEAL), "the client never received the ingredients"))
                .thenExecute(() -> onClient(() -> useBlock(absTable)))
                .thenWaitUntil(untilClient(helper, () -> mc().gui.screen() instanceof CraftingScreen, "right-clicking the table did not open the crafting screen"))
                .thenExecute(() -> onClient(() -> {
                    CraftingScreen screen = (CraftingScreen) mc().gui.screen();
                    CraftingMenu menu = screen.getMenu();
                    moveIntoSlot(screen, Items.RESIN_CLUMP, menu.getSlot(1));     // grid: top-left
                    moveIntoSlot(screen, Items.PISTON, menu.getSlot(4));          // grid: middle-left
                }))
                .thenWaitUntil(untilClient(helper, () -> mc().player.containerMenu.getSlot(0).getItem().is(Items.STICKY_PISTON),
                        "the crafting screen never showed a sticky piston for resin over a piston"))
                .thenExecute(() -> onClient(() -> clickSlot((CraftingScreen) mc().gui.screen(), mc().player.containerMenu.getSlot(0), true)))
                .thenWaitUntil(() -> check(helper, player.getInventory().countItem(Items.STICKY_PISTON) == 1, "shift-clicking the result should put the sticky piston in the inventory"))
                .thenExecute(() -> onClient(() -> {
                    CraftingScreen screen = (CraftingScreen) mc().gui.screen();
                    CraftingMenu menu = screen.getMenu();
                    moveIntoSlot(screen, Items.SULFUR_SPIKE, menu.getSlot(1));
                    moveIntoSlot(screen, Items.CHARCOAL, menu.getSlot(2));
                    moveIntoSlot(screen, Items.BONE_MEAL, menu.getSlot(3));
                }))
                .thenWaitUntil(untilClient(helper, () -> mc().player.containerMenu.getSlot(0).getItem().is(Items.GUNPOWDER)
                        && mc().player.containerMenu.getSlot(0).getItem().getCount() == 2,
                        "the crafting screen never showed 2 gunpowder for sulfur, charcoal and bone meal"))
                .thenExecute(() -> onClient(() -> clickSlot((CraftingScreen) mc().gui.screen(), mc().player.containerMenu.getSlot(0), true)))
                .thenWaitUntil(() -> check(helper, player.getInventory().countItem(Items.GUNPOWDER) == 2, "shift-clicking the result should put 2 gunpowder in the inventory"))
                .thenExecute(() -> onClient(() -> mc().player.closeContainer()))
                .thenExecute(() -> player.getInventory().clearContent())
                .thenSucceed();
    }

    /** The player opens the chat box, types /fiedler graph and presses Enter; the report arrives and the box closes. */
    private static void guiChat(GameTestHelper helper) {
        ClientChatLog.clear();
        helper.startSequence()
                .thenExecute(() -> onClient(() -> {
                    ChatScreen chat = new ChatScreen("/fiedler graph", false);
                    mc().gui.setScreen(chat);
                    chat.keyPressed(new KeyEvent(InputConstants.KEY_RETURN, 0, 0));
                }))
                .thenWaitUntil(() -> check(helper, ClientChatLog.snapshot().stream().anyMatch(m -> m.contains("with Fiedler:") && m.contains("β₀=1")),
                        "typing /fiedler graph into the chat box and pressing Enter did not produce the report"))
                .thenWaitUntil(untilClient(helper, () -> mc().gui.screen() == null, "pressing Enter should close the chat box"))
                .thenSucceed();
    }
}
