package com.aquasense.backend.repository;

import com.aquasense.backend.model.Alerta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertaRepository extends JpaRepository<Alerta, Long> {

    List<Alerta> findByProjetoIdOrderByCreadaEnDesc(Long projetoId);

    List<Alerta> findByProjetoIdAndAtivaOrderByCreadaEnDesc(Long projetoId, boolean ativa);

    // Comprobación de duplicados: una alerta activa por (proyecto, componente, tipo)
    Optional<Alerta> findByProjetoIdAndComponenteAndTipoAndAtivaTrue(
            Long projetoId, String componente, String tipo);

    // Usado por resolverAlertasSi para cerrar todas las alertas activas de un componente
    List<Alerta> findByProjetoIdAndComponenteAndAtivaTrue(Long projetoId, String componente);

    void deleteByProjetoId(Long projetoId);

    long countByProjetoIdAndAtivaTrue(Long projetoId);

    // Ciclo de vida: buscar una alerta específica perteneciente a un proyecto
    Optional<Alerta> findByProjetoIdAndId(Long projetoId, Long alertaId);
}
