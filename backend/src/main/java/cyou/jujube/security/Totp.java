package cyou.jujube.security;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class Totp {
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

  private Totp() {}

  public static String newSecret() {
    byte[] bytes = new byte[20];
    new SecureRandom().nextBytes(bytes);
    StringBuilder out = new StringBuilder();
    int bits = 0, value = 0;
    for (byte b : bytes) {
      value = (value << 8) | (b & 255);
      bits += 8;
      while (bits >= 5) {
        bits -= 5;
        out.append(ALPHABET.charAt((value >>> bits) & 31));
      }
    }
    return out.toString();
  }

  public static String code(String secret, long step) {
    try {
      int bits = 0, value = 0;
      var decoded = new java.io.ByteArrayOutputStream();
      for (char c : secret.toUpperCase(java.util.Locale.ROOT).toCharArray()) {
        int n = ALPHABET.indexOf(c);
        if (n < 0) throw new IllegalArgumentException();
        value = (value << 5) | n;
        bits += 5;
        if (bits >= 8) {
          bits -= 8;
          decoded.write((value >>> bits) & 255);
        }
      }
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(decoded.toByteArray(), "HmacSHA1"));
      byte[] digest = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int at = digest[digest.length - 1] & 15;
      int number =
          ((digest[at] & 127) << 24)
              | ((digest[at + 1] & 255) << 16)
              | ((digest[at + 2] & 255) << 8)
              | (digest[at + 3] & 255);
      return String.format(java.util.Locale.ROOT, "%06d", number % 1_000_000);
    } catch (Exception ex) {
      throw new IllegalArgumentException("Invalid TOTP secret", ex);
    }
  }

  public static long matchingStep(String secret, String input, long epochMillis) {
    if (input == null || !input.matches("\\d{6}")) return -1;
    long step = epochMillis / 30_000;
    for (long candidate = step - 1; candidate <= step + 1; candidate++)
      if (java.security.MessageDigest.isEqual(
          code(secret, candidate).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          input.getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return candidate;
    return -1;
  }
}
