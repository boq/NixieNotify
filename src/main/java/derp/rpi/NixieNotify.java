package derp.rpi;

import java.util.*;

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

    private static final int CYCLE_PERIOD = 1;
    private static final int UPDATE_PERIOD = 10;

    private static final Logger logger = LoggerFactory.getLogger(NixieNotify.class);

    public static void main(String[] args) {
        final List<Notify> notifies = Lists.newCopyOnWriteArrayList();

        final List<NotifySource> sources = ImmutableList.of(
                new GmailNotifier()
                );

        {
            final Thread update = new Thread() {
                @Override
                public void run() {
                    final Notify empty = new Notify("system:empty", new StateBuilder().bakeBits());
                    while (true) {
                        logger.info("Updating");

                        List<Notify> newNotifies = Lists.newArrayList();

                        for (NotifySource source : sources)
                            newNotifies.addAll(source.query());

                        if (newNotifies.isEmpty())
                            newNotifies.add(empty);

                        notifies.clear();
                        notifies.addAll(newNotifies);

                        try {
                            Thread.sleep(UPDATE_PERIOD * 1000);
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

        NixieControl control = null;
        try {
            control = new NixieControl(RaspiPin.GPIO_00, RaspiPin.GPIO_01, RaspiPin.GPIO_02, RaspiPin.GPIO_03);

            while (true) {
                for (Notify n : notifies) {
                    control.update(n.payload);
                    try {
                        Thread.sleep(CYCLE_PERIOD * 1000);
                    } catch (InterruptedException e) {
                        logger.info("Interrupted", e);
                        break;
                    }
                }
            }
        } finally {
            if (control != null)
                control.stop();
        }
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

                control.update(stateBuilder.bakeBits());
            }
        }
    }

}
