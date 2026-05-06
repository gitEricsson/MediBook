package com.medibook.security;

import com.medibook.domain.user.dto.UserResponse;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * Immutable Spring Security principal.
 * Carries all user state needed to build API responses without a second DB round-trip.
 * Implements {@link Serializable} so it can be stored in Redis-backed caches.
 */
@Getter
public class UserPrincipal implements UserDetails, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private final Long                                   id;
    private final String                                 email;
    private final String                                 password;
    private final String                                 firstName;
    private final String                                 lastName;
    private final String                                 phone;
    private final Role                                   role;
    private final LocalDate                              dateOfBirth;
    private final LocalDateTime                          createdAt;
    private final boolean                                enabled;
    private final boolean                                active;
    private final boolean                                twoFactorEnabled;
    private final Collection<? extends GrantedAuthority> authorities;

    private UserPrincipal(Long id, String email, String password,
                          String firstName, String lastName, String phone,
                          Role role, LocalDate dateOfBirth, LocalDateTime createdAt,
                          boolean enabled, boolean active, boolean twoFactorEnabled,
                          Collection<? extends GrantedAuthority> authorities) {
        this.id              = id;
        this.email           = email;
        this.password        = password;
        this.firstName       = firstName;
        this.lastName        = lastName;
        this.phone           = phone;
        this.role            = role;
        this.dateOfBirth     = dateOfBirth;
        this.createdAt       = createdAt;
        this.enabled         = enabled;
        this.active          = active;
        this.twoFactorEnabled = twoFactorEnabled;
        this.authorities     = authorities;
    }

    public static UserPrincipal fromUser(User user) {
        return new UserPrincipal(
                user.getId(),
                user.getEmail(),
                user.getPassword(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhone(),
                user.getRole(),
                user.getDateOfBirth(),
                user.getCreatedAt(),
                user.isEnabled(),
                user.isActive(),
                user.isTwoFactorEnabled(),
                List.of(new SimpleGrantedAuthority(user.getRole().name())));
    }

    /** Builds a response DTO without touching the database. */
    public UserResponse toUserResponse() {
        return UserResponse.builder()
                .id(id)
                .email(email)
                .firstName(firstName)
                .lastName(lastName)
                .fullName(firstName + " " + lastName)
                .phone(phone)
                .role(role)
                .enabled(enabled)
                .twoFactorEnabled(twoFactorEnabled)
                .createdAt(createdAt)
                .build();
    }

    public boolean hasRole(String roleName) {
        return authorities.stream().anyMatch(a -> a.getAuthority().equals(roleName));
    }

    @Override public String  getUsername()              { return email; }
    @Override public boolean isAccountNonExpired()      { return true; }
    @Override public boolean isAccountNonLocked()       { return true; }
    @Override public boolean isCredentialsNonExpired()  { return true; }
}
