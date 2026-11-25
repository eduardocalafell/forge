package forge.view.duel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.card.Card;
import forge.game.card.CardView;
import forge.game.keyword.Keyword;
import forge.game.phase.PhaseType;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.game.spellability.SpellAbilityView;
import forge.game.spellability.StackItemView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;
import forge.gamemodes.match.Selection;
import forge.gui.interfaces.IGuiGame;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.player.PlayerControllerHuman;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

/**
 * Interface do Forge para o navegador. Cada diálogo do Forge vira uma pergunta pendente no
 * protocolo; os inputs (prioridade, ataque, bloqueio, alvos, mana) ficam visíveis no estado e são
 * respondidos pelos mesmos métodos que a interface desktop usa ({@link PlayerControllerHuman}).
 */
public final class WebGuiGame extends AbstractGuiGame {
    private static final String DIVIDE_KEYWORD = "You may assign CARDNAME's combat damage divided as you choose among "
            + "defending player and/or any number of creatures they control.";

    private final DuelSession s;
    private PlayerControllerHuman controller;

    public WebGuiGame(final DuelSession session) {
        this.s = session;
    }

    public void setHumanController(final PlayerControllerHuman c) {
        controller = c;
    }

    /* ------------------------------------------------------------ estado visual → revisão */

    @Override
    protected void updateCurrentPlayer(final PlayerView player) {
        s.bump();
    }

    @Override
    public boolean isUiSetToSkipPhase(final PlayerView playerTurn, final PhaseType phase) {
        return s.isUiSetToSkipPhase(playerTurn, phase);
    }

    @Override
    public void openView(final TrackableCollection<PlayerView> myPlayers) {
        s.bump();
    }

    @Override public void showCombat() { s.bump(); }
    @Override public void finishGame() { s.bump(); }

    @Override
    public void showPromptMessage(final PlayerView playerView, final String message, final CardView card) {
        s.onPrompt(playerView, message, card);
    }

    @Override
    public void showPromptMessageNoCancel(final PlayerView playerView, final String message) {
        s.onPrompt(playerView, message, null);
    }

    @Override
    public void updateButtons(final PlayerView owner, final String label1, final String label2, final boolean enable1, final boolean enable2, final boolean focus1) {
        s.onButtons(owner, label1, label2, enable1, enable2);
    }

    @Override
    public void setSelection(final Selection next) {
        super.setSelection(next);
        s.onSelection(next);
    }

    @Override
    public void flashIncorrectAction() {
        s.onReject("O Forge recusou essa ação.");
    }

    @Override public void alertUser() { s.bump(); }
    @Override public void setPanelSelection(final CardView hostCard) { }
    @Override public void setCard(final CardView card) { }
    @Override public void setPlayerAvatar(final LobbyPlayer player, final IHasIcon ihi) { }

    @Override
    public void message(final String message, final String title) {
        notice(title, message);
    }

    @Override
    public void showErrorDialog(final String message, final String title) {
        notice(title, message);
    }

    private void notice(final String title, final String message) {
        final JsonObject ev = new JsonObject();
        ev.addProperty("type", "notice");
        ev.addProperty("title", Views.safe(title));
        ev.addProperty("text", Views.safe(message));
        s.addEvent(ev);
    }

    /* ------------------------------------------------------------ perguntas */

    private <T> JsonArray options(final List<T> items, final FSerializableFunction<T, String> display) {
        final JsonArray a = new JsonArray();
        for (T t : items) {
            final JsonObject o = new JsonObject();
            String label;
            try {
                label = display != null ? display.apply(t) : null;
            } catch (RuntimeException e) {
                label = null;
            }
            if (t instanceof CardView cv) {
                o.add("card", s.cardRef(cv));
                if (label == null) {
                    label = o.getAsJsonObject("card").has("name") ? o.getAsJsonObject("card").get("name").getAsString() : "Carta oculta";
                }
            } else if (t instanceof PlayerView || t instanceof StackItemView) {
                o.add("entity", s.entityRef(t));
                if (label == null) {
                    label = t instanceof PlayerView pv ? pv.getName() : ((StackItemView) t).getText();
                }
            } else if (t instanceof SpellAbilityView sav) {
                if (sav.getHostCard() != null) {
                    o.add("card", s.cardRef(sav.getHostCard()));
                }
            }
            if (label == null) {
                label = String.valueOf(t);
            }
            o.addProperty("label", Views.safe(label));
            a.add(o);
        }
        return a;
    }

    private static List<Integer> idx(final JsonObject answer, final String key) {
        final List<Integer> out = new ArrayList<>();
        if (answer.has(key)) {
            for (JsonElement el : answer.getAsJsonArray(key)) {
                out.add(el.getAsInt());
            }
        }
        return out;
    }

