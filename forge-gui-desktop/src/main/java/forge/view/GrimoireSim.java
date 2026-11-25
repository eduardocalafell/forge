package forge.view;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import forge.StaticData;
import forge.ai.AiProfileUtil;
import forge.ai.PrismaticStrandsAi;
import forge.localinstance.properties.ForgePreferences;
import forge.card.CardDb;
import forge.card.CardRules;
import forge.card.ICardFace;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.Game;
import forge.game.GameLogEntry;
import forge.game.GameOutcome;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.BuildInfo;
import forge.util.MyRandom;

/**
 * Adaptador de simulação do Grimoire.
 *
 * <pre>
 *   forge grimoire validate &lt;job.json&gt;
 *   forge grimoire run &lt;job.json&gt; [--logs &lt;dir&gt;]
 * </pre>
 *
 * Diferenças em relação ao modo {@code sim}:
 * <ul>
 *   <li>Cada carta é resolvida e validada aqui; o deck é montado só com cartas resolvidas, sem o
 *       carregamento tolerante do {@code CardPool} (troca Alchemy → não Alchemy, carta "não suportada").</li>
 *   <li>Cada jogo é um {@link Match} novo (jogos independentes), com semente própria e primeiro
 *       jogador forçado via {@link GameRules#setForcedStartingPlayer(int)}.</li>
 *   <li>Timeout é um resultado próprio ({@code timeout}), nunca empate. Depois de um timeout a JVM
 *       sai com {@link #EXIT_RESTART}: a thread do jogo pode continuar viva, então o supervisor
 *       retoma os jogos restantes num processo novo.</li>
 *   <li>Saída em NDJSON no stdout (uma linha por evento); o restante do Forge escreve no stderr.</li>
 * </ul>
 */
public final class GrimoireSim {
    public static final int EXIT_OK = 0;
    public static final int EXIT_BAD_INPUT = 2;
    public static final int EXIT_RESTART = 3;
    public static final int EXIT_INVALID_DECKS = 4;

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
    /** Versão das heurísticas de IA do Grimoire neste fork. */
    public static final String AI_VERSION = GrimoireAiConfig.VERSION;
    private static PrintStream out;

    private GrimoireSim() { }

    public static void main(final String[] args) {
        // O stdout fica reservado ao protocolo NDJSON; logs do Forge vão para o stderr.
        out = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);

