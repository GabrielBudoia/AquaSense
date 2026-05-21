package com.aquasense.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "tokens_revogados",
        indexes = { @Index(name = "idx_token_jti", columnList = "jti", unique = true) })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenRevogado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // JTI (JWT ID) — identificador único do token revogado
    @Column(nullable = false, unique = true, length = 64)
    private String jti;

    @Column(nullable = false)
    private LocalDateTime invalidadoEm;

    // Mantido para permitir limpeza de tokens já expirados
    @Column(nullable = false)
    private LocalDateTime expiraEm;
}
