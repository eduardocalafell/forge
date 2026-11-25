package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.PlayerControllerAi;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.view.GrimoireAiConfig;

/**
 * Cartas dos decks do usuário que ficaram presas na mão em partidas IA x IA (Seam Rip, Shardmage's Rescue).
 * Primeiro observa: cada cenário imprime o que a IA escolhe nos dois modos.
 */
public class GrimoireDeckAuditTest extends FlatMcTestBase {
    @BeforeMethod public void setup() { setUpGame(); }

    private void mode(final boolean bounded) {
        final JsonObject job = new JsonObject();
        job.addProperty("aiSearch", bounded ? "bounded" : "heuristic");
        new GrimoireAiConfig(job).apply(game);
    }

    private Card bf(final String name, final boolean mine) {
        final Card c = put(name, mine ? ai : opp, ZoneType.Battlefield);
        c.setSickness(false);
        return c;
    }

    private static List<String> names(final Iterable<Card> cards) {
        final List<String> out = new ArrayList<>();
        for (Card c : cards) out.add(c.getName());
        return out;
    }

    private void plains(final int n) { put("Plains", n, ai, ZoneType.Battlefield).forEach(c -> c.setSickness(false)); }

    private static String describe(final SpellAbility sa) {
        if (sa == null) return "pass";
        final List<String> t = new ArrayList<>();
        for (GameObject o : sa.getTargets()) t.add(o instanceof Card c ? c.getName() : String.valueOf(o));
        return sa.getHostCard().getName() + (t.isEmpty() ? "" : " -> " + t);
    }

    private String decide(final String scenario, final boolean bounded, final String card) {
        final StringBuilder why = new StringBuilder();
        for (Card c : ai.getCardsIn(ZoneType.Hand)) {
            if (!c.getName().equals(card)) continue;
            for (SpellAbility sa : c.getAllSpellAbilities()) {
                sa.setActivatingPlayer(ai);
                why.append(brain().canPlaySa(sa)).append(' ');
                sa.resetTargets();
            }
        }
        final List<SpellAbility> chosen = brain().chooseSpellAbilityToPlay();
        final SpellAbility first = chosen == null || chosen.isEmpty() ? null : chosen.get(0);
        String result = describe(first);
        if (first != null && first.getHostCard().getName().equals(card)) {
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(first);
            while (!game.getStack().isEmpty()) game.getStack().resolveStack();
        }
        System.out.println("DECKAUDIT " + scenario + " [" + (bounded ? "bounded" : "heuristic") + "] canPlaySa=" + why.toString().trim() + " choice=" + result);
        return result;
    }

    // ------------------------------------------------------------------ Seam Rip ({W}, exila permanente de VM <= 2)

    private String seamRip(final boolean bounded, final String target, final PhaseType phase) {
        mode(bounded);
        plains(2);
        bf("Skyward Spider", true);
        bf(target, false);
        put("Seam Rip", ai, ZoneType.Hand);
        at(phase, ai);
        return decide("seam-rip-vs-" + target.replace(' ', '-') + "-" + phase, bounded, "Seam Rip");
    }

    @Test public void seamRipGuideMain1H() { seamRip(false, "Goblin Guide", PhaseType.MAIN1); }
    @Test public void seamRipGuideMain1B() { seamRip(true, "Goblin Guide", PhaseType.MAIN1); }
    @Test public void seamRipSwiftspearMain2H() { seamRip(false, "Monastery Swiftspear", PhaseType.MAIN2); }
    @Test public void seamRipSwiftspearMain2B() { seamRip(true, "Monastery Swiftspear", PhaseType.MAIN2); }

    // ------------------------------------------------------------------ Shardmage's Rescue ({W}, lampejo, +1/+1, resistência a magia no turno)

    private String rescueMain(final boolean bounded) {
        mode(bounded);
        plains(1);
        bf("Skyward Spider", true);
        put("Shardmage's Rescue", ai, ZoneType.Hand);
        at(PhaseType.MAIN1, ai);
        return decide("rescue-own-main", bounded, "Shardmage's Rescue");
    }

    @Test public void rescueMainH() { rescueMain(false); }
    @Test public void rescueMainB() { rescueMain(true); }

    private String rescueVsBolt(final boolean bounded) {
        mode(bounded);
        plains(1);
        final Card spider = bf("Skyward Spider", true);
        put("Shardmage's Rescue", ai, ZoneType.Hand);
        at(PhaseType.MAIN1, opp);
        final SpellAbility bolt = put("Lightning Bolt", opp, ZoneType.Hand).getFirstSpellAbility();
        bolt.setActivatingPlayer(opp);
        bolt.getTargets().add(spider);
        game.getStack().add(bolt);
        final String r = decide("rescue-vs-bolt", bounded, "Shardmage's Rescue");
        while (!game.getStack().isEmpty()) game.getStack().resolveStack();
        System.out.println("DECKAUDIT rescue-vs-bolt [" + (bounded ? "bounded" : "heuristic") + "] spiderSurvives="
                + names(ai.getCardsIn(ZoneType.Battlefield)).contains("Skyward Spider"));
        return r;
    }

    @Test public void rescueVsBoltH() { rescueVsBolt(false); }
    @Test public void rescueVsBoltB() { rescueVsBolt(true); }
}
