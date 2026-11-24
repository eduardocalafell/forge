package forge.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import forge.card.ColorSet;
import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardLists;
import forge.game.combat.Combat;
import forge.game.phase.PhaseHandler;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

/**
 * Heurística dedicada do Grimoire para Prismatic Strands ("Prevent all damage that sources of the
 * color of your choice would deal this turn"; flashback: virar uma criatura branca desvirada).
 *
 * <p>Uma única avaliação ({@link #evaluate}) alimenta as duas decisões, para que o momento de conjurar
 * e a cor escolhida considerem o mesmo cenário: {@code ChooseColorAi} (conjurar ou esperar) e
 * {@code ComputerUtilCard.chooseColor} (cor na resolução). Usa só informação pública: atacantes,
 * bloqueadores, pilha, campo e vida. Reaproveita as previsões do motor ({@link ComputerUtilCombat},
 * {@link ComputerUtil#predictThreatenedObjects}). Não altera as regras da carta.</p>
 *
 * <p>Para cada cor, soma o que a prevenção evitaria (dano ao jogador, criaturas que morreriam) e o que
 * ela custaria (dano útil do próprio lado, inclusive o próprio dano letal). Fontes multicoloridas
 * contam uma vez em cada cor que possuem; incolores nunca são prevenidas por nenhuma cor; dano que
 * não pode ser prevenido não conta; cores já cobertas por uma prevenção ativa não contam de novo.</p>
 *
 * <p>Baseline selecionável: com a propriedade de sistema {@code grimoire.ai.prismaticStrands=baseline}
 * a carta volta à lógica original do Forge ({@code MostProminentInHumanDeck}: conjura sempre que pode,
 * cor mais presente no deck adversário).</p>
 */
public final class PrismaticStrandsAi {
    public static final String VERSION = "prismatic-strands/v1";
    public static final String LOGIC = "PrismaticStrands";

    /** Destino opcional do trace de decisões (o adaptador do Grimoire grava em arquivo por partida). */
    public static volatile Consumer<Map<String, Object>> traceSink;
    private static String lastTraceKey;

    private PrismaticStrandsAi() { }

    public static boolean isBaseline() {
        return "baseline".equalsIgnoreCase(System.getProperty("grimoire.ai.prismaticStrands", "v1"));
    }

    /**
     * A carta tem a heurística dedicada ativa. O script mantém AI:RemoveDeck:All (dica para geração de decks); com a
     * lógica original, essa marca tira a carta da lista de jogadas das heurísticas (só o Flat MC a considerava), então
     * na prática a IA nunca a conjurava. Com a v1, a carta volta à lista e quem decide é {@link #decide}.
     */
    public static boolean handlesHost(final forge.game.card.Card host) {
        if (host == null || isBaseline()) return false;
        for (SpellAbility sa : host.getSpellAbilities()) {
            if (LOGIC.equals(sa.getParam("AILogic"))) return true;
        }
        return false;
    }

    /** Avaliação de uma cor. Valores em "pontos" na escala de {@link ComputerUtilCard#evaluateCreature}. */
    public static final class ColorEval {
        public final String color;
        public int lifeSaved;
        public int creaturesSaved;
        public int creatureValueSaved;
        public int ownDamageLost;
        public int ownValueLost;
        public boolean preventsLethal;
        public boolean cancelsOwnLethal;
        public boolean alreadyPrevented;
        public int score;
        public final List<String> sources = new ArrayList<>();

        ColorEval(final String color) {
            this.color = color;
        }

        Map<String, Object> toMap() {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("lifeSaved", lifeSaved);
            m.put("creaturesSaved", creaturesSaved);
            m.put("creatureValueSaved", creatureValueSaved);
            m.put("ownDamageLost", ownDamageLost);
            m.put("ownValueLost", ownValueLost);
            m.put("preventsLethal", preventsLethal);
            m.put("cancelsOwnLethal", cancelsOwnLethal);
            m.put("alreadyPrevented", alreadyPrevented);
            m.put("score", score);
            m.put("sources", new ArrayList<>(sources));
            return m;
        }
    }

