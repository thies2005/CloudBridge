package io.github.x0b.safdav;

import java.nio.charset.StandardCharsets;

public final class SafBase64 {

    private static final char[] STD_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
    private static final char[] URL_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private SafBase64() {
    }

    public static String encodeStandard(byte[] input) {
        return encode(input, STD_ALPHABET, true);
    }

    public static String encodeUrlSafeNoPadding(byte[] input) {
        return encode(input, URL_ALPHABET, false);
    }

    public static String encodeBasicAuth(String username, String password) {
        byte[] raw = (username + ':' + password).getBytes(StandardCharsets.UTF_8);
        return "Basic " + encodeStandard(raw);
    }

    private static String encode(byte[] input, char[] alphabet, boolean pad) {
        if (input == null || input.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(((input.length + 2) / 3) * 4);
        int i = 0;
        while (i < input.length) {
            int b0 = input[i++] & 0xff;
            int b1 = i < input.length ? (input[i++] & 0xff) : -1;
            int b2 = i < input.length ? (input[i++] & 0xff) : -1;

            sb.append(alphabet[b0 >>> 2]);
            sb.append(alphabet[((b0 & 0x03) << 4) | (b1 >= 0 ? (b1 >>> 4) : 0)]);
            if (b1 >= 0) {
                sb.append(alphabet[((b1 & 0x0f) << 2) | (b2 >= 0 ? (b2 >>> 6) : 0)]);
            } else if (pad) {
                sb.append('=');
            }
            if (b2 >= 0) {
                sb.append(alphabet[b2 & 0x3f]);
            } else if (pad) {
                sb.append('=');
            }
        }
        return sb.toString();
    }
}
