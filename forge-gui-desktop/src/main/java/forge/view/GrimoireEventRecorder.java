package forge.view;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Multimap;
import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventCardSacrificed;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.GameEventTurnBegan;
import forge.game.phase.PhaseHandler;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

/**
 * Eventos de regras tipados, serializados NO MOMENTO da emissão (nenhuma referência a objetos
 * mutáveis do jogo é guardada). Protocolo versionado; cada linha tem {@code schemaVersion},
 * {@code sequence} (ordem), {@code turn}, {@code phase}, {@code activePlayer}, {@code eventType} e
 * campos do tipo. IDs de instância de carta são os IDs do Forge, estáveis durante a partida.
 *
 * <p>Separado do trace de decisões da IA (arquivo próprio): aqui só o que aconteceu.</p>
 */
public final class GrimoireEventRecorder {
    public static final int SCHEMA_VERSION = 1;

    private final Game game;
    private final int maxEvents;
    private final List<JsonObject> events = new ArrayList<>();
    private long sequence;
    private boolean truncated;
    private int unknown;

    public GrimoireEventRecorder(final Game game, final int maxEvents) {
        this.game = game;
        this.maxEvents = maxEvents;
    }

    public List<JsonObject> events() {
        return events;
    }

    public boolean truncated() {
        return truncated;
    }

    public long total() {
        return sequence;
    }

    public int ignoredTypes() {
        return unknown;
    }

    @Subscribe
    public void receive(final GameEvent ev) {
        final JsonObject o;
        try {
            o = toJson(ev);
        } catch (RuntimeException e) {
            return; // a gravação nunca interfere na partida
        }
        if (o == null) {
            unknown++;
            return;
        }
        sequence++;
        if (events.size() >= maxEvents) {
            truncated = true;
            return;
        }
        o.addProperty("schemaVersion", SCHEMA_VERSION);
        o.addProperty("sequence", sequence);
        final PhaseHandler ph = game.getPhaseHandler();
        if (ph != null) {
            o.addProperty("turn", ph.getTurn());
            o.addProperty("phase", ph.getPhase() == null ? null : ph.getPhase().name());
            o.addProperty("activePlayer", ph.getPlayerTurn() == null ? null : ph.getPlayerTurn().getName());
        }
        events.add(o);
    }

