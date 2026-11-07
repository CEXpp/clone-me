package cyou.jujube.ai;

/** 文本向量接口，导入索引与查询必须使用相同模型、维度和归一化方式。 fingerprint 标识完整向量空间；更换配置后必须重新建索引，不能直接混用旧向量。 */
public interface EmbeddingProvider {
  // 向量是描述文本语义的一组数字；这里只返回 float 数组，不返回自然语言回答。
  float[] embed(String requestId, String text);

  String fingerprint();

  boolean configured();
}
