package com.medibook.security;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.lang.annotation.*;

/**
 * Convenience annotation to inject the currently authenticated {@link UserPrincipal}
 * directly into controller method parameters.
 *
 * Usage: {@code public ResponseEntity<?> example(@CurrentUser UserPrincipal user)}
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal
public @interface CurrentUser {
}
