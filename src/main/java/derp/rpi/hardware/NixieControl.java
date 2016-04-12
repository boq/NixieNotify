package derp.rpi.hardware;

import java.util.BitSet;

import com.google.common.base.Preconditions;
import com.pi4j.io.gpio.*;

public class NixieControl implements AutoCloseable {

    public static class Config {
        public String din = RaspiPin.GPIO_00.getName();
        public String oe = RaspiPin.GPIO_01.getName();
        public String stcp = RaspiPin.GPIO_02.getName();
        public String shcp = RaspiPin.GPIO_03.getName();
        public String toggle = RaspiPin.GPIO_04.getName();
    }

    private final GpioController gpio = GpioFactory.getInstance();

    private final GpioPinDigitalOutput din;
    private final GpioPinDigitalOutput oe;
    private final GpioPinDigitalOutput stcp;
    private final GpioPinDigitalOutput shcp;

    private final GpioPinDigitalInput sw;

    private boolean initialized;

    private static Pin getPin(String name) {
        final Pin pin = RaspiPin.getPinByName(name);
        Preconditions.checkArgument(pin != null, "Invalid pin: %s", name);
        return pin;
    }

    public NixieControl(Config pins) {
        this.din = gpio.provisionDigitalOutputPin(getPin(pins.din), "DIN", PinState.LOW);
        this.din.setShutdownOptions(true, PinState.LOW);

        this.oe = gpio.provisionDigitalOutputPin(getPin(pins.oe), "OE", PinState.LOW);
        this.oe.setShutdownOptions(true, PinState.HIGH);

        this.stcp = gpio.provisionDigitalOutputPin(getPin(pins.stcp), "STCP", PinState.LOW);
        this.stcp.setShutdownOptions(true, PinState.LOW);

        this.shcp = gpio.provisionDigitalOutputPin(getPin(pins.shcp), "SHCP", PinState.LOW);
        this.shcp.setShutdownOptions(true, PinState.LOW);

        this.sw = gpio.provisionDigitalInputPin(getPin(pins.toggle), PinPullResistance.PULL_UP);
        this.sw.setShutdownOptions(true);

        this.initialized = true;
    }

    @Override
    public void close() {
        if (initialized) {
            gpio.shutdown();
            initialized = false;
        }
    }

    public void updateTube(BitSet state) {
        Preconditions.checkState(initialized, "GPIO not initialized");

        for (int i = 0; i < 16; i++) {
            final boolean bitState = state.get(i);
            din.setState(bitState);
            shcp.high();
            shcp.low();
        }

        stcp.high();
        stcp.low();
    }

    public void setTubeState(boolean state) {
        oe.setState(!state); // active LOW
    }

    public boolean isSwitchOn() {
        return sw.getState().isLow();
    }
}
