package com.leanowtech.bloge.gateway.testkit.acceptance;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Domain2: SHA256( UTF8(domain) || I32BE(payload.length) || payload ).
 * Package-private: used by Compiler, Protocol, and PrimitiveRegistry.
 */
final class Domain2 {
    private Domain2() {}

    static String compute(String domain, byte[] payload) {
        byte[] d = domain.getBytes(StandardCharsets.UTF_8);
        byte[] l = ByteBuffer.allocate(4).putInt(payload.length).array();
        byte[] in = new byte[d.length + 4 + payload.length];
        System.arraycopy(d, 0, in, 0, d.length);
        System.arraycopy(l, 0, in, d.length, 4);
        System.arraycopy(payload, 0, in, d.length + 4, payload.length);
        byte[] hash;
        try { hash = MessageDigest.getInstance("SHA-256").digest(in); }
        catch (Exception e) { throw new RuntimeException("SHA-256 unavailable", e); }
        return "sha256:" + toHex(hash);
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(Character.forDigit((v >>> 4) & 0xf, 16));
            sb.append(Character.forDigit( v        & 0xf, 16));
        }
        return sb.toString();
    }
}
