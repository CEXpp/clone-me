package cyou.jujube.ai;

import java.util.List;
import java.util.Map;

/** 可替换的证据筛选接口，只评估传入资料是否足以回答，不负责补充个人事实。 正常返回 insufficient 表示证据不足；调用异常交给上层记录并回退基础检索，二者不能混同。 */
public interface DecisionProvider {
  // accepted 只能是输入证据的子集；insufficient=true 是正常业务结论，区别于服务报错。
  record Evidence(String id, String kind, String mastery, String content) {}

  record Judgment(
      List<Evidence> accepted,
      boolean insufficient,
      Map<String, Double> supportProbabilities,
      String provider,
      String model) {}

  Judgment evaluate(String requestId, String question, List<Evidence> evidence);
}
