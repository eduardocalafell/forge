package forge.view.duel;

import java.util.Map;

import com.google.common.collect.Multimap;
import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import forge.game.GameEntityView;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventBlockersDeclared;
import forge.game.event.GameEventCardAttachment;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventCardCounters;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventCardSacrificed;
import forge.game.event.GameEventCardTapped;
import forge.game.event.GameEventCombatEnded;
import forge.game.event.GameEventGameOutcome;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventMulligan;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventPlayerLivesChanged;
import forge.game.event.GameEventPlayerPoisoned;
import forge.game.event.GameEventScry;
import forge.game.event.GameEventShuffle;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.event.GameEventSpellResolved;
import forge.game.event.GameEventTokenCreated;
import forge.game.event.GameEventTurnBegan;
import forge.game.event.GameEventTurnPhase;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

/**
 * Eventos estruturados para as animações do navegador. Toda carta passa pelo filtro de
 * visibilidade do humano ({@link DuelSession#cardRef}); carta oculta sai sem nome.
 * Os eventos descrevem o que já aconteceu no Forge: o estado (snapshot) continua sendo a verdade.
 */
public final class DuelEventListener {
    private final DuelSession s;

    public DuelEventListener(final DuelSession session) {
        this.s = session;
    }

    @Subscribe
    public void receive(final GameEvent ev) {
        try {
            final JsonObject o = toJson(ev);
            if (o != null) {
                s.addEvent(o);
            } else {
                s.bump();
            }
        } catch (RuntimeException e) {
            s.bump(); // a gravação de eventos nunca interfere na partida
        }
    }

    private static JsonObject ev(final String type) {
        final JsonObject o = new JsonObject();
        o.addProperty("type", type);
        return o;
    }

    private String pid(final PlayerView p) {
        return p == null ? null : String.valueOf(p.getId());
    }

