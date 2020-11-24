package spade.reporter;

import spade.core.AbstractReporter;

public class StitchReporter extends AbstractReporter {
    private static boolean isLaunched = false;

    private static StitchReporter instance;

    @Override
    public boolean launch(String arguments) {
        instance = this;
        isLaunched = true;
        return true;
    }

    @Override
    public boolean shutdown() {
        instance = null;
        isLaunched = false;
        return true;
    }

    public static boolean isLaunched() {
        return isLaunched;
    }

    public static StitchReporter getInstance() {
        return instance;
    }
}
