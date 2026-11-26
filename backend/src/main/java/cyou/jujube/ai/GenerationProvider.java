package cyou.jujube.ai;

/** 生成模型的业务接口，隔离 Spring AI 和具体供应商的实现。 资料提取、回答生成、答案核验通过 stage 区分记账和诊断，但都遵循先预留后调用的费用流程。 */
public interface GenerationProvider {
  // interface 只约定能力；ChatService 面向此接口，Spring 注入 DeepSeekProvider 实现。
  // system 是规则提示，user 是本次输入数据；返回文本仍需由业务层解析和验证。
  String generate(String requestId, String stage, String system, String user);

  boolean configured();
}
