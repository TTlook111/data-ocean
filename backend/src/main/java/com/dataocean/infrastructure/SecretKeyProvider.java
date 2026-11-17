package com.dataocean.infrastructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SecretKeyProvider {
    private final byte[] key;
    public SecretKeyProvider(@Value("${dataocean.secret-key-file}") String file) throws Exception {
        key = Files.readString(Path.of(file), StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalStateException("A private HMAC key of at least 32 bytes is required");
    }
    public byte[] hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { throw new IllegalStateException("Cannot compute command fingerprint",error); }
    }
    public boolean matches(byte[] left, byte[] right) { return MessageDigest.isEqual(left,right); }
}