    private static boolean cancelled(final JsonObject a) {
        return a.has("cancel") && a.get("cancel").getAsBoolean();
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final List<T> choices, final List<T> selected, final FSerializableFunction<T, String> display) {
        if (choices == null || choices.isEmpty()) {
            return new ArrayList<>();
        }
        final JsonObject p = new JsonObject();
        p.addProperty("title", Views.safe(message));
        p.add("options", options(choices, display));
        if (min == -1 && max == -1) {
            s.ask("reveal", p);
            return new ArrayList<>();
        }
        final int lo = Math.max(0, min);
        final int hi = max < 0 ? choices.size() : Math.min(max, choices.size());
        p.addProperty("min", lo);
        p.addProperty("max", hi);
        if (selected != null && !selected.isEmpty()) {
            final JsonArray pre = new JsonArray();
            for (T t : selected) {
                final int i = choices.indexOf(t);
                if (i >= 0) {
                    pre.add(i);
                }
            }
            p.add("preselected", pre);
        }
        final JsonObject a = s.ask("choose", p);
        final List<T> out = new ArrayList<>();
        for (int i : idx(a, "indices")) {
            out.add(choices.get(i));
        }
        return out;
    }

    @Override
    public Integer getInteger(final String message, final int min, final int max, final boolean sortDesc) {
        return number(message, min, max);
    }

    @Override
    public Integer getInteger(final String message, final int min, final int max, final int cutoff) {
        return number(message, min, max);
    }

    private Integer number(final String message, final int min, final int max) {
        if (max <= min) {
            return min;
        }
        final JsonObject p = new JsonObject();
        p.addProperty("title", Views.safe(message));
        p.addProperty("min", min);
        p.addProperty("max", max == Integer.MAX_VALUE ? 999 : max);
        final JsonObject a = s.ask("number", p);
        return a.get("value").getAsInt();
    }

    @Override
    public <T> IGuiGame.OrderResult<T> order(final String title, final String top, final int remainingObjectsMin, final int remainingObjectsMax,
                                             final List<T> sourceChoices, final List<T> destChoices, final CardView referenceCard,
                                             final boolean sideboardingMode, final boolean showRememberCheckbox) {
        if (sideboardingMode) {
            throw s.unsupported("sideboard");
        }
        final List<T> choices = new ArrayList<>(sourceChoices == null ? List.of() : sourceChoices);
        final int sourceCount = choices.size();
        if (destChoices != null) choices.addAll(destChoices);
        if (choices.isEmpty()) return new IGuiGame.OrderResult<>(new ArrayList<>(), false);
        final JsonObject p = new JsonObject();
        p.addProperty("title", Views.safe(title));
        p.addProperty("top", Views.safe(top));
        p.add("options", options(choices, null));
        final JsonArray initialOrder = new JsonArray();
        for (int i = sourceCount; i < choices.size(); i++) initialOrder.add(i);
        p.add("initialOrder", initialOrder);
        final int n = choices.size();
        final int rMin = Math.max(0, remainingObjectsMin);
        final int rMax = remainingObjectsMax < 0 ? n : Math.min(n, remainingObjectsMax);
        p.addProperty("remainingMin", rMin);
        p.addProperty("remainingMax", rMax);
        if (referenceCard != null) {
            p.add("card", s.cardRef(referenceCard));
        }
        final JsonObject a = s.ask("order", p);
        final List<T> out = new ArrayList<>();
        for (int i : idx(a, "indices")) {
            out.add(choices.get(i));
        }
        return new IGuiGame.OrderResult<>(out, false);
    }

    @Override
    public <T> List<T> insertInList(final String title, final T newItem, final List<T> oldItems) {
        final JsonObject p = new JsonObject();
        p.addProperty("title", Views.safe(title));
        final List<String> labels = new ArrayList<>();
        labels.add("No início");
        for (T t : oldItems) {
            labels.add("Depois de " + (t instanceof CardView cv ? s.cardRef(cv).has("name") ? s.cardRef(cv).get("name").getAsString() : "carta" : String.valueOf(t)));
        }
        p.add("options", labelOptions(labels));
        final JsonObject a = s.ask("option", p);
        final int i = a.get("index").getAsInt();
        final List<T> out = new ArrayList<>(oldItems);
        out.add(i, newItem);
        return out;
    }

    private static JsonArray labelOptions(final List<String> labels) {
        final JsonArray a = new JsonArray();
        for (String l : labels) {
            final JsonObject o = new JsonObject();
            o.addProperty("label", Views.safe(l));
            a.add(o);
        }
        return a;
    }

