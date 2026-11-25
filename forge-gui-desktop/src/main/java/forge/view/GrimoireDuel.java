package forge.view;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import forge.StaticData;
import forge.ai.AiProfileUtil;
import forge.deck.Deck;
import forge.deck.DeckFormat;
import forge.game.Game;
import forge.game.GameFormat;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.gui.control.FControlGameEventHandler;
import forge.localinstance.properties.ForgePreferences;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.player.LobbyPlayerHuman;
import forge.player.PlayerControllerHuman;
import forge.trackable.TrackableCollection;
import forge.util.BuildInfo;
import forge.util.MyRandom;
import forge.view.duel.DuelEventListener;
import forge.view.duel.DuelSession;
import forge.view.duel.DuelGuiDesktop;
import forge.gui.GuiBase;
import forge.view.duel.WebGuiGame;

/**
 * Sessão interativa humano×IA ("Jogar contra IA").
 *
 * <pre>
 *   GRIMOIRE_DUEL_SECRET=... forge grimoire duel &lt;job.json&gt;
 * </pre>
 *
 * Um processo = uma partida. O processo sobe um servidor HTTP (protegido pelo segredo da sessão,
 * nunca exposto ao navegador), monta a partida com um jogador humano controlado pelo protocolo e um
 * jogador IA do Forge, e fica vivo esperando o humano. Termina sozinho em fim de jogo, concessão,
 * inatividade, tempo máximo ou abandono. Protocolo: docs/duel-protocol.md no repositório do Grimoire.
 */
public final class GrimoireDuel {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    private static final int MAX_BODY = 64 * 1024;

    private GrimoireDuel() { }

    public static void main(final String[] args, final PrintStream out) {
        final JsonObject job;
        try {
            job = JsonParser.parseString(Files.readString(new File(args[2]).toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            out.println("{\"type\":\"error\",\"message\":\"job inválido\"}");
            return;
        }
        final String secret = System.getenv("GRIMOIRE_DUEL_SECRET");
        if (secret == null || secret.length() < 24) {
            out.println("{\"type\":\"error\",\"message\":\"GRIMOIRE_DUEL_SECRET ausente\"}");
            return;
        }
        final String generation = Long.toString(System.currentTimeMillis(), 36) + "-" + Integer.toString(new SecureRandom().nextInt(1 << 30), 36);
        final DuelSession session = new DuelSession(str(job, "sessionId", "local"), generation);
        final JsonObject limits = job.has("limits") ? job.getAsJsonObject("limits") : new JsonObject();
        final long maxMs = 1000L * num(limits, "maxSeconds", 3 * 3600);
        final long idleMs = 1000L * num(limits, "idleSeconds", 20 * 60);
        final long abandonMs = 1000L * num(limits, "abandonSeconds", 10 * 60);
        final long lingerMs = 1000L * num(limits, "lingerSeconds", 10 * 60);

        final HttpServer server;
        try {
            server = startServer(num(job, "port", 8787), secret, session);
        } catch (IOException e) {
            out.println("{\"type\":\"error\",\"message\":\"porta indisponível\"}");
            return;
        }
        out.println("{\"type\":\"listening\",\"generation\":\"" + generation + "\"}");

        try {
            setup(job, session);
        } catch (Throwable t) {
            session.fail("setup_failed", t.getClass().getSimpleName() + ": " + t.getMessage());
            t.printStackTrace();
        }

        // supervisor: tempo máximo, inatividade do humano, abandono (sem cliente) e encerramento
        long finalAt = -1;
        while (true) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                break;
            }
            final long now = System.currentTimeMillis();
            final long waited = session.tick();
            if (!session.isFinal()) {
                if (now - session.startedAt() > maxMs) {
                    session.expire("max_time");
                } else if (idleMs > 0 && waited > idleMs) {
                    session.expire("inactivity");
                } else if (abandonMs > 0 && now - session.lastClientAt() > abandonMs) {
                    session.expire("abandoned");
                }
            }
            if (session.isFinal()) {
                if (finalAt < 0) {
                    finalAt = now;
                    out.println(GSON.toJson(event("final", session)));
                }
                // fica disponível por um tempo para o cliente buscar o resultado; /close encerra antes
                if (closed || now - finalAt > lingerMs || now - session.lastClientAt() > Math.min(lingerMs, 120_000)) {
                    break;
                }
            }
        }
        server.stop(0);
        out.println("{\"type\":\"exit\"}");
        out.flush();
        System.exit(0);
    }

    private static volatile boolean closed;

    private static JsonObject event(final String type, final DuelSession s) {
        final JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("status", s.status());
        o.add("result", s.result());
        return o;
    }

    /* ------------------------------------------------------------------ partida */

