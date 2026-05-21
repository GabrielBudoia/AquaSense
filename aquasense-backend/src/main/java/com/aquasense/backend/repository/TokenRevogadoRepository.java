package com.aquasense.backend.repository;

import com.aquasense.backend.model.TokenRevogado;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface TokenRevogadoRepository extends JpaRepository<TokenRevogado, Long> {

    boolean existsByJti(String jti);

    // Limpeza periódica de registos de tokens já expirados (chamada no logout)
    @Modifying
    @Transactional
    @Query("DELETE FROM TokenRevogado t WHERE t.expiraEm < :agora")
    void deleteExpired(LocalDateTime agora);
}
