package forge.view.duel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.card.MagicColor;
import forge.game.Game;
import forge.game.GameEntityView;
import forge.game.GameLogEntry;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.combat.CombatView;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.Selection;
import forge.gamemodes.match.input.Input;
import forge.gamemodes.match.input.InputLockUI;
import forge.gui.FThreads;
import forge.player.PlayerControllerHuman;
import forge.trackable.TrackableObject;
import forge.trackable.TrackableTypes;
import forge.util.collect.FCollection;

/**
 * Estado de uma sessão humano×IA. O Forge decide tudo; esta classe só guarda a pergunta pendente,
 * monta a foto do estado do ponto de vista do humano (com a informação oculta já filtrada) e
 * encaminha comandos validados ao controlador humano do Forge.
 *
 * <p>Threads: a do jogo (Forge), a EDT (inputs do Forge), as do HTTP e o supervisor. Tudo que é
 * mutável aqui é protegido por {@link #lock}.</p>
 */
public final class DuelSession {
    public static final int PROTOCOL = 1;
    private static final int MAX_EVENTS = 4000;

    public final String sessionId;
    public final String generation;
    public final Object lock = new Object();

    // metadados fixos
    public JsonObject meta = new JsonObject();
    public JsonObject validation;

    // ciclo de vida
    private String status = "starting";
    private String statusDetail;
    private JsonObject error;
    private JsonObject result;
    private long revision = 1;
    private final long startedAt = System.currentTimeMillis();
    private long gameStartedAt;
    private volatile long lastClientAt = System.currentTimeMillis();
    private long humanWaitMs;
    private long otherMs;
    private long waitingSince = -1;
    private long tickAt = System.currentTimeMillis();

    // jogo
    private Game game;
    private Player human;
    private PlayerControllerHuman hc;
    private PlayerView humanView;
    private String humanSide;

    // pergunta / prompt
    private Decision pending;
    private int decisionSeq;
    private int promptId;
    private String promptMessage = "";
    private CardView promptCard;
    private String okLabel;
    private String cancelLabel;
    private boolean okEnabled;
    private boolean cancelEnabled;
    private Selection selection = Selection.NONE;
    private JsonObject lastReject;
    private int rejectSeq;

    // paradas de fase (onde o humano recebe prioridade com a pilha vazia)
    // (criadas em attach: PhaseType só pode ser carregado depois do FModel, por causa do Localizer)
    private EnumSet<PhaseType> stopsYou;
    private EnumSet<PhaseType> stopsOpp;

    // eventos
    private final ArrayDeque<JsonObject> events = new ArrayDeque<>();
    private long seq;

    // ids públicos de cartas ocultas na mão do oponente
    private final Map<Integer, String> hiddenIds = new HashMap<>();
    private final AtomicLong hiddenSeq = new AtomicLong();

    // comandos já processados (idempotência)
    private final Map<String, JsonObject> commandResults = new java.util.LinkedHashMap<>(256, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(final Map.Entry<String, JsonObject> e) {
            return size() > 500;
        }
    };

    public DuelSession(final String sessionId, final String generation) {
        this.sessionId = sessionId;
        this.generation = generation;
    }

    /* ------------------------------------------------------------ ciclo de vida */

    public void attach(final Game game0, final Player human0, final PlayerControllerHuman hc0, final String humanSide0) {
        synchronized (lock) {
            game = game0;
            human = human0;
            hc = hc0;
            humanView = human0.getView();
            humanSide = humanSide0;
            stopsYou = EnumSet.of(PhaseType.MAIN1, PhaseType.COMBAT_DECLARE_BLOCKERS, PhaseType.MAIN2);
            stopsOpp = EnumSet.of(PhaseType.COMBAT_DECLARE_ATTACKERS, PhaseType.END_OF_TURN);
            status = "running";
            gameStartedAt = System.currentTimeMillis();
            bumpLocked();
        }
    }

    public void setStatus(final String s, final String detail) {
        synchronized (lock) {
            status = s;
            statusDetail = detail;
            bumpLocked();
        }
    }

    public String status() {
        synchronized (lock) {
            return status;
        }
    }

    public boolean isFinal() {
        synchronized (lock) {
            return isFinalLocked();
        }
    }

    private boolean isFinalLocked() {
        return "over".equals(status) || "invalid".equals(status) || "error".equals(status) || "expired".equals(status);
    }

