package derp.rpi;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import derp.rpi.gmail.GmailNotifier;
import derp.rpi.hardware.*;
import derp.rpi.hardware.StateBuilder.Color;
import derp.rpi.hardware.StateBuilder.Digit;

public class NixieNotify {

    public static class Config {
        public Color startupFlashColor = Color.GREEN;
        public Color shutdownFlashColor = Color.RED;
        public Color heartbeatFlashColor = Color.CYAN;

        public boolean enableHeartbeat = true;

        public int cyclePeriod = 1 * 1000;
        public int updatePeriod = 10 * 1000;
        public int stateIndicationDuration = 500;
        public int heartbeatDuration = 500;
        public int heartbeatPeriod = 5 * 60; // in multiples of CYCLE_PERIOD
    }

    private final BitSet helloTube;
    private final BitSet stillAliveTube;
    private final BitSet byeTube;

    private final boolean heartbeatEnabled;

    private final int cyclePeriod;
    private final int updatePeriod;
    private final int blinkDuration;
    private final int heartbeatDuration;
    private final int heartbeatPeriod;

    private static final int IMMEDIATE = 0;

    private static final Logger logger = LoggerFactory.getLogger(NixieNotify.class);

    private final AtomicBoolean updatesEnabled = new AtomicBoolean(false);
    private final List<Notify> notifies = Lists.newCopyOnWriteArrayList();

    public NixieNotify(Config config) {
        this.helloTube = new StateBuilder().setColor(config.startupFlashColor).bakeBits();
        this.stillAliveTube = new StateBuilder().setColor(config.heartbeatFlashColor).bakeBits();
        this.byeTube = new StateBuilder().setColor(config.shutdownFlashColor).bakeBits();

        this.heartbeatEnabled = config.enableHeartbeat;

        this.blinkDuration = config.stateIndicationDuration;
        this.cyclePeriod = config.cyclePeriod;
        this.heartbeatPeriod = config.heartbeatPeriod;
        this.heartbeatDuration = config.heartbeatDuration;
        this.updatePeriod = config.updatePeriod;
    }

    public static class StateResult {
        public final State nextState;
        public final int delay;

        public StateResult(State nextState, int delay) {
            this.nextState = nextState;
            this.delay = delay;
        }
    }

    public interface State {
        public StateResult execute(NixieControl control);
    }

    public class StateIdle implements State {
        @Override
        public StateResult execute(NixieControl control) {
            if (control.isSwitchOn()) {
                updatesEnabled.set(true);
                control.updateTube(helloTube);
                control.setTubeState(true);
                return new StateResult(new StateWaitForUpdates(), blinkDuration);
            }

            control.setTubeState(false);
            return new StateResult(this, cyclePeriod);
        }
    }

    public class StateHeartbeat implements State {
        @Override
        public StateResult execute(NixieControl control) {
            if (!control.isSwitchOn())
                return new StateResult(new StateOff(), IMMEDIATE);

            control.updateTube(stillAliveTube);
            control.setTubeState(true);
            return new StateResult(new StateWaitForUpdates(), heartbeatDuration);
        }
    }

    public class StateWaitForUpdates implements State {
        private int heartbeatCountdown = heartbeatPeriod;

        @Override
        public StateResult execute(NixieControl control) {
            if (!control.isSwitchOn())
                return new StateResult(new StateOff(), IMMEDIATE);

            final Iterator<Notify> it = notifies.iterator();

            if (it.hasNext())
                return new StateResult(new StateDisplay(it), IMMEDIATE);

            if (heartbeatEnabled && heartbeatCountdown-- <= 0) {
                return new StateResult(new StateHeartbeat(), IMMEDIATE);
            } else {
                control.setTubeState(false);
                return new StateResult(this, cyclePeriod);
            }
        }

    }

    public class StateDisplay implements State {
        private final Iterator<Notify> it;

        public StateDisplay(Iterator<Notify> it) {
            this.it = it;
        }

        @Override
        public StateResult execute(NixieControl control) {
            if (!control.isSwitchOn())
                return new StateResult(new StateOff(), IMMEDIATE);

            if (!it.hasNext())
                return new StateResult(new StateWaitForUpdates(), IMMEDIATE);

            final Notify n = it.next();
            control.updateTube(n.payload);
            control.setTubeState(true);
            return new StateResult(this, cyclePeriod);
        }
    }

    public class StateOff implements State {
        @Override
        public StateResult execute(NixieControl control) {
            updatesEnabled.set(false);
            control.updateTube(byeTube);
            control.setTubeState(true);
            return new StateResult(new StateIdle(), blinkDuration);
        }
    }

    private void startUpdateThread(GmailNotifier.Config gmailConfig) {
        final List<NotifySource> sources = ImmutableList.of(new GmailNotifier(gmailConfig));

        final Thread update = new Thread() {
            @Override
            public void run() {
                while (true) {
                    if (updatesEnabled.get()) {
                        logger.debug("Updating");

                        final List<Notify> newNotifies = Lists.newArrayList();

                        for (NotifySource source : sources)
                            newNotifies.addAll(source.query());

                        notifies.clear();
                        notifies.addAll(newNotifies);
                    } else {
                        logger.debug("Skipping update due to switch state");
                        notifies.clear();
                    }

                    try {
                        Thread.sleep(updatePeriod);
                    } catch (InterruptedException e) {
                        logger.info("Interrupted", e);
                        break;
                    }
                }
            }
        };

        update.setName("update");
        update.setDaemon(true);

        update.start();
    }

    public void displayLoop(NixieControl.Config nixieConfig) {
        logger.info("Initializing GPIO!");
        try (final NixieControl control = new NixieControl(nixieConfig)) {
            logger.info("Entering main loop");
            State state = new StateIdle();
            while (true) {
                logger.debug("State: {}", state.getClass().getName());
                final StateResult r = state.execute(control);
                state = r.nextState;

                if (r.delay > 0) {
                    try {
                        Thread.sleep(r.delay);
                    } catch (InterruptedException e) {
                        logger.warn("Interrupted", e);
                        break;
                    }
                }
            }
        }
    }

    public static void main(String[] args) {
        final ConfigHelper configHelper = new ConfigHelper();
        final MainConfig config = configHelper.readConfig();

        final NixieNotify nixieNotify = new NixieNotify(config.display);
        nixieNotify.startUpdateThread(config.gmail);
        nixieNotify.displayLoop(config.nixieModule);
    }

    public static void nixieTest(NixieControl control) {
        try (Scanner reader = new Scanner(System.in)) {
            final StateBuilder stateBuilder = new StateBuilder();
            while (true) {
                final String line = reader.next().toUpperCase(Locale.ENGLISH);

                if (line.startsWith("C")) {
                    final Color color = Color.valueOf(line.substring(1));
                    stateBuilder.setColor(color);
                } else if (line.startsWith("D")) {
                    if (line.equals("D")) {
                        stateBuilder.setDigit(Optional.empty());
                    } else {
                        final Digit digit = Digit.valueOf(line);
                        stateBuilder.setDigit(Optional.of(digit));
                    }
                } else if (line.startsWith("'")) {
                    boolean val = Boolean.parseBoolean(line.substring(1));
                    stateBuilder.setUpperDot(val);
                } else if (line.startsWith(",")) {
                    boolean val = Boolean.parseBoolean(line.substring(1));
                    stateBuilder.setLowerDot(val);
                } else {
                    System.out.println("Invalid token: " + line);
                }

                control.updateTube(stateBuilder.bakeBits());
            }
        }
    }

}
