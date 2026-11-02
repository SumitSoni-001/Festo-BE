package com.festo.auth;

public interface EmailService {
    void sendOtp(String email, String otp);
}
