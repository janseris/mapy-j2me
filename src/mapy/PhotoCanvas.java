package mapy;

import java.util.Vector;
import javax.microedition.lcdui.*;

/** Full-screen photo viewer: left/right = previous/next photo, Back returns to the detail. */
public class PhotoCanvas extends Canvas implements CommandListener, Runnable {
    static final Command BACK = new Command("Zpět", Command.BACK, 1);
    static final Command PREV = new Command("Předchozí", Command.SCREEN, 2);
    static final Command NEXT = new Command("Další", Command.SCREEN, 3);

    final Mapy app;
    final Displayable back;
    final Vector urls;
    final String title;
    int index;
    volatile Image image;
    volatile String status = "";
    volatile boolean loading;

    PhotoCanvas(Mapy app, Displayable back, String title, Vector urls) {
        this.app = app; this.back = back; this.title = title; this.urls = urls;
        try { setFullScreenMode(true); } catch (Throwable e) {}
        addCommand(PREV); addCommand(NEXT); addCommand(BACK);
        setCommandListener(this);
        load();
    }

    void load() {
        if (loading) return;
        loading = true;
        image = null;
        status = "Načítám fotku " + (index + 1) + "/" + urls.size() + "...";
        repaint();
        new Thread(this).start();
    }

    public void run() {
        try {
            int h = getHeight() - 20;
            image = Photos.load(Photos.sized((String) urls.elementAt(index), h), "fotka " + (index + 1));
            status = "";
        } catch (Throwable e) {
            status = "Fotku se nepodařilo načíst: " + e.getMessage();
        } finally {
            loading = false;
            repaint();
        }
    }

    void go(int d) {
        if (loading || urls.size() < 2) return;
        index = (index + d + urls.size()) % urls.size();
        load();
    }

    public void commandAction(Command c, Displayable d) {
        if (c == PREV) go(-1);
        else if (c == NEXT) go(1);
        else app.display.setCurrent(back);
    }

    protected void keyPressed(int key) {
        int a = 0;
        try { a = getGameAction(key); } catch (Throwable e) {}
        if (a == LEFT || a == UP) go(-1);
        else if (a == RIGHT || a == DOWN || a == FIRE || key == ' ') go(1);
        else if (key == 8 || key == 27) app.display.setCurrent(back);
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(0x000000);
        g.fillRect(0, 0, w, h);
        Image im = image;
        if (im != null) g.drawImage(im, w / 2, (h - 16) / 2, Graphics.HCENTER | Graphics.VCENTER);
        Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL);
        g.setFont(f);
        g.setColor(0xFFFFFF);
        String cap = title + "   " + (index + 1) + " / " + urls.size() + (status.length() > 0 ? "   " + status : "   (šipky = další)");
        g.drawString(cap, 4, h - f.getHeight() - 1, Graphics.TOP | Graphics.LEFT);
    }
}
