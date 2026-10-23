package com.scooterrentalkandy.spring.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;
import com.scooterrentalkandy.spring.dto.UserDtos.LoginRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.RegisterRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.UpdateProfileRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Bean Validation rules of the auth request DTOs, without Spring or a database. */
class AuthDtosValidationTests {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    private static RegisterRequest register(String email, String password, String fullName, String phone, String nic) {
        return new RegisterRequest(email, password, fullName, phone, nic, "", "", "");
    }

    private static RegisterRequest validRegister() {
        return register("new.customer@example.test", "secret-pass-1", "New Customer", "+94 70 000 0000", "T00000000001");
    }

    private static Set<String> invalidFields(Object request) {
        return validator.validate(request).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void acceptsAValidRegisterRequestWithEmptyOptionalFields() {
        assertThat(invalidFields(validRegister())).isEmpty();
    }

    @Test
    void registerRequiresAValidEmail() {
        assertThat(invalidFields(register(null, "secret-pass-1", "N", "1", "T1"))).containsExactly("email");
        assertThat(invalidFields(register("  ", "secret-pass-1", "N", "1", "T1"))).containsExactly("email");
        assertThat(invalidFields(register("not-an-email", "secret-pass-1", "N", "1", "T1"))).containsExactly("email");
    }

    @Test
    void registerRequiresAPasswordOf8To72Characters() {
        assertThat(invalidFields(register("a@example.test", null, "N", "1", "T1"))).containsExactly("password");
        assertThat(invalidFields(register("a@example.test", " ", "N", "1", "T1"))).containsExactly("password");
        assertThat(invalidFields(register("a@example.test", "short7!", "N", "1", "T1"))).containsExactly("password");
        assertThat(invalidFields(register("a@example.test", "p".repeat(73), "N", "1", "T1"))).containsExactly("password");
        assertThat(invalidFields(register("a@example.test", "p".repeat(72), "N", "1", "T1"))).isEmpty();
    }

    @Test
    void registerRequiresFullNameAndPhone() {
        assertThat(invalidFields(register("a@example.test", "secret-pass-1", " ", "1", "T1"))).containsExactly("fullName");
        assertThat(invalidFields(register("a@example.test", "secret-pass-1", "N", null, "T1"))).containsExactly("phone");
        assertThat(invalidFields(register("a@example.test", "secret-pass-1", "N", "1".repeat(21), "T1")))
                .containsExactly("phone");
    }

    @Test
    void registerRequiresANicOfAtMost20Characters() {
        assertThat(invalidFields(register("a@example.test", "secret-pass-1", "N", "1", null))).containsExactly("nicNumber");
        assertThat(invalidFields(register("a@example.test", "secret-pass-1", "N", "1", "   "))).containsExactly("nicNumber");
        assertThat(invalidFields(register("a@example.test", "secret-pass-1", "N", "1", "T".repeat(21))))
                .containsExactly("nicNumber");
        assertThat(invalidFields(register("a@example.test", "secret-pass-1", "N", "1", "T".repeat(20)))).isEmpty();
    }

    @Test
    void registerLimitsOptionalFieldsToTheColumnSize() {
        RegisterRequest tooLong = new RegisterRequest("a@example.test", "secret-pass-1", "N", "1", "T1",
                "x".repeat(101), "x".repeat(101), "x".repeat(101));
        assertThat(invalidFields(tooLong)).containsExactlyInAnyOrder("idDocumentNumber", "drivingLicenseNo", "country");
    }

    @Test
    void loginRequiresEmailAndPassword() {
        assertThat(invalidFields(new LoginRequest("a@example.test", "any"))).isEmpty();
        assertThat(invalidFields(new LoginRequest("not-an-email", "any"))).containsExactly("email");
        assertThat(invalidFields(new LoginRequest(" ", " "))).containsExactlyInAnyOrder("email", "password");
    }

    @Test
    void profileUpdateRequiresFullNameAndPhone() {
        assertThat(invalidFields(new UpdateProfileRequest("Name", "+94 70 000 0000", null, null, null))).isEmpty();
        assertThat(invalidFields(new UpdateProfileRequest(" ", null, null, null, null)))
                .containsExactlyInAnyOrder("fullName", "phone");
    }
}
