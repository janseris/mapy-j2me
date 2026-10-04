package mapy;


import java.io.ByteArrayOutputStream;
import java.util.Vector;

/**
 * Seznam FastRPC 2.1 binary encoder/decoder (Content-Type application/x-frpc-rest),
 * which is what pubtran-backend.mapy.cz and vectmap.mapy.cz speak instead of JSON.
 *
 * Body layout: magic CA 11, version 02 01, then one value. Every value starts with a
 * type byte (type << 3 | info):
 *   1 INT (legacy, signed)   2 BOOL (info bit 0)       3 DOUBLE (8 bytes LE)
 *   4 STRING (len, UTF-8)    5 DATETIME                6 BINARY (len, bytes)
 *   7 INT positive (magnitude, info+1 bytes)           8 INT negative (magnitude)
 *   10 STRUCT (count; 1-byte key length, key, value)   11 ARRAY (count, values)
 *   12 NULL                  14 METHOD RESPONSE        15 FAULT (code, message)
 * Lengths/counts are little-endian and info+1 bytes long.
 *
 * Mirrors the C# FrpcCodec in PubtranClient, which re-encodes every request captured
 * from the Android app byte-for-byte.
 */
public class Frpc {
    private static final int T_INT = 1, T_BOOL = 2, T_DOUBLE = 3, T_STRING = 4, T_DATETIME = 5,
        T_BINARY = 6, T_INT_POS = 7, T_INT_NEG = 8, T_STRUCT = 10, T_ARRAY = 11, T_NULL = 12,
        T_METHOD_CALL = 13, T_METHOD_RESPONSE = 14, T_FAULT = 15;

    // ============================== encode ==============================

