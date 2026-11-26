package cyou.jujube.security;

import cyou.jujube.common.ApiException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** 封装随机令牌、SHA-256 哈希以及 AES-GCM 认证加密。 令牌和恢复码用哈希验证即可；TOTP 秘密需要恢复原文计算验证码，所以必须使用主密钥加密保存。 */
public final class Crypto {
  private static final SecureRandom RANDOM = new SecureRandom();

  private Crypto() {}

  public static String token() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String hash(String value) {
    return hash(value.getBytes(StandardCharsets.UTF_8));
  }

  public static String hash(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (Exception ex) {
      throw new IllegalStateException(ex);
    }
  }

  public static String hash(java.nio.file.Path file) throws java.io.IOException {
    try (var input = java.nio.file.Files.newInputStream(file)) {
      var digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
      return HexFormat.of().formatHex(digest.digest());
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  /** 每次生成新的随机初始化向量，使用认证加密保护 TOTP 秘密；相同明文也不应生成相同密文。 */
  public static String encrypt(String value, String master) {
    try {
      byte[] nonce = new byte[12];
      RANDOM.nextBytes(nonce);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(master), new GCMParameterSpec(128, nonce));
      byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder()
          .encodeToString(
              ByteBuffer.allocate(nonce.length + encrypted.length)
                  .put(nonce)
                  .put(encrypted)
                  .array());
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new IllegalStateException("Cannot protect authentication secret", ex);
    }
  }

  /** 验证认证标签后再返回明文；主密钥错误或密文被篡改必须失败，不能继续使用不可信数据。 */
  public static String decrypt(String value, String master) {
    try {
      ByteBuffer bytes = ByteBuffer.wrap(Base64.getDecoder().decode(value));
      byte[] nonce = new byte[12];
      bytes.get(nonce);
      byte[] encrypted = new byte[bytes.remaining()];
      bytes.get(encrypted);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(master), new GCMParameterSpec(128, nonce));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (ApiException ex) {
      throw ex;
    } catch (Exception ex) {
      throw ApiException.unavailable("AUTH_KEY_INVALID", "管理员保护密钥不可用");
    }
  }

  private static SecretKeySpec key(String master) {
    try {
      byte[] key = Base64.getDecoder().decode(master);
      if (key.length != 32) throw new IllegalArgumentException();
      return new SecretKeySpec(key, "AES");
    } catch (Exception ex) {
      throw ApiException.unavailable("MASTER_KEY_REQUIRED", "需要配置 32 字节 Base64 管理员保护密钥");
    }
  }
}
