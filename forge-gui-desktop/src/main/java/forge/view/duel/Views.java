package forge.view.duel;

import java.util.Map;

import com.google.common.collect.Multiset;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterType;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;

/**
 * Conversão das views do Forge em DTOs para o navegador, sempre do ponto de vista do jogador humano.
 * Carta que o motor não deixa o humano ver sai só como "oculta" (sem nome, sem id de impressão).
 */
final class Views {
    private Views() { }

    static boolean visible(final CardView c, final PlayerView viewer) {
        try {
            return c != null && c.canBeShownTo(viewer);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String name(final CardView c) {
        final String o = c.getOracleName();
        return o != null && !o.isEmpty() ? o : c.getName();
    }

    /** Referência curta a uma carta (para eventos, alvos e opções). */
    static JsonObject ref(final CardView c, final PlayerView viewer) {
        final JsonObject o = new JsonObject();
        if (c == null) {
            return o;
        }
        o.addProperty("id", c.getId());
        if (visible(c, viewer)) {
            o.addProperty("name", name(c));
            final CardStateView st = c.getCurrentState();
            o.addProperty("set", st == null ? null : st.getSetCode());
            o.addProperty("token", c.isToken());
            o.addProperty("faceName", st == null ? null : st.getName());
        } else {
            o.addProperty("hidden", true);
        }
        o.addProperty("controller", c.getController() == null ? null : c.getController().getId());
        return o;
    }

    static JsonObject card(final CardView c, final PlayerView viewer) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", c.getId());
        final boolean vis = visible(c, viewer);
        o.addProperty("owner", c.getOwner() == null ? null : c.getOwner().getId());
        o.addProperty("controller", c.getController() == null ? null : c.getController().getId());
        o.addProperty("zone", c.getZone() == null ? null : c.getZone().name());
        o.addProperty("tapped", c.isTapped());
        o.addProperty("faceDown", c.isFaceDown());
        if (!vis) {
            o.addProperty("hidden", true);
            return o;
        }
        final CardStateView st = c.getCurrentState();
        o.addProperty("name", name(c));
        o.addProperty("faceName", st.getName());
        o.addProperty("set", st.getSetCode());
        o.addProperty("token", c.isToken());
        o.addProperty("sick", c.isSick());
        o.addProperty("attacking", c.isAttacking());
        o.addProperty("blocking", c.isBlocking());
        o.addProperty("phasedOut", c.isPhasedOut());
        o.addProperty("transformed", c.isTransformed());
        o.addProperty("damage", c.getDamage());
        o.addProperty("manaCost", st.getManaCost() == null ? "" : st.getManaCost().toString());
        o.addProperty("type", st.getType() == null ? "" : st.getType().toString());
        o.addProperty("creature", st.isCreature());
        o.addProperty("land", st.isLand());
        if (st.isCreature()) {
            o.addProperty("power", st.getPower());
            o.addProperty("toughness", st.getToughness());
        }
        final String loyalty = st.getLoyalty();
        if (loyalty != null && !loyalty.isEmpty() && !"0".equals(loyalty)) {
            o.addProperty("loyalty", loyalty);
        }
        o.addProperty("text", safe(c.getText()));
        final Multiset<CounterType> counters = c.getCounters();
        if (counters != null && !counters.isEmpty()) {
            final JsonObject cs = new JsonObject();
            for (Multiset.Entry<CounterType> e : counters.entrySet()) {
                cs.addProperty(String.valueOf(e.getElement()), e.getCount());
            }
            o.add("counters", cs);
        }
        final GameEntityView att = c.getEntityAttachedTo();
        if (att != null) {
            o.addProperty("attachedTo", att.getId());
            o.addProperty("attachedToPlayer", att instanceof PlayerView);
        }
        return o;
    }

    static JsonObject stackItem(final StackItemView si, final PlayerView viewer) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", si.getId());
        o.addProperty("text", safe(si.getText()));
        o.addProperty("ability", si.isAbility());
        o.addProperty("trigger", si.isTrigger());
        o.addProperty("controller", si.getActivatingPlayer() == null ? null : si.getActivatingPlayer().getId());
        o.add("source", si.getSourceCard() == null ? JsonNull.INSTANCE : ref(si.getSourceCard(), viewer));
        final JsonArray targets = new JsonArray();
        if (si.getTargetCards() != null) {
            for (CardView t : si.getTargetCards()) {
                final JsonObject x = ref(t, viewer);
                x.addProperty("kind", "card");
                targets.add(x);
            }
        }
        if (si.getTargetPlayers() != null) {
            for (PlayerView p : si.getTargetPlayers()) {
                final JsonObject x = new JsonObject();
                x.addProperty("kind", "player");
                x.addProperty("id", p.getId());
                targets.add(x);
            }
        }
        if (si.getTargetStackItems() != null) {
            for (StackItemView t : si.getTargetStackItems()) {
                final JsonObject x = new JsonObject();
                x.addProperty("kind", "stack");
                x.addProperty("id", t.getId());
                targets.add(x);
            }
        }
        o.add("targets", targets);
        return o;
    }

    static String safe(final String s) {
        return s == null ? "" : s.length() > 2000 ? s.substring(0, 2000) : s;
    }

    static void put(final JsonObject o, final String k, final Map<?, ?> m) {
        final JsonObject x = new JsonObject();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            x.addProperty(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
        }
        o.add(k, x);
    }
}