    private JsonObject toJson(final GameEvent ev) {
        if (ev instanceof GameEventTurnBegan e) {
            final JsonObject o = base("turn_began");
            o.addProperty("player", name(e.turnOwner()));
            o.addProperty("turnNumber", e.turnNumber());
            return o;
        }
        if (ev instanceof GameEventMulligan e) {
            final JsonObject o = base("mulligan");
            o.addProperty("player", name(e.player()));
            return o;
        }
        if (ev instanceof GameEventLandPlayed e) {
            final JsonObject o = base("land_played");
            o.addProperty("player", name(e.player()));
            card(o, "card", e.land());
            return o;
        }
        if (ev instanceof GameEventSpellAbilityCast e) {
            final boolean spell = e.sa() != null && e.sa().isSpell();
            final boolean trigger = e.si() != null && e.si().isTrigger();
            final JsonObject o = base(spell ? "spell_cast" : trigger ? "ability_triggered" : "ability_activated");
            o.addProperty("player", e.si() == null ? null : name(e.si().getActivatingPlayer()));
            card(o, "source", e.si() != null ? e.si().getSourceCard() : e.sa() == null ? null : e.sa().getHostCard());
            o.addProperty("text", e.si() == null ? null : e.si().getText());
            o.addProperty("targets", e.targetDescription());
            o.addProperty("stackIndex", e.stackIndex());
            return o;
        }
        if (ev instanceof GameEventSpellResolved e) {
            final JsonObject o = base("spell_resolved");
            card(o, "source", e.spell() == null ? null : e.spell().getHostCard());
            o.addProperty("fizzled", e.hasFizzled());
            o.addProperty("text", e.stackDescription());
            return o;
        }
        if (ev instanceof GameEventAttackersDeclared e) {
            final JsonObject o = base("attackers_declared");
            o.addProperty("player", name(e.player()));
            final JsonArray arr = new JsonArray();
            for (Map.Entry<GameEntityView, CardView> en : e.attackersMap().entries()) {
                final JsonObject a = new JsonObject();
                card(a, "card", en.getValue());
                a.addProperty("defender", en.getKey() == null ? null : en.getKey().getName());
                a.addProperty("defenderId", en.getKey() == null ? null : en.getKey().getId());
                a.addProperty("defenderIsPlayer", en.getKey() instanceof PlayerView);
                arr.add(a);
            }
            o.add("attackers", arr);
            return o;
        }
        if (ev instanceof GameEventBlockersDeclared e) {
            final JsonObject o = base("blockers_declared");
            o.addProperty("player", name(e.defendingPlayer()));
            final JsonArray arr = new JsonArray();
            for (Map.Entry<GameEntityView, Multimap<CardView, CardView>> en : e.blockers().entrySet()) {
                for (Map.Entry<CardView, CardView> b : en.getValue().entries()) {
                    final JsonObject x = new JsonObject();
                    card(x, "blocker", b.getKey());
                    card(x, "attacker", b.getValue());
                    arr.add(x);
                }
            }
            o.add("blocks", arr);
            return o;
        }
        if (ev instanceof GameEventCardDamaged e) {
            final JsonObject o = base("card_damaged");
            card(o, "card", e.card());
            card(o, "source", e.source());
            o.addProperty("amount", e.amount());
            o.addProperty("damageType", e.type() == null ? null : e.type().name());
            return o;
        }
        if (ev instanceof GameEventPlayerDamaged e) {
            final JsonObject o = base("player_damaged");
            o.addProperty("player", name(e.target()));
            card(o, "source", e.source());
            o.addProperty("amount", e.amount());
            o.addProperty("combat", e.combat());
            o.addProperty("infect", e.infect());
            return o;
        }
        if (ev instanceof GameEventPlayerLivesChanged e) {
            final JsonObject o = base("life_changed");
            o.addProperty("player", name(e.player()));
            o.addProperty("before", e.oldLives());
            o.addProperty("after", e.newLives());
            return o;
        }
        if (ev instanceof GameEventCardChangeZone e) {
            final ZoneType from = e.from() == null ? null : e.from().zoneType();
            final ZoneType to = e.to() == null ? null : e.to().zoneType();
            if (from == to) {
                return null;
            }
            final JsonObject o = base("zone_change");
            card(o, "card", e.card());
            o.addProperty("fromZone", from == null ? null : from.name());
            o.addProperty("toZone", to == null ? null : to.name());
            o.addProperty("fromPlayer", e.from() == null ? null : name(e.from().player()));
            o.addProperty("toPlayer", e.to() == null ? null : name(e.to().player()));
            // Compras e embaralhamentos não revelam a carta na timeline (o campo fica, a interface decide)
            o.addProperty("hidden", from == ZoneType.Library && to == ZoneType.Hand);
            return o;
        }
        if (ev instanceof GameEventCardSacrificed e) {
            final JsonObject o = base("sacrificed");
            card(o, "card", e.card());
            return o;
        }
        if (ev instanceof GameEventGameOutcome e) {
            final JsonObject o = base("game_outcome");
            o.addProperty("winner", e.winningPlayerName());
            o.addProperty("lastTurn", e.lastTurnNumber());
            final JsonArray arr = new JsonArray();
            if (e.outcomeStrings() != null) {
                e.outcomeStrings().forEach(arr::add);
            }
            o.add("outcome", arr);
            return o;
        }
        return null;
    }

    private static JsonObject base(final String type) {
        final JsonObject o = new JsonObject();
        o.addProperty("eventType", type);
        return o;
    }

    private static String name(final PlayerView p) {
        return p == null ? null : p.getName();
    }

    /** Instância de carta: id estável, nome canônico (Oracle), controlador e zona no momento do evento. */
    private static void card(final JsonObject o, final String key, final CardView c) {
        if (c == null) {
            o.add(key, com.google.gson.JsonNull.INSTANCE);
            return;
        }
        final JsonObject x = new JsonObject();
        x.addProperty("id", c.getId());
        final String oracle = c.getOracleName();
        x.addProperty("name", oracle != null && !oracle.isEmpty() ? oracle : c.getName());
        x.addProperty("controller", c.getController() == null ? null : c.getController().getName());
        x.addProperty("token", c.isToken());
        o.add(key, x);
    }
}
