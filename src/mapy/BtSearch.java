package mapy;

import java.util.Vector;
import javax.bluetooth.*;
import javax.microedition.lcdui.*;

/**
 * Picks the Bluetooth GPS from the devices around (inquiry, ~12 s), like the probe. Recently seen
 * devices are listed too. While the 9300 has a Bluetooth link to a PC (PC Suite), the inquiry ends
 * at once with nothing found.
 */
public class BtSearch implements DiscoveryListener, CommandListener {
    public interface Picked {
        void picked(String address);
    }

    static final Command CANCEL = new Command("Zpět", Command.BACK, 1);
    static final Command PICK = new Command("Vybrat", Command.SCREEN, 1);

    final Display display;
    final Displayable back;
    final Picked picked;
    final Vector devices = new Vector();
    final Form wait = new Form("Hledám zařízení");
    final StringItem info = new StringItem(null, "Hledám Bluetooth zařízení v okolí (asi 12 s). Na Androidu musí běžet sdílení GPS.");
    DiscoveryAgent agent;
    List list;
    volatile boolean done;

    BtSearch(Display display, Displayable back, Picked picked) {
        this.display = display;
        this.back = back;
        this.picked = picked;
    }

    void start() {
        wait.append(info);
        wait.addCommand(CANCEL);
        wait.setCommandListener(this);
        display.setCurrent(wait);
        try {
            if (Gps.instance.running) Gps.instance.disconnect();     // one Bluetooth job at a time
            agent = LocalDevice.getLocalDevice().getDiscoveryAgent();
            add(agent.retrieveDevices(DiscoveryAgent.PREKNOWN));
            add(agent.retrieveDevices(DiscoveryAgent.CACHED));
            agent.startInquiry(DiscoveryAgent.GIAC, this);
            Log.add("bt inquiry start, known " + devices.size());
        } catch (Throwable e) {
            Log.add("bt inquiry: " + e);
            final String title = Gps.btOff(e) ? "Bluetooth je vypnutý" : "Hledání selhalo";
            display.callSerially(new Runnable() {
                public void run() { wait.setTitle(title); }
            });
            setInfo(Gps.btOff(e) ? "Bluetooth je vypnutý. Zapni ho v telefonu (Ovládací panel → Bluetooth) a hledej znovu."
                : "Hledání selhalo: " + e.getMessage() + "\nNení 9300 připojená přes Bluetooth k PC?");
            if (!Gps.btOff(e) && devices.size() > 0) showList("Známá zařízení");
        }
    }

    /** Form items are changed on the UI thread only (Bluetooth callbacks come on their own thread). */
    void setInfo(final String t) {
        display.callSerially(new Runnable() {
            public void run() { info.setText(t); }
        });
    }

    void add(RemoteDevice[] d) {
        if (d != null) for (int i = 0; i < d.length; i++) deviceDiscovered(d[i], null);
    }

    public void deviceDiscovered(RemoteDevice d, DeviceClass cod) {
        synchronized (devices) {
            for (int i = 0; i < devices.size(); i++)
                if (((RemoteDevice) devices.elementAt(i)).getBluetoothAddress().equals(d.getBluetoothAddress())) return;
            devices.addElement(d);
        }
        setInfo("Hledám... nalezeno " + devices.size());
    }

    public void inquiryCompleted(int type) {
        done = true;
        Log.add("bt inquiry done " + type + ", devices " + devices.size());
        if (devices.size() == 0) {
            setInfo((type == INQUIRY_ERROR ? "Chyba hledání. " : "Nic nenalezeno. ")
                + "Když je 9300 připojená přes Bluetooth k PC (PC Suite), hledání nefunguje. Zkontroluj, že je Android viditelný a sdílení GPS běží.");
            return;
        }
        showList(type == INQUIRY_COMPLETED ? "Vyber GPS" : "Vyber GPS (přerušeno)");
    }

    void showList(String title) {
        list = new List(title, List.IMPLICIT);
        synchronized (devices) {
            for (int i = 0; i < devices.size(); i++) {
                RemoteDevice d = (RemoteDevice) devices.elementAt(i);
                String name;
                try { name = d.getFriendlyName(false); } catch (Throwable e) { name = "?"; }
                list.append((name == null ? "?" : name) + " " + Gps.pretty(d.getBluetoothAddress()), null);
                Log.add("  bt device " + d.getBluetoothAddress() + " " + name);
            }
        }
        list.addCommand(PICK);
        list.setSelectCommand(PICK);
        list.addCommand(CANCEL);
        list.setCommandListener(this);
        display.setCurrent(list);
    }

    public void servicesDiscovered(int t, ServiceRecord[] r) {}
    public void serviceSearchCompleted(int t, int r) {}

    public void commandAction(Command c, Displayable d) {
        if (!done && agent != null) {
            try { agent.cancelInquiry(this); } catch (Throwable e) {}
        }
        if (c == PICK && list != null && list.getSelectedIndex() >= 0) {
            RemoteDevice r = (RemoteDevice) devices.elementAt(list.getSelectedIndex());
            picked.picked(r.getBluetoothAddress());
        }
        display.setCurrent(back);
    }
}
