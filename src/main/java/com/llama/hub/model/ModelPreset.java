package com.llama.hub.model;

import lombok.Data;
import java.time.LocalDateTime;


@Data
public class ModelPreset {
    private Long id;
    private String name;
    private String note;
    private String configJson;
    private String source;
    private LocalDateTime createdAt;
}
