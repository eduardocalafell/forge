# Grimoire AI v5

Both headless adapters (`GrimoireSim` and `GrimoireDuel`) use `GrimoireAiConfig` after `Match.createGame()`. They do not go through `HostedMatch`, which is why the desktop preference never enabled Flat Monte Carlo in the old adapters.

New jobs default to `aiSearch: "bounded"` and `aiOverrides: {"prismaticStrands": "v2"}`. `aiSearch: "heuristic"` retains the quick mode; `prismaticStrands: "v1"` retains the previous dedicated heuristic and `"baseline"` retains the original Forge behavior. One process owns one job/configuration, including the Strands system property. Do not multiplex different configurations in one JVM.

Bounded search explicitly enables the game flag and disables MAX/LITE. Per-controller ceilings are 350 ms per decision, 1400 ms per turn, four candidates, three rollouts per candidate, four playout plays (eight for combo), one extra round, one target variant and two discovered candidates. Opponent lookahead, attack search and broad discovery are off. Profiles can reduce these ceilings. These are cooperative soft deadlines: an in-flight rollout may finish after the budget. Existing circuit breakers and whole-game/supervisor timeouts remain active. Game copies do not inherit the enabling flag, preventing nested search. No new information about hidden cards is exposed; the existing hidden-information shuffler remains in use.

Validation/session metadata includes the full AI version and search configuration. Each simulation result and duel state exposes actual decision/rollout/failure counts, latency p95, active search controllers and circuit-breaker fallbacks. A seed does not guarantee identical Monte Carlo results under different CPU load. Keep snapshots and configurations fixed when comparing runs.

Strands v2 skips its own resolving stack entry when evaluating the pending threat. Combat evaluation compares the same remaining damage steps with and without each color: trample, first/double strike, marked damage, multiblocking, deathtouch, infect/toxic, counter eligibility, indestructibility and lifelink. Creatures are counted once only when the counterfactual actually changes predicted survival. Preventing one's own lethal damage is penalized.

This is a public-state heuristic, not a replacement for Forge's rules. Optional combat assignments, regeneration choices, future tricks and triggered abilities, and complex replacement/counter effects are not an exhaustive rules simulation. The engine still resolves the real game. The regression suite covers the seven defects from the db53339 audit plus existing decisions and configuration/hidden-information controls. The site repository contains the paired calibration manifests and runner; a small calibration sample establishes integration/cost, not competitive playing strength.

## v4: card audit (Ghostly Flicker, Tortured Existence)

Both cards carried `AI:RemoveDeck:All`, which in this fork also removes a card from the heuristic play list (bounded search only saw it as a discovered candidate). Neither card was ever played. v4 removes the flag and fixes the logic:

- **Ghostly Flicker** (`ChangeZoneAi.chooseMultiBlinkTargets`, any blink with two or more targets): first saves an own permanent threatened by the stack; when timing allows, adds enabled ETB triggers and permanents enchanted by an opponent's aura. Remaining targets: tapped lands, untapped lands, then the worst nonland. Never tokens, and never permanents that would lose their own auras, equipment or +1/+1 counters. Without a save or value it does not cast.
- **Tortured Existence** (`AILogic$ TradeUp`, `AIPreference:DiscardCost$Creature`): discards the worst creature in hand and returns the creature the targeting really picks (`chooseCreature`); activates only when the gain is at least 20 evaluation points (no bad trades or churn).

- **Bounded search discovery:** Flat MC offers plays the heuristics refused to the rollouts as "discovered" candidates. For these two cards the audited refusal is final (`ChangeZoneAi.auditedVetoIsFinal`): smoke games showed Tortured Existence churning in bounded mode (Mulldrifter for Merfolk Looter, then Looter for Looter) before this guard.

`GrimoireCardAuditTest` covers 13 scenarios in both search modes plus three discovery checks. Known limit: `evaluateCreature` does not value ETB effects, and the harness checks the chosen targets rather than resolved triggers. Inside Out was not audited.

## v5: deck audit, cards that stayed in hand

AI x AI games with the user's decks (Mono-White Aggro, Mono White Auras, Mono Green Energy padded with Forests) against a red midrange deck, eight games per search mode, count how often each card in hand was played. `GrimoireDeckAuditTest` reproduces the suspects:

- **Shardmage's Rescue** (fixed): the advanced flash-aura logic only answered a spell when the extra toughness saved the creature, so a hexproof aura never answered Lightning Bolt or Doom Blade (`AttachAi.targetingImmunitySave`). A buff aura now answers damage only when that damage would otherwise kill. After the fix, Rescue in hand was played 70% of the time in heuristic games (was 40%), with Searing Spear fizzling in real games.
- **Seam Rip, Sheltered by Ghosts**: cast whenever a target exists and mana allows; copies left in hand came from no target or mana spent on other plays.
- **Rhonas's Monument**: the second copy is legendary; the first waits for three mana.
- **Spellbook Vendor**: pays for the Role when it survives to combat.
