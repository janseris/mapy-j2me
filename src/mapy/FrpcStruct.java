package mapy;


import java.util.Hashtable;
import java.util.Vector;

/**
 * A FastRPC struct: string keys with values, keeping the insertion order (the order is
 * kept on encode so requests look exactly like the ones the Android app sends).
 *
 * Values are what Frpc decodes to / accepts for encoding:
 * FrpcStruct, Vector (array), Long/Integer, Double, String, Boolean, FrpcDate, byte[], null.
 * CLDC's Hashtable can't hold null, so nulls are stored as the NULL sentinel.
 */
public class FrpcStruct {
    private static final Object NULL = new Object();

    private Vector keys = new Vector();
    private Hashtable values = new Hashtable();

    public FrpcStruct put(String key, Object value) {
        if (!values.containsKey(key)) keys.addElement(key);
        values.put(key, value == null ? NULL : value);
        return this;
    }

    public FrpcStruct put(String key, int value) {
        return put(key, new Integer(value));
    }

    public FrpcStruct put(String key, long value) {
        return put(key, new Long(value));
    }

    public FrpcStruct put(String key, boolean value) {
        return put(key, value ? Boolean.TRUE : Boolean.FALSE);
    }

    public Object get(String key) {
        Object v = values.get(key);
        return v == NULL ? null : v;
    }

    public boolean has(String key) {
        return values.containsKey(key);
    }

    public int size() {
        return keys.size();
    }

    public String keyAt(int i) {
        return (String) keys.elementAt(i);
    }

    // --- typed getters -------------------------------------------------------------

    public String getString(String key, String def) {
        Object v = get(key);
        return (v instanceof String) ? (String) v : def;
    }

    public long getLong(String key, long def) {
        Object v = get(key);
        if (v instanceof Long) return ((Long) v).longValue();
        if (v instanceof Integer) return ((Integer) v).intValue();
        if (v instanceof Double) return (long) ((Double) v).doubleValue();
        if (v instanceof Boolean) return ((Boolean) v).booleanValue() ? 1 : 0;
        return def;
    }

    public int getInt(String key, int def) {
        return (int) getLong(key, def);
    }

    public boolean getBool(String key, boolean def) {
        Object v = get(key);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof Long) return ((Long) v).longValue() != 0;
        return def;
    }

    /** Returns the value as a Double, or null if missing/not a number. */
    public Double getDouble(String key) {
        Object v = get(key);
        if (v instanceof Double) return (Double) v;
        if (v instanceof Long) return new Double(((Long) v).longValue());
        return null;
    }

    public FrpcStruct getStruct(String key) {
        Object v = get(key);
        return (v instanceof FrpcStruct) ? (FrpcStruct) v : null;
    }

    /** Never null - an empty Vector when the key is missing. */
    public Vector getArray(String key) {
        Object v = get(key);
        return (v instanceof Vector) ? (Vector) v : new Vector();
    }

    /** Element i of array key as a struct, or null. */
    public static FrpcStruct structAt(Vector v, int i) {
        Object o = v.elementAt(i);
        return (o instanceof FrpcStruct) ? (FrpcStruct) o : null;
    }
}
