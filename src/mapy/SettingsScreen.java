package mapy;

import javax.microedition.lcdui.*;

/**
 * Settings as a list of "name: value" rows instead of a Form. On the 9300 a Form's choice
 * groups take the Up/Down keys (popups change their value, radio lists move inside the group),
 * so the other fields couldn't be reached. Here Up/Down only move between rows; Enter opens a
 * row: a choice opens its own list of values, a text opens the editor. Nothing changes until
 * "Uložit"; leaving with changes asks whether to save them.
 */
public class SettingsScreen implements CommandListener {
    static final Command SAVE = new Command("Uložit", Command.SCREEN, 1);
    static final Command BT_SEARCH = new Command("Hledat GPS zařízení", Command.SCREEN, 2);
    static final Command CLEAR_CACHE = new Command("Smazat mezipaměť", Command.SCREEN, 3);
    static final Command BACK = new Command("Zpět", Command.BACK, 4);
    static final Command DONE = new Command("Hotovo", Command.SCREEN, 1);
    static final Command DISCARD = new Command("Neukládat", Command.SCREEN, 2);
    static final Command STAY = new Command("Zpět do nastavení", Command.BACK, 3);

    static final int[] CACHE_MB = { 0, 4, 8, 16, 32, 48 };
    static final int[] PANEL_WIDTHS = { 110, 130, 150, 180, 210, 240 };
    static final String[] ON_OFF = { "zapnuto", "vypnuto" };

    // rows
    static final int PANEL = 0, CACHE = 1, PREVIEW = 2, BT = 3, GPS_AUTO = 4, FOLLOW = 5, LIMITS = 6, HTTP = 7,
        AKCE = 8, KEY = 9, PC = 10, UA = 11, LINK = 12, ABOUT = 13, ROWS = 14;
    static final String[] NAMES = { "Šířka levého panelu", "Mezipaměť v telefonu", "Náhled při najetí kurzorem",
        "Bluetooth GPS (adresa)", "Připojit GPS při spuštění", "Mapa sleduje polohu", "Rychlostní limity při jízdě",
        "Veřejná data přes HTTP", "Menu Akce telefonu", "Mapy.com API klíč", "PC pro log", "User-Agent", "Připojení k internetu", "Zdroje dat a licence" };

    final Mapy app;
    final Display display;
    final int[] choice = new int[ROWS];        // selected option of choice rows
    final String[] text = new String[ROWS];    // value of text rows
    String initial;
    List list, options;
    TextBox editor;
    Alert unsaved;
    int row;

    SettingsScreen(Mapy app) {
        this.app = app;
        this.display = app.display;
        choice[PANEL] = indexOf(PANEL_WIDTHS, Settings.panelWidth, 2);
        choice[CACHE] = indexOf(CACHE_MB, Settings.cacheMB, 3);
        choice[PREVIEW] = Settings.preview ? 0 : 1;
        choice[GPS_AUTO] = Settings.gpsAuto ? 0 : 1;
        choice[FOLLOW] = Settings.follow ? 0 : 1;
        choice[LIMITS] = Settings.speedLimits ? 0 : 1;
        choice[HTTP] = Settings.httpFirst ? 0 : 1;
        choice[AKCE] = Settings.akce ? 0 : 1;
        choice[LINK] = Settings.link;
        text[BT] = Gps.pretty(Settings.btAddress);
        text[KEY] = Settings.mapyKey;
        text[PC] = Settings.pc;
        text[UA] = Settings.userAgent;
        initial = state();
    }