    private JsonObject toJson(final GameEvent e) {
        if (e instanceof GameEventTurnBegan t) {
            return withPlayer(ev("turn"), "player", t.turnOwner());
        }
        if (e instanceof GameEventTurnPhase t) {
            final JsonObject o = withPlayer(ev("phase"), "player", t.playerTurn());
            o.addProperty("phase", t.phase() == null ? null : t.phase().name());
            return o;
        }
        if (e instanceof GameEventSpellAbilityCast c) {
            final JsonObject o = ev("cast");
            if (c.sa() != null && c.sa().getHostCard() != null) {
                o.add("card", s.cardRef(c.sa().getHostCard()));
            }
            o.addProperty("stackId", c.si() == null ? null : String.valueOf(c.si().getId()));
            o.addProperty("ability", c.sa() != null && !c.sa().isSpell());
            o.addProperty("controller", c.si() == null ? null : pid(c.si().getActivatingPlayer()));
            o.addProperty("text", Views.safe(c.si() == null ? null : c.si().getText()));
            return o;
        }
        if (e instanceof GameEventSpellResolved r) {
            final JsonObject o = ev("resolved");
            if (r.spell() != null && r.spell().getHostCard() != null) {
                o.add("card", s.cardRef(r.spell().getHostCard()));
            }
            o.addProperty("fizzled", r.hasFizzled());
            return o;
        }
        if (e instanceof GameEventCardChangeZone z) {
            final JsonObject o = ev("zone");
            final CardView c = z.card();
            o.addProperty("from", z.from() == null ? null : z.from().zoneType().name());
            o.addProperty("to", z.to() == null ? null : z.to().zoneType().name());
            o.addProperty("fromPlayer", z.from() == null ? null : pid(z.from().player()));
            o.addProperty("toPlayer", z.to() == null ? null : pid(z.to().player()));
            if (z.from() != null && z.from().zoneType() == ZoneType.Hand) {
                // o id opaco da mão vai junto para a animação achar a carta de origem
                final String before = s.hiddenIdOf(c);
                if (before != null) {
                    o.addProperty("fromHiddenId", before);
                }
                s.forgetHidden(c);
            }
            o.add("card", s.cardRef(c));
            return o;
        }
        if (e instanceof GameEventCardTapped t) {
            final JsonObject o = ev("tap");
            o.add("card", s.cardRef(t.card()));
            o.addProperty("tapped", t.tapped());
            return o;
        }
        if (e instanceof GameEventCardDamaged d) {
            final JsonObject o = ev("damage");
            o.add("target", s.entityRef(d.card()));
            if (d.source() != null) {
                o.add("source", s.cardRef(d.source()));
            }
            o.addProperty("amount", d.amount());
            o.addProperty("combat", d.type() != null && d.type().name().contains("Combat"));
            return o;
        }
        if (e instanceof GameEventPlayerDamaged d) {
            final JsonObject o = ev("damage");
            o.add("target", s.entityRef(d.target()));
            if (d.source() != null) {
                o.add("source", s.cardRef(d.source()));
            }
            o.addProperty("amount", d.amount());
            o.addProperty("combat", d.combat());
            return o;
        }
        if (e instanceof GameEventPlayerLivesChanged l) {
            final JsonObject o = withPlayer(ev("life"), "player", l.player());
            o.addProperty("from", l.oldLives());
            o.addProperty("to", l.newLives());
            return o;
        }
        if (e instanceof GameEventPlayerPoisoned p) {
            final JsonObject o = withPlayer(ev("poison"), "player", p.receiver());
            o.addProperty("amount", p.amount());
            return o;
        }
        if (e instanceof GameEventCardCounters c) {
            final JsonObject o = ev("counters");
            o.add("card", s.cardRef(c.card()));
            o.addProperty("counter", String.valueOf(c.type()));
            o.addProperty("from", c.oldValue());
            o.addProperty("to", c.newValue());
            return o;
        }
        if (e instanceof GameEventCardAttachment a) {
            final JsonObject o = ev("attach");
            o.add("card", s.cardRef(a.equipment()));
            if (a.newTarget() != null) {
                o.add("to", s.entityRef(a.newTarget()));
            }
            return o;
        }
        if (e instanceof GameEventAttackersDeclared a) {
            final JsonObject o = withPlayer(ev("attackers"), "player", a.player());
            final JsonArray list = new JsonArray();
            final Multimap<GameEntityView, CardView> m = a.attackersMap();
            if (m != null) {
                for (Map.Entry<GameEntityView, CardView> en : m.entries()) {
                    final JsonObject x = new JsonObject();
                    x.addProperty("attacker", s.publicId(en.getValue()));
                    x.add("defender", s.entityRef(en.getKey()));
                    list.add(x);
                }
            }
            o.add("attacks", list);
            return o;
        }
        if (e instanceof GameEventBlockersDeclared b) {
            final JsonObject o = withPlayer(ev("blockers"), "player", b.defendingPlayer());
            final JsonArray list = new JsonArray();
            if (b.blockers() != null) {
                for (Multimap<CardView, CardView> m : b.blockers().values()) {
                    for (Map.Entry<CardView, CardView> en : m.entries()) {
                        final JsonObject x = new JsonObject();
                        x.addProperty("attacker", s.publicId(en.getKey()));
                        x.addProperty("blocker", s.publicId(en.getValue()));
                        list.add(x);
                    }
                }
            }
            o.add("blocks", list);
            return o;
        }
        if (e instanceof GameEventCombatEnded) {
            return ev("combatEnded");
        }
        if (e instanceof GameEventLandPlayed l) {
            final JsonObject o = withPlayer(ev("land"), "player", l.player());
            o.add("card", s.cardRef(l.land()));
            return o;
        }
        if (e instanceof GameEventCardSacrificed sc) {
            final JsonObject o = ev("sacrificed");
            o.add("card", s.cardRef(sc.card()));
            return o;
        }
        if (e instanceof GameEventTokenCreated) {
            return ev("token");
        }
        if (e instanceof GameEventMulligan m) {
            return withPlayer(ev("mulligan"), "player", m.player());
        }
        if (e instanceof GameEventShuffle sh) {
            return withPlayer(ev("shuffle"), "player", sh.player());
        }
        if (e instanceof GameEventScry sc) {
            final JsonObject o = withPlayer(ev("scry"), "player", sc.player());
            o.addProperty("top", sc.toTop());
            o.addProperty("bottom", sc.toBottom());
            return o;
        }
        if (e instanceof GameEventGameOutcome g) {
            final JsonObject o = ev("gameOver");
            o.addProperty("winner", g.winningPlayerName());
            o.addProperty("turn", g.lastTurnNumber());
            return o;
        }
        return null;
    }

    private JsonObject withPlayer(final JsonObject o, final String key, final PlayerView p) {
        o.addProperty(key, pid(p));
        o.addProperty("you", p != null && s.humanView() != null && p.getId() == s.humanView().getId());
        return o;
    }
}