    /** Erro técnico ou pergunta não suportada: o jogo para aqui, sem escolha automática. */
    public void fail(final String code, final String message) {
        synchronized (lock) {
            if (isFinalLocked()) {
                return;
            }
            error = new JsonObject();
            error.addProperty("code", code);
            error.addProperty("message", message);
            status = "error";
            if (result == null) {
                result = baseResult("error");
                result.addProperty("reason", code);
            }
            bumpLocked();
        }
        abortPending("erro: " + code);
    }

    /** Fim normal do jogo, com o resultado do Forge. */
    public void gameFinished() {
        synchronized (lock) {
            if (isFinalLocked()) {
                return;
            }
            final forge.game.GameOutcome o = game == null ? null : game.getOutcome();
            if (o == null) {
                error = new JsonObject();
                error.addProperty("code", "no_outcome");
                error.addProperty("message", "O Forge encerrou o jogo sem resultado.");
                status = "error";
                result = baseResult("error");
                result.addProperty("reason", "no_outcome");
            } else {
                final String outcome;
                String winner = null;
                if (o.isDraw()) {
                    outcome = "draw";
                } else if (o.isWinner(human.getRegisteredPlayer())) {
                    outcome = "win";
                    winner = "human";
                } else {
                    outcome = conceded ? "conceded" : "loss";
                    winner = "ai";
                }
                status = "over";
                result = baseResult(outcome);
                result.addProperty("winner", winner);
                result.addProperty("reason", String.valueOf(o.getWinCondition()));
            }
            bumpLocked();
        }
    }

    private boolean conceded;

    /** Sessão expirada (inatividade, tempo máximo ou abandono): resultado próprio, nunca derrota técnica. */
    public void expire(final String reason) {
        synchronized (lock) {
            if (isFinalLocked()) {
                return;
            }
            status = "expired";
            statusDetail = reason;
            result = baseResult("inactivity".equals(reason) ? "inactivity" : "expired");
            result.addProperty("reason", reason);
            bumpLocked();
        }
        abortPending("expirada");
    }

    private JsonObject baseResult(final String outcome) {
        final JsonObject r = new JsonObject();
        r.addProperty("outcome", outcome);
        r.addProperty("turns", game == null || game.getPhaseHandler() == null ? 0 : game.getPhaseHandler().getTurn());
        final Player starter = game == null ? null : game.getStartingPlayer();
        r.addProperty("first", starter == null ? null : starter == human ? "human" : "ai");
        r.addProperty("durationMs", gameStartedAt == 0 ? 0 : System.currentTimeMillis() - gameStartedAt);
        r.addProperty("humanWaitMs", humanWaitMs);
        r.addProperty("engineMs", otherMs);
        r.addProperty("endedAt", System.currentTimeMillis());
        return r;
    }

    private void abortPending(final String why) {
        final Decision d;
        synchronized (lock) {
            d = pending;
            pending = null;
        }
        if (d != null) {
            d.abort(new Decision.DuelAborted(why));
        }
    }

    public JsonObject result() {
        synchronized (lock) {
            return result == null ? null : result.deepCopy();
        }
    }

    public void touchClient() {
        lastClientAt = System.currentTimeMillis();
    }

    public long lastClientAt() {
        return lastClientAt;
    }

    public long startedAt() {
        return startedAt;
    }

    /** Amostragem periódica: separa o tempo esperando o humano do tempo do motor/IA. */
    public long tick() {
        synchronized (lock) {
            final long now = System.currentTimeMillis();
            final long dt = now - tickAt;
            tickAt = now;
            if (!"running".equals(status)) {
                waitingSince = -1;
                return 0;
            }
            if (waitingForHumanLocked()) {
                humanWaitMs += dt;
                if (waitingSince < 0) {
                    waitingSince = now;
                }
                return now - waitingSince;
            }
            otherMs += dt;
            waitingSince = -1;
            return 0;
        }
    }

    private boolean waitingForHumanLocked() {
        if (pending != null) {
            return true;
        }
        final Input in = currentInput();
        return in != null && !(in instanceof InputLockUI);
    }

