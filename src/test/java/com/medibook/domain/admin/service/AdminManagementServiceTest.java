package com.medibook.domain.admin.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.admin.dto.CreateAdminRequest;
import com.medibook.domain.admin.dto.ResetAdminPasswordRequest;
import com.medibook.domain.admin.dto.UpdateAdminRequest;
import com.medibook.domain.user.dto.UserResponse;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.domain.user.service.RefreshTokenService;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminManagementService — Unit Tests")
class AdminManagementServiceTest {

    @Mock UserRepository      userRepository;
    @Mock PasswordEncoder     passwordEncoder;
    @Mock RefreshTokenService refreshTokenService;
    @Mock com.medibook.messaging.producer.AppointmentEventProducer eventProducer;

    @InjectMocks AdminManagementService service;

    private User adminUser;

    @BeforeEach
    void setUp() {
        adminUser = User.builder()
                .id(10L)
                .email("admin@medibook.com")
                .password("encoded")
                .firstName("Jane")
                .lastName("Admin")
                .role(Role.ROLE_ADMIN)
                .enabled(true)
                .isActive(true)
                .build();
    }

    // ─── createAdmin ────────────────────────────────────────────────────────

    @Test
    @DisplayName("createAdmin — success: persists admin with ROLE_ADMIN, active, and encoded password")
    void createAdmin_success() {
        CreateAdminRequest req = new CreateAdminRequest();
        req.setEmail("newadmin@medibook.com");
        req.setPassword("SecurePass1!");
        req.setFirstName("New");
        req.setLastName("Admin");

        when(userRepository.existsByEmailIgnoreCase("newadmin@medibook.com")).thenReturn(false);
        when(passwordEncoder.encode("SecurePass1!")).thenReturn("hashed");
        when(userRepository.save(any())).thenReturn(adminUser);

        UserResponse response = service.createAdmin(req);

        assertThat(response).isNotNull();
        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.getRole()).isEqualTo(Role.ROLE_ADMIN);
        assertThat(saved.isEnabled()).isTrue();
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getPassword()).isEqualTo("hashed");
    }

    @Test
    @DisplayName("createAdmin — duplicate email throws CONFLICT")
    void createAdmin_duplicateEmail_throwsConflict() {
        CreateAdminRequest req = new CreateAdminRequest();
        req.setEmail("admin@medibook.com");
        req.setPassword("SecurePass1!");
        req.setFirstName("X");
        req.setLastName("Y");

        when(userRepository.existsByEmailIgnoreCase("admin@medibook.com")).thenReturn(true);

        assertThatThrownBy(() -> service.createAdmin(req))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(userRepository, never()).save(any());
    }

    // ─── getAdmin ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getAdmin — returns admin if found with ROLE_ADMIN")
    void getAdmin_found() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(adminUser));

        UserResponse response = service.getAdmin(10L);

        assertThat(response.getId()).isEqualTo(10L);
        assertThat(response.getRole()).isEqualTo(Role.ROLE_ADMIN);
    }

    @Test
    @DisplayName("getAdmin — throws NOT_FOUND for non-existent id")
    void getAdmin_notFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAdmin(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getAdmin — throws NOT_FOUND when user exists but is not ROLE_ADMIN")
    void getAdmin_notAdmin_throwsNotFound() {
        User patient = User.builder().id(5L).role(Role.ROLE_PATIENT).email("p@x.com").build();
        when(userRepository.findById(5L)).thenReturn(Optional.of(patient));

        assertThatThrownBy(() -> service.getAdmin(5L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ─── listAdmins ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("listAdmins — without query delegates to findByRole")
    void listAdmins_noQuery_usesFindByRole() {
        PageRequest pageable = PageRequest.of(0, 20);
        Page<User> page = new PageImpl<>(List.of(adminUser));
        when(userRepository.findByRole(Role.ROLE_ADMIN, pageable)).thenReturn(page);

        Page<UserResponse> result = service.listAdmins(null, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(userRepository).findByRole(Role.ROLE_ADMIN, pageable);
        verify(userRepository, never()).searchByRole(any(), any(), any());
    }

    @Test
    @DisplayName("listAdmins — with query delegates to searchByRole")
    void listAdmins_withQuery_usesSearch() {
        PageRequest pageable = PageRequest.of(0, 20);
        Page<User> page = new PageImpl<>(List.of(adminUser));
        when(userRepository.searchByRole(Role.ROLE_ADMIN, "jane", pageable)).thenReturn(page);

        Page<UserResponse> result = service.listAdmins("jane", pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        verify(userRepository).searchByRole(Role.ROLE_ADMIN, "jane", pageable);
        verify(userRepository, never()).findByRole(any(), any());
    }

    // ─── updateAdmin ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateAdmin — persists updated name and phone")
    void updateAdmin_success() {
        UpdateAdminRequest req = new UpdateAdminRequest();
        req.setFirstName("Updated");
        req.setLastName("Name");
        req.setPhone("+1-800-555-0100");

        when(userRepository.findById(10L)).thenReturn(Optional.of(adminUser));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserResponse response = service.updateAdmin(10L, req);

        assertThat(response.getFirstName()).isEqualTo("Updated");
        assertThat(response.getLastName()).isEqualTo("Name");
    }

    // ─── activate / deactivate ───────────────────────────────────────────────

    @Test
    @DisplayName("activateAdmin — sets enabled=true and active=true")
    void activateAdmin_success() {
        adminUser.setEnabled(false);
        when(userRepository.findById(10L)).thenReturn(Optional.of(adminUser));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.activateAdmin(10L);

        assertThat(adminUser.isEnabled()).isTrue();
        assertThat(adminUser.isActive()).isTrue();
    }

    @Test
    @DisplayName("deactivateAdmin — sets enabled=false and revokes all sessions")
    void deactivateAdmin_revokesSessionsAndDisables() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(adminUser));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deactivateAdmin(10L);

        assertThat(adminUser.isEnabled()).isFalse();
        verify(refreshTokenService).revokeAllForUser(10L);
    }

    @Test
    @DisplayName("deactivateAdmin — throws FORBIDDEN when target is not ROLE_ADMIN")
    void deactivateAdmin_nonAdmin_throwsForbidden() {
        User superAdmin = User.builder().id(1L).role(Role.ROLE_SUPER_ADMIN).email("sa@x.com")
                .enabled(true).isActive(true).password("x").firstName("S").lastName("A").build();
        when(userRepository.findById(1L)).thenReturn(Optional.of(superAdmin));

        assertThatThrownBy(() -> service.deactivateAdmin(1L))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ─── deleteAdmin ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteAdmin — soft deletes by disabling the account")
    void deleteAdmin_softDelete() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(adminUser));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.deleteAdmin(10L);

        assertThat(adminUser.isEnabled()).isFalse();
        verify(refreshTokenService).revokeAllForUser(10L);
    }

    // ─── resetAdminPassword ──────────────────────────────────────────────────

    @Test
    @DisplayName("resetAdminPassword — encodes new password and revokes all sessions")
    void resetAdminPassword_encodesAndRevokes() {
        ResetAdminPasswordRequest req = new ResetAdminPasswordRequest();
        req.setNewPassword("NewSecure1!");

        when(userRepository.findById(10L)).thenReturn(Optional.of(adminUser));
        when(passwordEncoder.encode("NewSecure1!")).thenReturn("new-hash");
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resetAdminPassword(10L, req);

        assertThat(adminUser.getPassword()).isEqualTo("new-hash");
        verify(refreshTokenService).revokeAllForUser(10L);
    }
}
