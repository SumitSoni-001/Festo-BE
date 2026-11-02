package com.festo.auth;

import java.security.SecureRandom;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

// otp:<email> -> bcrypt(otp), 10 min TTL - matches DB Design 1.3
@Service
public class OtpService {

    private static final Duration OTP_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final PasswordEncoder passwordEncoder;

    public OtpService(StringRedisTemplate redis, PasswordEncoder passwordEncoder) {
        this.redis = redis;
        this.passwordEncoder = passwordEncoder;
    }

    public String generateAndStore(String email) {
        String otp = String.valueOf(100000 + new SecureRandom().nextInt(900000));
        redis.opsForValue().set(key(email), passwordEncoder.encode(otp), OTP_TTL);
        return otp;
    }

    public boolean verify(String email, String otp) {
        String hash = redis.opsForValue().get(key(email));
        if (hash == null) return false;
        boolean valid = passwordEncoder.matches(otp, hash);
        if (valid) redis.delete(key(email));
        return valid;
    }

    private String key(String email) {
        return "otp:" + email;
    }
}
