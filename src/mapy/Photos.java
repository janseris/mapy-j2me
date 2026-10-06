package mapy;

import java.util.Vector;
import javax.microedition.lcdui.*;

/**
 * Photos of a place (Mapy.com detail: headerImageUrl, gallery[].photoUrl/thumbUrl). They are JPEGs
 * on Seznam's image servers (d48-a.sdn.cz...), where "?fl=res,,800,3" in the URL picks the size,
 * so we ask for sizes that fit the 9300's screen. Downloads go through the disk cache.
 */
public class Photos {
    static final Lru images = new Lru(12);       // url -> decoded Image (memory)

    /**
     * Seznam's image server only serves the sizes the Mapy.com app asks for: "res,,800,3" and
     * "res,,200,3" work, any other size (120, 80...) is HTTP 400. So we always download the 200 px
     * version (tens of KB) and scale it down on the phone.
     */
    static final int SERVER_H = 200;

    /** Downloads the photo at the server's 200 px size and scales it to about h pixels high. */
    public static Image load(String url, int h, String label) throws Exception {
        String key = url + "#" + h;
        Image im = (Image) images.get(key);
        if (im != null) return im;
        im = load(sized(url, SERVER_H), label);
        if (h < im.getHeight()) {
            im = scale(im, im.getWidth() * h / im.getHeight(), h);
            images.put(key, im);
        }
        return im;
    }

    /** Nearest-neighbour scaling (MIDP 2.0 has none of its own). */
    static Image scale(Image src, int w, int h) {
        int sw = src.getWidth(), sh = src.getHeight();
        if (w < 1) w = 1;
        int[] in = new int[sw];
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            src.getRGB(in, 0, sw, 0, y * sh / h, sw, 1);
            for (int x = 0; x < w; x++) out[y * w + x] = in[x * sw / w];
        }
        return Image.createRGBImage(out, w, h, false);
    }

    /** The same photo at about h pixels (Seznam image servers and panorama previews). */
    public static String sized(String url, int h) {
        int i = url.indexOf("fl=res,");
        if (i >= 0) {
            int j = url.indexOf('&', i);
            return url.substring(0, i) + "fl=res,," + h + ",3" + (j < 0 ? "" : url.substring(j));
        }
        if (url.indexOf("compose_pano") >= 0) {
            url = param(url, "w", "" + (h * 16 / 9));
            return param(url, "h", "" + h);
        }
        return url;
    }

    static String param(String url, String name, String value) {
        int i = url.indexOf("&" + name + "=");
        if (i < 0) i = url.indexOf("?" + name + "=");
        if (i < 0) return url;
        int s = i + name.length() + 2, e = url.indexOf('&', s);
        return url.substring(0, s) + value + (e < 0 ? "" : url.substring(e));
    }

    /** Downloads (or takes from the caches) and decodes a photo. */
    public static Image load(String url, String label) throws Exception {
        Image im = (Image) images.get(url);
        if (im != null) return im;
        byte[] b = DiskCache.fetch(url, label);
        try {
            im = Image.createImage(b, 0, b.length);
        } catch (OutOfMemoryError e) {
            images.trim(2);
            System.gc();
            im = Image.createImage(b, 0, b.length);
        } catch (IllegalArgumentException e) {
            // not decodable here: log what it is (JPEG baseline/progressive, WebP, an HTML page...)
            StringBuffer h = new StringBuffer();
            for (int i = 0; i < Math.min(12, b.length); i++) h.append(Integer.toHexString((b[i] & 0xff) | 0x100).substring(1));
            boolean progressive = false;
            for (int i = 0; i + 1 < b.length; i++) if ((b[i] & 0xff) == 0xFF && (b[i + 1] & 0xff) == 0xC2) { progressive = true; break; }
            Log.add("photo not decodable: " + b.length + " B, starts " + h + (progressive ? ", progressive JPEG" : "") + ", " + url);
            DiskCache.remove(url);
            throw new IllegalArgumentException("image can't be decoded" + (progressive ? " (progressive JPEG)" : "") + ", " + b.length + " B");
        }
        images.put(url, im);
        return im;
    }

    /** The main photo of a detail, or null. */
    public static String header(FrpcStruct d) {
        String u = d.getString("headerImageUrl", "");
        if (u.length() > 0) return u;
        Vector g = gallery(d);
        return g.size() > 0 ? (String) g.elementAt(0) : null;
    }

    /** Photo URLs of a detail (full-size versions; resize with sized()). */
    public static Vector gallery(FrpcStruct d) {
        Vector out = new Vector();
        Vector g = d.getArray("gallery");
        for (int i = 0; i < g.size(); i++) {
            FrpcStruct p = FrpcStruct.structAt(g, i);
            if (p == null) continue;
            String u = p.getString("photoUrl", p.getString("thumbUrl", ""));
            if (u.length() > 0) out.addElement(u);
        }
        return out;
    }
}
