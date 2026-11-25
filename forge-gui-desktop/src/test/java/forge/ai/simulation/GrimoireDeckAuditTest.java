package forge.ai.simulation;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import static org.testng.Assert.*;

import forge.ai.PlayerControllerAi;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.view.GrimoireAiConfig;

/**
 * Cartas dos decks do usuário que ficaram presas na mão em partidas IA x IA (Seam Rip, Shardmage's Rescue).
 * Seam Rip já era jogado quando há alvo. Shardmage's Rescue nunca respondia a remoção: a lógica de aura com lampejo
 * só considerava a resistência extra, não a resistência a magia que faz a remoção perder o alvo.
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
        final String r = decide("seam-rip-vs-" + target.replace(' ', '-') + "-" + phase, bounded, "Seam Rip");
        assertEquals(r, "Seam Rip");
        assertFalse(names(opp.getCardsIn(ZoneType.Battlefield)).contains(target), target + " exilado");
        return r;
    }

    @Test public void seamRipGuideMain1H() { seamRip(false, "Goblin Guide", PhaseType.MAIN1); }
    @Test public void seamRipGuideMain1B() { seamRip(true, "Goblin Guide", PhaseType.MAIN1); }
    @Test public void seamRipSwiftspearMain2H() { seamRip(false, "Monastery Swiftspear", PhaseType.MAIN2); }
    @Test public void seamRipSwiftspearMain2B() { seamRip(true, "Monastery Swiftspear", PhaseType.MAIN2); }

    // ------------------------------------------------------------------ Shardmage's Rescue ({W}, lampejo, +1/+1, resistência a magia no turno)

    /** Remoção do oponente mirando nossa criatura: o Rescue entra com resistência a magia e a mágica perde o alvo. */
    private void rescueVsRemoval(final boolean bounded, final String creature, final String removal, final boolean expectCast) {
        mode(bounded);
        plains(1);
        final Card mine = bf(creature, true);
        put("Shardmage's Rescue", ai, ZoneType.Hand);
        at(PhaseType.MAIN1, opp);
        final SpellAbility spell = put(removal, opp, ZoneType.Hand).getFirstSpellAbility();
        spell.setActivatingPlayer(opp);
        spell.getTargets().add(mine);
        game.getStack().add(spell);
        final String scenario = "rescue-vs-" + removal.replace(' ', '-') + "-on-" + creature.replace(' ', '-');
        final String r = decide(scenario, bounded, "Shardmage's Rescue");
        while (!game.getStack().isEmpty()) game.getStack().resolveStack();
        final boolean survives = names(ai.getCardsIn(ZoneType.Battlefield)).contains(creature);
        System.out.println("DECKAUDIT " + scenario + " [" + (bounded ? "bounded" : "heuristic") + "] survives=" + survives);
        if (expectCast) {
            assertEquals(r, "Shardmage's Rescue -> [" + creature + "]");
            assertTrue(survives, creature + " sobrevive");
        } else {
            assertEquals(r, "pass", "a remoção não mata: guarda o Rescue");
        }
    }

    @Test public void rescueVsBoltH() { rescueVsRemoval(false, "Optimistic Scavenger", "Lightning Bolt", true); }
    @Test public void rescueVsBoltB() { rescueVsRemoval(true, "Optimistic Scavenger", "Lightning Bolt", true); }
    @Test public void rescueVsDoomBladeH() { rescueVsRemoval(false, "Veteran Survivor", "Doom Blade", true); }
    @Test public void rescueVsShockNotLethalH() { rescueVsRemoval(false, "Hill Giant", "Shock", false); }
    @Test public void rescueVsShockNotLethalB() { rescueVsRemoval(true, "Hill Giant", "Shock", false); }
}
