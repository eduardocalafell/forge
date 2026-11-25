package forge.view.duel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Validação estrutural das respostas (formato, faixas, contagens). O significado de cada resposta
 * continua sendo do Forge: a resposta vira exatamente o retorno que o diálogo do Forge esperaria.
 * Resposta inválida é rejeitada; nunca é corrigida ou completada.
 */
final class DecisionRules {
    private DecisionRules() { }

    /** @return null quando a resposta é aceitável; senão a mensagem de erro. */
    static String validate(final Decision d, final JsonObject a) {
        final JsonObject p = d.payload;
        final int n = p.has("options") ? p.getAsJsonArray("options").size() : 0;
        switch (d.kind) {
            case "reveal":
                return null;
            case "choose":
            case "entities": {
                final List<Integer> idx = indices(a, "indices", n);
                if (idx == null) {
                    return "Seleção inválida.";
                }
                final int min = p.get("min").getAsInt();
                final int max = p.get("max").getAsInt();
                if (idx.size() < min || idx.size() > max) {
                    return "Escolha entre " + min + " e " + max + ".";
                }
                return null;
            }
            case "order": {
                final List<Integer> idx = indices(a, "indices", n);
                if (idx == null) {
                    return "Ordem inválida.";
                }
                final int remaining = n - idx.size();
                final int rMin = p.get("remainingMin").getAsInt();
                final int rMax = p.get("remainingMax").getAsInt();
                if (remaining < rMin || remaining > rMax) {
                    return "Quantidade escolhida fora do permitido.";
                }
                return null;
            }
            case "confirm":
            case "option":
            case "ability":
            case "entity": {
                if (a.has("cancel") && a.get("cancel").getAsBoolean()) {
                    return p.has("optional") && p.get("optional").getAsBoolean() ? null : "Esta pergunta não pode ser cancelada.";
                }
                final Integer i = integer(a, "index");
                if (i == null || i < 0 || i >= n) {
                    return "Opção inválida.";
                }
                if ("ability".equals(d.kind)) {
                    final JsonObject opt = p.getAsJsonArray("options").get(i).getAsJsonObject();
                    if (opt.has("playable") && !opt.get("playable").getAsBoolean()) {
                        return "Essa habilidade não pode ser usada agora.";
                    }
                }
                return null;
            }
            case "number": {
                final Integer v = integer(a, "value");
                if (v == null) {
                    return "Número inválido.";
                }
                if (v < p.get("min").getAsInt() || v > p.get("max").getAsInt()) {
                    return "Fora do intervalo permitido.";
                }
                return null;
            }
            case "text": {
                if (!a.has("text") || !a.get("text").isJsonPrimitive()) {
                    return "Texto obrigatório.";
                }
                final String t = a.get("text").getAsString();
                if (t.length() > 200) {
                    return "Texto longo demais.";
                }
                if (p.has("numeric") && p.get("numeric").getAsBoolean() && !t.trim().matches("-?\\d{1,9}")) {
                    return "Digite um número.";
                }
                return null;
            }
            case "distribute":
                return distribute(p, a, n);
            case "damage":
                return damage(p, a);
            case "piles":
                return piles(a, n);
            default:
                return "Tipo de pergunta desconhecido.";
        }
    }

    private static String distribute(final JsonObject p, final JsonObject a, final int n) {
        final List<Integer> amounts = amounts(a, n);
        if (amounts == null) {
            return "Distribuição inválida.";
        }
        final int total = p.get("amount").getAsInt();
        final int floor = p.has("atLeastOne") && p.get("atLeastOne").getAsBoolean() ? 1 : 0;
        int sum = 0;
        for (int v : amounts) {
            if (v < floor) {
                return floor == 1 ? "Cada alvo precisa receber pelo menos 1." : "Valores negativos não são permitidos.";
            }
            sum += v;
        }
        return sum == total ? null : "Distribua exatamente " + total + " (faltam " + (total - sum) + ").";
    }

    /**
     * Mesmas regras do diálogo do Forge (VAssignCombatDamage): soma exata; sem "ignorar ordem",
     * um bloqueador só recebe dano se os anteriores já têm dano letal, e o jogador/planeswalker
     * defensor só recebe excedente (atropelar) com todos os bloqueadores com dano letal.
     */
    private static String damage(final JsonObject p, final JsonObject a) {
        if (a.has("skip") && a.get("skip").getAsBoolean()) {
            return p.get("maySkip").getAsBoolean() ? null : "Não é possível pular esta atribuição.";
        }
        final JsonArray targets = p.getAsJsonArray("targets");
        final List<Integer> amounts = amounts(a, targets.size());
        if (amounts == null) {
            return "Atribuição inválida.";
        }
        int sum = 0;
        for (int v : amounts) {
            if (v < 0) {
                return "Valores negativos não são permitidos.";
            }
            sum += v;
        }
        final int total = p.get("damage").getAsInt();
        if (sum != total) {
            return "Atribua exatamente " + total + " de dano (faltam " + (total - sum) + ").";
        }
        final boolean override = p.get("overrideOrder").getAsBoolean();
        final boolean divide = p.get("divide").getAsBoolean();
        if (override && divide) {
            return null;
        }
        boolean aliveBefore = false;
        boolean allLethal = true;
        for (int i = 0; i < targets.size(); i++) {
            final JsonObject t = targets.get(i).getAsJsonObject();
            final boolean isDefender = t.get("defender").getAsBoolean();
            final int lethal = t.get("lethal").getAsInt();
            final int v = amounts.get(i);
            if (isDefender) {
                if (v > 0 && !allLethal) {
                    return "Só o excedente vai para o defensor: cada bloqueador precisa de dano letal antes.";
                }
                continue;
            }
            if (!override && aliveBefore && v > 0) {
                return "Atribua dano letal ao bloqueador anterior antes de passar para o próximo.";
            }
            if (v < lethal) {
                aliveBefore = true;
                allLethal = false;
            }
        }
        return null;
    }

    private static String piles(final JsonObject a, final int n) {
        final List<Integer> top = indices(a, "top", n);
        final List<Integer> second = indices(a, "second", n);
        if (top == null || second == null) {
            return "Pilhas inválidas.";
        }
        final Set<Integer> all = new HashSet<>(top);
        for (int i : second) {
            if (!all.add(i)) {
                return "Uma carta está nas duas pilhas.";
            }
        }
        return all.size() == n ? null : "Coloque todas as cartas em uma das pilhas.";
    }

    /** Índices distintos dentro de [0, n). */
    static List<Integer> indices(final JsonObject a, final String key, final int n) {
        if (!a.has(key) || !a.get(key).isJsonArray()) {
            return null;
        }
        final List<Integer> out = new ArrayList<>();
        final Set<Integer> seen = new HashSet<>();
        for (JsonElement el : a.getAsJsonArray(key)) {
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            final int i = el.getAsInt();
            if (i < 0 || i >= n || !seen.add(i)) {
                return null;
            }
            out.add(i);
        }
        return out;
    }

    static List<Integer> amounts(final JsonObject a, final int n) {
        if (!a.has("amounts") || !a.get("amounts").isJsonArray()) {
            return null;
        }
        final JsonArray arr = a.getAsJsonArray("amounts");
        if (arr.size() != n) {
            return null;
        }
        final List<Integer> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
                return null;
            }
            out.add(el.getAsInt());
        }
        return out;
    }

    static Integer integer(final JsonObject a, final String key) {
        if (!a.has(key) || !a.get(key).isJsonPrimitive() || !a.get(key).getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return a.get(key).getAsInt();
    }
}