    /** Resultado: conjurar agora ou não, cor, motivo legível e o detalhe por cor. */
    public static final class Plan {
        public boolean cast;
        public String color;
        public String reason;
        public String situation;
        public boolean waitForBlocks;
        public int incomingDamage;
        public int lifeAfterCombat = Integer.MIN_VALUE;
        public int castCost;
        public final Map<String, ColorEval> byColor = new LinkedHashMap<>();
        public final List<String> threats = new ArrayList<>();

        public ColorEval best() {
            ColorEval b = null;
            for (ColorEval e : byColor.values()) {
                if (b == null || e.score > b.score) {
                    b = e;
                }
            }
            return b;
        }
    }

    /* ======================================================================================== avaliação */

    public static Plan evaluate(final Player ai, final SpellAbility sa) {
        final Game game = ai.getGame();
        final PhaseHandler ph = game.getPhaseHandler();
        final Combat combat = game.getCombat();
        final Plan plan = new Plan();
        for (String c : MagicColor.Constant.ONLY_COLORS) {
            plan.byColor.put(c, new ColorEval(c));
        }
        markAlreadyPrevented(game, plan);

        boolean stackThreat = evaluateStack(ai, sa, plan);
        boolean combatSeen = false;
        if (combat != null && !ph.getPhase().isAfter(PhaseType.COMBAT_DAMAGE)
                && ph.getPhase().isAfter(PhaseType.COMBAT_BEGIN)) {
            combatSeen = evaluateCombat(ai, combat, plan);
        }

        // Pontuação por cor
        final int life = Math.max(1, ai.getLife());
        final int lifeWeight = life <= 5 ? 40 : life <= 10 ? 20 : 8;
        for (ColorEval e : plan.byColor.values()) {
            if (e.alreadyPrevented) {
                e.score = Integer.MIN_VALUE / 2;
                continue;
            }
            int s = e.lifeSaved * lifeWeight + e.creatureValueSaved - e.ownValueLost - e.ownDamageLost * 15;
            if (e.preventsLethal) {
                s += 10000;
            }
            if (e.cancelsOwnLethal) {
                s -= 20000;
            }
            e.score = s;
        }

        plan.castCost = castCost(ai, sa, ph, combat);
        final ColorEval best = plan.best();
        plan.color = best == null ? MagicColor.Constant.WHITE : best.color;

        // Decisão de conjurar
        if (!stackThreat && !combatSeen) {
            plan.cast = false;
            plan.situation = "no_threat";
            plan.reason = "Nada a prevenir agora: sem dano relevante na pilha nem combate em andamento.";
        } else if (best == null || best.score <= 0) {
            plan.cast = false;
            plan.situation = "no_benefit";
            plan.reason = "Prevenir qualquer cor não traz benefício líquido neste momento.";
        } else if (plan.waitForBlocks && !best.preventsLethal && !stackThreat) {
            plan.cast = false;
            plan.situation = "wait_blocks";
            plan.reason = "Esperando os bloqueios: o combate ainda vai revelar mais informação.";
        } else if (best.preventsLethal) {
            plan.cast = true;
            plan.situation = stackThreat ? "lethal_stack" : "lethal_combat";
            plan.reason = "Evita dano letal de fontes " + colorPt(best.color) + ".";
        } else {
            final int threshold = 140 + plan.castCost;
            plan.cast = best.score >= threshold;
            plan.situation = plan.cast ? "value" : "below_threshold";
            plan.reason = plan.cast
                    ? "Preserva " + (best.creaturesSaved > 0 ? best.creaturesSaved + " criatura(s)" : "") + (best.creaturesSaved > 0 && best.lifeSaved > 0 ? " e " : "")
                      + (best.lifeSaved > 0 ? best.lifeSaved + " de vida" : "") + " contra fontes " + colorPt(best.color) + "."
                    : "Benefício abaixo do custo de gastar a carta agora (" + best.score + " < " + threshold + ").";
        }
        return plan;
    }

