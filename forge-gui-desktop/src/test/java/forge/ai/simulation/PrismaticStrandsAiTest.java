package forge.ai.simulation;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import forge.ai.ComputerUtilCard;
import forge.ai.PrismaticStrandsAi;
import forge.ai.PrismaticStrandsAi.Plan;
import forge.card.MagicColor;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

/**
 * Fixtures determinísticas da heurística de Prismatic Strands (grimoire-ai, prismatic-strands/v1).
 * Cada cenário monta o estado público (campo, combate, pilha, vida) e confere a decisão real da IA:
 * conjurar ou não, a cor, e os motivos registrados no trace.
 */
public class PrismaticStrandsAiTest extends FlatMcTestBase {
    private final List<Map<String, Object>> trace = new ArrayList<>();

    @BeforeMethod
    public void setUp() {
        setUpGame();
        System.clearProperty("grimoire.ai.prismaticStrands");
        trace.clear();
        PrismaticStrandsAi.traceSink = trace::add;
    }

    @AfterMethod
    public void tearDown() {
        PrismaticStrandsAi.traceSink = null;
        System.clearProperty("grimoire.ai.prismaticStrands");
    }

    private SpellAbility strands(final ZoneType zone) {
        final Card c = put("Prismatic Strands", ai, zone);
        final SpellAbility sa = c.getFirstSpellAbility();
        sa.setActivatingPlayer(ai);
        return sa;
    }

    private Card creature(final String name, final boolean mine) {
        final Card c = put(name, mine ? ai : opp, ZoneType.Battlefield);
        c.setSickness(false);
        return c;
    }

    private Combat combatOnOpponentsTurnAfterBlocks() {
        at(PhaseType.COMBAT_DECLARE_BLOCKERS, opp);
        return game.getCombat();
    }

    private Plan decide(final SpellAbility sa) {
        return PrismaticStrandsAi.evaluate(ai, sa);
    }

    // 1 -------------------------------------------------------------------------------------------
    @Test
    public void preventsLethalCombatDamage() {
        ai.setLife(3, null);
        final Combat combat = combatOnOpponentsTurnAfterBlocks();
        combat.addAttacker(creature("Grizzly Bears", false), ai);
        combat.addAttacker(creature("Grizzly Bears", false), ai);
        final Plan p = decide(strands(ZoneType.Hand));
        assertTrue(p.cast, p.reason);
        assertEquals(p.color, MagicColor.Constant.GREEN);
        assertTrue(p.byColor.get("green").preventsLethal);
        assertEquals(p.situation, "lethal_combat");
    }

    // 2 -------------------------------------------------------------------------------------------
    @Test
    public void respondsToLethalDamageOnTheStack() {
        ai.setLife(3, null);
        at(PhaseType.MAIN1, opp);
        final Card bolt = put("Lightning Bolt", opp, ZoneType.Hand);
        final SpellAbility boltSa = bolt.getFirstSpellAbility();
        boltSa.setActivatingPlayer(opp);
        boltSa.getTargets().add(ai);
        game.getStack().add(boltSa);
        final Plan p = decide(strands(ZoneType.Hand));
        assertTrue(p.cast, p.reason);
        assertEquals(p.color, MagicColor.Constant.RED);
        assertEquals(p.situation, "lethal_stack");
    }

    // 3 -------------------------------------------------------------------------------------------
    @Test
    public void picksTheMinorityColorThatIsTheRealThreat() {
        ai.setLife(5, null);
        // O adversário tem muito mais cartas brancas (a lógica original escolheria branco)...
        for (int i = 0; i < 5; i++) {
            creature("Savannah Lions", false);
            put("Savannah Lions", opp, ZoneType.Library);
        }
        // ...mas quem ataca para matar é uma criatura verde.
        final Combat combat = combatOnOpponentsTurnAfterBlocks();
        combat.addAttacker(creature("Craw Wurm", false), ai);
        final SpellAbility sa = strands(ZoneType.Hand);
        final Plan p = decide(sa);
        assertTrue(p.cast, p.reason);
        assertEquals(p.color, MagicColor.Constant.GREEN);
        // e a escolha de cor na resolução segue a mesma avaliação (gancho em ComputerUtilCard.chooseColor)
        final List<String> chosen = ComputerUtilCard.chooseColor(sa, 1, 1, new ArrayList<>(MagicColor.Constant.ONLY_COLORS));
        assertEquals(chosen.get(0), MagicColor.Constant.GREEN);
        // baseline preservada e selecionável: a lógica original escolhe a cor mais presente (branco)
        System.setProperty("grimoire.ai.prismaticStrands", "baseline");
        assertTrue(PrismaticStrandsAi.isBaseline());
        final List<String> base = ComputerUtilCard.chooseColor(sa, 1, 1, new ArrayList<>(MagicColor.Constant.ONLY_COLORS));
        assertEquals(base.get(0), MagicColor.Constant.WHITE);
    }

