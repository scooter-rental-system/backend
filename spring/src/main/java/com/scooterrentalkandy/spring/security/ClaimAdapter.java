package com.scooterrentalkandy.spring.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClaimAdapter  implements Converter<Map<String, Object>, Map<String, Object>> {

    private final MappedJwtClaimSetConverter delegate =
            MappedJwtClaimSetConverter.withDefaults(Collections.emptyMap());

    @Override
    public Map<String, Object> convert(Map<String, Object> claims) {

        Map<String, Object> converted =
                new HashMap<>(delegate.convert(claims));

        Object groupsObj = converted.get("cognito:groups");

        List<String> roles =
                groupsObj instanceof List<?> list
                        ? list.stream()
                        .map(g -> "ROLE_" + g.toString().toUpperCase())
                        .toList()
                        : List.of();

        converted.put("roles", roles);
        return converted;
    }
}