    private static void setup(final JsonObject job, final DuelSession session) {
        final String language = str(job, "language", "pt-BR");
        FModel.initialize(null, prefs -> {
            prefs.setPref(ForgePreferences.FPref.UI_LANGUAGE, language);
            prefs.setPref(ForgePreferences.FPref.YIELD_DECLINE_SCOPE_STACK_YIELD, "NEVER");
            prefs.setPref(ForgePreferences.FPref.YIELD_DECLINE_SCOPE_NO_ACTIONS, "NEVER");
            prefs.setPref(ForgePreferences.FPref.YIELD_SKIP_PHASE_DELAY, "true");
            return null;
        });
        System.setProperty("grimoire.ai.prismaticStrands", "v1");

        final String engine = BuildInfo.getVersionString();
        final JsonObject build = GrimoireSim.readBuildInfo();
        final String engineCommit = build.has("engineCommit") ? build.get("engineCommit").getAsString() : null;
        String profile = str(job, "aiProfile", "");
        if (!profile.isEmpty() && !AiProfileUtil.getProfilesDisplayList().contains(profile)) {
            profile = "";
        }
        final JsonObject meta = new JsonObject();
        meta.addProperty("engine", engine);
        meta.addProperty("engineCommit", engineCommit);
        meta.addProperty("aiVersion", GrimoireSim.AI_VERSION);
        meta.addProperty("aiProfile", profile.isEmpty() ? "Default" : profile);
        meta.addProperty("cardDb", StaticData.instance().getCommonCards().getUniqueCards().size() + " cartas");
        final String format = str(job, "format", "constructed");
        meta.addProperty("format", format);
        meta.addProperty("mode", "bo1");
        session.meta = meta;

        // decks fixos desta sessão: resolvidos aqui, sem troca silenciosa
        final JsonObject decks = job.getAsJsonObject("decks");
        final GrimoireSim.ResolvedDeck human = GrimoireSim.resolveDeck("human", decks.getAsJsonObject("human"), false);
        final GrimoireSim.ResolvedDeck ai = GrimoireSim.resolveDeck("ai", decks.getAsJsonObject("ai"), false);
        final JsonObject validation = new JsonObject();
        boolean ok = human.ok && ai.ok;
        for (Object[] side : new Object[][] {{"human", human}, {"ai", ai}}) {
            final GrimoireSim.ResolvedDeck rd = (GrimoireSim.ResolvedDeck) side[1];
            final JsonObject rep = rd.report.deepCopy();
            final String problem = rd.ok ? formatProblem(format, rd.build(rd.name)) : null;
            if (problem != null) {
                rep.addProperty("formatProblem", problem);
                ok = false;
            }
            validation.add((String) side[0], rep);
        }
        validation.addProperty("ok", ok);
        session.validation = validation;
        if (!ok) {
            session.setStatus("invalid", "Decks com problemas: corrija antes de jogar.");
            return;
        }

        // semente e quem começa: sempre registrados
        long seed;
        try {
            seed = Long.parseLong(str(job, "seed", "").trim());
        } catch (NumberFormatException e) {
            seed = new SecureRandom().nextLong();
        }
        meta.addProperty("seed", Long.toString(seed));
        MyRandom.setRandom(new Random(seed));
        String first = str(job, "first", "random");
        if ("random".equals(first)) {
            first = new Random(seed ^ 0x5DEECE66DL).nextBoolean() ? "human" : "ai";
            meta.addProperty("firstDrawn", true);
        }
        meta.addProperty("first", first);

        final GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        rules.setGamesPerMatch(1);
        rules.setForcedStartingPlayer("ai".equals(first) ? 1 : 0);

        final String humanName = str(job, "humanName", "Você");
        final RegisteredPlayer rpHuman = new RegisteredPlayer(human.build("Você · " + human.name));
        rpHuman.setPlayer(new LobbyPlayerHuman(humanName));
        final RegisteredPlayer rpAi = new RegisteredPlayer(ai.build("IA · " + ai.name));
        rpAi.setPlayer(GamePlayerUtil.createAiPlayer("IA (Forge)", 1, profile));
        final List<RegisteredPlayer> players = new ArrayList<>();
        players.add(rpHuman);
        players.add(rpAi);
        final Match match = new Match(rules, players, "Grimoire duel " + session.sessionId);
        final Game game = match.createGame();

        Player hp = null;
        for (Player p : game.getPlayers()) {
            if (p.getRegisteredPlayer() == rpHuman) {
                hp = p;
            }
        }
        if (hp == null || !(hp.getController() instanceof PlayerControllerHuman)) {
            throw new IllegalStateException("jogador humano não foi criado como PlayerControllerHuman");
        }
        final PlayerControllerHuman hc = (PlayerControllerHuman) hp.getController();
        final WebGuiGame gui = new WebGuiGame(session);
        GuiBase.setInterface(new DuelGuiDesktop(gui));
        gui.setHumanController(hc);
        hc.setGui(gui);
        gui.setGameView(game.getView());
        gui.setOriginalGameController(hp.getView(), hc);
        game.subscribeToEvents(new FControlGameEventHandler(hc));
        game.subscribeToEvents(hc.getYieldController());
        game.subscribeToEvents(new DuelEventListener(session));
        for (Player p : game.getPlayers()) {
            p.updateOpponentsForView();
        }
        session.attach(game, hp, hc, "human");
        final TrackableCollection<forge.game.player.PlayerView> mine = new TrackableCollection<>();
        mine.add(hp.getView());
        gui.openView(mine);

        game.getAction().invoke(() -> {
            try {
                match.startGame(game);
            } catch (RuntimeException | Error t) {
                if (!session.isFinal()) {
                    session.fail("engine_error", t.getClass().getSimpleName() + ": " + t.getMessage());
                }
                t.printStackTrace();
                return;
            }
            session.gameFinished();
        });
    }

