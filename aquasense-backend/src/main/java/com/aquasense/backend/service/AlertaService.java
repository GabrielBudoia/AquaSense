package com.aquasense.backend.service;

import com.aquasense.backend.dto.AlertaDTO;
import com.aquasense.backend.exception.ResourceNotFoundException;
import com.aquasense.backend.model.Alerta;
import com.aquasense.backend.model.ComentarioAlerta;
import com.aquasense.backend.model.NivelAlerta;
import com.aquasense.backend.model.Projeto;
import com.aquasense.backend.repository.AlertaRepository;
import com.aquasense.backend.repository.ComentarioAlertaRepository;
import com.aquasense.backend.repository.EquipamentoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AlertaService {

    private final AlertaRepository alertaRepository;
    private final ComentarioAlertaRepository comentarioAlertaRepository;
    private final EquipamentoRepository equipamentoRepository;
    private final AuditoriaService auditoriaService;
    private final NotificacionService notificacionService;

    @Async
    @Transactional
    public void evaluarUmbral(Projeto projeto, String componente, Map<String, Double> valores) {
        switch (componente) {
            case "desinfeccion"       -> evaluarDesinfeccion(projeto, valores);
            case "reservorio"         -> evaluarReservorio(projeto, valores);
            case "coagulacion"        -> evaluarCoagulacion(projeto, valores);
            case "bomba_captacao"     -> evaluarBombaCaptacao(projeto, valores);
            case "reja_tamiz"         -> evaluarRejaTamiz(projeto, valores);
            case "decantador"         -> evaluarDecantador(projeto, valores);
            case "filtracion"         -> evaluarFiltracion(projeto, valores);
            case "bomba_distribucion" -> evaluarBombaDistribucion(projeto, valores);
            default                   -> log.warn("Componente desconocido: {}", componente);
        }
    }

    // -------------------------------------------------------------------------
    // Lógica de umbrales por componente
    // -------------------------------------------------------------------------

    private void evaluarDesinfeccion(Projeto projeto, Map<String, Double> v) {
        Double cloro = v.get("cloroResidual");
        Double ph    = v.get("ph");
        Double orp   = v.get("orp");

        if (cloro != null) {
            if (cloro < 0.2) {
                crearAlerta(projeto, "desinfeccion", "cloro_critico", NivelAlerta.CRITICA,
                        "Cloro residual crítico: " + cloro + " mg/L (mín 0.2)", "aumentarCloro");
            } else if (cloro < 0.5) {
                crearAlerta(projeto, "desinfeccion", "cloro_bajo", NivelAlerta.ADVERTENCIA,
                        "Cloro residual bajo: " + cloro + " mg/L (mín 0.5)", "aumentarCloro");
            } else {
                resolverAlertasSi(projeto, "desinfeccion", "cloro_critico", "cloro_bajo");
            }
        }

        if (ph != null) {
            if (ph < 6.0) {
                crearAlerta(projeto, "desinfeccion", "ph_critico", NivelAlerta.CRITICA,
                        "pH crítico: " + ph + " (rango 6.5–9.0)", "dosificarNaOH");
            } else if (ph < 6.5) {
                crearAlerta(projeto, "desinfeccion", "ph_bajo", NivelAlerta.ADVERTENCIA,
                        "pH bajo: " + ph + " (rango 6.5–9.0)", "dosificarNaOH");
            } else if (ph > 9.0) {
                crearAlerta(projeto, "desinfeccion", "ph_alto", NivelAlerta.CRITICA,
                        "pH alto: " + ph + " (rango 6.5–9.0)", null);
            } else {
                resolverAlertasSi(projeto, "desinfeccion", "ph_critico", "ph_bajo", "ph_alto");
            }
        }

        if (orp != null && orp < 650) {
            crearAlerta(projeto, "desinfeccion", "orp_bajo", NivelAlerta.ADVERTENCIA,
                    "ORP bajo: " + orp + " mV (mín 650)", null);
        } else if (orp != null) {
            resolverAlertasSi(projeto, "desinfeccion", "orp_bajo");
        }
    }

    private void evaluarReservorio(Projeto projeto, Map<String, Double> v) {
        Double nivel  = v.get("nivel");
        Double cloro  = v.get("cloroResidual");
        Double turbid = v.get("turbidez");

        if (nivel != null) {
            if (nivel > 95) {
                crearAlerta(projeto, "reservorio", "nivel_critico", NivelAlerta.CRITICA,
                        "Nivel reservorio crítico: " + nivel + "% (máx 95%)", "cerrarValvulaEntrada");
            } else if (nivel > 90) {
                crearAlerta(projeto, "reservorio", "nivel_alto", NivelAlerta.ADVERTENCIA,
                        "Nivel reservorio alto: " + nivel + "% (máx 90%)", "cerrarValvulaEntrada");
            } else if (nivel < 10) {
                crearAlerta(projeto, "reservorio", "nivel_bajo", NivelAlerta.CRITICA,
                        "Nivel reservorio muy bajo: " + nivel + "%", null);
            } else {
                resolverAlertasSi(projeto, "reservorio", "nivel_critico", "nivel_alto", "nivel_bajo");
            }
        }

        if (cloro != null && cloro < 0.5) {
            crearAlerta(projeto, "reservorio", "cloro_bajo", NivelAlerta.ADVERTENCIA,
                    "Cloro en reservorio bajo: " + cloro + " mg/L", "aumentarCloro");
        } else if (cloro != null) {
            resolverAlertasSi(projeto, "reservorio", "cloro_bajo");
        }

        if (turbid != null && turbid > 1.0) {
            crearAlerta(projeto, "reservorio", "turbidez_alta", NivelAlerta.ADVERTENCIA,
                    "Turbidez en reservorio: " + turbid + " NTU (máx 1.0)", null);
        } else if (turbid != null) {
            resolverAlertasSi(projeto, "reservorio", "turbidez_alta");
        }
    }

    private void evaluarCoagulacion(Projeto projeto, Map<String, Double> v) {
        Double ph = v.get("phPostCoagulacion");
        if (ph != null && (ph < 6.0 || ph > 8.0)) {
            crearAlerta(projeto, "coagulacion", "ph_fuera_rango", NivelAlerta.ADVERTENCIA,
                    "pH post-coagulación fuera de rango: " + ph, ph < 6.0 ? "dosificarNaOH" : null);
        } else if (ph != null) {
            resolverAlertasSi(projeto, "coagulacion", "ph_fuera_rango");
        }

        Double turbidSalida = v.get("turbidezSalida");
        if (turbidSalida != null && turbidSalida > 10) {
            crearAlerta(projeto, "coagulacion", "turbidez_alta", NivelAlerta.ADVERTENCIA,
                    "Turbidez salida coagulación alta: " + turbidSalida + " NTU", null);
        } else if (turbidSalida != null) {
            resolverAlertasSi(projeto, "coagulacion", "turbidez_alta");
        }
    }

    private void evaluarBombaCaptacao(Projeto projeto, Map<String, Double> v) {
        Double temp = v.get("temperaturaMotor");
        if (temp != null && temp > 80) {
            crearAlerta(projeto, "bomba_captacao", "temperatura_critica", NivelAlerta.CRITICA,
                    "Temperatura motor bomba captación: " + temp + "°C (máx 80)", null);
        } else if (temp != null && temp > 70) {
            crearAlerta(projeto, "bomba_captacao", "temperatura_alta", NivelAlerta.ADVERTENCIA,
                    "Temperatura motor bomba captación elevada: " + temp + "°C", null);
        } else if (temp != null) {
            resolverAlertasSi(projeto, "bomba_captacao", "temperatura_critica", "temperatura_alta");
        }
    }

    private void evaluarRejaTamiz(Projeto projeto, Map<String, Double> v) {
        Double dp = v.get("diferencialPresion");
        if (dp != null && dp > 0.5) {
            crearAlerta(projeto, "reja_tamiz", "diferencial_alto", NivelAlerta.ADVERTENCIA,
                    "Diferencial de presión en reja/tamiz: " + dp + " bar (máx 0.5) — posible colmatación", null);
        } else if (dp != null) {
            resolverAlertasSi(projeto, "reja_tamiz", "diferencial_alto");
        }
    }

    private void evaluarDecantador(Projeto projeto, Map<String, Double> v) {
        Double lodo = v.get("nivelLodo");
        if (lodo != null && lodo > 80) {
            crearAlerta(projeto, "decantador", "lodo_alto", NivelAlerta.ADVERTENCIA,
                    "Nivel de lodo en decantador alto: " + lodo + "%", null);
        } else if (lodo != null) {
            resolverAlertasSi(projeto, "decantador", "lodo_alto");
        }

        Double turbid = v.get("turbidezSalida");
        if (turbid != null && turbid > 5) {
            crearAlerta(projeto, "decantador", "turbidez_alta", NivelAlerta.ADVERTENCIA,
                    "Turbidez salida decantador: " + turbid + " NTU (máx 5)", null);
        } else if (turbid != null) {
            resolverAlertasSi(projeto, "decantador", "turbidez_alta");
        }
    }

    private void evaluarFiltracion(Projeto projeto, Map<String, Double> v) {
        Double perdida = v.get("perdidaCarga");
        if (perdida != null && perdida > 2.5) {
            crearAlerta(projeto, "filtracion", "perdida_carga_alta", NivelAlerta.ADVERTENCIA,
                    "Pérdida de carga en filtración: " + perdida + " m (máx 2.5) — lavado recomendado", null);
        } else if (perdida != null) {
            resolverAlertasSi(projeto, "filtracion", "perdida_carga_alta");
        }

        Double horas = v.get("horasDesdelavado");
        if (horas != null && horas > 48) {
            crearAlerta(projeto, "filtracion", "horas_lavado_altas", NivelAlerta.ADVERTENCIA,
                    "Filtro sin lavado hace " + horas + " horas (máx 48h)", null);
        } else if (horas != null) {
            resolverAlertasSi(projeto, "filtracion", "horas_lavado_altas");
        }
    }

    private void evaluarBombaDistribucion(Projeto projeto, Map<String, Double> v) {
        Double presion = v.get("presionSalida");
        if (presion != null && presion < 1.0) {
            crearAlerta(projeto, "bomba_distribucion", "presion_baja", NivelAlerta.ADVERTENCIA,
                    "Presión de salida baja: " + presion + " bar (mín 1.0)", null);
        } else if (presion != null) {
            resolverAlertasSi(projeto, "bomba_distribucion", "presion_baja");
        }
    }

    // -------------------------------------------------------------------------
    // Métodos auxiliares de gestión de alertas
    // -------------------------------------------------------------------------

    protected void crearAlerta(Projeto projeto, String componente, String tipo,
                               NivelAlerta nivel, String mensagem, String accion) {
        // Deduplicar por (projetoId, componente, tipo) — no por mensagem.
        boolean yaExiste = alertaRepository
                .findByProjetoIdAndComponenteAndTipoAndAtivaTrue(
                        projeto.getId(), componente, tipo)
                .isPresent();

        if (!yaExiste) {
            Alerta alerta = Alerta.builder()
                    .projeto(projeto)
                    .componente(componente)
                    .tipo(tipo)
                    .nivel(nivel)
                    .mensagem(mensagem)
                    .ativa(true)
                    .accionAutomatica(accion)
                    .build();
            alertaRepository.save(alerta);
            log.info("[ALERTA {}] {} / {} — {} → accion: {}", nivel, componente, tipo, mensagem, accion);

            if (accion != null) {
                ejecutarAccionAutomatica(projeto, componente, accion);
            }

            // Notificación por email para alertas CRÍTICAS
            if (nivel == NivelAlerta.CRITICA) {
                notificacionService.notificarAlertaCritica(projeto, componente, mensagem);
            }
        }
    }

    private void resolverAlertasSi(Projeto projeto, String componente, String... tipos) {
        Set<String> tiposAResolver = Set.of(tipos);
        alertaRepository.findByProjetoIdAndComponenteAndAtivaTrue(projeto.getId(), componente)
                .stream()
                .filter(a -> tiposAResolver.contains(a.getTipo()))
                .forEach(a -> {
                    a.setAtiva(false);
                    alertaRepository.save(a);
                });
    }

    private void ejecutarAccionAutomatica(Projeto projeto, String componente, String accion) {
        // Muda o equipamento para MANUAL — a ação descritiva já está gravada em Alerta.accionAutomatica
        log.info("[ACCION AUTO] {}: {} — equipamento mudado para MANUAL", componente, accion);
        equipamentoRepository.findByProjetoIdAndComponenteId(projeto.getId(), componente)
                .ifPresent(equip -> {
                    equip.setEstado("MANUAL");
                    equipamentoRepository.save(equip);
                });
    }

    // -------------------------------------------------------------------------
    // Métodos del ciclo de vida de las alertas
    // -------------------------------------------------------------------------

    @Transactional
    public AlertaDTO ackAlerta(Long projetoId, Long alertaId, String autorEmail, String comentario) {
        Alerta alerta = findAlertaByProjetoAndId(projetoId, alertaId);
        alerta.setReconocidaPor(autorEmail);
        alerta.setReconocidaEn(LocalDateTime.now());
        alertaRepository.save(alerta);
        if (comentario != null && !comentario.isBlank()) {
            crearComentario(alerta, autorEmail, comentario);
        }
        auditoriaService.registrar(projetoId, autorEmail, "ACK_ALERTA",
                "Alerta:" + alertaId, null, null, null);
        log.info("[ACK] Alerta {} reconocida por {}", alertaId, autorEmail);
        return toDTOWithComentarios(alerta);
    }

    @Transactional
    public AlertaDTO silenciarAlerta(Long projetoId, Long alertaId, LocalDateTime hasta) {
        Alerta alerta = findAlertaByProjetoAndId(projetoId, alertaId);
        alerta.setSilenciadaHasta(hasta);
        alertaRepository.save(alerta);
        log.info("[SILENCE] Alerta {} silenciada hasta {}", alertaId, hasta);
        return toDTOWithComentarios(alerta);
    }

    @Transactional
    public AlertaDTO asignarAlerta(Long projetoId, Long alertaId, String email) {
        Alerta alerta = findAlertaByProjetoAndId(projetoId, alertaId);
        alerta.setAsignadaA(email);
        alertaRepository.save(alerta);
        log.info("[ASSIGN] Alerta {} asignada a {}", alertaId, email);
        return toDTOWithComentarios(alerta);
    }

    @Transactional
    public AlertaDTO resolverAlerta(Long projetoId, Long alertaId, String autorEmail, String comentario) {
        Alerta alerta = findAlertaByProjetoAndId(projetoId, alertaId);
        alerta.setResueltaPor(autorEmail);
        alerta.setResueltaEn(LocalDateTime.now());
        alerta.setAtiva(false);
        alertaRepository.save(alerta);
        if (comentario != null && !comentario.isBlank()) {
            crearComentario(alerta, autorEmail, comentario);
        }
        auditoriaService.registrar(projetoId, autorEmail, "RESOLVER_ALERTA",
                "Alerta:" + alertaId, null, null, null);
        log.info("[RESOLVE] Alerta {} resuelta por {}", alertaId, autorEmail);
        return toDTOWithComentarios(alerta);
    }

    @Transactional
    public AlertaDTO.ComentarioDTO comentarAlerta(Long projetoId, Long alertaId,
                                                  String autorEmail, String texto) {
        Alerta alerta = findAlertaByProjetoAndId(projetoId, alertaId);
        ComentarioAlerta comentario = crearComentario(alerta, autorEmail, texto);
        return toComentarioDTO(comentario);
    }

    // -------------------------------------------------------------------------
    // Consultas
    // -------------------------------------------------------------------------

    public List<AlertaDTO> getAlertas(Long projetoId, Boolean activas) {
        List<Alerta> alertas = activas != null
                ? alertaRepository.findByProjetoIdAndAtivaOrderByCreadaEnDesc(projetoId, activas)
                : alertaRepository.findByProjetoIdOrderByCreadaEnDesc(projetoId);

        return alertas.stream().map(this::toDTOWithComentarios).collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Métodos auxiliares privados
    // -------------------------------------------------------------------------

    private Alerta findAlertaByProjetoAndId(Long projetoId, Long alertaId) {
        return alertaRepository.findByProjetoIdAndId(projetoId, alertaId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Alerta no encontrada: " + alertaId + " en proyecto: " + projetoId));
    }

    private ComentarioAlerta crearComentario(Alerta alerta, String autorEmail, String texto) {
        ComentarioAlerta comentario = ComentarioAlerta.builder()
                .alerta(alerta)
                .autorEmail(autorEmail)
                .texto(texto)
                .build();
        return comentarioAlertaRepository.save(comentario);
    }

    private AlertaDTO toDTOWithComentarios(Alerta a) {
        List<AlertaDTO.ComentarioDTO> comentarios = comentarioAlertaRepository
                .findByAlertaIdOrderByCreadoEnAsc(a.getId())
                .stream()
                .map(this::toComentarioDTO)
                .collect(Collectors.toList());

        return AlertaDTO.builder()
                .id(a.getId())
                .componente(a.getComponente())
                .tipo(a.getTipo())
                .nivel(a.getNivel())
                .mensagem(a.getMensagem())
                .ativa(a.isAtiva())
                .accionAutomatica(a.getAccionAutomatica())
                .creadaEn(a.getCreadaEn())
                .reconocidaPor(a.getReconocidaPor())
                .reconocidaEn(a.getReconocidaEn())
                .silenciadaHasta(a.getSilenciadaHasta())
                .asignadaA(a.getAsignadaA())
                .resueltaPor(a.getResueltaPor())
                .resueltaEn(a.getResueltaEn())
                .comentarios(comentarios)
                .build();
    }

    private AlertaDTO.ComentarioDTO toComentarioDTO(ComentarioAlerta c) {
        return AlertaDTO.ComentarioDTO.builder()
                .id(c.getId())
                .texto(c.getTexto())
                .autorEmail(c.getAutorEmail())
                .creadoEn(c.getCreadoEn())
                .build();
    }
}