    @Override
    public boolean confirm(final CardView c, final String question, final boolean defaultIsYes, final List<String> options) {
        final List<String> labels = options == null || options.isEmpty() ? List.of("Sim", "Não") : options;
        final JsonObject p = new JsonObject();
        p.addProperty("title", Views.safe(question));
        if (c != null) {
            p.add("card", s.cardRef(c));
        }
        p.add("options", labelOptions(labels));
        p.addProperty("suggested", defaultIsYes ? 0 : 1);
        final JsonObject a = s.ask("confirm", p);
        return a.get("index").getAsInt() == 0;
    }

    @Override
    public boolean showConfirmDialog(final String message, final String title, final String yesButtonText, final String noButtonText, final boolean defaultYes) {
        final JsonObject p = new JsonObject();
        p.addProperty("title", Views.safe(title));
        p.addProperty("message", Views.safe(message));
        p.add("options", labelOptions(List.of(yesButtonText == null ? "Sim" : yesButtonText, noButtonText == null ? "Não" : noButtonText)));
        p.addProperty("suggested", defaultYes ? 0 : 1);
        final JsonObject a = s.ask("confirm", p);
        return a.get("index").getAsInt() == 0;
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon, final List<String> options, final int defaultOption) {
        final JsonObject p = new JsonObject();
        p.addProperty("title", Views.safe(title));
        p.addProperty("message", Views.safe(message));
        p.add("options", labelOptions(options));
        p.addProperty("suggested", defaultOption);
        final JsonObject a = s.ask("option", p);
        return a.get("index").getAsInt();
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon, final String initialInput, final List<String> inputOptions, final boolean isNumeric) {
        final JsonObject p = new JsonObject();
        p.addProperty("title", Views.safe(title));
        p.addProperty("message", Views.safe(message));
        if (inputOptions != null && !inputOptions.isEmpty()) {
            p.add("options", labelOptions(inputOptions));
            p.addProperty("optional", true);
            final JsonObject a = s.ask("option", p);
            return cancelled(a) ? null : inputOptions.get(a.get("index").getAsInt());
        }
        p.addProperty("initial", initialInput == null ? "" : initialInput);
        p.addProperty("numeric", isNumeric);
        final JsonObject a = s.ask("text", p);
        return a.get("text").getAsString().trim();
    }

    @Override
    public SpellAbilityView getAbilityToPlay(final CardView hostCard, final List<SpellAbilityView> abilities, final ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) {
            return null;
        }
        // mesmo comportamento da interface desktop para cliques sem menu: uma única opção é usada direto
        if (abilities.size() == 1 && triggerEvent == null) {
            return abilities.get(0);
        }
        final JsonObject p = new JsonObject();
        p.addProperty("title", "Escolha o que fazer");
        if (hostCard != null) {
            p.add("card", s.cardRef(hostCard));
        }
        final JsonArray opts = new JsonArray();
        for (SpellAbilityView sa : abilities) {
            final JsonObject o = new JsonObject();
            o.addProperty("label", Views.safe(sa.getDescription()));
            o.addProperty("playable", sa.canPlay());
            opts.add(o);
        }
        p.add("options", opts);
        p.addProperty("optional", true);
        final JsonObject a = s.ask("ability", p);
        return cancelled(a) ? null : abilities.get(a.get("index").getAsInt());
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(final CardView attacker, final List<CardView> blockers, final int damage,
                                                     final GameEntityView defender, final boolean overrideOrder, final boolean maySkip) {
        final Card atk = controller == null ? null : controller.getCard(attacker);
        final boolean deathtouch = atk != null && atk.hasKeyword(Keyword.DEATHTOUCH);
        final boolean divide = atk != null && atk.hasKeyword(DIVIDE_KEYWORD);
        final JsonObject p = new JsonObject();
        p.addProperty("title", "Atribua o dano de combate");
        p.add("attacker", s.cardRef(attacker));
        p.addProperty("damage", damage);
        p.addProperty("overrideOrder", overrideOrder);
        p.addProperty("divide", divide);
        p.addProperty("maySkip", maySkip);
        p.addProperty("deathtouch", deathtouch);
        final JsonArray targets = new JsonArray();
        for (CardView b : blockers) {
            final JsonObject t = new JsonObject();
            t.add("entity", s.entityRef(b));
            int lethal = Math.max(0, b.getLethalDamage());
            if (b.getCurrentState().isPlaneswalker()) {
                lethal = parse(b.getCurrentState().getLoyalty());
            } else if (deathtouch) {
                lethal = Math.min(lethal, 1);
            }
            t.addProperty("lethal", lethal);
            t.addProperty("defender", false);
            targets.add(t);
        }
        if (defender != null) {
            final JsonObject t = new JsonObject();
            t.add("entity", s.entityRef(defender));
            t.addProperty("lethal", defender instanceof PlayerView pv ? pv.getLife() : defender instanceof CardView cv ? parse(cv.getCurrentState().getLoyalty()) : 0);
            t.addProperty("defender", true);
            targets.add(t);
        }
        p.add("targets", targets);
        final JsonObject a = s.ask("damage", p);
        if (a.has("skip") && a.get("skip").getAsBoolean()) {
            return null;
        }
        final JsonArray amounts = a.getAsJsonArray("amounts");
        final Map<CardView, Integer> out = new HashMap<>();
        for (int i = 0; i < blockers.size(); i++) {
            out.put(blockers.get(i), amounts.get(i).getAsInt());
        }
        if (defender != null) {
            out.put(null, amounts.get(blockers.size()).getAsInt());
        }
        return out;
    }

