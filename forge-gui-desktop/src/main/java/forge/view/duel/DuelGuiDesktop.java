package forge.view.duel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import forge.GuiDesktop;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.util.FSerializableFunction;

/** Alguns efeitos chamam SGuiChoose/GuiBase diretamente, sem passar pelo IGuiGame do jogador. */
public final class DuelGuiDesktop extends GuiDesktop {
    private final WebGuiGame gui;

    public DuelGuiDesktop(final WebGuiGame gui0) { gui = gui0; }

    @Override
    public <T> List<T> order(final String title, final String top, final int min, final int max,
                            final List<T> source, final List<T> destination) {
        return gui.order(title, top, min, max, source, destination, null, false, false).ordered();
    }

    @Override
    public <T> List<T> getChoices(final String message, final int min, final int max, final Collection<T> choices,
                                  final Collection<T> selected, final FSerializableFunction<T, String> display) {
        return gui.getChoices(message, min, max, choices == null ? List.of() : new ArrayList<>(choices),
                selected == null ? null : new ArrayList<>(selected), display);
    }

    @Override
    public int showOptionDialog(final String message, final String title, final FSkinProp icon,
                                final List<String> options, final int defaultOption) {
        return gui.showOptionDialog(message, title, icon, options, defaultOption);
    }

    @Override
    public String showInputDialog(final String message, final String title, final FSkinProp icon,
                                   final String initial, final List<String> options, final boolean numeric) {
        return gui.showInputDialog(message, title, icon, initial, options, numeric);
    }

    @Override
    public PaperCard chooseCard(final String title, final String message, final List<PaperCard> cards) {
        final List<PaperCard> answer = gui.getChoices(message, 1, 1, cards, null, null);
        return answer.isEmpty() ? null : answer.get(0);
    }

    @Override
    public void showBugReportDialog(final String title, final String text, final boolean showExitAppBtn) {
        gui.showErrorDialog(text, title);
    }
}
