package org.lotro.conductor;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;

/** Prevents an unauthenticated LAN peer from registering characters or issuing actions. */
public final class Auth {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Auth() { }
    public static String nonce() {
        byte[] bytes = new byte[24]; RANDOM.nextBytes(bytes); return HexFormat.of().formatHex(bytes);
    }
    public static String response(String key, String nonce, String nodeId, String role) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((nonce + ":" + nodeId + ":" + role).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    public static boolean equalsSecure(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }
}
