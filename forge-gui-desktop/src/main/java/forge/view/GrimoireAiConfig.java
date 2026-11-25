package forge.view;

import com.google.gson.JsonObject;

import forge.ai.PlayerControllerAi;
import forge.ai.simulation.FlatMonteCarloStats;
import forge.game.Game;
import forge.game.player.Player;

/** One immutable configuration per JVM/job, shared by simulations and web duels. */
public final class GrimoireAiConfig {
    public static final String VERSION = "grimoire-ai/4";
    private final String mode;
    private final String strands;

    public GrimoireAiConfig(final JsonObject job) {
        mode = job.has("aiSearch") ? job.get("aiSearch").getAsString() : "bounded";
        if (!"bounded".equals(mode) && !"heuristic".equals(mode)) {
            throw new IllegalArgumentException("aiSearch must be bounded or heuristic");
        }
        final JsonObject overrides = job.has("aiOverrides") ? job.getAsJsonObject("aiOverrides") : new JsonObject();
        strands = overrides.has("prismaticStrands") ? overrides.get("prismaticStrands").getAsString() : "v2";
        if (!"baseline".equals(strands) && !"v1".equals(strands) && !"v2".equals(strands)) {
            throw new IllegalArgumentException("prismaticStrands must be baseline, v1 or v2");
        }
        System.setProperty("grimoire.ai.prismaticStrands", strands);
    }

    public void apply(final Game game) {
        final boolean enabled = "bounded".equals(mode);
        game.AI_FLAT_MONTE_CARLO = enabled;
        game.AI_FLAT_MONTE_CARLO_MAX = false;
        game.AI_FLAT_MONTE_CARLO_LITE = false;
        for (Player player : game.getPlayers()) {
            if (player.getController() instanceof PlayerControllerAi controller) {
                controller.getAi().setBoundedFlatMonteCarlo(enabled);
                controller.getAi().setFlatMonteCarloMaxOverride(false);
                controller.getAi().setFlatMonteCarloAttackOverride(false);
            }
        }
        FlatMonteCarloStats.reset();
    }

    public String version() { return VERSION + "; prismatic-strands/" + strands + "; search/" + mode + "/1"; }

    public JsonObject metadata() {
        final boolean enabled = "bounded".equals(mode);
        final JsonObject config = new JsonObject();
        config.addProperty("mode", mode);
        config.addProperty("version", 1);
        config.addProperty("enabled", enabled);
        config.addProperty("maximized", false);
        config.addProperty("decisionBudgetMs", enabled ? 350 : 0);
        config.addProperty("turnBudgetMs", enabled ? 1400 : 0);
        config.addProperty("maxCandidates", enabled ? 4 : 0);
        config.addProperty("rolloutsPerCandidate", enabled ? 3 : 0);
        config.addProperty("maxPlayoutPlays", enabled ? 4 : 0);
        config.addProperty("comboPlayoutPlays", enabled ? 8 : 0);
        config.addProperty("softDeadlines", true);
        return config;
    }

    public static JsonObject statistics(final Game game) {
        final JsonObject stats = new JsonObject();
        stats.addProperty("enabled", game.getAIFlatMonteCarlo());
        stats.addProperty("decisions", FlatMonteCarloStats.getDecisions());
        stats.addProperty("rollouts", FlatMonteCarloStats.getRollouts());
        stats.addProperty("failures", FlatMonteCarloStats.getFailureCount());
        stats.addProperty("p95Ms", FlatMonteCarloStats.getPercentileMillis(95));
        int fallback = 0;
        int active = 0;
        for (Player player : game.getPlayers()) {
            if (player.getController() instanceof PlayerControllerAi controller) {
                if (controller.getAi().isFlatMonteCarloTripped()) fallback++;
                if (controller.getAi().flatMonteCarloActive()) active++;
            }
        }
        stats.addProperty("activePlayers", active);
        stats.addProperty("fallbackPlayers", fallback);
        return stats;
    }
}
