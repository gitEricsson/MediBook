package com.medibook.domain.user.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TokenResponse {

    private String accessToken;
    private String refreshToken;
    private String tokenType;
    private long expiresIn;

    /** Set to true when 2FA is enabled and OTP step is required */
    private Boolean twoFactorRequired;

    /** Partial token only valid to complete the 2FA step */
    private String twoFactorToken;

    public static TokenResponse of(String accessToken, String refreshToken, long expiresInMs) {
        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(expiresInMs / 1000)
                .build();
    }

    public static TokenResponse twoFactorChallenge(String twoFactorToken) {
        return TokenResponse.builder()
                .twoFactorRequired(true)
                .twoFactorToken(twoFactorToken)
                .tokenType("Bearer")
                .build();
    }
}