    public static byte[] encode(Object value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xCA);
        out.write(0x11);
        out.write(0x02);
        out.write(0x01);
        writeValue(out, value);
        return out.toByteArray();
    }

    /**
     * A FastRPC method call (what vectmap.mapy.cz/rpc expects, Content-Type application/x-frpc):
     * magic, version, type 13, 1-byte name length, name, then the parameters one after another.
     */
    public static byte[] encodeCall(String method, Object[] params) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xCA);
        out.write(0x11);
        out.write(0x02);
        out.write(0x01);
        out.write(T_METHOD_CALL << 3);
        byte[] n = utf8Encode(method);
        out.write(n.length);
        out.write(n, 0, n.length);
        for (int i = 0; i < params.length; i++) writeValue(out, params[i]);
        return out.toByteArray();
    }

    private static void writeValue(ByteArrayOutputStream out, Object v) {
        if (v == null) {
            out.write(T_NULL << 3);
        }
        else if (v instanceof Boolean) {
            out.write((T_BOOL << 3) | (((Boolean) v).booleanValue() ? 1 : 0));
        }
        else if (v instanceof Integer) {
            writeInt(out, ((Integer) v).intValue());
        }
        else if (v instanceof Long) {
            writeInt(out, ((Long) v).longValue());
        }
        else if (v instanceof Double) {
            out.write(T_DOUBLE << 3);
            writeLE(out, Double.doubleToLongBits(((Double) v).doubleValue()), 8);
        }
        else if (v instanceof String) {
            byte[] b = utf8Encode((String) v);
            writeLengthHeader(out, T_STRING, b.length);
            out.write(b, 0, b.length);
        }
        else if (v instanceof FrpcDate) {
            FrpcDate d = (FrpcDate) v;
            out.write(T_DATETIME << 3);
            out.write(d.zone & 0xFF);
            writeLE(out, d.unixSeconds & 0xFFFFFFFFL, 4);
            writeLE(out, d.packedFields(), 5);
        }
        else if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            writeLengthHeader(out, T_BINARY, b.length);
            out.write(b, 0, b.length);
        }
        else if (v instanceof FrpcStruct) {
            FrpcStruct s = (FrpcStruct) v;
            writeLengthHeader(out, T_STRUCT, s.size());
            for (int i = 0; i < s.size(); i++) {
                String key = s.keyAt(i);
                byte[] k = utf8Encode(key);
                out.write(k.length);
                out.write(k, 0, k.length);
                writeValue(out, s.get(key));
            }
        }
        else if (v instanceof Vector) {
            Vector a = (Vector) v;
            writeLengthHeader(out, T_ARRAY, a.size());
            for (int i = 0; i < a.size(); i++) writeValue(out, a.elementAt(i));
        }
        else {
            throw new IllegalArgumentException("FastRPC: can't encode " + v.getClass().getName());
        }
    }

    private static int byteCount(long v) {
        int n = 1;
        while (n < 8 && (v >>> (8 * n)) != 0) n++;
        return n;
    }

    private static void writeLE(ByteArrayOutputStream out, long v, int n) {
        for (int i = 0; i < n; i++) out.write((int) (v >>> (8 * i)) & 0xFF);
    }

    private static void writeLengthHeader(ByteArrayOutputStream out, int type, long length) {
        int n = byteCount(length);
        out.write((type << 3) | (n - 1));
        writeLE(out, length, n);
    }

    private static void writeInt(ByteArrayOutputStream out, long v) {
        long mag = v >= 0 ? v : -v;
        int n = byteCount(mag);
        out.write(((v >= 0 ? T_INT_POS : T_INT_NEG) << 3) | (n - 1));
        writeLE(out, mag, n);
    }

    // ============================== decode ==============================

    private byte[] d;
    private int pos;
    private int major;

    private Frpc(byte[] data) {
        d = data;
    }

    /** Decodes a response body. Throws on a FastRPC fault or malformed data. */
    public static Object decode(byte[] data) throws Exception {
        if (data == null || data.length < 4 || (data[0] & 0xFF) != 0xCA || (data[1] & 0xFF) != 0x11) {
            throw new Exception("Response is not FastRPC (" + (data == null ? 0 : data.length) + " bytes)");
        }
        Frpc r = new Frpc(data);
        r.major = data[2] & 0xFF;
        r.pos = 4;
        return r.readValue();
    }

    private long u(int n) {
        long v = 0;
        for (int i = 0; i < n; i++) v |= ((long) (d[pos + i] & 0xFF)) << (8 * i);
        pos += n;
        return v;
    }

    private Object readValue() throws Exception {
        int h = d[pos++] & 0xFF;
        int type = h >> 3;
        int info = h & 7;
        switch (type) {
            case T_INT: {
                int n = info + 1;
                long raw = u(n);
                if (major >= 3) {
                    return new Long((raw >>> 1) ^ -(raw & 1)); // zigzag
                }
                int shift = 64 - 8 * n;
                return new Long((raw << shift) >> shift); // sign extend
            }
            case T_BOOL:
                return (info & 1) == 1 ? Boolean.TRUE : Boolean.FALSE;
            case T_DOUBLE:
                return new Double(Double.longBitsToDouble(u(8)));
            case T_STRING: {
                int len = (int) u(info + 1);
                String s = utf8Decode(d, pos, len);
                pos += len;
                return s;
            }
            case T_DATETIME: {
                FrpcDate dt = new FrpcDate();
                dt.zone = (byte) d[pos++];
                dt.unixSeconds = major >= 3 ? u(8) : (long) (int) u(4);
                long f = u(5); // packed wall-clock fields
                dt.weekday = (int) (f & 7);
                dt.second = (int) ((f >> 3) & 63);
                dt.minute = (int) ((f >> 9) & 63);
                dt.hour = (int) ((f >> 15) & 31);
                dt.day = (int) ((f >> 20) & 31);
                dt.month = (int) ((f >> 25) & 15);
                dt.year = (int) ((f >> 29) & 2047) + 1600;
                return dt;
            }
            case T_BINARY: {
                int len = (int) u(info + 1);
                byte[] b = new byte[len];
                System.arraycopy(d, pos, b, 0, len);
                pos += len;
                return b;
            }
            case T_INT_POS:
                return new Long(u(info + 1));
            case T_INT_NEG:
                return new Long(-u(info + 1));
            case T_STRUCT: {
                long n = u(info + 1);
                FrpcStruct s = new FrpcStruct();
                for (long i = 0; i < n; i++) {
                    int kl = d[pos++] & 0xFF;
                    String key = utf8Decode(d, pos, kl);
                    pos += kl;
                    s.put(key, readValue());
                }
                return s;
            }
            case T_ARRAY: {
                long n = u(info + 1);
                Vector a = new Vector((int) Math.min(n, 1024));
                for (long i = 0; i < n; i++) a.addElement(readValue());
                return a;
            }
            case T_NULL:
                return null;
            case T_METHOD_RESPONSE:
                return readValue();
            case T_FAULT: {
                Object code = readValue();
                Object msg = readValue();
                throw new Exception("FastRPC fault " + code + ": " + msg);
            }
            case T_METHOD_CALL:
            default:
                throw new Exception("FastRPC: unexpected type " + type + " at " + (pos - 1));
        }
    }

    // ============================== UTF-8 ==============================
    // Done by hand: String.getBytes("UTF-8") isn't guaranteed on every CLDC device.

    static byte[] utf8Encode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (c >= 0xD800 && c <= 0xDBFF && i + 1 < s.length()) {
                int lo = s.charAt(i + 1);
                if (lo >= 0xDC00 && lo <= 0xDFFF) {
                    int cp = 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00);
                    out.write(0xF0 | (cp >> 18));
                    out.write(0x80 | ((cp >> 12) & 0x3F));
                    out.write(0x80 | ((cp >> 6) & 0x3F));
                    out.write(0x80 | (cp & 0x3F));
                    i++;
                    continue;
                }
            }
            if (c < 0x80) {
                out.write(c);
            }
            else if (c < 0x800) {
                out.write(0xC0 | (c >> 6));
                out.write(0x80 | (c & 0x3F));
            }
            else {
                out.write(0xE0 | (c >> 12));
                out.write(0x80 | ((c >> 6) & 0x3F));
                out.write(0x80 | (c & 0x3F));
            }
        }
        return out.toByteArray();
    }

    static String utf8Decode(byte[] b, int off, int len) {
        StringBuffer sb = new StringBuffer(len);
        int end = off + len;
        int i = off;
        while (i < end) {
            int c = b[i++] & 0xFF;
            if (c < 0x80) {
                sb.append((char) c);
            }
            else if ((c & 0xE0) == 0xC0 && i < end) {
                sb.append((char) (((c & 0x1F) << 6) | (b[i++] & 0x3F)));
            }
            else if ((c & 0xF0) == 0xE0 && i + 1 < end) {
                sb.append((char) (((c & 0x0F) << 12) | ((b[i] & 0x3F) << 6) | (b[i + 1] & 0x3F)));
                i += 2;
            }
            else if ((c & 0xF8) == 0xF0 && i + 2 < end) {
                int cp = ((c & 0x07) << 18) | ((b[i] & 0x3F) << 12) | ((b[i + 1] & 0x3F) << 6) | (b[i + 2] & 0x3F);
                i += 3;
                cp -= 0x10000;
                sb.append((char) (0xD800 + (cp >> 10)));
                sb.append((char) (0xDC00 + (cp & 0x3FF)));
            }
            else {
                sb.append('?');
            }
        }
        return sb.toString();
    }
}
