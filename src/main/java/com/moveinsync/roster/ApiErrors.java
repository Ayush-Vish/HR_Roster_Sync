package com.moveinsync.roster;

import static com.moveinsync.roster.Models.Invalid;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** Stable, non-leaking API errors. Request bodies and exception messages are never logged. */
@RestControllerAdvice
public final class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(Invalid.class)
    ResponseEntity<?> invalid(Invalid error) {
        HttpStatus status =
                error.code.equals("NOT_FOUND")
                        ? HttpStatus.NOT_FOUND
                        : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(Map.of("code", error.code));
    }

    @ExceptionHandler({
        org.springframework.http.converter.HttpMessageNotReadableException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
        org.springframework.web.multipart.MultipartException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    Object malformed(Exception ignored) {
        return Map.of("code", "INVALID_REQUEST");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    Object unexpected(Exception error) {
        LOG.atError()
                .addKeyValue("error_type", error.getClass().getSimpleName())
                .log("request_failed");
        return Map.of("code", "INTERNAL_ERROR");
    }
}
