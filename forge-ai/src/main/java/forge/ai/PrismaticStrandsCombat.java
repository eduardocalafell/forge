package forge.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.card.MagicColor;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CounterEnumType;
import forge.game.combat.Combat;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.GameLossReason;
import forge.game.player.Player;
import forge.game.staticability.StaticAbilityAssignCombatDamageAsUnblocked;

/** Public-information forecast, not a rules replacement. Compare the same remaining damage steps
 * with and without one color prevented. No real card, counter, stack or RNG is mutated.
 * Combat tricks, optional regeneration and future triggered abilities remain heuristic limits. */
final class PrismaticStrandsCombat {
    private PrismaticStrandsCombat() { }

    static boolean evaluate(final Player ai, final Combat combat, final PrismaticStrandsAi.Plan plan) {
        final PhaseType phase = ai.getGame().getPhaseHandler().getPhase();
        if (combat.getAttackers().isEmpty() || !phase.isBefore(PhaseType.COMBAT_DAMAGE)) return false;
        plan.waitForBlocks = !phase.isAfter(PhaseType.COMBAT_DECLARE_ATTACKERS)
                && ai.getGame().getPhaseHandler().getPlayerTurn().isOpponentOf(ai);
        final boolean firstDone = phase == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE;
        final Forecast before = new Forecast(combat, firstDone, null);
        plan.incomingDamage = before.lifeDamage(ai);
        plan.lifeAfterCombat = ai.getLife() - before.lifeDamage(ai);
        for (Card attacker : combat.getAttackers()) {
            plan.threats.add(attacker.getName() + " (" + attacker.getId() + "): combate pendente");
        }
        for (PrismaticStrandsAi.ColorEval e : plan.byColor.values()) {
            if (e.alreadyPrevented) continue;
            final Forecast after = new Forecast(combat, firstDone, e.color);
            e.lifeSaved += Math.max(0, before.lifeDamage(ai) - after.lifeDamage(ai));
            e.poisonSaved += Math.max(0, before.poison(ai) - after.poison(ai));
            e.preventsLethal |= before.lethal(ai) && !after.lethal(ai);
            for (Card card : before.dead) {
                if (after.dead.contains(card)) continue;
                if (card.getController().equals(ai)) {
                    e.creaturesSaved++;
                    e.creatureValueSaved += ComputerUtilCard.evaluateCreature(card);
                } else if (card.getController().isOpponentOf(ai)) {
                    e.ownValueLost += ComputerUtilCard.evaluateCreature(card);
                }
            }
            for (Player opp : ai.getOpponents()) {
                e.ownDamageLost += Math.max(0, before.lifeDamage(opp) - after.lifeDamage(opp));
                e.cancelsOwnLethal |= before.lethal(opp) && !after.lethal(opp);
            }
            for (Card source : before.combatants) {
                if (source.canDamagePrevented(true) && source.getColor().hasAnyColor(MagicColor.fromName(e.color))) {
                    e.sources.add(source.getName());
                }
            }
        }
        return true;
    }

    private static final class Hit {
        final Card source;
        final GameEntity target;
        final int amount;
        Hit(final Card source, final GameEntity target, final int amount) {
            this.source = source; this.target = target; this.amount = amount;
        }
    }

    private static final class Forecast {
        final Set<Card> combatants = new LinkedHashSet<>();
        final Set<Card> dead = new LinkedHashSet<>();
        final Map<Card, Integer> marked = new LinkedHashMap<>();
        final Map<Card, Integer> minus = new LinkedHashMap<>();
        final Set<Card> deathtouched = new LinkedHashSet<>();
        final Map<Player, Integer> life = new LinkedHashMap<>();
        final Map<Player, Integer> poisons = new LinkedHashMap<>();
        final String color;

        Forecast(final Combat combat, final boolean firstDone, final String color) {
            this.color = color;
            for (Card attacker : combat.getAttackers()) {
                combatants.add(attacker);
                combatants.addAll(combat.getBlockers(attacker));
            }
            if (!firstDone) step(combat, true);
            step(combat, false);
        }

        int lifeDamage(final Player p) { return life.getOrDefault(p, 0); }
        int poison(final Player p) { return poisons.getOrDefault(p, 0); }
        boolean lethal(final Player p) {
            return !p.cantLoseForZeroOrLessLife() && p.getLife() - lifeDamage(p) < 1
                    || !p.cantLoseCheck(GameLossReason.Poisoned)
                    && p.getPoisonCounters() + poison(p) >= p.getGame().getRules().getPoisonCountersToLose();
        }

