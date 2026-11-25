package forge.ai.simulation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.testng.annotations.AfterClass;
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
 * Auditoria de cartas que tinham AI:RemoveDeck:All (Ghostly Flicker, Tortured Existence). Cada cenário roda nos
 * dois modos (heurístico e busca limitada), grava o que a IA escolheu de fato em /tmp/grimoire-card-audit.json e
 * confere o comportamento corrigido. Antes da correção as duas cartas nunca eram jogadas: o filtro de cartas
 * RemoveDeck as tirava da lista no modo heurístico, a lógica de "piscar" só rodava com um alvo (o Flicker exige
 * dois) e o custo de descartar criatura não tinha preferência de descarte.
 */
public class GrimoireCardAuditTest extends FlatMcTestBase {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<Map<String, Object>> ROWS = new ArrayList<>();

    @BeforeMethod public void setup() { setUpGame(); }

    @AfterClass(alwaysRun = true) public void report() throws Exception {
        final Path p = Path.of(System.getProperty("grimoire.audit.report", "/tmp/grimoire-card-audit.json"));
        Files.createDirectories(p.getParent());
        Files.writeString(p, GSON.toJson(ROWS));
    }

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

    private static String describe(final SpellAbility sa) {
        if (sa == null) return "pass";
        final StringBuilder b = new StringBuilder(sa.getHostCard().getName());
        final List<String> t = new ArrayList<>();
        for (GameObject o : sa.getTargets()) t.add(o instanceof Card c ? c.getName() + "#" + c.getId() + (c.isToken() ? "(token)" : "") : String.valueOf(o));
        if (!t.isEmpty()) b.append(" -> ").append(t);
        return b.toString();
    }

    /** O que a IA quer fazer agora (primeira escolha) e, se for a carta auditada, o efeito real depois de pagar e resolver. */
    private Map<String, Object> decide(final String card) {
        final List<String> decisions = new ArrayList<>();
        for (Card c : game.getCardsInGame()) {
            if (!c.getName().equals(card) || !c.getController().equals(ai)) continue;
            for (SpellAbility sa : c.getAllSpellAbilities()) {
                sa.setActivatingPlayer(ai);
                decisions.add(c.getZone().getZoneType() + ":" + brain().canPlaySa(sa) + (sa.getTargets().isEmpty() ? "" : " " + describe(sa)));
                sa.resetTargets();
            }
        }
        final List<SpellAbility> chosen = brain().chooseSpellAbilityToPlay();
        final SpellAbility first = chosen == null || chosen.isEmpty() ? null : chosen.get(0);
        final Map<String, Object> o = new LinkedHashMap<>();
        o.put("canPlaySa", decisions);
        o.put("choice", describe(first));
        o.put("usesCard", first != null && first.getHostCard().getName().equals(card));
        if (first != null && first.getHostCard().getName().equals(card)) {
            final List<String> handBefore = names(ai.getCardsIn(ZoneType.Hand));
            final List<String> gyBefore = names(ai.getCardsIn(ZoneType.Graveyard));
            ((PlayerControllerAi) ai.getController()).playChosenSpellAbility(first);
            o.put("targetsAfterPay", describe(first));
            game.getStack().resolveStack();
            while (!game.getStack().isEmpty()) game.getStack().resolveStack();
            o.put("handBefore", handBefore);
            o.put("handAfter", names(ai.getCardsIn(ZoneType.Hand)));
            o.put("graveyardBefore", gyBefore);
            o.put("graveyardAfter", names(ai.getCardsIn(ZoneType.Graveyard)));
            o.put("battlefieldAfter", names(ai.getCardsIn(ZoneType.Battlefield)));
        }
        return o;
    }

    private static List<String> names(final Iterable<Card> cards) {
        final List<String> out = new ArrayList<>();
        for (Card c : cards) out.add(c.getName() + (c.isToken() ? "(token)" : ""));
        out.sort(null);
        return out;
    }

    private Map<String, Object> last;

    private void row(final String scenario, final String mode, final String expectation, final Map<String, Object> observed) {
        last = observed;
        final Map<String, Object> r = new LinkedHashMap<>();
        r.put("scenario", scenario);
        r.put("mode", mode);
        r.put("expected", expectation);
        r.put("observed", observed);
        ROWS.add(r);
        System.out.println("AUDIT " + scenario + " [" + mode + "] " + GSON.toJson(observed).replace('\n', ' '));
    }

    // ------------------------------------------------------------------ controles do arnês

    @Test public void controlCastsCreature() {
        mode(false);
        put("Forest", 2, ai, ZoneType.Battlefield).forEach(c -> c.setSickness(false));
        put("Grizzly Bears", ai, ZoneType.Hand);
        at(PhaseType.MAIN2, ai);
        row("control-casts-creature", "heuristic", "conjura Grizzly Bears", decide("Grizzly Bears"));
        assertEquals(last.get("usesCard"), true, "controle: o arnês deixa a IA jogar uma criatura");
    }

