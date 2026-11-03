package com.scooterrentalkandy.spring.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthenticationResultType;

import java.util.Map;

@Service
public class CognitoAuthService {
    private final CognitoIdentityProviderClient cognito;
    private final String clientId;

    public CognitoAuthService(@Value("${app.cognito.region}") String region,
                              @Value("${app.cognito.client-id}") String clientId) {
        this.cognito = CognitoIdentityProviderClient.builder().region(Region.of(region)).build();
        this.clientId = clientId;
    }

    public void register(String email, String password) {
        cognito.signUp(r -> r.clientId(clientId).username(email).password(password)
                .userAttributes(AttributeType.builder().name("email").value(email).build()));
    }

    public void confirm(String email, String code) {
        cognito.confirmSignUp(r -> r.clientId(clientId).username(email).confirmationCode(code));
    }

    public AuthenticationResultType login(String email, String password) {
        return cognito.initiateAuth(r -> r.clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", email, "PASSWORD", password))).authenticationResult();
    }

    public AuthenticationResultType refresh(String refreshToken) {
        return cognito.initiateAuth(r -> r.clientId(clientId)
                .authFlow(AuthFlowType.REFRESH_TOKEN_AUTH)
                .authParameters(Map.of("REFRESH_TOKEN", refreshToken))).authenticationResult();
    }
}
