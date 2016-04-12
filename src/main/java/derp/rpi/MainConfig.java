package derp.rpi;

import derp.rpi.gmail.GmailNotifier;
import derp.rpi.hardware.NixieControl;

public class MainConfig {
    public NixieControl.Config nixieModule = new NixieControl.Config();
    public GmailNotifier.Config gmail = new GmailNotifier.Config();
    public NixieNotify.Config display = new NixieNotify.Config();
}
