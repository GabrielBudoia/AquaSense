package com.aquasense.backend.service;

import com.aquasense.backend.config.JwtConfig;
import com.aquasense.backend.model.TokenRevogado;
import com.aquasense.backend.repository.TokenRevogadoRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtConfig jwtConfig;
    private final TokenRevogadoRepository tokenRevogadoRepository;

    // Mantido para retrocompatibilidade: tokens emitidos antes desta versão (sem JTI)
    // ficam aqui até expirarem naturalmente (máx 8h). Não persiste entre restarts.
    private final Set<String> legacyBlacklist = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtConfig.getSecret().getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateToken(UserDetails userDetails) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtConfig.getExpiration());

        return Jwts.builder()
                .subject(userDetails.getUsername())
                .id(UUID.randomUUID().toString())
                .claim("globalRole", "USER")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    public String extractUsername(String token) {
        return getClaims(token).getSubject();
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        if (legacyBlacklist.contains(token)) {
            return false;
        }
        Claims claims = getClaims(token);
        String jti = claims.getId();
        if (jti != null && tokenRevogadoRepository.existsByJti(jti)) {
            return false;
        }
        String username = claims.getSubject();
        return username.equals(userDetails.getUsername()) && !claims.getExpiration().before(new Date());
    }

    public void invalidateToken(String token) {
        try {
            Claims claims = getClaims(token);
            String jti = claims.getId();
            if (jti != null) {
                LocalDateTime expiraEm = claims.getExpiration().toInstant()
                        .atZone(ZoneId.systemDefault()).toLocalDateTime();
                tokenRevogadoRepository.save(TokenRevogado.builder()
                        .jti(jti)
                        .invalidadoEm(LocalDateTime.now())
                        .expiraEm(expiraEm)
                        .build());
                // Limpa registos de tokens já expirados para não crescer indefinidamente
                tokenRevogadoRepository.deleteExpired(LocalDateTime.now());
                return;
            }
        } catch (Exception ignored) {}
        // Token sem JTI (emitido antes desta versão): blacklist em memória
        legacyBlacklist.add(token);
    }

    private boolean isTokenExpired(String token) {
        return getClaims(token).getExpiration().before(new Date());
    }

    private Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