    private static int parse(final String s) {
        try {
            return Integer.parseInt(s);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(final CardView effectSource, final Map<Object, Integer> target, final int amount,
                                                    final boolean atLeastOne, final String amountLabel) {
        final List<Object> keys = new ArrayList<>(target.keySet());
        final JsonObject p = new JsonObject();
        p.addProperty("title", "Distribua " + amount + (amountLabel == null ? "" : " (" + amountLabel + ")"));
        if (effectSource != null) {
            p.add("card", s.cardRef(effectSource));
        }
        p.add("options", options(keys, null));
        p.addProperty("amount", amount);
        p.addProperty("atLeastOne", atLeastOne);
        final JsonObject a = s.ask("distribute", p);
        final JsonArray amounts = a.getAsJsonArray("amounts");
        final Map<Object, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            out.put(keys.get(i), amounts.get(i).getAsInt());
        }
        return out;
    }

    @Override
    public List<PaperCard> sideboard(final CardPool sideboard, final CardPool main, final String message) {
        throw s.unsupported("sideboard");
    }

    private void addRevealed(final JsonObject p, final DelayedReveal reveal) {
        if (reveal == null || reveal.getCards() == null) {
            return;
        }
        final JsonArray a = new JsonArray();
        for (CardView c : reveal.getCards()) {
            a.add(s.cardRef(c));
        }
        p.add("revealed", a);
        p.addProperty("revealTitle", Views.safe(reveal.getMessagePrefix()));
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(final String title, final List<? extends GameEntityView> optionList,
                                                      final DelayedReveal delayedReveal, final boolean isOptional) {
        if (optionList == null || optionList.isEmpty()) {
            if (delayedReveal != null) {
                final JsonObject p = new JsonObject();
                p.addProperty("title", Views.safe(delayedReveal.getMessagePrefix()));
                p.add("options", options(new ArrayList<>(delayedReveal.getCards()), null));
                s.ask("reveal", p);
            }
            return null;
        }
        final JsonObject p = new JsonObject();
        p.addProperty("title", Views.safe(title));
        p.add("options", options(new ArrayList<GameEntityView>(optionList), null));
        p.addProperty("optional", isOptional);
        addRevealed(p, delayedReveal);
        final JsonObject a = s.ask("entity", p);
        return cancelled(a) ? null : optionList.get(a.get("index").getAsInt());
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(final String title, final List<? extends GameEntityView> optionList, final int min, final int max,
                                                        final DelayedReveal delayedReveal) {
        if (optionList == null || optionList.isEmpty()) {
            return Collections.emptyList();
        }
        final JsonObject p = new JsonObject();
        p.addProperty("title", Views.safe(title));
        p.add("options", options(new ArrayList<GameEntityView>(optionList), null));
        p.addProperty("min", Math.max(0, min));
        p.addProperty("max", max < 0 ? optionList.size() : Math.min(max, optionList.size()));
        addRevealed(p, delayedReveal);
        final JsonObject a = s.ask("entities", p);
        final List<GameEntityView> out = new ArrayList<>();
        for (int i : idx(a, "indices")) {
            out.add(optionList.get(i));
        }
        return out;
    }

    @Override
    public List<CardView> manipulateCardList(final String title, final Iterable<CardView> cards, final Iterable<CardView> manipulable, final ZoneType secondPile) {
        final List<CardView> all = new ArrayList<>();
        cards.forEach(all::add);
        final List<CardView> movable = new ArrayList<>();
        manipulable.forEach(movable::add);
        final JsonObject p = new JsonObject();
        p.addProperty("title", Views.safe(title));
        p.add("options", options(movable, null));
        p.addProperty("secondPile", secondPile == null ? null : secondPile.name());
        final JsonObject a = s.ask("piles", p);
        final List<CardView> out = new ArrayList<>();
        for (int i : idx(a, "top")) {
            out.add(movable.get(i));
        }
        for (CardView c : all) {
            if (!movable.contains(c)) {
                out.add(c);
            }
        }
        for (int i : idx(a, "second")) {
            out.add(movable.get(i));
        }
        return out;
    }
}
