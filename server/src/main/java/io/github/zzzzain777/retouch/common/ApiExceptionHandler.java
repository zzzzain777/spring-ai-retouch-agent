package io.github.zzzzain777.retouch.common;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 统一错误响应 {@code {"detail": "..."}}。请求体或参数不合法一律 422（与上游 FastAPI 的约定一致），不用 Spring 默认的 400。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, String>> api(ApiException exc) {
        return body(exc.status(), exc.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalidBody(MethodArgumentNotValidException exc) {
        var error = exc.getBindingResult().getFieldErrors().stream().findFirst();
        String detail = error.map(field -> field.getField() + "：" + field.getDefaultMessage()).orElse("请求参数不合法");
        return body(HttpStatus.UNPROCESSABLE_CONTENT, detail);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            HandlerMethodValidationException.class})
    ResponseEntity<Map<String, String>> unreadable(Exception exc) {
        return body(HttpStatus.UNPROCESSABLE_CONTENT, "请求参数不合法");
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooLarge(MaxUploadSizeExceededException exc) {
        return body(HttpStatus.CONTENT_TOO_LARGE, "文件超过 20 MB 上限");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Map<String, String>> noRoute(NoResourceFoundException exc) {
        return body(HttpStatus.NOT_FOUND, "Not Found");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, String>> unexpected(Exception exc) {
        log.error("未处理的异常", exc);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误");
    }

    public static ResponseEntity<Map<String, String>> body(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(Map.of("detail", detail));
    }
}
