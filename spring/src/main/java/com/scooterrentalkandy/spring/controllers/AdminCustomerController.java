package com.scooterrentalkandy.spring.controllers;

import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.UserDtos.UserResponse;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.UserRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/customers")
public class AdminCustomerController {

    private final UserRepository users;

    public AdminCustomerController(UserRepository users) {
        this.users = users;
    }

    @GetMapping
    public List<UserResponse> list() {
        return users.findByRoleOrderByCreatedAtDesc(Role.USER).stream().map(UserResponse::from).toList();
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable UUID id) {
        return UserResponse.from(users.findById(id).orElseThrow(() -> ApiException.notFound("Customer")));
    }

    @PatchMapping("/{id}/active")
    @Transactional
    public UserResponse setActive(@PathVariable UUID id, @RequestParam boolean value) {
        User user = users.findById(id).orElseThrow(() -> ApiException.notFound("Customer"));
        if (user.getRole() != Role.USER) {
            throw ApiException.badRequest("Only customer (USER) accounts can be toggled here");
        }
        user.setActive(value);
        return UserResponse.from(users.save(user));
    }
}