    private static String formatProblem(final String format, final Deck deck) {
        if ("casual".equalsIgnoreCase(format)) {
            return null;
        }
        final String base = DeckFormat.Constructed.getDeckConformanceProblem(deck);
        if (base != null) {
            return base;
        }
        if ("constructed".equalsIgnoreCase(format)) {
            return null;
        }
        final GameFormat f = FModel.getFormats().getFormat(format);
        if (f == null) {
            return "Formato desconhecido no Forge: " + format;
        }
        return f.getDeckConformanceProblem(deck);
    }

    /* ------------------------------------------------------------------ HTTP */

    private static HttpServer startServer(final int port, final String secret, final DuelSession session) throws IOException {
        final HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 64);
        server.setExecutor(Executors.newFixedThreadPool(8, r -> {
            final Thread t = new Thread(r, "duel-http");
            t.setDaemon(true);
            return t;
        }));
        final byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", ex -> {
            try {
                handle(ex, secretBytes, session);
            } catch (Throwable t) {
                send(ex, 500, err("internal", "erro interno"));
            } finally {
                ex.close();
            }
        });
        server.start();
        return server;
    }

    private static void handle(final HttpExchange ex, final byte[] secret, final DuelSession session) throws IOException {
        final String path = ex.getRequestURI().getPath();
        if ("/health".equals(path)) {
            final JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("status", session.status());
            send(ex, 200, o);
            return;
        }
        final String given = ex.getRequestHeaders().getFirst("x-duel-secret");
        if (given == null || !MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), secret)) {
            send(ex, 401, err("unauthorized", "segredo inválido"));
            return;
        }
        final String method = ex.getRequestMethod();
        if ("GET".equals(method) && "/state".equals(path)) {
            session.touchClient();
            final Map<String, String> q = query(ex.getRequestURI());
            final long rev = parseLong(q.get("rev"), 0);
            final long after = parseLong(q.get("after"), 0);
            final long wait = parseLong(q.get("wait"), 0);
            send(ex, 200, session.waitState(rev, after, wait));
            return;
        }
        if ("POST".equals(method) && "/command".equals(path)) {
            final JsonObject body = body(ex);
            if (body == null) {
                send(ex, 400, err("bad_request", "JSON inválido ou grande demais"));
                return;
            }
            send(ex, 200, session.command(body));
            return;
        }
        if ("POST".equals(method) && "/close".equals(path)) {
            session.touchClient();
            if (!session.isFinal()) {
                send(ex, 409, err("running", "A partida ainda está em andamento: conceda antes de encerrar."));
                return;
            }
            closed = true;
            final JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            send(ex, 200, o);
            return;
        }
        send(ex, 404, err("not_found", "rota desconhecida"));
    }

    private static JsonObject body(final HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
                if (buf.size() > MAX_BODY) {
                    return null;
                }
            }
            return JsonParser.parseString(buf.toString(StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void send(final HttpExchange ex, final int code, final JsonObject o) throws IOException {
        final byte[] bytes = GSON.toJson(o).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("content-type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("cache-control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static JsonObject err(final String code, final String message) {
        final JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("code", code);
        o.addProperty("message", message);
        return o;
    }

    private static Map<String, String> query(final URI uri) {
        final Map<String, String> m = new HashMap<>();
        final String q = uri.getRawQuery();
        if (q == null) {
            return m;
        }
        for (String part : q.split("&")) {
            final int i = part.indexOf('=');
            if (i > 0) {
                m.put(part.substring(0, i), java.net.URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    private static long parseLong(final String s, final long def) {
        try {
            return s == null ? def : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String str(final JsonObject o, final String k, final String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    private static int num(final JsonObject o, final String k, final int def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : def;
    }
}
