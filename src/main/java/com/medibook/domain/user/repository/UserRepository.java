package com.medibook.domain.user.repository;

import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByRole(Role role);

    List<User> findAllByRole(Role role);

    Page<User> findByRole(Role role, Pageable pageable);

    @Query("SELECT u FROM User u WHERE u.role = :role AND u.enabled = true")
    List<User> findActiveByRole(Role role);

    @Query("""
           SELECT u FROM User u
           WHERE u.role = :role
           AND (:q IS NULL
                OR LOWER(u.email)     LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(u.firstName) LIKE LOWER(CONCAT('%', :q, '%'))
                OR LOWER(u.lastName)  LIKE LOWER(CONCAT('%', :q, '%')))
           ORDER BY u.createdAt DESC
           """)
    Page<User> searchByRole(@Param("role") Role role, @Param("q") String q, Pageable pageable);
}
