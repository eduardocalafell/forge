package forge.view.duel;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.testng.annotations.Test;
import org.testng.annotations.BeforeMethod;
import forge.ai.simulation.FlatMcTestBase;
import forge.gui.GuiBase;
import forge.gui.util.SGuiChoose;
import static org.testng.Assert.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Exercita perguntas reais e respostas pela mesma sessão usada no HTTP, sem janela Swing. */
public class DuelBridgeTest extends FlatMcTestBase {
    @BeforeMethod
    public void setup() { setUpGame(); }
    private static JsonObject json(final String value) { return JsonParser.parseString(value).getAsJsonObject(); }

    private <T> T answer(final DuelSession session, final Callable<T> question, final String answer,
                               final java.util.function.Consumer<JsonObject> check) throws Exception {
        session.attach(game, ai, null, "human");
        session.setStatus("running", "teste");
        final CompletableFuture<T> task = CompletableFuture.supplyAsync(() -> {
            try { return question.call(); } catch (Exception e) { throw new RuntimeException(e); }
        });
        try {
            JsonObject decision = null;
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                if (task.isCompletedExceptionally()) task.get();
                final JsonObject state = session.snapshot();
                if (state.has("decision") && state.get("decision").isJsonObject()) { decision = state.getAsJsonObject("decision"); break; }
                Thread.sleep(10);
            }
            assertNotNull(decision, "A pergunta precisa chegar à sessão web");
            check.accept(decision);
            final JsonObject cmd = json("{\"type\":\"answer\"}");
            cmd.addProperty("commandId", UUID.randomUUID().toString());
            cmd.addProperty("decisionId", decision.get("id").getAsString());
            cmd.add("answer", json(answer));
            assertTrue(session.command(cmd).get("ok").getAsBoolean());
            return task.get(5, TimeUnit.SECONDS);
        } finally {
            if (!task.isDone()) session.fail("test_cleanup", "fim do teste");
        }
    }

    @Test
    public void ordersPrepopulatedDestinationThroughWebDecision() throws Exception {
        final DuelSession s = new DuelSession("test", "g");
        final WebGuiGame gui = new WebGuiGame(s);
        final List<String> selected = answer(s,
                () -> gui.order("Ordenar", "Topo", 0, 0, List.of("A", "B"), List.of("C"), null, false, false).ordered(),
                "{\"indices\":[2,1,0]}", d -> {
                    assertEquals(d.get("kind").getAsString(), "order");
                    assertEquals(d.getAsJsonArray("options").size(), 3);
                    assertEquals(d.getAsJsonArray("initialOrder").toString(), "[2]");
                });
        assertEquals(selected, List.of("C", "B", "A"));
    }

    @Test
    public void directDesktopChoicesAreRoutedToTheBrowser() throws Exception {
        final DuelSession s = new DuelSession("test", "g");
        final DuelGuiDesktop desktop = new DuelGuiDesktop(new WebGuiGame(s));
        final var previous = GuiBase.getInterface();
        try {
            GuiBase.setInterface(desktop);
            final List<String> selected = answer(s,
                    () -> SGuiChoose.getChoices("Escolha", 1, 1, List.of("A", "B")),
                    "{\"indices\":[1]}", d -> assertEquals(d.get("kind").getAsString(), "choose"));
            assertEquals(selected, List.of("B"));
        } finally { GuiBase.setInterface(previous); }
    }

    @Test
    public void assignsDamageAcrossMultipleBlockersAndDefender() throws Exception {
        final var attacker = put("Craw Wurm", ai, forge.game.zone.ZoneType.Battlefield).getView();
        final var first = put("Hill Giant", opp, forge.game.zone.ZoneType.Battlefield).getView();
        final var second = put("Goblin Piker", opp, forge.game.zone.ZoneType.Battlefield).getView();
        final DuelSession session = new DuelSession("damage", "g");
        final WebGuiGame gui = new WebGuiGame(session);
        final var result = answer(session,
                () -> gui.assignCombatDamage(attacker, List.of(first, second), 6, opp.getView(), false, false),
                "{\"amounts\":[3,1,2]}", d -> {
                    assertEquals(d.get("kind").getAsString(), "damage");
                    assertEquals(d.getAsJsonArray("targets").size(), 3);
                    assertEquals(d.getAsJsonArray("targets").get(0).getAsJsonObject().get("lethal").getAsInt(), 3);
                    assertEquals(d.getAsJsonArray("targets").get(1).getAsJsonObject().get("lethal").getAsInt(), 1);
                    assertTrue(d.getAsJsonArray("targets").get(2).getAsJsonObject().get("defender").getAsBoolean());
                });
        assertEquals(result.get(first).intValue(), 3);
        assertEquals(result.get(second).intValue(), 1);
        assertEquals(result.get(null).intValue(), 2);
    }

    @Test
    public void rejectsFractionalDuplicateAndOutOfRangeIndices() {
        final Decision d = new Decision("d1", "order", json("{\"options\":[{},{},{}],\"remainingMin\":0,\"remainingMax\":0}"));
        assertNull(DecisionRules.validate(d, json("{\"indices\":[2,0,1]}")));
        assertNotNull(DecisionRules.validate(d, json("{\"indices\":[0,1,1]}")));
        assertNotNull(DecisionRules.validate(d, json("{\"indices\":[0,1,3]}")));
        assertNotNull(DecisionRules.validate(d, json("{\"indices\":[0,1,2.5]}")));
    }
}
