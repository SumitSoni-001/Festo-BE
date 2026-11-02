package com.festo.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// stub - logs instead of sending; swap for a Brevo-backed implementation once API keys exist
@Slf4j
@Service
public class ConsoleEmailService implements EmailService {

    @Override
    public void sendOtp(String email, String otp) {
        log.info("OTP for {}: {}", email, otp);
    }
}
