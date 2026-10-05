package mapy;

import javax.microedition.lcdui.*;

/**
 * Route planning form: Odkud, Kam (each opens a PlacePicker, like the pubtran app's search,
 * with "Moje poloha" in both), then the options and "Naplánovat". Up/Down choose a row, Enter
 * opens / toggles it, left/right toggle options.
 */
public class RouteForm extends Canvas implements CommandListener {
    static final Command PLAN = new Command("Naplánovat", Command.SCREEN, 1);
    static final Command SWAP = new Command("Prohodit", Command.SCREEN, 2);
    static final Command BACK = new Command("Zpět", Command.BACK, 3);

    static final int FROM = 0, TO = 1, MODE = 2, TOLL = 3, NAV = 4, GO = 5, ROWS = 6;

    final Mapy app;
    Place from = PlacePicker.gpsPlace(), to;
    boolean car = true, noToll, startNav = true;
    int sel = TO;

    RouteForm(Mapy app) {
        this.app = app;
        setTitle("Trasa");
        addCommand(PLAN); addCommand(SWAP); addCommand(BACK);
        setCommandListener(this);
    }

    static boolean isGps(Place p) {
        return p != null && PlacePicker.GPS.equals(p.source);
    }

    String label(int row) {
        switch (row) {
            case FROM: return "Odkud:  " + (from == null ? "(vyberte)" : from.title);
            case TO: return "Kam:  " + (to == null ? "(vyberte)" : to.title);
            case MODE: return "Způsob:  " + (car ? "autem" : "pěšky");
            case TOLL: return car ? "Placené úseky:  " + (noToll ? "bez placených úseků" : "s placenými úseky") : "Placené úseky:  (jen autem)";
            case NAV: return "Pak spustit navigaci:  " + (startNav ? "ano" : "ne") + (isGps(from) ? "" : " (jen z Moje poloha)");
            default: return "Naplánovat trasu";
        }
    }

    void activate(int row) {
        if (row == FROM || row == TO) {
            final boolean f = row == FROM;
            app.display.setCurrent(new PlacePicker(app, f ? "Odkud" : "Kam", this, new PlacePicker.Picked() {
                public void picked(Place p) {
                    if (f) from = p; else to = p;
                    if (f && to == null) sel = TO;
                    else if (!f) sel = GO;
                    repaint();
                }
            }));
        } else if (row == MODE) car = !car;
        else if (row == TOLL) { if (car) noToll = !noToll; }
        else if (row == NAV) startNav = !startNav;
        else plan();
        repaint();
    }

    void plan() {
        if (from == null || to == null) {
            sel = from == null ? FROM : TO;
            repaint();
            return;
        }
        if (isGps(to) && isGps(from)) return;
        app.planRoute(from, to, car, car && noToll, startNav && isGps(from));
    }

    protected void keyPressed(int k) {
        int a = 0;
        try { a = getGameAction(k); } catch (Throwable e) {}
        if (a == UP) sel = (sel + ROWS - 1) % ROWS;
        else if (a == DOWN) sel = (sel + 1) % ROWS;
        else if (a == LEFT || a == RIGHT) { if (sel >= MODE && sel <= NAV) activate(sel); }
        else if (a == FIRE || k == 10 || k == 13) activate(sel);
        repaint();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == PLAN) plan();
        else if (c == SWAP) { Place t = from; from = to; to = t; repaint(); }
        else app.showMap();
    }

    protected void paint(Graphics g) {
        Font f = MapCanvas.small(), b = MapCanvas.bold();
        int w = getWidth(), h = getHeight();
        g.setColor(0x1E1F22);
        g.fillRect(0, 0, w, h);
        int rh = Math.min(b.getHeight() + 8, (h - 4) / ROWS), y = 2;
        for (int i = 0; i < ROWS; i++) {
            int ry = y + i * rh;
            boolean s = i == sel;
            int bg = i == GO ? (s ? 0x2E9E5B : 0x248046) : (s ? 0x1565C0 : 0x2B2D31);
            g.setColor(bg);
            g.fillRect(4, ry, w - 8, rh - 2);
            if (s) { g.setColor(0x8AB4F8); g.drawRect(4, ry, w - 9, rh - 3); }
            g.setFont(i <= TO || i == GO ? b : f);
            g.setColor((i == TOLL && !car) ? 0x80848E : 0xFFFFFF);
            String t = label(i);
            g.drawString(MapCanvas.clip(g.getFont(), t, w - 20), 10, ry + (rh - 2 - g.getFont().getHeight()) / 2, Graphics.TOP | Graphics.LEFT);
        }
    }
}
