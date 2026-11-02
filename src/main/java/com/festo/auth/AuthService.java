package com.festo.auth;

import com.festo.auth.dto.LoginRequest;
import com.festo.auth.dto.LoginResponse;
import com.festo.auth.dto.RefreshRequest;
import com.festo.auth.dto.RegisterRequest;
import com.festo.auth.dto.TokenResponse;
import com.festo.auth.dto.UserSummary;
import com.festo.auth.dto.VerifyOtpRequest;
import com.festo.college.College;
import com.festo.college.CollegeRepository;
import com.festo.common.exception.BadRequestException;
import com.festo.common.exception.ConflictException;
import com.festo.common.exception.ForbiddenException;
import com.festo.common.exception.UnauthorizedException;
import com.festo.common.security.JwtService;
import com.festo.user.PlatformRole;
import com.festo.user.User;
import com.festo.user.UserRepository;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private static final Duration PENDING_REGISTRATION_TTL = Duration.ofMinutes(10);

    private final UserRepository userRepository;
    private final CollegeRepository collegeRepository;
    private final OtpService otpService;
    private final EmailService emailService;
    private final JwtService jwtService;
    private final RefreshTokenStore refreshTokenStore;
    private final PasswordEncoder passwordEncoder;
    private final StringRedisTemplate redis;

    public AuthService(
            UserRepository userRepository,
            CollegeRepository collegeRepository,
            OtpService otpService,
            EmailService emailService,
            JwtService jwtService,
            RefreshTokenStore refreshTokenStore,
            PasswordEncoder passwordEncoder,
            StringRedisTemplate redis) {
        this.userRepository = userRepository;
        this.collegeRepository = collegeRepository;
        this.otpService = otpService;
        this.emailService = emailService;
        this.jwtService = jwtService;
        this.refreshTokenStore = refreshTokenStore;
        this.passwordEncoder = passwordEncoder;
        this.redis = redis;
    }

    // account isn't created yet - just OTP + pending details, same pattern as the OTP itself
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("EMAIL_ALREADY_EXISTS", "An account with this email already exists.");
        }
        storePendingRegistration(request);  // Store user details in redis
        String otp = otpService.generateAndStore(request.email());
        emailService.sendOtp(request.email(), otp);
    }

    public LoginResponse verifyOtp(VerifyOtpRequest request) {
        if (!otpService.verify(request.email(), request.otp())) {
            throw new BadRequestException("INVALID_OTP", "The OTP is incorrect or has expired.");
        }
        PendingRegistration pending = readPendingRegistration(request.email()); // Read registering user details from redis
        if (pending == null) {
            throw new BadRequestException("OTP_EXPIRED", "Registration details expired - please register again.");
        }

        User user = new User();
        user.setName(pending.name());
        user.setEmail(request.email());
        user.setPhone(pending.phone());
        user.setPasswordHash(pending.passwordHash());
        user.setPlatformRole(PlatformRole.CUSTOMER);
        collegeForEmail(request.email()).ifPresent(user::setCollege);
        user.setEmailVerified(true);
        userRepository.save(user);  // Create new user

        redis.delete(pendingKey(request.email()));  // Delete registering user details from Redis
        return issueTokens(user);
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new UnauthorizedException("INVALID_CREDENTIALS", "Incorrect email or password."));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("INVALID_CREDENTIALS", "Incorrect email or password.");
        }
        if (!user.isActive()) {
            throw new ForbiddenException("ACCOUNT_DEACTIVATED", "This account has been deactivated.");
        }
        return issueTokens(user);
    }

    public TokenResponse refresh(RefreshRequest request) {
        Long userId = userIdFromRefreshToken(request.refreshToken());
        if (!refreshTokenStore.matches(userId, request.refreshToken())) {
            throw new UnauthorizedException("INVALID_REFRESH_TOKEN", "Refresh token is invalid or has been revoked.");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("INVALID_REFRESH_TOKEN", "User no longer exists."));

        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getPlatformRole().name());
        String refreshToken = jwtService.generateRefreshToken(user.getId());
        refreshTokenStore.store(user.getId(), refreshToken);
        return new TokenResponse(accessToken, refreshToken);
    }

    public void logout(RefreshRequest request) {
        refreshTokenStore.delete(userIdFromRefreshToken(request.refreshToken()));
    }

    private LoginResponse issueTokens(User user) {
        String accessToken = jwtService.generateAccessToken(user.getId(), user.getEmail(), user.getPlatformRole().name());
        String refreshToken = jwtService.generateRefreshToken(user.getId());
        refreshTokenStore.store(user.getId(), refreshToken);
        UserSummary summary = new UserSummary(user.getId(), user.getName(), user.getEmail(), user.getPlatformRole().name());
        return new LoginResponse(accessToken, refreshToken, summary);
    }

    private Long userIdFromRefreshToken(String token) {
        try {
            return Long.valueOf(jwtService.parseToken(token).getSubject());
        } catch (JwtException e) {
            throw new UnauthorizedException("INVALID_REFRESH_TOKEN", "Refresh token is invalid or has expired.");
        }
    }

    private Optional<College> collegeForEmail(String email) {
        String domain = email.substring(email.indexOf('@') + 1);
        return collegeRepository.findByEmailDomain(domain);
    }

    private void storePendingRegistration(RegisterRequest request) {
        String key = pendingKey(request.email());
        redis.opsForHash().putAll(key, Map.of(
                "name", request.name(),
                "phone", request.phone() == null ? "" : request.phone(),
                "passwordHash", passwordEncoder.encode(request.password())));
        redis.expire(key, PENDING_REGISTRATION_TTL);
    }

    private PendingRegistration readPendingRegistration(String email) {
        Map<Object, Object> fields = redis.opsForHash().entries(pendingKey(email));
        if (fields.isEmpty()) return null;
        String phone = (String) fields.get("phone");
        return new PendingRegistration(
                (String) fields.get("name"), phone.isEmpty() ? null : phone, (String) fields.get("passwordHash"));
    }

    private String pendingKey(String email) {
        return "pending_registration:" + email;
    }

    private record PendingRegistration(String name, String phone, String passwordHash) {
    }
}
