package com.medibook.domain.user.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.domain.user.dto.ChangePasswordRequest;
import com.medibook.domain.user.dto.UpdateProfileRequest;
import com.medibook.domain.user.dto.UserResponse;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public UserResponse getProfile(Long userId) {
        return UserResponse.fromUser(loadUser(userId));
    }

    @Transactional
    public UserResponse updateProfile(Long userId, UpdateProfileRequest request) {
        User user = loadUser(userId);
        if (request.getFirstName() != null)   user.setFirstName(request.getFirstName());
        if (request.getLastName() != null)    user.setLastName(request.getLastName());
        if (request.getPhone() != null)       user.setPhone(request.getPhone());
        if (request.getDateOfBirth() != null) user.setDateOfBirth(request.getDateOfBirth());
        return UserResponse.fromUser(userRepository.save(user));
    }

    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest request) {
        User user = loadUser(userId);
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new MediBookException("Invalid current password", HttpStatus.BAD_REQUEST, "INVALID_CREDENTIALS");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
    }

    @Transactional
    public UserResponse updateNotificationPreferences(Long userId, Map<String, Boolean> prefs) {
        User user = loadUser(userId);
        if (prefs.containsKey("email")) user.setEmailNotifications(prefs.get("email"));
        if (prefs.containsKey("sms"))   user.setSmsNotifications(prefs.get("sms"));
        return UserResponse.fromUser(userRepository.save(user));
    }

    @Transactional
    public UserResponse updateLocale(Long userId, String language) {
        User user = loadUser(userId);
        user.setLocale(language);
        return UserResponse.fromUser(userRepository.save(user));
    }

    private User loadUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new MediBookException("User not found", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
    }
}
