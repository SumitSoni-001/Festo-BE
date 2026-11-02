package com.festo.auth.dto;

public record LoginResponse(String accessToken, String refreshToken, UserSummary user) {
}
