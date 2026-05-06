package com.medibook.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TokenResponse {

    private UserResponse user;

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private Long expiresIn;

    private Boolean twoFactorRequired;

    public static TokenResponse of(String accessToken, String refreshToken, long expiresInMs) {
        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(expiresInMs / 1000)
                .build();
    }

    public static TokenResponse twoFactorChallenge() {
        return TokenResponse.builder()
                .twoFactorRequired(true)
                .build();
    }
}
