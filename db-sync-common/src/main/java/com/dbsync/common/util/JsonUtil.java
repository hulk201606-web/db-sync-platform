package com.dbsync.common.util;

import com.dbsync.common.exception.DbSyncException;
import com.dbsync.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 序列化/反序列化（不引入第三方库，保持 {@code db-sync-common} 零重量级依赖）。
 *
 * <p>支持：{@code Map}、{@code List}、{@code String}、{@code Number}、{@code Boolean}、{@code null}、
 * {@code byte[]}（Base64）、其它对象（{@code toString()} 加引号）。
 * 解析结果类型：{@code Map<String,Object>}、{@code List<Object>}、{@code String}、
 * {@link BigDecimal}、{@code Boolean}、{@code null}。
 *
 * <p>不支持：注释、尾随逗号、多文档、非标准转义扩展。元数据库里只存平台自己写的 JSON，
 * 因此够用；如需复杂 JSON 能力，上层模块（api）可用 Jackson，但**不得**把 Jackson 引入 common。
 */
public final class JsonUtil {

    private JsonUtil() {
    }

    /**
     * 序列化。
     *
     * @param value 任意值
     * @return JSON 串（不为 null）
     */
    public static String toJson(Object value) {
        StringBuilder sb = new StringBuilder();
        write(value, sb);
        return sb.toString();
    }

    /**
     * 反序列化。
     *
     * @param json JSON 串
     * @return 解析结果
     * @throws DbSyncException {@code E1003} 解析失败
     */
    public static Object parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Parser p = new Parser(json);
            Object v = p.readValue();
            p.skipWs();
            return v;
        } catch (RuntimeException e) {
            throw new DbSyncException(ErrorCode.JSON_SERIALIZE_FAILED, e,
                    json.length() > 64 ? json.substring(0, 64) + "..." : json);
        }
    }

    /**
     * 反序列化为对象。
     *
     * @param json JSON 串
     * @return map；非对象时返回空 map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object v = parse(json);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    /**
     * 反序列化为数组。
     *
     * @param json JSON 串
     * @return list；非数组时返回空 list
     */
    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String json) {
        Object v = parse(json);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    /**
     * 写值。
     *
     * @param v  值
     * @param sb 输出
     */
    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String) {
            writeString((String) v, sb);
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Number) {
            sb.append(v instanceof BigDecimal ? ((BigDecimal) v).toPlainString() : v.toString());
        } else if (v instanceof byte[]) {
            writeString(java.util.Base64.getEncoder().encodeToString((byte[]) v), sb);
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object o : (Iterable<?>) v) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else if (v.getClass().isArray()) {
            sb.append('[');
            int len = java.lang.reflect.Array.getLength(v);
            for (int i = 0; i < len; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                write(java.lang.reflect.Array.get(v, i), sb);
            }
            sb.append(']');
        } else {
            writeString(v.toString(), sb);
        }
    }

    /**
     * 写字符串（含转义）。
     *
     * @param s  字符串
     * @param sb 输出
     */
    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** 递归下降解析器。 */
    private static final class Parser {
        private final String src;
        private int pos;

        private Parser(String src) {
            this.src = src;
            this.pos = 0;
        }

        private Object readValue() {
            skipWs();
            if (pos >= src.length()) {
                return null;
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<>();
            pos++;
            skipWs();
            if (peek() == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                if (peek() != ':') {
                    throw new IllegalStateException("缺少 ':' @" + pos);
                }
                pos++;
                map.put(key, readValue());
                skipWs();
                char c = peek();
                pos++;
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalStateException("对象分隔符非法 @" + pos);
                }
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<>();
            pos++;
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWs();
                char c = peek();
                pos++;
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new IllegalStateException("数组分隔符非法 @" + pos);
                }
            }
        }

        private String readString() {
            if (peek() != '"') {
                throw new IllegalStateException("期望字符串 @" + pos);
            }
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw new IllegalStateException("非法转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalStateException("字符串未闭合");
        }

        private BigDecimal readNumber() {
            int start = pos;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            return new BigDecimal(src.substring(start, pos));
        }

        private void expect(String token) {
            if (!src.startsWith(token, pos)) {
                throw new IllegalStateException("期望 " + token + " @" + pos);
            }
            pos += token.length();
        }

        private char peek() {
            return pos < src.length() ? src.charAt(pos) : '\0';
        }

        private void skipWs() {
            while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}
