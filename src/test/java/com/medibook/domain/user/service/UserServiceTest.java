package com.medibook.domain.user.service;

import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.user.dto.UserResponse;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — Unit Tests")
class UserServiceTest {

    @Mock UserRepository userRepository;
    @Mock AuthService    authService;
    @Mock RefreshTokenService refreshTokenService;

    @InjectMocks UserService userService;

    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder()
                .id(1L).email("alice@test.com")
                .firstName("Alice").lastName("Patient")
                .role(Role.ROLE_PATIENT).enabled(true).build();
    }


    @Test
    @DisplayName("getUserById — existing user returns mapped UserResponse")
    void getUserById_existing_returnsMappedResponse() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        UserResponse response = userService.getUserById(1L);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getEmail()).isEqualTo("alice@test.com");
        assertThat(response.getFullName()).isEqualTo("Alice Patient");
        assertThat(response.getRole()).isEqualTo(Role.ROLE_PATIENT);
    }

    @Test
    @DisplayName("getUserById — unknown ID throws NOT_FOUND")
    void getUserById_notFound_throwsNotFound() {
        when(userRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserById(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }


    @Test
    @DisplayName("getAllUsers — returns paginated list mapped to UserResponse")
    void getAllUsers_returnsMappedPage() {
        var pageable = PageRequest.of(0, 20);
        when(userRepository.findAll(pageable))
                .thenReturn(new PageImpl<>(List.of(user), pageable, 1));

        Page<UserResponse> result = userService.getAllUsers(pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).getEmail()).isEqualTo("alice@test.com");
    }


    @Test
    @DisplayName("enableTwoFactor — delegates to AuthService.enableTwoFactor with the same userId")
    void enableTwoFactor_delegatesToAuthService() {
        userService.enableTwoFactor(1L);

        verify(authService).enableTwoFactor(1L);
    }


    @Test
    @DisplayName("disableUser — sets enabled=false on the user and saves")
    void disableUser_setsEnabledFalseAndSaves() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        userService.disableUser(1L);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().isEnabled()).isFalse();
        verify(refreshTokenService).revokeAllForUser(1L);
    }

    @Test
    @DisplayName("disableUser — unknown ID throws NOT_FOUND without saving")
    void disableUser_notFound_throwsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.disableUser(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(userRepository, never()).save(any());
    }


    @Test
    @DisplayName("getByEmail — existing email returns UserResponse")
    void getByEmail_existing_returnsResponse() {
        when(userRepository.findByEmail("alice@test.com")).thenReturn(Optional.of(user));

        UserResponse response = userService.getByEmail("alice@test.com");

        assertThat(response.getEmail()).isEqualTo("alice@test.com");
    }

    @Test
    @DisplayName("getByEmail — unknown email throws NOT_FOUND")
    void getByEmail_notFound_throwsNotFound() {
        when(userRepository.findByEmail("ghost@test.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getByEmail("ghost@test.com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
