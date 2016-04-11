package derp.rpi;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.pi4j.io.gpio.RaspiPin;

import derp.rpi.gmail.GmailNotifier;
import derp.rpi.hardware.*;
import derp.rpi.hardware.StateBuilder.Color;
import derp.rpi.hardware.StateBuilder.Digit;

public class NixieNotify {

    private static final BitSet HELLO_TUBE = new StateBuilder().setColor(Color.GREEN).bakeBits();
    private static final BitSet STILL_ALIVE_TUBE = new StateBuilder().setColor(Color.CYAN).bakeBits();
    private static final BitSet BYE_TUBE = new StateBuilder().setColor(Color.RED).bakeBits();

    private static final int CYCLE_PERIOD = 1 * 1000;
    private static final int UPDATE_PERIOD = 10 * 1000;
    private static final int BLINK_PERIOD = 500;
    private static final int SHORT_BLINK_PERIOD = 500;
    private static final int HEARTBEAT_PERIOD = 5 * 60; // in multiples of CYCLE_PERIOD
    private static final int IMMEDIATE = 0;

    private static final Logger logger = LoggerFactory.getLogger(NixieNotify.class);

    private final AtomicBoolean updatesEnabled = new AtomicBoolean(false);
    private final List<Notify> notifies = Lists.newCopyOnWriteArrayList();

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
                control.updateTube(HELLO_TUBE);
                control.setTubeState(true);
                return new StateResult(new StateWaitForUpdates(), BLINK_PERIOD);
            }

            control.setTubeState(false);
            return new StateResult(this, CYCLE_PERIOD);
        }
    }

    public class StateHeartbeat implements State {
        @Override
        public StateResult execute(NixieControl control) {
            if (!control.isSwitchOn())
                return new StateResult(new StateOff(), IMMEDIATE);

            control.updateTube(STILL_ALIVE_TUBE);
            control.setTubeState(true);
            return new StateResult(new StateWaitForUpdates(), SHORT_BLINK_PERIOD);
        }
    }

    public class StateWaitForUpdates implements State {
        private int heartbeatCountdown = HEARTBEAT_PERIOD;

        @Override
        public StateResult execute(NixieControl control) {
            if (!control.isSwitchOn())
                return new StateResult(new StateOff(), IMMEDIATE);

            final Iterator<Notify> it = notifies.iterator();

            if (it.hasNext())
                return new StateResult(new StateDisplay(it), IMMEDIATE);

            if (heartbeatCountdown-- <= 0) {
                return new StateResult(new StateHeartbeat(), IMMEDIATE);
            } else {
                control.setTubeState(false);
                return new StateResult(this, CYCLE_PERIOD);
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
            return new StateResult(this, CYCLE_PERIOD);
        }
    }

    public class StateOff implements State {
        @Override
        public StateResult execute(NixieControl control) {
            updatesEnabled.set(false);
            control.updateTube(BYE_TUBE);
            control.setTubeState(true);
            return new StateResult(new StateIdle(), BLINK_PERIOD);
        }
    }

    private void startUpdateThread() {
        final List<NotifySource> sources = ImmutableList.of(
                new GmailNotifier()
                );

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
                        Thread.sleep(UPDATE_PERIOD);
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

    public void loop() {
        NixieControl control = null;
        try {
            logger.info("Initializing GPIO!");
            control = new NixieControl(RaspiPin.GPIO_00, RaspiPin.GPIO_01, RaspiPin.GPIO_02, RaspiPin.GPIO_03, RaspiPin.GPIO_04);
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
        } finally {
            if (control != null)
                control.stop();
        }
    }

    public static void main(String[] args) {
        final NixieNotify nixieNotify = new NixieNotify();
        nixieNotify.startUpdateThread();
        nixieNotify.loop();
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
