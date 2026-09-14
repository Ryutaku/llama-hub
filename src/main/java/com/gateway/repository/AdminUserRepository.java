package com.gateway.repository;

import com.gateway.model.AdminUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserRepository extends JpaRepository<AdminUser, Long> {

    AdminUser findByUsername(String username);
}
