package forge.ai.simulation;

import static org.testng.Assert.*;

import java.util.concurrent.TimeUnit;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.google.gson.JsonObject;
import forge.ai.PrismaticStrandsAi;
import forge.game.card.Card;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.view.GrimoireAiConfig;

public class GrimoireAiV2Test extends FlatMcTestBase {
    @BeforeMethod public void setup() { setUpGame(); System.clearProperty("grimoire.ai.prismaticStrands"); }
    @AfterMethod public void clear() { System.clearProperty("grimoire.ai.prismaticStrands"); }

    private SpellAbility strands() {
        final SpellAbility sa = put("Prismatic Strands", ai, ZoneType.Hand).getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    private Card attacker(final String name) {
        final Card card = put(name, opp, ZoneType.Battlefield);
        card.setSickness(false);
        game.getCombat().addAttacker(card, ai);
        return card;
    }

    @Test public void preservesV1ForComparisons() {
        at(PhaseType.COMBAT_DECLARE_BLOCKERS, opp);
        ai.setLife(3, null);
        final Card atk = attacker("Colossal Dreadmaw");
        game.getCombat().addBlocker(atk, put("Ornithopter", ai, ZoneType.Battlefield));
        final SpellAbility sa = strands();
        System.setProperty("grimoire.ai.prismaticStrands", "v1");
        assertFalse(PrismaticStrandsAi.evaluate(ai, sa).cast);
        assertEquals(PrismaticStrandsAi.version(), "prismatic-strands/v1");
        System.clearProperty("grimoire.ai.prismaticStrands");
        assertTrue(PrismaticStrandsAi.evaluate(ai, sa).cast);
    }

    @Test public void preventsLethalToxicAndHonorsCounterImmunity() {
        at(PhaseType.COMBAT_DECLARE_BLOCKERS, opp);
        ai.setPoisonCounters(9, opp);
        attacker("Crawling Chorus");
        final SpellAbility sa = strands();
        assertTrue(PrismaticStrandsAi.evaluate(ai, sa).byColor.get("white").preventsLethal);
        put("Melira, Sylvok Outcast", ai, ZoneType.Battlefield);
        assertFalse(PrismaticStrandsAi.evaluate(ai, sa).byColor.get("white").preventsLethal);
    }

    @Test public void firstStrikeOnlyHasNoDamageLeft() {
        at(PhaseType.COMBAT_FIRST_STRIKE_DAMAGE, opp);
        ai.setLife(1, null);
        attacker("White Knight");
        assertFalse(PrismaticStrandsAi.evaluate(ai, strands()).cast);
    }

    @Test public void doubleStrikeBeforeDamageHasBothHits() {
        at(PhaseType.COMBAT_DECLARE_BLOCKERS, opp);
        ai.setLife(2, null);
        attacker("Fencing Ace");
        assertTrue(PrismaticStrandsAi.evaluate(ai, strands()).byColor.get("white").preventsLethal);
    }

    @Test public void resolutionDoesNotUseEnemyBoardMajority() {
        at(PhaseType.MAIN1, opp);
        ai.setLife(3, null);
        final SpellAbility bolt = put("Lightning Bolt", opp, ZoneType.Hand).getFirstSpellAbility();
        bolt.setActivatingPlayer(opp); bolt.getTargets().add(ai); game.getStack().add(bolt);
        final SpellAbility sa = strands();
        assertEquals(PrismaticStrandsAi.evaluate(ai, sa).color, "red");
        game.getStack().add(sa);
        // An irrelevant green permanent must never override the pending Bolt at real resolution.
        put("Craw Wurm", opp, ZoneType.Battlefield);
        game.getStack().resolveStack();
        assertTrue(sa.getHostCard().getChosenColors().iterator().next().equals("red"));
        game.getStack().resolveStack();
        assertEquals(ai.getLife(), 3);
    }

    @Test public void sharedConfigEnablesSearchAndCapsMaxProfile() {
        final GrimoireAiConfig config = new GrimoireAiConfig(new JsonObject());
        config.apply(game);
        assertTrue(game.getAIFlatMonteCarlo());
        assertFalse(game.getAIFlatMonteCarloMax());
        assertTrue(brain().flatMonteCarloActive());
        final FlatMonteCarloPicker.Config limits = new FlatMonteCarloPicker.Config().maximized();
        brain().applyFlatMonteCarloLimits(limits);
        assertEquals(limits.budgetNanos, TimeUnit.MILLISECONDS.toNanos(350));
        assertEquals(limits.turnBudgetNanos, TimeUnit.MILLISECONDS.toNanos(1400));
        assertEquals(limits.maxCandidates, 4);
        assertEquals(limits.opponentLookaheadPercent, 0);
        assertEquals(config.version(), "grimoire-ai/4; prismatic-strands/v2; search/bounded/1");
    }

    @Test public void quickModeDisablesSearchAndDoesNotMutateOtherGames() {
        final JsonObject job = new JsonObject(); job.addProperty("aiSearch", "heuristic");
        new GrimoireAiConfig(job).apply(game);
        assertFalse(game.getAIFlatMonteCarlo());
        assertFalse(brain().flatMonteCarloActive());
        assertEquals(GrimoireAiConfig.statistics(game).get("rollouts").getAsLong(), 0);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void rejectsUnboundedSearchMode() {
        final JsonObject job = new JsonObject(); job.addProperty("aiSearch", "maximum");
        new GrimoireAiConfig(job);
    }

    @Test public void rolloutCopiesNeverEnableRecursiveSearch() {
        new GrimoireAiConfig(new JsonObject()).apply(game);
        final forge.game.Game copy = new GameCopier(game).makeCopy(null, ai);
        assertFalse(copy.getAIFlatMonteCarlo());
    }
}
