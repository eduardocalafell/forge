package forge.ai.simulation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import static org.testng.Assert.*;
import forge.ai.ComputerUtilCombat;
import forge.ai.PrismaticStrandsAi;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/** Audit regressions: expected failures document defects in db53339; never a production patch. */
public class GrimoireAiAuditTest extends FlatMcTestBase {
    private final List<Map<String, Object>> observations = new ArrayList<>();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    @BeforeMethod public void setup() { setUpGame(); System.clearProperty("grimoire.ai.prismaticStrands"); }
    private Card creature(String name, boolean mine) { Card c = put(name, mine ? ai : opp, ZoneType.Battlefield); c.setSickness(false); return c; }
    private SpellAbility strands() { SpellAbility s = put("Prismatic Strands", ai, ZoneType.Hand).getFirstSpellAbility(); s.setActivatingPlayer(ai); return s; }
    private Combat combat(boolean attacking) { at(PhaseType.COMBAT_DECLARE_BLOCKERS, attacking ? ai : opp); return game.getCombat(); }
    private void record(String scenario, Map<String, Object> expected, Map<String, Object> observed) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("scenario", scenario); row.put("expected", expected); row.put("observed", observed); row.put("matchesExpectation", expected.equals(observed)); observations.add(row);
        assertEquals(observed, expected, scenario);
    }
    @AfterClass(alwaysRun = true) public void report() throws Exception {
        Path p = Path.of(System.getProperty("grimoire.audit.report", "/tmp/grimoire-ai-audit.json"));
        Files.createDirectories(p.getParent()); Files.writeString(p, GSON.toJson(Map.of("engineCommit", "db53339b7493547eee5c94f2c2865feb5bf24b6a", "cases", observations)));
    }
    @Test public void countsOneCreatureSavedOnceAcrossSameColorBlockers() {
        Combat c = combat(true); Card attacker = creature("Craw Wurm", true);
        c.addAttacker(attacker, opp); c.addBlocker(attacker, creature("Hill Giant", false)); c.addBlocker(attacker, creature("Hill Giant", false));
        var p = PrismaticStrandsAi.evaluate(ai, strands());
        record("same-color-double-block", Map.of("redCreaturesSaved", 1), Map.of("redCreaturesSaved", p.byColor.get("red").creaturesSaved));
    }
    @Test public void doesNotSaveCreatureWhenAnotherColorStillDealsLethal() {
        Combat c = combat(true); Card attacker = creature("Craw Wurm", true);
        c.addAttacker(attacker, opp); c.addBlocker(attacker, creature("Craw Wurm", false)); c.addBlocker(attacker, creature("Hill Giant", false));
        var p = PrismaticStrandsAi.evaluate(ai, strands());
        Map<String,Object> observed = new LinkedHashMap<>(); observed.put("redCreaturesSaved", p.byColor.get("red").creaturesSaved); observed.put("castsRed", p.cast && "red".equals(p.color));
        Map<String,Object> expected = Map.of("redCreaturesSaved", 0, "castsRed", false);
        System.out.println("AUDIT mixed-color choice=" + p.color + " cast=" + p.cast + " reason=" + p.reason);
        record("mixed-color-double-block", expected, observed);
    }
    @Test public void preventsLethalTrampleThroughABlocker() {
        ai.setLife(3, null); Combat c = combat(false); Card attacker = creature("Colossal Dreadmaw", false);
        c.addAttacker(attacker, ai); c.addBlocker(attacker, creature("Ornithopter", true));
        assertTrue(ComputerUtilCombat.lifeThatWouldRemain(ai, c) < 1, "Fixture must be lethal according to Forge");
        var p = PrismaticStrandsAi.evaluate(ai, strands());
        record("defensive-lethal-trample", Map.of("cast", true, "greenPreventsLethal", true), Map.of("cast", p.cast, "greenPreventsLethal", p.byColor.get("green").preventsLethal));
    }
    @Test public void preservesItsOwnLethalTrampleAttack() {
        opp.setLife(4, null); Combat c = combat(true); Card trampler = creature("Colossal Dreadmaw", true), giant = creature("Shivan Dragon", true);
        c.addAttacker(trampler, opp); c.addBlocker(trampler, creature("Goblin Piker", false));
        c.addAttacker(giant, opp); c.addBlocker(giant, creature("Thornweald Archer", false));
        assertTrue(ComputerUtilCombat.lifeThatWouldRemain(opp, c) < 1, "Fixture must win according to Forge");
        var p = PrismaticStrandsAi.evaluate(ai, strands());
        System.out.println("AUDIT own-lethal-trample choice=" + p.color + " cast=" + p.cast + " reason=" + p.reason);
        record("own-lethal-trample", Map.of("cast", false, "greenCancelsOwnLethal", true), Map.of("cast", p.cast, "greenCancelsOwnLethal", p.byColor.get("green").cancelsOwnLethal));
    }
    @Test public void preventsLethalInfectDamage() {
        ai.setPoisonCounters(9, opp); Combat c = combat(false); c.addAttacker(creature("Glistener Elf", false), ai);
        assertTrue(ai.getPoisonCounters() + ComputerUtilCombat.resultingPoison(ai, c) >= 10, "Fixture must be poison lethal");
        var p = PrismaticStrandsAi.evaluate(ai, strands());
        record("lethal-infect", Map.of("cast", true, "color", "green"), Map.of("cast", p.cast, "color", p.color));
    }
    @Test public void choosesTheThreatColorDuringActualStackResolution() {
        ai.setLife(3, null); at(PhaseType.MAIN1, opp); creature("Craw Wurm", false);
        SpellAbility bolt = put("Lightning Bolt", opp, ZoneType.Hand).getFirstSpellAbility(); bolt.setActivatingPlayer(opp); bolt.getTargets().add(ai); game.getStack().add(bolt);
        SpellAbility protection = strands(); var before = PrismaticStrandsAi.evaluate(ai, protection);
        assertTrue(before.cast); assertEquals(before.color, "red");
        game.getStack().add(protection); game.getStack().resolveStack();
        List<String> chosen = new ArrayList<>(); protection.getHostCard().getChosenColors().forEach(chosen::add);
        game.getStack().resolveStack();
        record("color-at-real-resolution", Map.of("chosenColors", List.of("red"), "lifeAfterBolt", 3), Map.of("chosenColors", chosen, "lifeAfterBolt", ai.getLife()));
    }
    @Test public void doesNotCountFirstStrikeDamageAgain() {
        ai.setLife(2, null); at(PhaseType.COMBAT_FIRST_STRIKE_DAMAGE, opp);
        Card attacker = creature("Fencing Ace", false); game.getCombat().addAttacker(attacker, ai);
        var p = PrismaticStrandsAi.evaluate(ai, strands());
        record("double-strike-second-hit", Map.of("cast", false, "whitePreventsLethal", false), Map.of("cast", p.cast, "whitePreventsLethal", p.byColor.get("white").preventsLethal));
    }
    @Test public void controlWrongColorOpeningHandIsAlreadyRejected() {
        CardCollection hand = collection(put("Plains", ai, ZoneType.Hand), put("Plains", ai, ZoneType.Hand), put("Llanowar Elves", ai, ZoneType.Hand));
        record("control-wrong-color-hand", Map.of("castsSomething", false), Map.of("castsSomething", OpeningHand.castsSomething(hand, ai)));
    }
    @Test public void controlHybridManaCanUseOnlyWhiteSources() {
        CardCollection hand = collection(put("Plains", ai, ZoneType.Hand), put("Plains", ai, ZoneType.Hand), put("Skyward Spider", ai, ZoneType.Hand));
        record("control-hybrid-white-hand", Map.of("castsSomething", true), Map.of("castsSomething", OpeningHand.castsSomething(hand, ai)));
    }
    @Test public void controlOpeningHandWithoutCreatureForItsAurasIsRejected() {
        put("Plains", 20, ai, ZoneType.Library); put("Savannah Lions", 20, ai, ZoneType.Library);
        CardCollection hand = collection(put("Plains", ai, ZoneType.Hand), put("Plains", ai, ZoneType.Hand), put("Plains", ai, ZoneType.Hand),
                put("Ethereal Armor", ai, ZoneType.Hand), put("Hyena Umbra", ai, ZoneType.Hand), put("Feather of Flight", ai, ZoneType.Hand), put("Sentinel's Eyes", ai, ZoneType.Hand));
        record("control-auras-without-body", Map.of("keeps", false), Map.of("keeps", OpeningHand.keep(ai, hand, 0, true, DeckPlan.of(DeckPlan.Archetype.AGGRO), 5)));
    }
}
