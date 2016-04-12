package derp.rpi.hardware;

import java.util.BitSet;
import java.util.Optional;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

public class StateBuilder {

    public enum Led {
        RED(1),
        GREEN(2),
        BLUE(0);

        private final int pin;

        private Led(int pin) {
            this.pin = pin;
        }

        private void set(BitSet state, boolean value) {
            state.set(pin, !value); // HIGH disables LED
        }
    }

    public enum Color {
        NONE(),

        RED(Led.RED),
        GREEN(Led.GREEN),
        BLUE(Led.BLUE),

        CYAN(Led.GREEN, Led.BLUE),
        MAGENTA(Led.BLUE, Led.RED),
        YELLOW(Led.RED, Led.GREEN),

        WHITE(Led.RED, Led.GREEN, Led.BLUE);

        private final Set<Led> components;

        private Color(Led... components) {
            this.components = ImmutableSet.copyOf(components);
        }

    }

    public enum Digit {
        D0(12),
        D1(3),
        D2(4),
        D3(5),
        D4(6),
        D5(7),
        D6(8),
        D7(9),
        D8(10),
        D9(11);

        private final int pin;

        private Digit(int pin) {
            this.pin = pin;
        }

        private void set(BitSet state) {
            state.set(pin);
        }
    }

    private Optional<Digit> digit = Optional.empty();

    private boolean upperDot;
    private boolean lowerDot;

    private Set<Led> leds = Sets.newHashSet();

    public StateBuilder setDigit(Optional<Digit> digit) {
        this.digit = digit;
        return this;
    }

    public StateBuilder setUpperDot(boolean upperDot) {
        this.upperDot = upperDot;
        return this;
    }

    public StateBuilder setLowerDot(boolean lowerDot) {
        this.lowerDot = lowerDot;
        return this;
    }

    public StateBuilder ledOn(Led color) {
        leds.add(color);
        return this;
    }

    public StateBuilder ledOff(Led color) {
        leds.remove(color);
        return this;
    }

    public StateBuilder setColor(Color color) {
        leds.clear();
        leds.addAll(color.components);
        return this;
    }

    public BitSet bakeBits() {
        final BitSet state = new BitSet(16);
        for (Led led : Led.values())
            led.set(state, leds.contains(led));

        digit.ifPresent(d -> d.set(state));

        state.set(13, upperDot);
        state.set(14, lowerDot);

        return state;
    }
}