    @Test public void controlBurnsForLethal() {
        mode(false);
        opp.setLife(3, null);
        put("Mountain", 1, ai, ZoneType.Battlefield).forEach(c -> c.setSickness(false));
        put("Lightning Bolt", ai, ZoneType.Hand);
        at(PhaseType.MAIN1, ai);
        row("control-burns-for-lethal", "heuristic", "Raio no oponente", decide("Lightning Bolt"));
        assertEquals(last.get("usesCard"), true, "controle: Raio letal");
    }

    // ------------------------------------------------------------------ Ghostly Flicker

    private void islands(final int n) { put("Island", n, ai, ZoneType.Battlefield).forEach(c -> c.setSickness(false)); }

    private void flickerSavesFromBolt(final boolean bounded) {
        mode(bounded);
        islands(3);
        final Card angel = bf("Hill Giant", true);
        put("Ghostly Flicker", ai, ZoneType.Hand);
        at(PhaseType.MAIN1, opp);
        final SpellAbility bolt = put("Lightning Bolt", opp, ZoneType.Hand).getFirstSpellAbility();
        bolt.setActivatingPlayer(opp);
        bolt.getTargets().add(angel);
        game.getStack().add(bolt);
        final Map<String, Object> o = decide("Ghostly Flicker");
        while (!game.getStack().isEmpty()) game.getStack().resolveStack();
        o.put("angelSurvives", names(ai.getCardsIn(ZoneType.Battlefield)).contains("Hill Giant"));
        row("flicker-saves-from-bolt", bounded ? "bounded" : "heuristic", "responde ao Raio exilando Hill Giant + um terreno, e o Giant sobrevive", o);
        assertEquals(o.get("usesCard"), true, "Ghostly Flicker responde à remoção");
        assertTrue(String.valueOf(o.get("choice")).contains("Hill Giant") && String.valueOf(o.get("choice")).contains("Island"), String.valueOf(o.get("choice")));
        assertEquals(o.get("angelSurvives"), true, "o alvo do Raio sobrevive");
    }
    @Test public void flickerSavesFromBoltHeuristic() { flickerSavesFromBolt(false); }
    @Test public void flickerSavesFromBoltBounded() { flickerSavesFromBolt(true); }

    private void flickerEtbValue(final boolean bounded) {
        mode(bounded);
        islands(4);
        bf("Man-o'-War", true);
        bf("Elvish Visionary", true);
        bf("Craw Wurm", false);
        put("Island", 10, ai, ZoneType.Library);
        put("Ghostly Flicker", ai, ZoneType.Hand);
        at(PhaseType.MAIN2, ai);
        row("flicker-etb-value", bounded ? "bounded" : "heuristic", "pisca Man-o'-War + Elvish Visionary (devolve o Craw Wurm e compra 1)", decide("Ghostly Flicker"));
        assertEquals(last.get("choice"), "Ghostly Flicker -> [Man-o'-War#5, Elvish Visionary#6]".replace("#5", "#" + idOf("Man-o'-War")).replace("#6", "#" + idOf("Elvish Visionary")));
    }
    @Test public void flickerEtbValueHeuristic() { flickerEtbValue(false); }
    @Test public void flickerEtbValueBounded() { flickerEtbValue(true); }

    private void flickerNoValue(final boolean bounded) {
        mode(bounded);
        islands(3);
        bf("Grizzly Bears", true);
        bf("Grizzly Bears", true);
        put("Ghostly Flicker", ai, ZoneType.Hand);
        at(PhaseType.MAIN2, ai);
        row("flicker-no-value", bounded ? "bounded" : "heuristic", "não conjura (nada a ganhar)", decide("Ghostly Flicker"));
        assertEquals(last.get("usesCard"), false, "sem ameaça nem efeito de entrada, não gasta a carta");
    }
    @Test public void flickerNoValueHeuristic() { flickerNoValue(false); }
    @Test public void flickerNoValueBounded() { flickerNoValue(true); }

    private void flickerAvoidsToken(final boolean bounded) {
        mode(bounded);
        islands(3);
        final Card bears = bf("Grizzly Bears", true);
        final Card token = put("Grizzly Bears", ai, ZoneType.Battlefield);
        token.setGamePieceType(forge.card.GamePieceType.TOKEN);
        token.setSickness(false);
        put("Ghostly Flicker", ai, ZoneType.Hand);
        at(PhaseType.MAIN1, opp);
        final SpellAbility bolt = put("Lightning Bolt", opp, ZoneType.Hand).getFirstSpellAbility();
        bolt.setActivatingPlayer(opp);
        bolt.getTargets().add(bears);
        game.getStack().add(bolt);
        final Map<String, Object> o = decide("Ghostly Flicker");
        while (!game.getStack().isEmpty()) game.getStack().resolveStack();
        o.put("bearsSurvive", names(ai.getCardsIn(ZoneType.Battlefield)).contains("Grizzly Bears"));
        o.put("tokenSurvives", names(ai.getCardsIn(ZoneType.Battlefield)).contains("Grizzly Bears(token)"));
        row("flicker-avoids-token", bounded ? "bounded" : "heuristic", "se conjurar para salvar os Bears, o segundo alvo é um terreno, nunca a ficha", o);
        assertEquals(o.get("tokenSurvives"), true, "a ficha nunca vai para o exílio");
        assertEquals(o.get("bearsSurvive"), true, "os Bears ameaçados são salvos");
    }
    @Test public void flickerAvoidsTokenHeuristic() { flickerAvoidsToken(false); }
    @Test public void flickerAvoidsTokenBounded() { flickerAvoidsToken(true); }

