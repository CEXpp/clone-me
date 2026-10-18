package cyou.jujube.common;

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
