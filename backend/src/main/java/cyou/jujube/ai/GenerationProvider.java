package cyou.jujube.ai;

/** 生成模型的业务接口，隔离 Spring AI 和具体供应商的实现。 资料提取、回答生成、答案核验通过 stage 区分记账和诊断，但都遵循先预留后调用的费用流程。 */
public interface GenerationProvider {
  String generate(String requestId, String stage, String system, String user);

  boolean configured();
}
