package cyou.jujube.ai;

public interface GenerationProvider {
  String generate(String requestId, String stage, String system, String user);

  boolean configured();
}
