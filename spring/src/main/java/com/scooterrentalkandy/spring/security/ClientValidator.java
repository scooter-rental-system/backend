package com.scooterrentalkandy.spring.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.Objects;

public class ClientValidator implements OAuth2TokenValidator<Jwt> {

    private final String audience;

    public ClientValidator(String audience) {
        this.audience = audience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {

        OAuth2Error error = new OAuth2Error(
                "invalid_token",
                "Invalid audience or client_id",
                null
        );

        Object audClaim = jwt.getClaims().get("aud");
        String clientIdClaim = (String) jwt.getClaims().get("client_id");

        boolean audValid = false;

        if (audClaim instanceof String aud) {
            audValid = Objects.equals(aud, audience);
        } else if (audClaim instanceof List<?> audList) {
            audValid = audList.contains(audience);
        }

        boolean clientValid = Objects.equals(clientIdClaim, audience);

        if (audValid || clientValid) {
            return OAuth2TokenValidatorResult.success();
        }

        return OAuth2TokenValidatorResult.failure(error);
    }
}