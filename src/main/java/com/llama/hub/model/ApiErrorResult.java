package com.llama.hub.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiErrorResult {

    private ErrorDetail error;

    public static ApiErrorResult of(String type, String message) {
        return new ApiErrorResult(new ErrorDetail(message, type));
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorDetail {
        private String message;
        private String type;
    }
}