        boolean strikes(final Card c, final boolean first) {
            return !dead.contains(c) && (first ? c.hasFirstStrike() || c.hasDoubleStrike() : !c.hasFirstStrike() || c.hasDoubleStrike());
        }

        int power(final Card c) {
            return Math.max(0, c.getNetCombatDamage() - minus.getOrDefault(c, 0));
        }

        int lethalAssignment(final Card source, final Card target) {
            final int left = Math.max(0, target.getNetToughness() - target.getDamage()
                    - marked.getOrDefault(target, 0) - minus.getOrDefault(target, 0));
            return source.hasKeyword(Keyword.DEATHTOUCH) && left > 0 ? 1 : left;
        }

        void assign(final List<Hit> hits, final Card source, final List<Card> targets, final GameEntity defender,
                    final boolean trample, final boolean blocked) {
            int left = power(source);
            for (int i = 0; i < targets.size() && left > 0; i++) {
                final Card target = targets.get(i);
                final int assigned = i == targets.size() - 1 && !trample ? left : Math.min(left, lethalAssignment(source, target));
                hits.add(new Hit(source, target, assigned));
                left -= assigned;
            }
            if (left > 0 && defender != null && (trample || !blocked)) hits.add(new Hit(source, defender, left));
        }

        void step(final Combat combat, final boolean first) {
            final List<Hit> hits = new ArrayList<>();
            for (Card attacker : combat.getAttackers()) {
                if (!strikes(attacker, first)) continue;
                final List<Card> blockers = new ArrayList<>();
                for (Card b : combat.getBlockers(attacker)) if (!dead.contains(b)) blockers.add(b);
                final boolean asUnblocked = StaticAbilityAssignCombatDamageAsUnblocked.assignCombatDamageAsUnblocked(attacker);
                assign(hits, attacker, asUnblocked ? List.of() : blockers, combat.getDefenderByAttacker(attacker),
                        attacker.hasKeyword(Keyword.TRAMPLE), !asUnblocked && (combat.isBlocked(attacker) || !combat.getBlockers(attacker).isEmpty()));
            }
            // One blocker can block multiple attackers: divide its power once, not once per attacker.
            for (Card blocker : combatants) {
                if (!combat.isBlocking(blocker) || !strikes(blocker, first)) continue;
                final List<Card> targets = new ArrayList<>();
                for (Card a : combat.getAttackersBlockedBy(blocker)) if (!dead.contains(a)) targets.add(a);
                assign(hits, blocker, targets, null, false, true);
            }
            for (Hit hit : hits) apply(hit);
            for (Card card : combatants) {
                final int toughness = card.getNetToughness() - minus.getOrDefault(card, 0);
                if (toughness <= 0 || card.canBeDestroyed() && (deathtouched.contains(card)
                        || card.getDamage() + marked.getOrDefault(card, 0) >= toughness)) dead.add(card);
            }
        }

        void apply(final Hit hit) {
            if (color != null && hit.source.canDamagePrevented(true)
                    && hit.source.getColor().hasAnyColor(MagicColor.fromName(color))) return;
            final int damage = Math.max(0, ComputerUtilCombat.predictDamageTo(hit.target, hit.amount, hit.source, true));
            if (damage == 0) return;
            if (hit.source.hasKeyword(Keyword.LIFELINK) && hit.source.getController().canGainLife()) {
                life.merge(hit.source.getController(), -damage, Integer::sum);
            }
            if (hit.target instanceof Player p) {
                if (hit.source.isInfectDamage(p)) {
                    if (p.canReceiveCounters(CounterEnumType.POISON)) poisons.merge(p, damage, Integer::sum);
                } else if (p.canLoseLife()) {
                    life.merge(p, damage, Integer::sum);
                }
                if (p.canReceiveCounters(CounterEnumType.POISON)) {
                    final int extra = ComputerUtilCombat.predictExtraPoisonWithDamage(hit.source, p, hit.amount);
                    // Forge's helper predicts both hits; this forecast invokes it once per remaining hit.
                    poisons.merge(p, hit.source.hasDoubleStrike() ? extra / 2 : extra, Integer::sum);
                }
            } else if (hit.target instanceof Card c && c.isCreature()) {
                if (hit.source.hasKeyword(Keyword.INFECT) || hit.source.hasKeyword(Keyword.WITHER)) {
                    if (c.canReceiveCounters(CounterEnumType.M1M1)) minus.merge(c, damage, Integer::sum);
                } else {
                    marked.merge(c, damage, Integer::sum);
                }
                if (hit.source.hasKeyword(Keyword.DEATHTOUCH)) deathtouched.add(c);
            }
        }
    }
}
