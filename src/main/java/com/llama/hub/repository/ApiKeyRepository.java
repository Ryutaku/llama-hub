package com.llama.hub.repository;

import com.llama.hub.model.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    ApiKey findByKeyHash(String keyHash);
}
