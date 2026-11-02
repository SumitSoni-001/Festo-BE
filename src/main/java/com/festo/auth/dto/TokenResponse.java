package com.festo.auth.dto;

// /refresh returns just the token pair, no user object - matches API Design Contract
public record TokenResponse(String accessToken, String refreshToken) {
}