    // ------------------------------------------------------------------ Tortured Existence

    private void swamps(final int n) { put("Swamp", n, ai, ZoneType.Battlefield).forEach(c -> c.setSickness(false)); }

    private void tortured(final String scenario, final String expectation, final boolean bounded, final List<String> hand, final List<String> graveyard, final int mana, final PhaseType phase, final boolean ownTurn) {
        mode(bounded);
        swamps(mana);
        bf("Tortured Existence", true);
        for (String h : hand) put(h, ai, ZoneType.Hand);
        for (String g : graveyard) put(g, ai, ZoneType.Graveyard);
        put("Swamp", 10, ai, ZoneType.Library);
        at(phase, ownTurn ? ai : opp);
        final Map<String, Object> o = decide("Tortured Existence");
        row(scenario, bounded ? "bounded" : "heuristic", expectation, o);
    }

    private void expectTrade(final String discarded, final String returned) {
        assertEquals(last.get("usesCard"), true, "ativa Tortured Existence");
        @SuppressWarnings("unchecked") final List<String> hand = (List<String>) last.get("handAfter");
        @SuppressWarnings("unchecked") final List<String> gy = (List<String>) last.get("graveyardAfter");
        assertTrue(hand.contains(returned), "volta " + returned + ": " + hand);
        assertTrue(gy.contains(discarded), "descarta " + discarded + ": " + gy);
    }

    private void expectPass() {
        assertEquals(last.get("usesCard"), false, "não ativa: " + last.get("choice"));
    }

    private int idOf(final String name) {
        for (Card c : ai.getCardsIn(ZoneType.Battlefield)) if (c.getName().equals(name)) return c.getId();
        return -1;
    }

    @Test public void tortGoodTradeHeuristic() { tortured("tortured-good-trade", "descarta Grizzly Bears e recupera Gurmag Angler", false, List.of("Grizzly Bears"), List.of("Gurmag Angler"), 1, PhaseType.END_OF_TURN, false); expectTrade("Grizzly Bears", "Gurmag Angler"); }
    @Test public void tortGoodTradeBounded() { tortured("tortured-good-trade", "descarta Grizzly Bears e recupera Gurmag Angler", true, List.of("Grizzly Bears"), List.of("Gurmag Angler"), 1, PhaseType.END_OF_TURN, false); expectTrade("Grizzly Bears", "Gurmag Angler"); }
    @Test public void tortBadTradeHeuristic() { tortured("tortured-bad-trade", "não ativa (perderia o Angler pelo Bears)", false, List.of("Gurmag Angler"), List.of("Grizzly Bears"), 1, PhaseType.END_OF_TURN, false); expectPass(); }
    @Test public void tortBadTradeBounded() { tortured("tortured-bad-trade", "não ativa (perderia o Angler pelo Bears)", true, List.of("Gurmag Angler"), List.of("Grizzly Bears"), 1, PhaseType.END_OF_TURN, false); expectPass(); }
    @Test public void tortChurnHeuristic() { tortured("tortured-churn", "não ativa (troca idêntica, só gasta mana)", false, List.of("Grizzly Bears"), List.of("Grizzly Bears"), 3, PhaseType.MAIN2, true); expectPass(); }
    @Test public void tortChurnBounded() { tortured("tortured-churn", "não ativa (troca idêntica, só gasta mana)", true, List.of("Grizzly Bears"), List.of("Grizzly Bears"), 3, PhaseType.MAIN2, true); expectPass(); }
    @Test public void tortBestPairHeuristic() { tortured("tortured-best-pair", "descarta Grizzly Bears (pior da mão) e recupera Serra Angel (melhor do cemitério ao alcance da mana)", false, List.of("Grizzly Bears", "Hill Giant"), List.of("Serra Angel", "Elvish Warrior"), 4, PhaseType.END_OF_TURN, false); expectTrade("Grizzly Bears", "Serra Angel"); }
    @Test public void tortBestPairBounded() { tortured("tortured-best-pair", "descarta Grizzly Bears (pior da mão) e recupera Serra Angel (melhor do cemitério ao alcance da mana)", true, List.of("Grizzly Bears", "Hill Giant"), List.of("Serra Angel", "Elvish Warrior"), 4, PhaseType.END_OF_TURN, false); expectTrade("Grizzly Bears", "Serra Angel"); }
    @Test public void tortSmallGainHeuristic() { tortured("tortured-small-gain", "não ativa (Elvish Warrior quase igual ao Bears não paga a mana nem a carta)", false, List.of("Grizzly Bears"), List.of("Elvish Warrior"), 1, PhaseType.END_OF_TURN, false); expectPass(); }
}
