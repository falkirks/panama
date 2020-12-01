package spade.reporter;

import spade.core.AbstractEdge;
import spade.core.AbstractReporter;

import java.util.logging.Level;
import java.util.logging.Logger;

public class StitchReporter extends AbstractReporter {
    private static boolean isLaunched = false;
    private static final Logger logger = Logger.getLogger(StitchReporter.class.getName());

    private static StitchReporter instance;

    @Override
    public synchronized boolean launch(String arguments) {
        instance = this;
        isLaunched = true;
        return true;
    }

    public void reportStitch(AbstractEdge e){
        logger.log(Level.INFO, "StitchReporter received a stitch");
        putEdge(e);
    }

    @Override
    public boolean shutdown() {
        isLaunched = false;
        instance = null;
        return true;
    }

    public static boolean isLaunched() {
        return isLaunched;
    }

    public static synchronized StitchReporter getInstance() {
        return instance;
    }
}
