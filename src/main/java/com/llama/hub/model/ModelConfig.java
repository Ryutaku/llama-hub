package com.llama.hub.model;

import lombok.Data;
import java.time.LocalDateTime;


@Data
public class ModelConfig {
    private Long id;
    private String configJson;
    private String source;
    private LocalDateTime updatedAt;
}