    private Input currentInput() {
        if (hc == null || game == null || game.isGameOver()) {
            return null;
        }
        try {
            return hc.getInputQueue().getInput();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /* ------------------------------------------------------------ revisões */

    void bump() {
        synchronized (lock) {
            bumpLocked();
        }
    }

    private void bumpLocked() {
        revision++;
        lock.notifyAll();
    }

    public long revision() {
        synchronized (lock) {
            return revision;
        }
    }

    /* ------------------------------------------------------------ prompt (inputs do Forge) */

    void onPrompt(final PlayerView owner, final String message, final CardView card) {
        synchronized (lock) {
            if (owner != null && humanView != null && owner.getId() != humanView.getId()) {
                return;
            }
            promptId++;
            promptMessage = message == null ? "" : message;
            promptCard = card;
            bumpLocked();
        }
    }

    void onButtons(final PlayerView owner, final String l1, final String l2, final boolean e1, final boolean e2) {
        synchronized (lock) {
            if (owner != null && humanView != null && owner.getId() != humanView.getId()) {
                return;
            }
            okLabel = l1;
            cancelLabel = l2;
            okEnabled = e1;
            cancelEnabled = e2;
            bumpLocked();
        }
    }

    void onSelection(final Selection s) {
        synchronized (lock) {
            selection = s == null ? Selection.NONE : s;
            bumpLocked();
        }
    }

    void onReject(final String why) {
        synchronized (lock) {
            rejectSeq++;
            lastReject = new JsonObject();
            lastReject.addProperty("seq", rejectSeq);
            lastReject.addProperty("message", why);
            bumpLocked();
        }
    }

    boolean isUiSetToSkipPhase(final PlayerView turn, final PhaseType phase) {
        synchronized (lock) {
            if (stopsYou == null) {
                return false;
            }
            final boolean mine = humanView != null && turn != null && turn.getId() == humanView.getId();
            if (!(mine ? stopsYou : stopsOpp).contains(phase)) {
                return true;
            }
            // paradas de combate só valem quando há combate de fato
            if (phase == PhaseType.COMBAT_DECLARE_ATTACKERS || phase == PhaseType.COMBAT_DECLARE_BLOCKERS) {
                final CombatView cv = game == null ? null : game.getView().getCombat();
                return cv == null || cv.getNumAttackers() == 0;
            }
            return false;
        }
    }

    /* ------------------------------------------------------------ decisões bloqueantes */

    /** Chamado pela thread do Forge (jogo ou EDT): bloqueia até a resposta validada chegar. */
    JsonObject ask(final String kind, final JsonObject payload) {
        final Decision d;
        synchronized (lock) {
            if (isFinalLocked()) {
                throw new Decision.DuelAborted("sessão encerrada");
            }
            if (pending != null) {
                // Duas perguntas ao mesmo tempo (EDT e jogo): o protocolo atende uma por vez.
                fail("concurrent_decision", "O Forge abriu duas perguntas ao mesmo tempo (" + pending.kind + " e " + kind + ").");
                throw new Decision.DuelAborted("perguntas simultâneas");
            }
            decisionSeq++;
            d = new Decision("d" + decisionSeq, kind, payload);
            pending = d;
            bumpLocked();
        }
        try {
            return d.await();
        } finally {
            synchronized (lock) {
                if (pending == d) {
                    pending = null;
                }
                bumpLocked();
            }
        }
    }

    /** Pergunta que este adaptador ainda não sabe apresentar: erro explícito, jogo parado. */
    RuntimeException unsupported(final String what) {
        fail("unsupported_decision", "Pergunta do Forge ainda não suportada nesta mesa: " + what + ". A partida foi interrompida sem escolha automática.");
        return new Decision.DuelAborted("não suportado: " + what);
    }

    /* ------------------------------------------------------------ eventos */

    void addEvent(final JsonObject ev) {
        synchronized (lock) {
            seq++;
            ev.addProperty("seq", seq);
            if (game != null && game.getPhaseHandler() != null) {
                ev.addProperty("turn", game.getPhaseHandler().getTurn());
            }
            events.addLast(ev);
            while (events.size() > MAX_EVENTS) {
                events.removeFirst();
            }
            bumpLocked();
        }
    }

    /* ------------------------------------------------------------ ids públicos */

    PlayerView humanView() {
        return humanView;
    }

    PlayerControllerHuman controller() {
        return hc;
    }

    /**
     * Id público de uma carta. Visível: o id do motor. Na mão do oponente: um id opaco por estadia
     * na mão (some ao mudar de zona). Grimório oculto: nenhum id.
     */
    String publicId(final CardView c) {
        if (c == null) {
            return null;
        }
        if (Views.visible(c, humanView)) {
            return String.valueOf(c.getId());
        }
        final ZoneType z = c.getZone();
        if (z == ZoneType.Hand) {
            synchronized (lock) {
                return hiddenIds.computeIfAbsent(c.getId(), k -> "h" + hiddenSeq.incrementAndGet());
            }
        }
        if (z == ZoneType.Battlefield || z == ZoneType.Exile || z == ZoneType.Stack || z == ZoneType.Command) {
            return String.valueOf(c.getId());
        }
        return null;
    }

    String hiddenIdOf(final CardView c) {
        if (c == null) {
            return null;
        }
        synchronized (lock) {
            return hiddenIds.get(c.getId());
        }
    }

    void forgetHidden(final CardView c) {
        if (c == null) {
            return;
        }
        synchronized (lock) {
            hiddenIds.remove(c.getId());
        }
    }

    JsonObject cardRef(final CardView c) {
        final JsonObject o = Views.ref(c, humanView);
        final String id = publicId(c);
        if (id == null) {
            o.remove("id");
        } else {
            o.addProperty("id", id);
        }
        o.remove("controller");
        if (c != null && c.getController() != null) {
            o.addProperty("controller", String.valueOf(c.getController().getId()));
        }
        return o;
    }

    JsonObject cardFull(final CardView c) {
        final JsonObject o = Views.card(c, humanView);
        final String id = publicId(c);
        if (id == null) {
            o.remove("id");
        } else {
            o.addProperty("id", id);
        }
        for (String k : new String[] {"owner", "controller", "attachedTo"}) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                o.addProperty(k, o.get(k).getAsString());
            }
        }
        return o;
    }

