package fiedler.gametest;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.gametest.framework.GameTestHelper;
import org.jspecify.annotations.Nullable;

/**
 * The bridge between the common GameTest registry and the client-only test code in {@code fiedler.client}.
 *
 * <p>Client tests drive the real local player: they use items, right-click entities and type commands, all through the
 * client→server packet path. Their test <em>functions</em> are registered on both sides, so the
 * {@code test_function} registry is identical everywhere. Each function delegates here, and only the client installs a
 * runner. Their test <em>instances</em> are registered only by the client, so dedicated servers never schedule them.
 */
public final class ClientTests {
    /** Client test name → max ticks. Each test gets its own environment, so the tests run one after another. */
    public static final Map<String, Integer> TESTS = new LinkedHashMap<>();

    static {
        TESTS.put("client_horn_alarm", 300);
        TESTS.put("client_cat_cushion", 900);
        TESTS.put("client_brush_moss", 400);
        TESTS.put("client_graph_command", 200);
        TESTS.put("client_enchanting_glyphs", 200);
        TESTS.put("client_gui_enchanting", 600);
        TESTS.put("client_gui_crafting", 600);
        TESTS.put("client_gui_chat", 200);
    }

    private static volatile @Nullable BiConsumer<String, GameTestHelper> runner;

    private ClientTests() {}

    /** Called by the client entry point. */
    public static void install(BiConsumer<String, GameTestHelper> clientRunner) {
        runner = clientRunner;
    }

    static void run(String name, GameTestHelper helper) {
        BiConsumer<String, GameTestHelper> r = runner;
        if (r == null) {
            helper.fail("client test " + name + " needs a game client");
        } else {
            r.accept(name, helper);
        }
    }
}
