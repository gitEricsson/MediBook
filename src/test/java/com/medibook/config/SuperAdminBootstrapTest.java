package com.medibook.config;

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
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SuperAdminBootstrap — Unit Tests")
class SuperAdminBootstrapTest {

    @Mock UserRepository  userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @Mock ApplicationArguments args;

    @InjectMocks
    SuperAdminBootstrap bootstrap;

    @BeforeEach
    void injectProperties() {
        ReflectionTestUtils.setField(bootstrap, "email",     "super@hospital.test");
        ReflectionTestUtils.setField(bootstrap, "password",  "SecurePass1!");
        ReflectionTestUtils.setField(bootstrap, "firstName", "Super");
        ReflectionTestUtils.setField(bootstrap, "lastName",  "Admin");
        ReflectionTestUtils.setField(bootstrap, "phone",     "");
    }

    @Test
    @DisplayName("creates super admin when none exists")
    void run_noSuperAdminExists_createsSuperAdmin() throws Exception {
        when(userRepository.existsByRole(Role.ROLE_SUPER_ADMIN)).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("super@hospital.test")).thenReturn(false);
        when(passwordEncoder.encode("SecurePass1!")).thenReturn("hashed");

        User saved = User.builder()
                .id(1L)
                .email("super@hospital.test")
                .role(Role.ROLE_SUPER_ADMIN)
                .enabled(true)
                .isActive(true)
                .password("hashed")
                .firstName("Super")
                .lastName("Admin")
                .build();
        when(userRepository.save(any())).thenReturn(saved);

        bootstrap.run(args);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User persisted = captor.getValue();
        assertThat(persisted.getRole()).isEqualTo(Role.ROLE_SUPER_ADMIN);
        assertThat(persisted.getEmail()).isEqualTo("super@hospital.test");
        assertThat(persisted.getPassword()).isEqualTo("hashed");
        assertThat(persisted.isEnabled()).isTrue();
        assertThat(persisted.isActive()).isTrue();
    }

    @Test
    @DisplayName("password is encoded — raw password is never stored")
    void run_passwordIsEncoded() throws Exception {
        when(userRepository.existsByRole(Role.ROLE_SUPER_ADMIN)).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase(anyString())).thenReturn(false);
        when(passwordEncoder.encode("SecurePass1!")).thenReturn("bcrypt-hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        bootstrap.run(args);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPassword())
                .isEqualTo("bcrypt-hash")
                .doesNotContain("SecurePass1!");
    }

    @Test
    @DisplayName("does not create duplicate when super admin already exists")
    void run_superAdminAlreadyExists_skips() throws Exception {
        when(userRepository.existsByRole(Role.ROLE_SUPER_ADMIN)).thenReturn(true);

        bootstrap.run(args);

        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    @DisplayName("fails fast when SUPER_ADMIN_EMAIL is blank")
    void run_blankEmail_throwsIllegalState() {
        when(userRepository.existsByRole(Role.ROLE_SUPER_ADMIN)).thenReturn(false);
        ReflectionTestUtils.setField(bootstrap, "email", "");

        assertThatThrownBy(() -> bootstrap.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPER_ADMIN_EMAIL");
    }

    @Test
    @DisplayName("fails fast when SUPER_ADMIN_PASSWORD is blank")
    void run_blankPassword_throwsIllegalState() {
        when(userRepository.existsByRole(Role.ROLE_SUPER_ADMIN)).thenReturn(false);
        ReflectionTestUtils.setField(bootstrap, "password", "  ");

        assertThatThrownBy(() -> bootstrap.run(args))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPER_ADMIN_PASSWORD");
    }

    @Test
    @DisplayName("skips creation if configured email already exists under a different role")
    void run_emailExistsWithDifferentRole_skips() throws Exception {
        when(userRepository.existsByRole(Role.ROLE_SUPER_ADMIN)).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("super@hospital.test")).thenReturn(true);

        bootstrap.run(args);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("normalises email to lower-case before persisting")
    void run_emailNormalized_toLowerCase() throws Exception {
        ReflectionTestUtils.setField(bootstrap, "email", "  UPPER@Hospital.Test  ");
        when(userRepository.existsByRole(Role.ROLE_SUPER_ADMIN)).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("upper@hospital.test")).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        bootstrap.run(args);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("upper@hospital.test");
    }
}
