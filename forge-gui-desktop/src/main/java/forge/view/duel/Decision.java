package forge.view.duel;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;

/**
 * Pergunta bloqueante do motor ao jogador humano (diálogo). O jogo fica parado em {@link #await()} até a
 * resposta validada chegar pelo protocolo; não existe resposta padrão automática.
 */
final class Decision {
    final String id;
    final String kind;
    final JsonObject payload;
    private final CompletableFuture<JsonObject> answer = new CompletableFuture<>();

    Decision(final String id, final String kind, final JsonObject payload) {
        this.id = id;
        this.kind = kind;
        this.payload = payload;
    }

    void complete(final JsonObject value) {
        answer.complete(value);
    }

    void abort(final RuntimeException why) {
        answer.completeExceptionally(why);
    }

    boolean isDone() {
        return answer.isDone();
    }

    JsonObject await() {
        try {
            return answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DuelAborted("interrompido");
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw new DuelAborted(String.valueOf(e.getCause()));
        }
    }

    /** Encerra a thread do jogo quando a sessão termina com uma pergunta pendente. */
    static final class DuelAborted extends RuntimeException {
        DuelAborted(final String msg) {
            super(msg);
        }
    }
}
