package ru.peredatchik.core;

import java.util.*;

/** Минимальный JSON (объекты, массивы, строки, числа, bool, null) без зависимостей. */
public final class Json {
    private Json() {}

    public static String write(Object o) { StringBuilder sb = new StringBuilder(); w(sb, o); return sb.toString(); }

    @SuppressWarnings("unchecked")
    private static void w(StringBuilder sb, Object o) {
        if (o == null) sb.append("null");
        else if (o instanceof String) str(sb, (String) o);
        else if (o instanceof Boolean || o instanceof Integer || o instanceof Long) sb.append(o);
        else if (o instanceof Number) sb.append(((Number) o).doubleValue());
        else if (o instanceof Map) {
            sb.append('{'); boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) {
                if (!first) sb.append(','); first = false;
                str(sb, e.getKey()); sb.append(':'); w(sb, e.getValue());
            }
            sb.append('}');
        } else if (o instanceof Collection) {
            sb.append('['); boolean first = true;
            for (Object x : (Collection<Object>) o) { if (!first) sb.append(','); first = false; w(sb, x); }
            sb.append(']');
        } else if (o instanceof long[]) {
            List<Object> l = new ArrayList<>(); for (long v : (long[]) o) l.add(v); w(sb, l);
        } else str(sb, o.toString());
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        sb.append('"');
    }

    public static Object parse(String s) {
        P p = new P(s); p.ws(); Object v = p.val(); p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("JSON: лишние символы");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(String s) { return (Map<String, Object>) parse(s); }

    private static final class P {
        final String s; int i;
        P(String s) { this.s = s; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        char peek() { if (i >= s.length()) throw new IllegalArgumentException("JSON: конец"); return s.charAt(i); }
        Object val() {
            char c = peek();
            if (c == '{') {
                i++; Map<String, Object> m = new LinkedHashMap<>(); ws();
                if (peek() == '}') { i++; return m; }
                while (true) {
                    ws(); String k = (String) val(); ws();
                    if (s.charAt(i++) != ':') throw new IllegalArgumentException("JSON: ожидалось :");
                    ws(); m.put(k, val()); ws();
                    char d = s.charAt(i++);
                    if (d == '}') return m;
                    if (d != ',') throw new IllegalArgumentException("JSON: ожидалось ,");
                }
            }
            if (c == '[') {
                i++; List<Object> l = new ArrayList<>(); ws();
                if (peek() == ']') { i++; return l; }
                while (true) {
                    ws(); l.add(val()); ws();
                    char d = s.charAt(i++);
                    if (d == ']') return l;
                    if (d != ',') throw new IllegalArgumentException("JSON: ожидалось ,");
                }
            }
            if (c == '"') {
                i++; StringBuilder sb = new StringBuilder();
                while (true) {
                    char d = s.charAt(i++);
                    if (d == '"') return sb.toString();
                    if (d == '\\') {
                        char e = s.charAt(i++);
                        switch (e) {
                            case 'n': sb.append('\n'); break;
                            case 'r': sb.append('\r'); break;
                            case 't': sb.append('\t'); break;
                            case 'b': sb.append('\b'); break;
                            case 'f': sb.append('\f'); break;
                            case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                            default: sb.append(e);
                        }
                    } else sb.append(d);
                }
            }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(st, i);
            if (n.isEmpty()) throw new IllegalArgumentException("JSON: неожиданный символ " + c);
            if (n.contains(".") || n.contains("e") || n.contains("E")) return Double.parseDouble(n);
            return Long.parseLong(n);
        }
    }

    public static long num(Object o) { return o == null ? 0 : ((Number) o).longValue(); }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