    // 4 -------------------------------------------------------------------------------------------
    @Test
    public void whiteMirrorCountsOwnDamageThatWouldBePrevented() {
        ai.setLife(4, null);
        final Combat combat = combatOnOpponentsTurnAfterBlocks();
        final Card knightA = creature("White Knight", false);
        final Card knightB = creature("White Knight", false);
        final Card knightC = creature("White Knight", false);
        combat.addAttacker(knightA, ai);
        combat.addAttacker(knightB, ai);
        combat.addAttacker(knightC, ai);
        // meu bloqueador branco mataria um atacante: esse dano também seria prevenido
        combat.addBlocker(knightC, creature("Serra Angel", true));
        final Plan p = decide(strands(ZoneType.Hand));
        assertTrue(p.cast, p.reason);
        assertEquals(p.color, MagicColor.Constant.WHITE);
        assertTrue(p.byColor.get("white").ownValueLost > 0, "o dano útil do próprio lado entra na conta");
        assertTrue(p.byColor.get("white").preventsLethal);
    }

    // 5 -------------------------------------------------------------------------------------------
    @Test
    public void threatsOfDifferentColorsPickTheLargestPreventableShare() {
        ai.setLife(4, null);
        final Combat combat = combatOnOpponentsTurnAfterBlocks();
        combat.addAttacker(creature("Hill Giant", false), ai);   // 3, vermelho
        combat.addAttacker(creature("Grizzly Bears", false), ai); // 2, verde
        final Plan p = decide(strands(ZoneType.Hand));
        assertTrue(p.cast, p.reason);
        assertEquals(p.color, MagicColor.Constant.RED);
        assertEquals(p.byColor.get("red").lifeSaved, 3);
        assertEquals(p.byColor.get("green").lifeSaved, 2);
    }

    // 6 -------------------------------------------------------------------------------------------
    @Test
    public void multicoloredSourcesCountOncePerColor() {
        ai.setLife(5, null);
        final Combat combat = combatOnOpponentsTurnAfterBlocks();
        combat.addAttacker(creature("Watchwolf", false), ai);     // 3/3 verde e branco
        combat.addAttacker(creature("Grizzly Bears", false), ai); // 2/2 verde
        final Plan p = decide(strands(ZoneType.Hand));
        assertEquals(p.byColor.get("green").lifeSaved, 5, "Watchwolf conta uma vez no verde");
        assertEquals(p.byColor.get("white").lifeSaved, 3);
        assertEquals(p.color, MagicColor.Constant.GREEN);
        assertTrue(p.cast, p.reason);
    }

    // 7 -------------------------------------------------------------------------------------------
    @Test
    public void colorlessSourcesAreNeverPreventedByAColor() {
        ai.setLife(5, null);
        final Combat combat = combatOnOpponentsTurnAfterBlocks();
        combat.addAttacker(creature("Juggernaut", false), ai);    // 5/3 incolor
        combat.addAttacker(creature("Grizzly Bears", false), ai); // 2/2 verde
        final Plan p = decide(strands(ZoneType.Hand));
        for (PrismaticStrandsAi.ColorEval e : p.byColor.values()) {
            assertFalse(e.sources.contains("Juggernaut"), "fonte incolor não entra em nenhuma cor");
            assertFalse(e.preventsLethal, "nenhuma cor evita os 5 do Juggernaut");
        }
        assertEquals(p.byColor.get("green").lifeSaved, 2);
        assertFalse(p.cast, "prevenir 2 não muda o desfecho: guarda a carta");
    }

    // 8 -------------------------------------------------------------------------------------------
    @Test
    public void unpreventableDamageIsIgnored() {
        ai.setLife(3, null);
        creature("Leyline of Punishment", false); // dano não pode ser prevenido
        final Combat combat = combatOnOpponentsTurnAfterBlocks();
        combat.addAttacker(creature("Hill Giant", false), ai);
        final Plan p = decide(strands(ZoneType.Hand));
        assertEquals(p.byColor.get("red").lifeSaved, 0);
        assertFalse(p.cast, p.reason);
    }

