package io.agentscope.demo.app.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException ex, HttpServletRequest req) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        p.setTitle("Bad Request");
        p.setInstance(java.net.URI.create(req.getRequestURI()));
        return p;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail tooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        ProblemDetail p =
                ProblemDetail.forStatusAndDetail(
                        HttpStatus.PAYLOAD_TOO_LARGE, "上传文件超过大小限制");
        p.setTitle("Payload Too Large");
        p.setInstance(java.net.URI.create(req.getRequestURI()));
        return p;
    }
}