    static int indexOf(int[] a, int v, int def) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return def;
    }

    static boolean isText(int r) { return r == BT || r == KEY || r == PC || r == UA; }

    String[] optionsOf(int r) {
        if (r == PANEL) {
            String[] s = new String[PANEL_WIDTHS.length];
            for (int i = 0; i < s.length; i++) s[i] = PANEL_WIDTHS[i] + " px";
            return s;
        }
        if (r == CACHE) {
            String[] s = new String[CACHE_MB.length];
            for (int i = 0; i < s.length; i++) s[i] = CACHE_MB[i] == 0 ? "vypnuto" : CACHE_MB[i] + " MB";
            return s;
        }
        if (r == LINK) return new String[] { "automaticky (" + (Net.usbLink() ? "teď: pauzy" : "teď: bez pauz") + ")",
            "USB z PC (IP passthrough): krátké pauzy", "GPRS / jiné: bez pauz" };
        if (r == AKCE) return new String[] { "zapnuto (šipky v něm hýbou mapou)", "vypnuto (menu mapy: Tab)" };
        return ON_OFF;
    }

    String value(int r) {
        if (r == ABOUT) return "";
        if (isText(r)) {
            String t = text[r];
            if (r == KEY && t.length() > 0) return "zadán";
            return t.length() == 0 ? "(prázdné)" : t;
        }
        return optionsOf(r)[choice[r]];
    }

    String state() {
        StringBuffer b = new StringBuffer();
        for (int r = 0; r < ROWS; r++) b.append(isText(r) ? text[r] : "" + choice[r]).append('|');
        return b.toString();
    }

    void show() {
        list = new List("Nastavení", List.IMPLICIT);
        for (int r = 0; r < ROWS; r++) list.append(NAMES[r] + (r == ABOUT ? "" : ": " + value(r)), null);
        list.setSelectedIndex(row, true);
        // four commands: all on the 9300's side buttons
        list.addCommand(SAVE);
        list.addCommand(BT_SEARCH);
        list.addCommand(CLEAR_CACHE);
        list.addCommand(BACK);
        list.setCommandListener(this);
        display.setCurrent(list);
    }

    void open(int r) {
        row = r;
        if (r == ABOUT) {
            Alert a = new Alert("Zdroje dat", "Mapy: © OpenStreetMap contributors (openstreetmap.org/copyright), OpenTopoMap (CC-BY-SA), © ČÚZK. "
                + "Body zájmu, limity, radary: OpenStreetMap přes Overpass API. Trasy: OSRM na serveru FOSSGIS (routing.openstreetmap.de). "
                + "Chyba v mapě? openstreetmap.org/fixthemap. Hledání, detaily, fotky a ikony: Mapy.com.", null, AlertType.INFO);
            a.setTimeout(Alert.FOREVER);
            display.setCurrent(a, list);
        } else if (isText(r)) {
            int max = r == UA ? 200 : r == KEY ? 100 : 64;
            editor = new TextBox(NAMES[r], text[r], max, TextField.ANY);
            editor.addCommand(DONE);
            editor.addCommand(BACK);
            editor.setCommandListener(this);
            display.setCurrent(editor);
        } else {
            options = new List(NAMES[r], List.IMPLICIT, optionsOf(r), null);
            options.setSelectedIndex(choice[r], true);
            options.addCommand(BACK);
            options.setCommandListener(this);
            display.setCurrent(options);
        }
    }

    void save() {
        Settings.panelWidth = PANEL_WIDTHS[choice[PANEL]];
        Settings.cacheMB = CACHE_MB[choice[CACHE]];
        Settings.preview = choice[PREVIEW] == 0;
        String bt = Gps.clean(text[BT]);
        if (!bt.equals(Settings.btAddress) && Gps.instance.running) Gps.instance.disconnect();
        Settings.btAddress = bt.length() == 12 ? bt : Settings.DEFAULT_BT;
        Settings.gpsAuto = choice[GPS_AUTO] == 0;
        Settings.follow = choice[FOLLOW] == 0;
        Settings.speedLimits = choice[LIMITS] == 0;
        Settings.httpFirst = choice[HTTP] == 0;
        Settings.akce = choice[AKCE] == 0;
        Settings.link = choice[LINK];
        String key = text[KEY].trim();
        boolean keyChanged = !key.equals(Settings.mapyKey);
        Settings.mapyKey = key;
        Settings.pc = text[PC].trim();
        String ua = text[UA].trim();
        Settings.userAgent = ua.length() > 0 ? ua : Settings.DEFAULT_UA;
        Settings.save();
        MapCanvas m = app.map;
        m.applyCommands();
        m.follow = Settings.follow;
        if (keyChanged) m.layerChanged();
        m.repaint();
        Log.add("settings saved");
        app.showMap();
    }

    void leave() {
        if (state().equals(initial)) { app.showMap(); return; }
        unsaved = new Alert("Neuložené změny", "Nastavení se změnilo. Uložit změny?", null, AlertType.CONFIRMATION);
        unsaved.setTimeout(Alert.FOREVER);
        unsaved.addCommand(SAVE);
        unsaved.addCommand(DISCARD);
        unsaved.addCommand(STAY);
        unsaved.setCommandListener(this);
        display.setCurrent(unsaved);
    }

    public void commandAction(Command c, Displayable d) {
        if (d == list) {
            if (c == List.SELECT_COMMAND) open(list.getSelectedIndex());
            else if (c == SAVE) save();
            else if (c == BT_SEARCH) {
                row = BT;
                new BtSearch(display, list, new BtSearch.Picked() {
                    public void picked(String a) { text[BT] = Gps.pretty(a); show(); }
                }).start();
            } else if (c == CLEAR_CACHE) {
                DiskCache.clear();
                Photos.images.clear();
                app.details.clear();
                Alert a = new Alert("Mezipaměť", "Smazáno.", null, AlertType.INFO);
                a.setTimeout(2000);
                display.setCurrent(a, list);
            } else leave();
        } else if (d == options) {
            if (c == List.SELECT_COMMAND) choice[row] = options.getSelectedIndex();
            show();
        } else if (d == editor) {
            if (c == DONE) text[row] = editor.getString();
            show();
        } else if (d == unsaved) {
            if (c == SAVE) save();
            else if (c == DISCARD) app.showMap();
            else show();
        }
    }
}