        if (args.length >= 3 && "duel".equals(args[1])) {
            GrimoireDuel.main(args, out);
            return;
        }
        if (args.length < 3 || !("validate".equals(args[1]) || "run".equals(args[1]))) {
            emit(event("error").add("message", "Uso: grimoire validate|run <job.json> [--logs <dir>]"));
            exit(EXIT_BAD_INPUT);
        }
        final JsonObject job;
        try {
            job = JsonParser.parseString(Files.readString(new File(args[2]).toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            emit(event("error").add("message", "Job inválido: " + e.getMessage()));
            exit(EXIT_BAD_INPUT);
            return;
        }
        File logDir = null;
        for (int i = 3; i < args.length - 1; i++) {
            if ("--logs".equals(args[i])) {
                logDir = new File(args[i + 1]);
                logDir.mkdirs();
            }
        }

        // Nomes canônicos (inglês) no log e nos eventos: a interface traduz a estrutura, não os nomes das cartas.
        FModel.initialize(null, prefs -> {
            prefs.setPref(ForgePreferences.FPref.UI_LANGUAGE, "en-US");
            return null;
        });
        final String engine = BuildInfo.getVersionString();
        final JsonObject build = readBuildInfo();
        final String engineCommit = build.has("engineCommit") ? build.get("engineCommit").getAsString() : null;
        // Variante da IA para cartas com heurística própria (baseline = lógica original do Forge).
        final GrimoireAiConfig aiConfig = new GrimoireAiConfig(job);
        final String aiVersion = aiConfig.version();
        final String cardDb = "forge-res@" + (engineCommit == null ? "?" : engineCommit.substring(0, Math.min(12, engineCommit.length())))
                + " (" + StaticData.instance().getCommonCards().getUniqueCards().size() + " cartas)";
        final boolean commander = "commander".equalsIgnoreCase(str(job, "format", "constructed"));

        final Map<String, ResolvedDeck> decks = new LinkedHashMap<>();
        final Ev report = event("validation").add("engine", engine).add("engineCommit", engineCommit)
                .add("aiVersion", aiVersion).add("aiSearch", aiConfig.metadata()).add("cardDb", cardDb).add("eventsSchema", GrimoireEventRecorder.SCHEMA_VERSION);
        boolean ok = true;
        for (String side : new String[] {"A", "B"}) {
            final JsonObject spec = job.getAsJsonObject("decks").getAsJsonObject(side);
            final ResolvedDeck rd = resolveDeck(side, spec, commander);
            decks.put(side, rd);
            report.add(side, rd.report);
            ok &= rd.ok;
        }
        report.add("ok", ok);
        emit(report);

        if ("validate".equals(args[1])) {
            exit(ok ? EXIT_OK : EXIT_INVALID_DECKS);
        }
        if (!ok) {
            emit(event("aborted").add("reason", "invalid_decks"));
            exit(EXIT_INVALID_DECKS);
        }

        final int timeoutSec = Math.max(5, num(job, "timeoutSec", 120));
        final int maxLogLines = num(job, "maxLogLines", 5000);
        String profile = str(job, "aiProfile", "");
        if (!profile.isEmpty() && !AiProfileUtil.getProfilesDisplayList().contains(profile)) {
            emit(event("error").add("message", "Perfil de IA desconhecido: " + profile));
            exit(EXIT_BAD_INPUT);
        }

        final JsonArray games = job.getAsJsonArray("games");
        for (JsonElement el : games) {
            final JsonObject g = el.getAsJsonObject();
            final String id = g.get("id").getAsString();
            // A semente chega como string (ou número, em jobs antigos); Long.parseLong preserva os 64 bits.
            final long seed = Long.parseLong(g.get("seed").getAsString().trim());
            final String first = g.has("first") ? g.get("first").getAsString() : "A";
            final boolean timedOut = runGame(id, seed, first, decks.get("A"), decks.get("B"), commander, profile, timeoutSec, logDir, maxLogLines, engine, aiConfig);
            if (timedOut) {
                emit(event("restart").add("after", id).add("reason", "timeout"));
                exit(EXIT_RESTART);
            }
        }
        emit(event("done"));
        exit(EXIT_OK);
    }

    /* ------------------------------------------------------------------ jogos */

    private static boolean runGame(final String id, final long seed, final String first, final ResolvedDeck a, final ResolvedDeck b,
                                   final boolean commander, final String profile, final int timeoutSec, final File logDir,
                                   final int maxLogLines, final String engine, final GrimoireAiConfig aiConfig) {
        MyRandom.setRandom(new Random(seed));
        final GameType type = commander ? GameType.Commander : GameType.Constructed;
        final GameRules rules = new GameRules(type);
        rules.setAppliedVariants(EnumSet.of(type));
        rules.setGamesPerMatch(1);
        rules.setSimTimeout(timeoutSec);
        rules.setForcedStartingPlayer("B".equals(first) ? 1 : 0);

        final RegisteredPlayer rpA = register(a, "A", commander, profile, 0);
        final RegisteredPlayer rpB = register(b, "B", commander, profile, 1);
        final List<RegisteredPlayer> players = new ArrayList<>();
        players.add(rpA);
        players.add(rpB);
        final Match match = new Match(rules, players, "Grimoire " + id);
        final Game game = match.createGame();
        aiConfig.apply(game);
        game.setNoGUIUser();
        final GrimoireEventRecorder recorder = new GrimoireEventRecorder(game, Math.max(1000, maxLogLines * 4));
        game.subscribeToEvents(recorder);
        final List<java.util.Map<String, Object>> trace = new ArrayList<>();
        PrismaticStrandsAi.traceSink = m -> {
            if (trace.size() < 2000) {
                trace.add(m);
            }
        };

        final long start = System.nanoTime();
        String result;
        String reason = null;
        String error = null;
        try {
            TimeLimitedCodeBlock.runWithTimeout(() -> match.startGame(game), timeoutSec, TimeUnit.SECONDS);
            final GameOutcome o = game.getOutcome();
            if (o == null) {
                result = "error";
                error = "Jogo terminou sem resultado";
            } else if (o.isDraw()) {
                result = "draw";
                reason = String.valueOf(o.getWinCondition());
            } else {
                result = o.isWinner(rpA) ? "A" : o.isWinner(rpB) ? "B" : "error";
                reason = String.valueOf(o.getWinCondition());
            }
        } catch (TimeoutException e) {
            result = "timeout";
        } catch (Throwable t) {
            result = "error";
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        final long ms = (System.nanoTime() - start) / 1_000_000L;

        final Player starter = game.getStartingPlayer();
        final String started = starter == null ? null : starter.getRegisteredPlayer() == rpA ? "A" : "B";
        final int turns = game.getPhaseHandler() == null ? 0 : game.getPhaseHandler().getTurn();

        PrismaticStrandsAi.traceSink = null;
        String logFile = null;
        String eventsFile = null;
        String traceFile = null;
        if (logDir != null) {
            logFile = writeLog(logDir, id, game, maxLogLines);
            eventsFile = writeEvents(logDir, id, recorder, result, seed);
            traceFile = trace.isEmpty() ? null : writeTrace(logDir, id, trace);
        }
        final Ev ev = event("game")
                .add("id", id).add("result", result).add("reason", reason).add("first", started)
                .add("requestedFirst", first).add("turns", turns).add("ms", ms).add("seed", Long.toString(seed))
                .add("aiSearchStats", GrimoireAiConfig.statistics(game))
                .add("engine", engine).add("log", logFile).add("events", eventsFile).add("trace", traceFile).add("error", error);
        emit(ev);
        return "timeout".equals(result);
    }

    private static RegisteredPlayer register(final ResolvedDeck rd, final String side, final boolean commander, final String profile, final int idx) {
        // Cópia independente por jogo: em espelho, A e B nunca compartilham o mesmo objeto Deck.
        final Deck d = rd.build(side + " · " + rd.name);
        final RegisteredPlayer rp = commander ? RegisteredPlayer.forCommander(d) : new RegisteredPlayer(d);
        rp.setPlayer(GamePlayerUtil.createAiPlayer(side, idx, profile));
        return rp;
    }

    /** Eventos estruturados: cabeçalho, eventos em ordem de sequence e rodapé de integridade (contagem e truncamento). */
    private static String writeEvents(final File dir, final String id, final GrimoireEventRecorder rec, final String result, final long seed) {
        final File f = new File(dir, id.replaceAll("[^A-Za-z0-9_-]", "_") + ".events.ndjson");
        try (PrintWriter w = new PrintWriter(new FileWriter(f, StandardCharsets.UTF_8))) {
            final JsonObject head = new JsonObject();
            head.addProperty("type", "header");
            head.addProperty("schemaVersion", GrimoireEventRecorder.SCHEMA_VERSION);
            head.addProperty("game", id);
            head.addProperty("seed", Long.toString(seed));
            head.addProperty("players", "A,B");
            w.println(GSON.toJson(head));
            for (JsonObject o : rec.events()) {
                w.println(GSON.toJson(o));
            }
            final JsonObject end = new JsonObject();
            end.addProperty("type", "end");
            end.addProperty("count", rec.events().size());
            end.addProperty("emitted", rec.total());
            end.addProperty("truncated", rec.truncated());
            end.addProperty("result", result);
            w.println(GSON.toJson(end));
            return f.getName();
        } catch (IOException e) {
            return null;
        }
    }

    /** Trace de decisões da IA (separado dos eventos de regras). */
    private static String writeTrace(final File dir, final String id, final List<java.util.Map<String, Object>> trace) {
        final File f = new File(dir, id.replaceAll("[^A-Za-z0-9_-]", "_") + ".trace.ndjson");
        try (PrintWriter w = new PrintWriter(new FileWriter(f, StandardCharsets.UTF_8))) {
            for (java.util.Map<String, Object> m : trace) {
                w.println(GSON.toJson(m));
            }
            return f.getName();
        } catch (IOException e) {
            return null;
        }
    }

    /** grimoire-build.json (gerado no build do snapshot) com o commit do fork. */
    static JsonObject readBuildInfo() {
        for (String p : new String[] {"../grimoire-build.json", "grimoire-build.json"}) {
            try {
                final File f = new File(p);
                if (f.isFile()) {
                    return JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                }
            } catch (Exception ignored) {
                // segue sem o commit
            }
        }
        return new JsonObject();
    }

    private static String writeLog(final File dir, final String id, final Game game, final int maxLines) {
        final File f = new File(dir, id.replaceAll("[^A-Za-z0-9_-]", "_") + ".log");
        try (PrintWriter w = new PrintWriter(new FileWriter(f, StandardCharsets.UTF_8))) {
            final List<GameLogEntry> log = new ArrayList<>(game.getGameLog().getLogEntries(null));
            Collections.reverse(log);
            int n = 0;
            for (GameLogEntry l : log) {
                if (n++ >= maxLines) {
                    w.println("[log truncado em " + maxLines + " linhas]");
                    break;
                }
                w.println(l);
            }
            return f.getName();
        } catch (IOException e) {
            return null;
        }
    }

    /* --------------------------------------------------------------- validação */

    static final class ResolvedDeck {
        String name;
        boolean ok = true;
        final JsonObject report = new JsonObject();
        final Map<DeckSection, Map<PaperCard, Integer>> sections = new LinkedHashMap<>();

        Deck build(final String label) {
            final Deck d = new Deck(label);
            for (Map.Entry<DeckSection, Map<PaperCard, Integer>> s : sections.entrySet()) {
                for (Map.Entry<PaperCard, Integer> c : s.getValue().entrySet()) {
                    d.getOrCreate(s.getKey()).add(c.getKey(), c.getValue());
                }
            }
            return d;
        }
    }

    static ResolvedDeck resolveDeck(final String side, final JsonObject spec, final boolean commander) {
        final ResolvedDeck rd = new ResolvedDeck();
        rd.name = str(spec, "name", side);
        final JsonArray cards = new JsonArray();
        int mainCount = 0;
        final Object[][] parts = {
                {"main", DeckSection.Main},
                {"sideboard", DeckSection.Sideboard},
                {"commander", DeckSection.Commander},
        };
        for (Object[] part : parts) {
            final String key = (String) part[0];
            final DeckSection section = (DeckSection) part[1];
            if (!spec.has(key)) {
                continue;
            }
            if (section == DeckSection.Commander && !commander) {
                continue;
            }
            for (JsonElement el : spec.getAsJsonArray(key)) {
                final JsonObject c = el.getAsJsonObject();
                final String requested = c.get("name").getAsString();
                final int qty = c.get("qty").getAsInt();
                final JsonObject r = checkCard(requested);
                r.addProperty("qty", qty);
                r.addProperty("section", key);
                cards.add(r);
                if (!"ok".equals(r.get("status").getAsString())) {
                    rd.ok = false;
                    continue;
                }
                final PaperCard pc = StaticData.instance().getCommonCards().getCard(r.get("resolved").getAsString());
                rd.sections.computeIfAbsent(section, s -> new LinkedHashMap<>()).merge(pc, qty, Integer::sum);
                if (section == DeckSection.Main) {
                    mainCount += qty;
                }
            }
        }
        if (mainCount == 0) {
            rd.ok = false;
            rd.report.addProperty("error", "Deck principal vazio");
        }
        rd.report.addProperty("name", rd.name);
        rd.report.addProperty("ok", rd.ok);
        rd.report.addProperty("mainCount", mainCount);
        rd.report.add("cards", cards);
        return rd;
    }

    /**
     * Resolve o nome exatamente. A única normalização aceita é a do nome completo de cartas de
     * duas faces ("Frente // Verso") para o nome da face principal, e só quando a outra face confere:
     * é a mesma carta, com as mesmas regras.
     */
    private static JsonObject checkCard(final String requested) {
        final JsonObject r = new JsonObject();
        r.addProperty("name", requested);
        final CardDb db = StaticData.instance().getCommonCards();
        final String name = requested.trim();
        CardRules rules = db.getRules(name, false);
        String resolved = name;
        if (rules == null && name.contains(" // ")) {
            final int cut = name.indexOf(" // ");
            final String front = name.substring(0, cut);
            final String back = name.substring(cut + 4);
            final CardRules fr = db.getRules(front, false);
            final ICardFace other = fr == null ? null : fr.getOtherPart();
            if (other != null && other.getName().equalsIgnoreCase(back)) {
                rules = fr;
                resolved = front;
                r.addProperty("normalized", "face_name");
            }
        }
        if (rules == null) {
            r.addProperty("status", "unknown");
            r.addProperty("message", "Carta não encontrada nesta versão do motor");
            return r;
        }
        if (rules.isUnsupported()) {
            r.addProperty("status", "unsupported");
            r.addProperty("message", "Carta não implementada nesta versão do motor");
            return r;
        }
        if (db.getCard(resolved) == null) {
            r.addProperty("status", "no_printing");
            r.addProperty("message", "Carta sem impressão disponível no motor");
            return r;
        }
        r.addProperty("status", "ok");
        r.addProperty("resolved", resolved);
        if (rules.getAiHints() != null && rules.getAiHints().getRemAIDecks()) {
            r.addProperty("warning", "ai_weak");
        }
        return r;
    }

    /* ------------------------------------------------------------------ util */

    private static final class Ev {
        final JsonObject o = new JsonObject();
        Ev add(final String k, final Object v) {
            if (v == null) {
                o.add(k, com.google.gson.JsonNull.INSTANCE);
            } else if (v instanceof JsonElement) {
                o.add(k, (JsonElement) v);
            } else if (v instanceof Number) {
                o.addProperty(k, (Number) v);
            } else if (v instanceof Boolean) {
                o.addProperty(k, (Boolean) v);
            } else {
                o.addProperty(k, v.toString());
            }
            return this;
        }
    }

    private static Ev event(final String type) {
        return new Ev().add("type", type);
    }

    private static void emit(final Ev ev) {
        out.println(GSON.toJson(ev.o));
        out.flush();
    }

    private static void exit(final int code) {
        out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(code); // não espera threads de jogo presas
    }

    private static String str(final JsonObject o, final String k, final String def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : def;
    }

    private static int num(final JsonObject o, final String k, final int def) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : def;
    }
}
