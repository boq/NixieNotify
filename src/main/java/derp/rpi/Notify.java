package derp.rpi;

import java.util.BitSet;

public class Notify {

    public final String id;

    public final BitSet payload;

    public Notify(String id, BitSet payload) {
        this.id = id;
        this.payload = payload;
    }
}
