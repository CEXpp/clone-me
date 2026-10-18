package cyou.jujube.ai;

public interface EmbeddingProvider {
  float[] embed(String requestId, String text);

  String fingerprint();

  boolean configured();
}
