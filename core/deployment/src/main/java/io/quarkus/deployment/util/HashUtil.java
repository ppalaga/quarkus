package io.quarkus.deployment.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class HashUtil {

    private HashUtil() {
    }

    public static String sha1(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(value.getBytes(StandardCharsets.UTF_8));
            return formatDigest(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String formatDigest(byte[] digest) {
        final StringBuilder sb = new StringBuilder(40);
        for (int i = 0; i < digest.length; ++i) {
            sb.append(Integer.toHexString((digest[i] & 0xFF) | 0x100).substring(1, 3));
        }
        return sb.toString();
    }

    public static String sha1(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            final byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) >= 0) {
                md.update(buf, 0, len);
            }
            return formatDigest(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new RuntimeException(String.format("Could not read %s", file), e);
        }
    }

    public static String methodSignatureSha1(Method method) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(method.getReturnType().getName().getBytes(StandardCharsets.UTF_8));
            md.update(((byte) ' '));
            md.update(method.getName().getBytes(StandardCharsets.UTF_8));
            md.update(((byte) '('));
            boolean first = true;
            for (Class<?> paramType : method.getParameterTypes()) {
                if (first) {
                    first = false;
                } else {
                    md.update(((byte) ','));
                }
                md.update(paramType.getName().getBytes(StandardCharsets.UTF_8));
            }
            md.update(((byte) ')'));
            byte[] digest = md.digest();
            final StringBuilder sb = new StringBuilder(40);
            for (int i = 0; i < digest.length; ++i) {
                sb.append(Integer.toHexString((digest[i] & 0xFF) | 0x100).substring(1, 3));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
