package mapy;

import java.util.Hashtable;
import java.util.Vector;

/**
 * Minimal JSON parser for CLDC 1.1: objects -> Hashtable, arrays -> Vector, numbers -> Double,
 * strings -> String, true/false -> Boolean, null -> NULL. Enough for routing responses.
 */
public class Json {
    public static final Object NULL = new Object();
    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        return j.value();
    }

    void ws() {
        while (i < s.length() && s.charAt(i) <= ' ') i++;
    }

    Object value() {
        ws();
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return NULL; }
        return number();
    }

    Hashtable object() {
        Hashtable h = new Hashtable();
        i++;
        ws();
        if (s.charAt(i) == '}') { i++; return h; }
        while (true) {
            ws();
            String k = string();
            ws();
            i++;            // ':'
            h.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') return h;
        }
    }

    Vector array() {
        Vector v = new Vector();
        i++;
        ws();
        if (s.charAt(i) == ']') { i++; return v; }
        while (true) {
            v.addElement(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') return v;
        }
    }

    String string() {
        StringBuffer b = new StringBuffer();
        i++;            // opening quote
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: b.append(e);
            }
        }
    }

    Double number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        return Double.valueOf(s.substring(st, i));
    }

    // ---- helpers ----

    public static Hashtable obj(Object o, String k) {
        Object v = o instanceof Hashtable ? ((Hashtable) o).get(k) : null;
        return v instanceof Hashtable ? (Hashtable) v : null;
    }

    public static Vector arr(Object o, String k) {
        Object v = o instanceof Hashtable ? ((Hashtable) o).get(k) : null;
        return v instanceof Vector ? (Vector) v : new Vector();
    }

    public static String str(Object o, String k) {
        Object v = o instanceof Hashtable ? ((Hashtable) o).get(k) : null;
        return v instanceof String ? (String) v : "";
    }

    public static double num(Object o, String k) {
        Object v = o instanceof Hashtable ? ((Hashtable) o).get(k) : null;
        return v instanceof Double ? ((Double) v).doubleValue() : 0;
    }
}
