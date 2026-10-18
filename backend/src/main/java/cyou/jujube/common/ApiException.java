package cyou.jujube.common;

/** 可预期的业务失败，携带 HTTP 状态、稳定错误码和可向用户展示的中文提示。 前端按错误码决定是否要求登录、验证或重试，不应依赖提示文案做分支判断。 */
public class ApiException extends RuntimeException {
  private final int status;
  private final String code;

  public ApiException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public int status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static ApiException badRequest(String code, String message) {
    return new ApiException(400, code, message);
  }

  public static ApiException unavailable(String code, String message) {
    return new ApiException(503, code, message);
  }

  public static ApiException missing() {
    return new ApiException(404, "NOT_FOUND", "记录不存在或不可访问");
  }
}