    /** Cores já cobertas por uma prevenção ativa neste turno (outra Prismatic Strands, por exemplo). */
    private static void markAlreadyPrevented(final Game game, final Plan plan) {
        try {
            for (Card c : game.getCardsIn(ZoneType.Command)) {
                if (!c.getName().contains("Prismatic Strands")) {
                    continue;
                }
                Iterable<String> chosen = c.getChosenColors();
                if ((chosen == null || !chosen.iterator().hasNext()) && c.getEffectSource() != null) {
                    chosen = c.getEffectSource().getChosenColors();
                }
                if (chosen == null) {
                    continue;
                }
                for (String col : chosen) {
                    final ColorEval e = plan.byColor.get(col.toLowerCase());
                    if (e != null) {
                        e.alreadyPrevented = true;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // informação de apoio; sem ela a avaliação continua válida
        }
    }

    /* ------------------------------------------------------------------------------------- combate */

    private static boolean evaluateCombat(final Player ai, final Combat combat, final Plan plan) {
        final Game game = ai.getGame();
        final PhaseHandler ph = game.getPhaseHandler();
        final boolean defending = ph.getPlayerTurn().isOpponentOf(ai);
        final boolean blocksKnown = ph.getPhase().isAfter(PhaseType.COMBAT_DECLARE_ATTACKERS);
        if (combat.getAttackers().isEmpty()) {
            return false;
        }
        if (defending && !blocksKnown) {
            // Antes dos bloqueios só faz sentido agir se nem o melhor bloqueio salva (avaliado abaixo como letal).
            plan.waitForBlocks = true;
        }

        // 1. Dano que chegaria ao jogador da IA (atacantes adversários sem bloqueio / com atropelar).
        if (defending) {
            final int remain = ComputerUtilCombat.lifeThatWouldRemain(ai, combat);
            plan.lifeAfterCombat = remain;
            plan.incomingDamage = Math.max(0, ai.getLife() - remain);
            for (Card atk : combat.getAttackersOf(ai)) {
                final ColorSet cs = atk.getColor();
                final boolean blocked = !combat.getBlockers(atk).isEmpty();
                int dmg = 0;
                if (!blocked) {
                    dmg = ComputerUtilCombat.damageIfUnblocked(atk, ai, combat, false);
                }
                if (dmg <= 0) {
                    continue;
                }
                plan.threats.add(atk.getName() + " (" + atk.getId() + "): " + dmg + " ao jogador");
                if (!atk.canDamagePrevented(true)) {
                    continue; // dano que não pode ser prevenido
                }
                for (ColorEval e : plan.byColor.values()) {
                    if (cs.hasAnyColor(MagicColor.fromName(e.color))) {
                        e.lifeSaved += dmg;
                        e.sources.add(atk.getName());
                    }
                }
            }
            if (plan.incomingDamage >= ai.getLife() || plan.lifeAfterCombat < 1) {
                for (ColorEval e : plan.byColor.values()) {
                    if (plan.lifeAfterCombat + e.lifeSaved >= 1) {
                        e.preventsLethal = true;
                    }
                }
            }
        }

        // 2. Criaturas da IA que morreriam no combate (bloqueando ou bloqueadas), pela cor de quem causa o dano.
        for (Card atk : combat.getAttackers()) {
            final List<Card> blockers = combat.getBlockers(atk);
            if (blockers.isEmpty()) {
                continue;
            }
            if (atk.getController().equals(ai)) {
                // Meu atacante bloqueado: preveni-lo se os bloqueadores o matariam.
                if (ComputerUtilCombat.attackerWouldBeDestroyed(ai, atk, combat)) {
                    for (Card b : blockers) {
                        if (!b.canDamagePrevented(true)) {
                            continue;
                        }
                        addSaved(plan, b.getColor(), atk, b);
                    }
                }
                // e o dano útil que o meu atacante causa nos bloqueadores seria perdido se for da cor escolhida
                for (Card b : blockers) {
                    if (ComputerUtilCombat.blockerWouldBeDestroyed(ai, b, combat)) {
                        addOwnLoss(plan, atk.getColor(), ComputerUtilCard.evaluateCreature(b), 0);
                    }
                }
            } else {
                // Atacante adversário bloqueado por criaturas minhas.
                for (Card b : blockers) {
                    if (!b.getController().equals(ai)) {
                        continue;
                    }
                    if (ComputerUtilCombat.blockerWouldBeDestroyed(ai, b, combat) && atk.canDamagePrevented(true)) {
                        addSaved(plan, atk.getColor(), b, atk);
                    }
                }
                // Meus bloqueadores que matariam o atacante: esse dano também seria prevenido.
                if (ComputerUtilCombat.attackerWouldBeDestroyed(ai, atk, combat)) {
                    for (Card b : blockers) {
                        if (b.getController().equals(ai)) {
                            addOwnLoss(plan, b.getColor(), ComputerUtilCard.evaluateCreature(atk), 0);
                        }
                    }
                }
            }
        }

        // 3. Meu ataque: dano ao adversário que seria prevenido (inclusive o meu dano letal).
        if (!defending) {
            for (Player opp : ai.getOpponents()) {
                final int oppLife = opp.getLife();
                int total = 0;
                final Map<String, Integer> byColorDmg = new LinkedHashMap<>();
                for (Card atk : combat.getAttackersOf(opp)) {
                    if (!combat.getBlockers(atk).isEmpty()) {
                        continue;
                    }
                    final int dmg = ComputerUtilCombat.damageIfUnblocked(atk, opp, combat, false);
                    total += dmg;
                    for (String c : MagicColor.Constant.ONLY_COLORS) {
                        if (atk.getColor().hasAnyColor(MagicColor.fromName(c))) {
                            byColorDmg.merge(c, dmg, Integer::sum);
                        }
                    }
                }
                for (Map.Entry<String, Integer> en : byColorDmg.entrySet()) {
                    final ColorEval e = plan.byColor.get(en.getKey());
                    e.ownDamageLost += en.getValue();
                    if (total >= oppLife && total - en.getValue() < oppLife) {
                        e.cancelsOwnLethal = true;
                    }
                }
            }
        }
        return true;
    }

    private static void addSaved(final Plan plan, final ColorSet sourceColors, final Card saved, final Card source) {
        final int v = ComputerUtilCard.evaluateCreature(saved);
        for (ColorEval e : plan.byColor.values()) {
            if (sourceColors.hasAnyColor(MagicColor.fromName(e.color))) {
                e.creaturesSaved += 1;
                e.creatureValueSaved += v;
                e.sources.add(source.getName());
            }
        }
        plan.threats.add(saved.getName() + " (" + saved.getId() + ") morreria para " + source.getName());
    }

    private static void addOwnLoss(final Plan plan, final ColorSet ownColors, final int value, final int damage) {
        for (ColorEval e : plan.byColor.values()) {
            if (ownColors.hasAnyColor(MagicColor.fromName(e.color))) {
                e.ownValueLost += value;
                e.ownDamageLost += damage;
            }
        }
    }

    /* --------------------------------------------------------------------------------------- pilha */

    /** Dano na pilha vindo de fonte adversária (só o objeto do topo, como no restante da IA). */
    private static boolean evaluateStack(final Player ai, final SpellAbility sa, final Plan plan) {
        final Game game = ai.getGame();
        if (game.getStack().isEmpty()) {
            return false;
        }
        final SpellAbilityStackInstance top = game.getStack().peek();
        final SpellAbility topSa = top.getSpellAbility();
        if (topSa == null || topSa.getActivatingPlayer() == null || !topSa.getActivatingPlayer().isOpponentOf(ai)) {
            return false;
        }
        final Card source = topSa.getHostCard();
        if (source == null) {
            return false;
        }
        final ColorSet colors = source.getColor();
        boolean found = false;
        int toPlayer = 0;
        for (SpellAbility ab = topSa; ab != null; ab = ab.getSubAbility()) {
            final ApiType api = ab.getApi();
            if (api == ApiType.DealDamage || api == ApiType.DamageAll) {
                found = true;
                if (!ab.hasParam("NumDmg")) {
                    continue;
                }
                final int amount = AbilityUtils.calculateAmount(source, ab.getParam("NumDmg"), ab);
                boolean hitsMe = false;
                if (ab.usesTargeting() && ab.getTargets() != null) {
                    for (GameEntity t : ab.getTargets().getTargetPlayers()) {
                        if (t.equals(ai)) {
                            hitsMe = true;
                        }
                    }
                }
                if (api == ApiType.DamageAll && ab.hasParam("ValidPlayers")) {
                    hitsMe = true;
                }
                if (ab.hasParam("Defined") && AbilityUtils.getDefinedPlayers(source, ab.getParam("Defined"), ab).contains(ai)) {
                    hitsMe = true;
                }
                if (hitsMe) {
                    toPlayer += ComputerUtilCombat.predictDamageTo(ai, amount, source, false);
                }
            }
        }
        if (!found) {
            return false;
        }
        final boolean preventable = source.canDamagePrevented(false);
        plan.threats.add(source.getName() + " na pilha" + (toPlayer > 0 ? ": " + toPlayer + " ao jogador" : "") + (preventable ? "" : " (não pode ser prevenido)"));
        if (!preventable) {
            return true;
        }
        final CardCollection mineThreatened = new CardCollection();
        for (GameObject o : ComputerUtil.predictThreatenedObjects(ai, sa, true)) {
            if (o instanceof Card c && c.getController().equals(ai) && c.isCreature()) {
                mineThreatened.add(c);
            }
        }
        for (ColorEval e : plan.byColor.values()) {
            if (!colors.hasAnyColor(MagicColor.fromName(e.color))) {
                continue;
            }
            e.sources.add(source.getName());
            e.lifeSaved += toPlayer;
            if (toPlayer >= ai.getLife() && ai.canLoseLife()) {
                e.preventsLethal = true;
            }
            for (Card c : mineThreatened) {
                e.creaturesSaved += 1;
                e.creatureValueSaved += ComputerUtilCard.evaluateCreature(c);
            }
        }
        return true;
    }

    /* ---------------------------------------------------------------------------- custo de conjurar */

    /**
     * Custo de oportunidade. Da mão: a carta continua disponível por flashback depois, então o custo é
     * baixo (a mana). Por flashback: a carta é exilada e uma criatura branca desvirada é virada; se essa
     * criatura ainda poderia bloquear ou atacar neste turno, o custo inclui o valor dela.
     */
    private static int castCost(final Player ai, final SpellAbility sa, final PhaseHandler ph, final Combat combat) {
        final Card host = sa.getHostCard();
        final boolean fromGraveyard = host != null && host.isInZone(ZoneType.Graveyard);
        if (!fromGraveyard) {
            return 20;
        }
        int cost = 60; // a última utilização da carta
        final CardCollection whites = CardLists.filter(ai.getCreaturesInPlay(), c -> c.isWhite() && c.isUntapped());
        if (whites.isEmpty()) {
            return cost;
        }
        int cheapest = Integer.MAX_VALUE;
        for (Card w : whites) {
            int v = 0;
            final boolean blockersStillOpen = ph.getPlayerTurn().isOpponentOf(ai) && !ph.getPhase().isAfter(PhaseType.COMBAT_DECLARE_ATTACKERS);
            final boolean attacksStillOpen = ph.isPlayerTurn(ai) && ph.getPhase().isBefore(PhaseType.COMBAT_DECLARE_ATTACKERS);
            final boolean inCombat = combat != null && (combat.isAttacking(w) || combat.isBlocking(w));
            if (blockersStillOpen || attacksStillOpen) {
                v = ComputerUtilCard.evaluateCreature(w) / 2; // perde a chance de bloquear/atacar
            }
            if (inCombat) {
                v = 0; // virar um bloqueador já declarado não o tira do combate
            }
            cheapest = Math.min(cheapest, v);
        }
        return cost + (cheapest == Integer.MAX_VALUE ? 0 : cheapest);
    }

    /* ======================================================================================== integrações */

    /** Decisão de conjurar (ChooseColorAi). */
    public static AiAbilityDecision decide(final Player ai, final SpellAbility sa) {
        final Plan plan = evaluate(ai, sa);
        trace(ai, sa, "consider", plan);
        if (plan.cast) {
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }
        return new AiAbilityDecision(0, plan.waitForBlocks ? AiPlayDecision.AnotherTime : AiPlayDecision.CantPlayAi);
    }

    /** Cor na resolução: a mesma avaliação. Sem ameaça atual, a cor das fontes de dano adversárias mais fortes no campo. */
    public static String chooseColor(final Player ai, final SpellAbility sa, final List<String> choices) {
        final Plan plan = evaluate(ai, sa);
        String color = null;
        final ColorEval best = plan.best();
        if (best != null && best.score > 0 && choices.contains(best.color)) {
            color = best.color;
        }
        if (color == null) {
            final Map<String, Integer> power = new LinkedHashMap<>();
            for (Player opp : ai.getOpponents()) {
                for (Card c : opp.getCreaturesInPlay()) {
                    for (String col : choices) {
                        if (c.getColor().hasAnyColor(MagicColor.fromName(col))) {
                            power.merge(col, Math.max(0, c.getNetCombatDamage()), Integer::sum);
                        }
                    }
                }
            }
            color = power.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(choices.isEmpty() ? MagicColor.Constant.WHITE : choices.get(0));
            plan.reason = "Sem ameaça imediata na resolução; cor com mais poder de ataque adversário no campo.";
        }
        plan.color = color;
        trace(ai, sa, "choose_color", plan);
        return color;
    }

    private static void trace(final Player ai, final SpellAbility sa, final String kind, final Plan plan) {
        final Consumer<Map<String, Object>> sink = traceSink;
        if (sink == null) {
            return;
        }
        final Game game = ai.getGame();
        final String key = kind + "|" + game.getPhaseHandler().getTurn() + "|" + game.getPhaseHandler().getPhase() + "|" + plan.cast + "|" + plan.color + "|" + game.getStack().size();
        if ("consider".equals(kind) && key.equals(lastTraceKey)) {
            return; // a IA reavalia várias vezes na mesma prioridade; grava só mudanças
        }
        lastTraceKey = key;
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("ai", VERSION);
        m.put("card", "Prismatic Strands");
        m.put("cardInstanceId", sa.getHostCard() == null ? null : sa.getHostCard().getId());
        m.put("kind", kind);
        m.put("player", ai.getName());
        m.put("turn", game.getPhaseHandler().getTurn());
        m.put("phase", String.valueOf(game.getPhaseHandler().getPhase()));
        m.put("zone", sa.getHostCard() != null && sa.getHostCard().isInZone(ZoneType.Graveyard) ? "graveyard" : "hand");
        m.put("cast", plan.cast);
        m.put("color", plan.color);
        m.put("situation", plan.situation);
        m.put("reason", plan.reason);
        m.put("life", ai.getLife());
        m.put("incomingDamage", plan.incomingDamage);
        m.put("castCost", plan.castCost);
        m.put("threats", new ArrayList<>(plan.threats));
        final Map<String, Object> evals = new LinkedHashMap<>();
        for (ColorEval e : plan.byColor.values()) {
            evals.put(e.color, e.toMap());
        }
        m.put("byColor", evals);
        try {
            sink.accept(m);
        } catch (RuntimeException ignored) {
            // o trace nunca interfere na partida
        }
    }

    private static String colorPt(final String c) {
        switch (c) {
            case MagicColor.Constant.WHITE: return "brancas";
            case MagicColor.Constant.BLUE: return "azuis";
            case MagicColor.Constant.BLACK: return "pretas";
            case MagicColor.Constant.RED: return "vermelhas";
            case MagicColor.Constant.GREEN: return "verdes";
            default: return c;
        }
    }
}
