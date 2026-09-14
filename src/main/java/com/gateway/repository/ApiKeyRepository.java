package com.gateway.repository;

import com.gateway.model.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    ApiKey findByKeyHash(String keyHash);
}