    // 9 -------------------------------------------------------------------------------------------
    @Test
    public void preservesAnImportantCreature() {
        ai.setLife(20, null);
        final Combat combat = combatOnOpponentsTurnAfterBlocks();
        final Card wurm = creature("Craw Wurm", false);   // 6/4 verde
        combat.addAttacker(wurm, ai);
        final Card angel = creature("Serra Angel", true); // 4/4 branco: morreria bloqueando
        combat.addBlocker(wurm, angel);
        final Plan p = decide(strands(ZoneType.Hand));
        assertTrue(p.cast, p.reason);
        assertEquals(p.color, MagicColor.Constant.GREEN);
        assertEquals(p.byColor.get("green").creaturesSaved, 1);
        assertEquals(p.byColor.get("green").ownValueLost, 0, "o anjo é branco: o dano dele no Wurm continua");
    }

    // 10 ------------------------------------------------------------------------------------------
    @Test
    public void noBenefitMeansNoCast() {
        at(PhaseType.MAIN1, ai);
        creature("Grizzly Bears", false);
        final Plan p = decide(strands(ZoneType.Hand));
        assertFalse(p.cast);
        assertEquals(p.situation, "no_threat");
        assertTrue(PrismaticStrandsAi.decide(ai, strands(ZoneType.Hand)).decision() != forge.ai.AiPlayDecision.WillPlay);
    }

    // 11 ------------------------------------------------------------------------------------------
    @Test
    public void neverCancelsItsOwnLethalAttack() {
        opp.setLife(4, null);
        at(PhaseType.COMBAT_DECLARE_BLOCKERS, ai);
        final Combat combat = game.getCombat();
        combat.addAttacker(creature("White Knight", true), opp);
        combat.addAttacker(creature("White Knight", true), opp);
        final Plan p = decide(strands(ZoneType.Hand));
        assertTrue(p.byColor.get("white").cancelsOwnLethal);
        assertNotEquals(p.color, MagicColor.Constant.WHITE);
        assertFalse(p.cast, p.reason);
    }

    // 12 ------------------------------------------------------------------------------------------
    @Test
    public void flashbackWeighsTheWhiteCreatureToTap() {
        // A: depois dos bloqueios, letal chegando; virar um bloqueador já declarado não o tira do combate.
        ai.setLife(3, null);
        final Combat combat = combatOnOpponentsTurnAfterBlocks();
        final Card bears = creature("Grizzly Bears", false);
        final Card bears2 = creature("Grizzly Bears", false);
        combat.addAttacker(bears, ai);
        combat.addAttacker(bears2, ai);
        final Card lions = creature("Savannah Lions", true);
        combat.addBlocker(bears2, lions);
        final Plan fromGy = decide(strands(ZoneType.Graveyard));
        assertTrue(fromGy.cast, fromGy.reason);
        assertEquals(fromGy.color, MagicColor.Constant.GREEN);
        final int costAfterBlocks = fromGy.castCost;

        // B: antes dos bloqueios, a criatura branca ainda poderia bloquear: custo maior; e a IA espera.
        setUp();
        ai.setLife(20, null);
        at(PhaseType.COMBAT_DECLARE_ATTACKERS, opp);
        game.getCombat().addAttacker(creature("Grizzly Bears", false), ai);
        creature("Serra Angel", true);
        final Plan before = decide(strands(ZoneType.Graveyard));
        assertTrue(before.castCost > costAfterBlocks, "virar um possível bloqueador custa mais");
        assertFalse(before.cast, before.reason);

        // C: da mão o custo é menor (a carta continua disponível por flashback depois).
        final Plan hand = decide(strands(ZoneType.Hand));
        assertTrue(hand.castCost < costAfterBlocks);
    }

    // trace -----------------------------------------------------------------------------------------
    @Test
    public void traceComesFromTheRealDecision() {
        ai.setLife(3, null);
        final Combat combat = combatOnOpponentsTurnAfterBlocks();
        combat.addAttacker(creature("Grizzly Bears", false), ai);
        combat.addAttacker(creature("Grizzly Bears", false), ai);
        PrismaticStrandsAi.decide(ai, strands(ZoneType.Hand));
        assertFalse(trace.isEmpty());
        final Map<String, Object> t = trace.get(trace.size() - 1);
        assertEquals(t.get("kind"), "consider");
        assertEquals(t.get("cast"), true);
        assertEquals(t.get("color"), "green");
        assertTrue(((List<?>) t.get("threats")).size() >= 2);
    }
}