    JsonObject entityRef(final Object e) {
        final JsonObject o;
        if (e instanceof CardView cv) {
            o = cardRef(cv);
            o.addProperty("kind", "card");
        } else if (e instanceof PlayerView pv) {
            o = new JsonObject();
            o.addProperty("kind", "player");
            o.addProperty("id", String.valueOf(pv.getId()));
            o.addProperty("you", humanView != null && pv.getId() == humanView.getId());
            o.addProperty("name", pv.getName());
        } else if (e instanceof StackItemView si) {
            o = new JsonObject();
            o.addProperty("kind", "stack");
            o.addProperty("id", String.valueOf(si.getId()));
            o.addProperty("text", Views.safe(si.getText()));
        } else {
            o = new JsonObject();
            o.addProperty("kind", "other");
        }
        return o;
    }

    /* ------------------------------------------------------------ estado (long-poll) */

    public JsonObject waitState(final long sinceRevision, final long afterSeq, final long waitMs) {
        final long deadline = System.currentTimeMillis() + Math.max(0, Math.min(waitMs, 25_000));
        synchronized (lock) {
            while (revision <= sinceRevision) {
                final long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        final JsonObject out = new JsonObject();
        out.add("state", snapshot());
        final JsonArray evs = new JsonArray();
        boolean resync = false;
        synchronized (lock) {
            final JsonObject first = events.peekFirst();
            if (first != null && afterSeq >= 0 && first.get("seq").getAsLong() > afterSeq + 1 && afterSeq < seq) {
                resync = true;
            }
            for (JsonObject e : events) {
                if (e.get("seq").getAsLong() > afterSeq) {
                    evs.add(e);
                }
            }
        }
        out.addProperty("resync", resync);
        out.add("events", evs);
        return out;
    }

    public JsonObject snapshot() {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                return snapshotOnce();
            } catch (java.util.ConcurrentModificationException | IndexOutOfBoundsException | NullPointerException e) {
                last = e; // o jogo mudou enquanto líamos; tenta de novo
                try {
                    Thread.sleep(15);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        final JsonObject s = header();
        s.addProperty("snapshotError", String.valueOf(last));
        return s;
    }

    private JsonObject header() {
        final JsonObject s = new JsonObject();
        synchronized (lock) {
            s.addProperty("protocol", PROTOCOL);
            s.addProperty("sessionId", sessionId);
            s.addProperty("generation", generation);
            s.addProperty("revision", String.valueOf(revision));
            s.addProperty("seq", String.valueOf(seq));
            s.addProperty("status", status);
            s.addProperty("statusDetail", statusDetail);
            s.add("meta", meta);
            if (validation != null) {
                s.add("validation", validation);
            }
            if (error != null) {
                s.add("error", error);
            }
            if (result != null) {
                s.add("result", result);
            }
            s.addProperty("serverTime", System.currentTimeMillis());
        }
        return s;
    }

    private JsonObject snapshotOnce() {
        final JsonObject s = header();
        final Game g;
        final PlayerView me;
        synchronized (lock) {
            g = game;
            me = humanView;
        }
        if (g == null || me == null) {
            return s;
        }
        final GameView gv = g.getView();
        final JsonObject gameO = new JsonObject();
        gameO.addProperty("turn", gv.getTurn());
        gameO.addProperty("phase", gv.getPhase() == null ? null : gv.getPhase().name());
        gameO.addProperty("activePlayer", gv.getPlayerTurn() == null ? null : String.valueOf(gv.getPlayerTurn().getId()));
        String prio = null;
        final JsonArray players = new JsonArray();
        final List<PlayerView> ordered = new ArrayList<>();
        ordered.add(me);
        for (PlayerView p : gv.getPlayers()) {
            if (p.getId() != me.getId()) {
                ordered.add(p);
            }
        }
        for (PlayerView p : ordered) {
            if (p.getHasPriority()) {
                prio = String.valueOf(p.getId());
            }
            players.add(player(p, p.getId() == me.getId()));
        }
        gameO.addProperty("priority", prio);
        gameO.addProperty("gameOver", gv.isGameOver());
        gameO.addProperty("mulligan", gv.isMulligan());
        s.add("game", gameO);
        s.add("players", players);

        final JsonArray stack = new JsonArray();
        if (gv.getStack() != null) {
            for (StackItemView si : gv.getStack()) {
                final JsonObject o = Views.stackItem(si, me);
                o.addProperty("id", String.valueOf(si.getId()));
                o.add("source", si.getSourceCard() == null ? new JsonObject() : cardRef(si.getSourceCard()));
                o.addProperty("controller", si.getActivatingPlayer() == null ? null : String.valueOf(si.getActivatingPlayer().getId()));
                final JsonArray t = new JsonArray();
                for (JsonElement el : o.getAsJsonArray("targets")) {
                    final JsonObject x = el.getAsJsonObject();
                    x.addProperty("id", x.get("id").getAsString());
                    t.add(x);
                }
                o.add("targets", t);
                stack.add(o);
            }
        }
        s.add("stack", stack);

        final JsonArray combat = new JsonArray();
        final CombatView cv = gv.getCombat();
        if (cv != null) {
            for (CardView a : cv.getAttackers()) {
                final JsonObject o = new JsonObject();
                o.addProperty("attacker", publicId(a));
                final GameEntityView d = cv.getDefender(a);
                o.add("defender", d == null ? new JsonObject() : entityRef(d));
                final JsonArray bl = new JsonArray();
                final FCollection<CardView> blockers = cv.getBlockers(a);
                if (blockers != null) {
                    for (CardView b : blockers) {
                        bl.add(publicId(b));
                    }
                }
                o.add("blockers", bl);
                combat.add(o);
            }
        }
        s.add("combat", combat);

        synchronized (lock) {
            s.add("prompt", promptLocked());
            s.add("decision", pending == null || pending.isDone() ? null : decisionDto(pending));
            if (lastReject != null) {
                s.add("reject", lastReject);
            }
            if (stopsYou != null) {
                final JsonObject stops = new JsonObject();
                stops.add("you", phases(stopsYou));
                stops.add("opp", phases(stopsOpp));
                s.add("stops", stops);
            }
            s.addProperty("waitingFor", "running".equals(status) ? (waitingForHumanLocked() ? "human" : "ai") : null);
            s.addProperty("autoPass", hc != null && hc.mayAutoPass());
        }

        final JsonArray log = new JsonArray();
        try {
            final List<GameLogEntry> entries = g.getGameLog().getLogEntries(null);
            for (int i = 0; i < Math.min(40, entries.size()); i++) {
                final GameLogEntry e = entries.get(i);
                final JsonObject o = new JsonObject();
                o.addProperty("type", e.type().name());
                o.addProperty("text", Views.safe(e.message()));
                log.add(o);
            }
        } catch (RuntimeException ignored) {
            // log é só leitura auxiliar
        }
        s.add("log", log);
        return s;
    }

    private static JsonArray phases(final Set<PhaseType> set) {
        final JsonArray a = new JsonArray();
        for (PhaseType p : set) {
            a.add(p.name());
        }
        return a;
    }

    private JsonObject player(final PlayerView p, final boolean you) {
        final JsonObject o = new JsonObject();
        o.addProperty("id", String.valueOf(p.getId()));
        o.addProperty("you", you);
        o.addProperty("name", p.getName());
        o.addProperty("life", p.getLife());
        o.addProperty("poison", p.getCounters(forge.game.card.CounterEnumType.POISON));
        o.addProperty("hasPriority", p.getHasPriority());
        o.addProperty("landsThisTurn", p.getNumLandThisTurn());
        o.addProperty("maxLands", p.getMaxLandPlay());
        o.addProperty("handCount", p.getZoneSize(ZoneType.Hand));
        o.addProperty("libraryCount", p.getZoneSize(ZoneType.Library));
        final JsonObject mana = new JsonObject();
        final String[] keys = {"W", "U", "B", "R", "G", "C"};
        for (int i = 0; i < MagicColor.WUBRGC.length; i++) {
            final int n = p.getMana(MagicColor.WUBRGC[i]);
            if (n > 0) {
                mana.addProperty(keys[i], n);
            }
        }
        o.add("mana", mana);
        o.add("hand", zone(p, ZoneType.Hand, true));
        o.add("battlefield", zone(p, ZoneType.Battlefield, true));
        o.add("graveyard", zone(p, ZoneType.Graveyard, true));
        o.add("exile", zone(p, ZoneType.Exile, true));
        o.add("command", zone(p, ZoneType.Command, true));
        o.add("libraryVisible", zone(p, ZoneType.Library, false));
        return o;
    }

    /** includeHidden: cartas ocultas entram como verso (mão do oponente, face para baixo). */
    private JsonArray zone(final PlayerView p, final ZoneType z, final boolean includeHidden) {
        final JsonArray a = new JsonArray();
        final Iterable<CardView> cards = p.getCards(z);
        if (cards == null) {
            return a;
        }
        for (CardView c : cards) {
            final boolean vis = Views.visible(c, humanView);
            if (!vis && !includeHidden) {
                continue;
            }
            a.add(cardFull(c));
        }
        return a;
    }

    private JsonObject promptLocked() {
        final Input in = currentInput();
        if (in == null || in instanceof InputLockUI) {
            return null;
        }
        final JsonObject o = new JsonObject();
        o.addProperty("id", "p" + promptId);
        o.addProperty("input", in.getClass().getSimpleName());
        o.addProperty("kind", inputKind(in));
        o.addProperty("message", promptMessage);
        if (promptCard != null) {
            o.add("card", cardRef(promptCard));
        }
        final JsonObject b = new JsonObject();
        if (okLabel != null) {
            final JsonObject x = new JsonObject();
            x.addProperty("label", okLabel);
            x.addProperty("enabled", okEnabled);
            b.add("ok", x);
        }
        if (cancelLabel != null) {
            final JsonObject x = new JsonObject();
            x.addProperty("label", cancelLabel);
            x.addProperty("enabled", cancelEnabled);
            b.add("cancel", x);
        }
        o.add("buttons", b);
        final Selection sel = selection;
        o.add("selectable", ids(sel.cards()));
        o.addProperty("min", sel.min());
        o.addProperty("max", sel.max());
        o.add("targets", entities(sel.targets()));
        o.add("chosen", entities(sel.chosen()));
        o.add("actionable", ids(sel.actionable()));
        return o;
    }

    static String inputKind(final Input in) {
        final String n = in.getClass().getSimpleName();
        switch (n) {
            case "InputPassPriority": return "priority";
            case "InputConfirmMulligan": return "mulligan";
            case "InputLondonMulligan": return "mulliganBottom";
            case "InputAttack": return "attack";
            case "InputBlock": return "block";
            case "InputSelectTargets": return "targets";
            case "InputPayMana":
            case "InputPayManaOfCostPayment": return "payMana";
            case "InputConfirm": return "confirm";
            case "InputSelectCardsFromList":
            case "InputSelectEntitiesFromList":
            case "InputSelectCardsForConvokeOrImprovise": return "select";
            case "InputSelectStackItems": return "selectStack";
            case "InputChooseStartingHand": return "startingHand";
            default: return "other";
        }
    }

    private JsonArray ids(final List<CardView> cards) {
        final JsonArray a = new JsonArray();
        if (cards == null) {
            return a;
        }
        final Set<String> seen = new HashSet<>();
        for (CardView c : cards) {
            final String id = publicId(c);
            if (id != null && seen.add(id)) {
                a.add(id);
            }
        }
        return a;
    }

    private JsonArray entities(final List<TrackableObject> list) {
        final JsonArray a = new JsonArray();
        if (list == null) {
            return a;
        }
        for (TrackableObject t : list) {
            a.add(entityRef(t));
        }
        return a;
    }

    private JsonObject decisionDto(final Decision d) {
        final JsonObject o = d.payload.deepCopy();
        o.addProperty("id", d.id);
        o.addProperty("kind", d.kind);
        return o;
    }

    /* ------------------------------------------------------------ comandos */

    public JsonObject command(final JsonObject cmd) {
        touchClient();
        final String commandId = str(cmd, "commandId");
        if (commandId == null || commandId.isEmpty() || commandId.length() > 80) {
            return reject("bad_command", "commandId obrigatório");
        }
        synchronized (lock) {
            final JsonObject prev = commandResults.get(commandId);
            if (prev != null) {
                final JsonObject r = prev.deepCopy();
                r.addProperty("duplicate", true);
                return r;
            }
        }
        JsonObject r;
        try {
            r = dispatch(cmd);
        } catch (RuntimeException e) {
            r = reject("bad_command", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
        synchronized (lock) {
            commandResults.put(commandId, r);
            r.addProperty("revision", String.valueOf(revision));
        }
        return r;
    }

    private JsonObject dispatch(final JsonObject cmd) {
        final String type = str(cmd, "type");
        if (type == null) {
            return reject("bad_command", "type obrigatório");
        }
        switch (type) {
            case "answer":
                return answer(cmd);
            case "stops":
                return setStops(cmd);
            case "concede":
                return concede();
            default:
                return inputCommand(type, cmd);
        }
    }

    private JsonObject answer(final JsonObject cmd) {
        final Decision d;
        synchronized (lock) {
            if (!"running".equals(status)) {
                return reject("not_running", "A partida não está em andamento.");
            }
            d = pending;
        }
        if (d == null || d.isDone()) {
            return reject("stale", "Não há pergunta pendente.");
        }
        if (!d.id.equals(str(cmd, "decisionId"))) {
            return reject("stale", "Essa pergunta já foi respondida ou substituída.");
        }
        final String problem = DecisionRules.validate(d, cmd.has("answer") && cmd.get("answer").isJsonObject() ? cmd.getAsJsonObject("answer") : new JsonObject());
        if (problem != null) {
            return reject("invalid_answer", problem);
        }
        d.complete(cmd.getAsJsonObject("answer"));
        return ok();
    }

    private JsonObject setStops(final JsonObject cmd) {
        synchronized (lock) {
            if (stopsYou == null) {
                return reject("not_running", "A partida ainda não começou.");
            }
            for (String side : new String[] {"you", "opp"}) {
                if (!cmd.has(side)) {
                    continue;
                }
                final EnumSet<PhaseType> set = "you".equals(side) ? stopsYou : stopsOpp;
                final EnumSet<PhaseType> next = EnumSet.noneOf(PhaseType.class);
                for (JsonElement el : cmd.getAsJsonArray(side)) {
                    next.add(PhaseType.valueOf(el.getAsString()));
                }
                set.clear();
                set.addAll(next);
            }
            bumpLocked();
        }
        return ok();
    }

    private JsonObject concede() {
        final boolean hasPending;
        synchronized (lock) {
            if (!"running".equals(status)) {
                return reject("not_running", "A partida não está em andamento.");
            }
            conceded = true;
            hasPending = pending != null;
        }
        if (hasPending) {
            // O Forge está parado numa pergunta: registramos a concessão e encerramos o processo.
            synchronized (lock) {
                status = "over";
                result = baseResult("conceded");
                result.addProperty("winner", "ai");
                result.addProperty("reason", "concede");
                bumpLocked();
            }
            abortPending("concedeu");
            return ok();
        }
        final PlayerControllerHuman c = hc;
        FThreads.invokeInEdtLater(c::concede);
        return ok();
    }

    private JsonObject inputCommand(final String type, final JsonObject cmd) {
        final PlayerControllerHuman c;
        final Game g;
        synchronized (lock) {
            if (!"running".equals(status)) {
                return reject("not_running", "A partida não está em andamento.");
            }
            if (pending != null) {
                return reject("decision_pending", "Responda a pergunta pendente primeiro.");
            }
            final Input in = currentInput();
            if (in == null || in instanceof InputLockUI) {
                return reject("not_your_turn", "Aguarde: o Forge não está esperando uma ação sua.");
            }
            if (!("p" + promptId).equals(str(cmd, "promptId"))) {
                return reject("stale", "O estado mudou desde o seu clique; confira e tente de novo.");
            }
            c = hc;
            g = game;
        }
        switch (type) {
            case "ok":
                if (!okEnabled) {
                    return reject("disabled", "Esse botão não está disponível agora.");
                }
                FThreads.invokeInEdtLater(c::selectButtonOk);
                return ok();
            case "cancel":
                if (!cancelEnabled) {
                    return reject("disabled", "Esse botão não está disponível agora.");
                }
                FThreads.invokeInEdtLater(c::selectButtonCancel);
                return ok();
            case "card": {
                final CardView cv = lookupCard(g, str(cmd, "cardId"));
                if (cv == null) {
                    return reject("unknown_object", "Carta desconhecida ou não visível.");
                }
                FThreads.invokeInEdtLater(() -> {
                    final boolean accepted = c.selectCard(cv, null, null);
                    if (!accepted) {
                        onReject("O Forge não aceitou essa carta agora.");
                    }
                });
                return ok();
            }
            case "player": {
                final PlayerView pv = lookupPlayer(g, str(cmd, "playerId"));
                if (pv == null) {
                    return reject("unknown_object", "Jogador desconhecido.");
                }
                FThreads.invokeInEdtLater(() -> c.selectPlayer(pv, null));
                return ok();
            }
            case "stack": {
                final StackItemView si = lookupStack(g, str(cmd, "stackId"));
                if (si == null) {
                    return reject("unknown_object", "Item da pilha desconhecido.");
                }
                FThreads.invokeInEdtLater(() -> c.selectStackItem(si));
                return ok();
            }
            case "mana": {
                final String color = str(cmd, "color");
                final byte b = color == null ? -1 : MagicColor.fromName(color.toLowerCase());
                if (color == null || ("c".equalsIgnoreCase(color) ? false : b == 0)) {
                    return reject("bad_command", "Cor de mana inválida.");
                }
                FThreads.invokeInEdtLater(() -> c.useMana("c".equalsIgnoreCase(color) ? MagicColor.COLORLESS : b));
                return ok();
            }
            case "passTurn":
                FThreads.invokeInEdtLater(() -> {
                    c.autoPassUntilEndOfTurn();
                    c.selectButtonOk();
                });
                return ok();
            case "alphaStrike":
                FThreads.invokeInEdtLater(c::alphaStrike);
                return ok();
            default:
                return reject("bad_command", "Comando desconhecido: " + type);
        }
    }

    public void cancelAutoPass() {
        final PlayerControllerHuman c = hc;
        if (c != null) {
            FThreads.invokeInEdtLater(c::autoPassCancel);
        }
    }

    private CardView lookupCard(final Game g, final String id) {
        if (id == null || !id.matches("\\d{1,9}")) {
            return null;
        }
        final CardView cv = g.getTracker().getObj(TrackableTypes.CardViewType, Integer.parseInt(id));
        if (cv == null) {
            return null;
        }
        // só objetos que o humano enxerga (ou permanentes face para baixo, que ele pode apontar)
        if (!Views.visible(cv, humanView) && cv.getZone() != ZoneType.Battlefield) {
            return null;
        }
        return cv;
    }

    private static PlayerView lookupPlayer(final Game g, final String id) {
        if (id == null || !id.matches("\\d{1,9}")) {
            return null;
        }
        return g.getTracker().getObj(TrackableTypes.PlayerViewType, Integer.parseInt(id));
    }

    private static StackItemView lookupStack(final Game g, final String id) {
        if (id == null || !id.matches("\\d{1,9}")) {
            return null;
        }
        return g.getTracker().getObj(TrackableTypes.StackItemViewType, Integer.parseInt(id));
    }

    private static JsonObject ok() {
        final JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static JsonObject reject(final String code, final String message) {
        final JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("code", code);
        o.addProperty("message", message);
        return o;
    }

    static String str(final JsonObject o, final String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}
