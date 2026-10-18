package cyou.jujube.common;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiErrors {
  private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

  @ExceptionHandler(ApiException.class)
  ResponseEntity<?> business(ApiException ex) {
    LOG.warn("stage=REQUEST event=REQUEST_REJECTED code={} status={}", ex.code(), ex.status());
    return error(ex.status(), ex.code(), ex.getMessage());
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    IllegalArgumentException.class,
    org.springframework.http.converter.HttpMessageNotReadableException.class
  })
  ResponseEntity<?> invalid(Exception ex) {
    return error(400, "INVALID_INPUT", "请求参数无效");
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<?> tooLarge(Exception ex) {
    return error(413, "FILE_TOO_LARGE", "文件超过上传大小限制");
  }

  @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
  ResponseEntity<?> notFound(Exception ex) {
    return error(404, "RESOURCE_NOT_FOUND", "请求的资源不存在");
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<?> unexpected(Exception ex) {
    var frames =
        java.util.Arrays.stream(ex.getStackTrace())
            .filter(f -> f.getClassName().startsWith("cyou.jujube."))
            .limit(8)
            .map(StackTraceElement::toString)
            .toList();
    LOG.error(
        "stage=REQUEST event=UNEXPECTED_FAILURE exceptionType={} applicationFrames={}",
        ex.getClass().getSimpleName(),
        frames);
    return error(500, "INTERNAL_ERROR", "请求处理失败，可凭请求编号查看诊断");
  }

  private ResponseEntity<?> error(int status, String code, String message) {
    return ResponseEntity.status(status)
        .body(
            Map.of(
                "code",
                code,
                "message",
                message,
                "requestId",
                MDC.get("requestId") == null ? "" : MDC.get("requestId")));
  }
}
