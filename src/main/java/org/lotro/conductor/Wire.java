package org.lotro.conductor;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Small bounded UTF-8 line protocol: TYPE|base64url(value)|... . Not a general RPC interface. */
public final class Wire {
    private Wire() { }
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    public record Message(String type, List<String> fields) {
        public String at(int i) { return fields.get(i); }
        public void size(int expected) throws IOException {
            if (fields.size() != expected) throw new IOException("Expected " + expected + " fields for " + type);
        }
    }
    public static String encode(String type, String... fields) {
        if (!type.matches("[A-Z_]{2,20}")) throw new IllegalArgumentException("Invalid message type");
        StringBuilder s = new StringBuilder(type);
        for (String field : fields) s.append('|').append(ENC.encodeToString(field.getBytes(StandardCharsets.UTF_8)));
        if (s.length() > 32768) throw new IllegalArgumentException("Message exceeds 32768 characters");
        return s.toString();
    }
    public static Message decode(String line) throws IOException {
        if (line == null || line.length() > 32768) throw new IOException("Message too long or missing");
        String[] parts = line.split("\\|", -1);
        if (parts.length > 160 || !parts[0].matches("[A-Z_]{2,20}")) throw new IOException("Malformed protocol message");
        ArrayList<String> fields = new ArrayList<>();
        try {
            for (int i = 1; i < parts.length; i++) {
                byte[] b = DEC.decode(parts[i]);
                if (b.length > 2048) throw new IOException("Oversized message field");
                fields.add(new String(b, StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException e) { throw new IOException("Invalid base64 message", e); }
        return new Message(parts[0], List.copyOf(fields));
    }
    /** Read without BufferedReader.readLine's unbounded allocation. */
    public static String read(Reader in) throws IOException {
        StringBuilder s = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return s.toString();
            if (c != '\r') { if (s.length() == 32768) throw new IOException("Line too long"); s.append((char) c); }
        }
        return s.isEmpty() ? null : throwEof();
    }
    private static String throwEof() throws EOFException { throw new EOFException("Incomplete line"); }
    public static void write(Writer out, String type, String... fields) throws IOException {
        out.write(encode(type, fields)); out.write('\n'); out.flush();
    }
}
