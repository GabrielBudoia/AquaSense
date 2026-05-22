package com.aquasense.backend.service;

import com.aquasense.backend.dto.EquipamentoDTO;
import com.aquasense.backend.dto.EstadoDTO;
import com.aquasense.backend.dto.LeituraDTO;
import com.aquasense.backend.dto.ProjetoDTO;
import com.aquasense.backend.dto.RolAssignmentDTO;
import com.aquasense.backend.exception.ResourceNotFoundException;
import com.aquasense.backend.model.Equipamento;
import com.aquasense.backend.model.LeituraSensor;
import com.aquasense.backend.model.Projeto;
import com.aquasense.backend.model.RolProyecto;
import com.aquasense.backend.model.Usuario;
import com.aquasense.backend.model.UsuarioProyectoRol;
import com.aquasense.backend.repository.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.security.access.AccessDeniedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Slf4j
public class ProjetoService {

    private static final List<String> COMPONENTES = List.of(
            "bomba_captacao", "reja_tamiz", "coagulacion",
            "decantador", "filtracion", "desinfeccion",
            "reservorio", "bomba_distribucion"
    );

    private static final String LAYOUT_VAZIO = "{\"componentes\":[],\"tuberias\":[]}";

    private final ProjetoRepository projetoRepository;
    private final UsuarioRepository usuarioRepository;
    private final LeituraRepository leituraRepository;
    private final AlertaRepository alertaRepository;
    private final EquipamentoRepository equipamentoRepository;
    private final AlertaService alertaService;
    private final ObjectMapper objectMapper;
    private final UsuarioProyectoRolRepository usuarioProyectoRolRepository;
    private final AuditoriaService auditoriaService;

    public List<ProjetoDTO> listByUsuario(String email) {
        Usuario usuario = findUsuario(email);
        // Proyectos creados por el usuario
        List<Projeto> owned = projetoRepository.findByUsuarioId(usuario.getId());
        // Proyectos compartidos (donde tiene un rol explícito)
        List<Long> sharedIds = usuarioProyectoRolRepository.findByUsuarioId(usuario.getId())
                .stream()
                .map(r -> r.getProjeto().getId())
                .collect(Collectors.toList());
        List<Projeto> shared = sharedIds.isEmpty()
                ? List.of()
                : projetoRepository.findAllById(sharedIds).stream()
                    .filter(p -> !p.getUsuario().getId().equals(usuario.getId()))
                    .collect(Collectors.toList());

        return java.util.stream.Stream.concat(owned.stream(), shared.stream())
                .distinct()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProjetoDTO create(ProjetoDTO dto, String email) {
        Usuario usuario = findUsuario(email);
        Projeto projeto = Projeto.builder()
                .nombre(dto.getNombre())
                .ubicacion(dto.getUbicacion())
                .descripcion(dto.getDescripcion())
                .usuario(usuario)
                .layout(LAYOUT_VAZIO)
                .build();
        Projeto saved = projetoRepository.save(projeto);

        // Inicializar entradas de equipamiento para los 8 componentes
        COMPONENTES.forEach(comp ->
                equipamentoRepository.save(Equipamento.builder()
                        .projeto(saved)
                        .componenteId(comp)
                        .estado("AUTO")
                        .build()));

        return toDTO(saved);
    }

    @Transactional
    @CacheEvict(value = "layout", key = "#id")
    public void deleteById(Long id, String email) {
        Projeto projeto = findOwnedProject(id, email);
        alertaRepository.deleteByProjetoId(id);
        leituraRepository.deleteByProjetoId(id);
        equipamentoRepository.deleteByProjetoId(id);
        projetoRepository.delete(projeto);
    }

    public ProjetoDTO getById(Long id, String email) {
        return toDTO(findOwnedProject(id, email));
    }

    public EstadoDTO getEstado(Long id, String email) {
        findOwnedProject(id, email);

        // Carga los modos de todos los componentes (tabla equipamentos)
        Map<String, String> modoComponentes = new HashMap<>();
        equipamentoRepository.findByProjetoId(id).forEach(eq ->
                modoComponentes.put(eq.getComponenteId(), eq.getEstado()));
        // Garantiza que los 8 componentes aparecen (por defecto AUTO)
        COMPONENTES.forEach(c -> modoComponentes.putIfAbsent(c, "AUTO"));

        Map<String, EstadoDTO.ComponenteEstado> componentes = new HashMap<>();
        LocalDateTime lastUpdate = null;

        // 1 query para todos los componentes (fix N+1: eran 8 queries por request)
        Map<String, LeituraSensor> latestByComp = leituraRepository
                .findLatestPerComponente(id)
                .stream()
                .collect(Collectors.toMap(LeituraSensor::getComponente, l -> l));

        for (String comp : COMPONENTES) {
            LeituraSensor lectura = latestByComp.get(comp);
            if (lectura == null) continue;
            final String modo = modoComponentes.getOrDefault(comp, "AUTO");
            try {
                Map<String, Double> valores = objectMapper.readValue(
                        lectura.getValores(), new TypeReference<>() {});
                componentes.put(comp, EstadoDTO.ComponenteEstado.builder()
                        .valores(valores)
                        .timestamp(lectura.getTimestamp())
                        .origen(lectura.getOrigen())
                        .modo(modo)
                        .build());
            } catch (Exception e) {
                log.warn("Error parsing valores for component {}", comp, e);
            }
        }

        if (!componentes.isEmpty()) {
            lastUpdate = componentes.values().stream()
                    .map(EstadoDTO.ComponenteEstado::getTimestamp)
                    .max(LocalDateTime::compareTo)
                    .orElse(null);
        }

        return EstadoDTO.builder()
                .componentes(componentes)
                .ultimaActualizacion(lastUpdate)
                .modoComponentes(modoComponentes)
                .build();
    }

    private static final int MAX_HISTORICO_ROWS = 2000;

    public List<LeituraDTO> getHistorico(Long id, String email,
                                         String componente, LocalDateTime desde, LocalDateTime hasta) {
        findOwnedProject(id, email);

        if (desde == null) desde = LocalDateTime.now().minusDays(7);
        if (hasta == null) hasta = LocalDateTime.now();

        // Obtener las MAX_HISTORICO_ROWS filas más recientes primero (DESC), luego ordenar ASC para el gráfico
        var limit = PageRequest.of(0, MAX_HISTORICO_ROWS);

        List<LeituraSensor> lecturas;
        if (componente != null && !componente.isBlank()) {
            lecturas = leituraRepository
                    .findRecentByProjetoIdAndComponenteAndTimestampBetween(
                            id, componente, desde, hasta, limit);
        } else {
            lecturas = leituraRepository
                    .findRecentByProjetoIdAndTimestampBetween(id, desde, hasta, limit);
        }

        return lecturas.stream()
                .sorted(Comparator.comparing(LeituraSensor::getTimestamp))
                .map(this::toLeituraDTO)
                .collect(Collectors.toList());
    }

    private static final int MAX_EXPORT_ROWS = 50_000;
    private static final DateTimeFormatter CSV_TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public byte[] exportHistoricoCsv(Long id, String email,
                                      String componente, LocalDateTime desde, LocalDateTime hasta) {
        findOwnedProject(id, email);

        if (desde == null) desde = LocalDateTime.now().minusDays(7);
        if (hasta == null) hasta = LocalDateTime.now();

        var limit = PageRequest.of(0, MAX_EXPORT_ROWS, Sort.by("timestamp").ascending());

        List<LeituraSensor> lecturas;
        if (componente != null && !componente.isBlank()) {
            lecturas = leituraRepository
                    .findByProjetoIdAndComponenteAndTimestampBetweenOrderByTimestampAsc(
                            id, componente, desde, hasta, limit);
        } else {
            lecturas = leituraRepository
                    .findByProjetoIdAndTimestampBetweenOrderByTimestampAsc(id, desde, hasta, limit);
        }

        try (var out = new ByteArrayOutputStream();
             var writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
             var printer = new CSVPrinter(writer, CSVFormat.DEFAULT.builder()
                     .setHeader("timestamp", "componente", "parametro", "valor")
                     .build())) {

            for (LeituraSensor lectura : lecturas) {
                try {
                    Map<String, Double> valores = objectMapper.readValue(
                            lectura.getValores(), new TypeReference<>() {});
                    String ts = lectura.getTimestamp().format(CSV_TS_FORMAT);
                    for (Map.Entry<String, Double> entry : valores.entrySet()) {
                        printer.printRecord(ts, lectura.getComponente(), entry.getKey(), entry.getValue());
                    }
                } catch (Exception e) {
                    log.warn("Error al parsear valores para lectura {}", lectura.getId(), e);
                }
            }
            printer.flush();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Error al generar CSV: " + e.getMessage(), e);
        }
    }

    @Transactional
    public Map<String, String> sendControl(Long id, String email, Map<String, Object> comando) {
        Projeto projeto = findOwnedProject(id, email);

        // Soporta nuevo contrato: { "modo": "MANUAL"|"AUTO", "componenteId": "..." }
        // y contrato legado:      { "componente": "...", "comando": "..." }
        String componenteId = comando.containsKey("componenteId")
                ? (String) comando.get("componenteId")
                : (String) comando.get("componente");
        String modo = comando.containsKey("modo")
                ? (String) comando.get("modo")
                : (String) comando.get("comando");

        if (componenteId == null || modo == null) {
            throw new IllegalArgumentException(
                    "Campos 'componenteId'+'modo' (o legado 'componente'+'comando') son obligatorios");
        }

        // Valida el valor del modo
        if (!modo.equals("AUTO") && !modo.equals("MANUAL")) {
            throw new IllegalArgumentException("Valor de 'modo' inválido: solo se aceptan 'AUTO' o 'MANUAL'");
        }

        Equipamento equip = equipamentoRepository
                .findByProjetoIdAndComponenteId(projeto.getId(), componenteId)
                .orElseGet(() -> Equipamento.builder()
                        .projeto(projeto)
                        .componenteId(componenteId)
                        .estado("AUTO")
                        .build());
        String estadoAnterior = equip.getEstado();
        equip.setEstado(modo);
        equipamentoRepository.save(equip);

        auditoriaService.registrar(id, email, "CONTROL_MANUAL",
                "Equipamento:" + componenteId, estadoAnterior, modo, null);

        log.info("[modo] Componente {} del proyecto {} cambiado a {}", componenteId, id, modo);
        return Map.of("status", "ok", "componenteId", componenteId, "modo", modo);
    }

    public List<EquipamentoDTO> getEquipos(Long id, String email) {
        findOwnedProject(id, email);
        return equipamentoRepository.findByProjetoId(id).stream()
                .map(e -> EquipamentoDTO.builder()
                        .id(e.getId())
                        .componenteId(e.getComponenteId())
                        .estado(e.getEstado())
                        .configuracion(e.getConfiguracion())
                        .ultimaActualizacion(e.getUltimaActualizacion())
                        .build())
                .collect(Collectors.toList());
    }

    @Cacheable(value = "layout", key = "#id")
    public String getLayout(Long id, String email) {
        Projeto projeto = findOwnedProject(id, email);
        return projeto.getLayout() != null ? projeto.getLayout() : "{}";
    }

    @Transactional
    @CacheEvict(value = "layout", key = "#id")
    public void saveLayout(Long id, String email, String layoutJson) {
        Projeto projeto = findOwnedProject(id, email);
        projeto.setLayout(layoutJson);
        projetoRepository.save(projeto);
        // Acción diferenciada: limpiar canvas vs editar
        String accion = isLayoutVazio(layoutJson) ? "LAYOUT_LIMPO" : "EDITAR_LAYOUT";
        auditoriaService.registrar(id, email, accion, "Projeto:" + id, null, null, null);
    }

    private boolean isLayoutVazio(String layoutJson) {
        try {
            var node = objectMapper.readTree(layoutJson);
            var comps = node.get("componentes");
            return comps != null && comps.isArray() && comps.size() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // Usado por ComponentesInternController — devuelve los componenteIds del layout del proyecto
    public List<String> getComponentesFromLayout(Long id) {
        Projeto projeto = projetoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proyecto no encontrado: " + id));
        String layout = projeto.getLayout();
        if (layout == null || layout.isBlank()) return List.of();
        try {
            var node = objectMapper.readTree(layout);
            var comps = node.get("componentes");
            if (comps == null || !comps.isArray()) return List.of();
            List<String> ids = new java.util.ArrayList<>();
            comps.forEach(c -> {
                var cid = c.get("componenteId");
                if (cid != null && !cid.isNull()) ids.add(cid.asText());
            });
            return ids;
        } catch (Exception e) {
            log.warn("Error al parsear layout del proyecto {}", id, e);
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // Control de simulación
    // -------------------------------------------------------------------------

    @Transactional
    public Map<String, Object> startSimulacao(Long id) {
        Projeto projeto = projetoRepository.findById(id).orElseThrow();
        projeto.setSimulacaoAtiva(true);
        projetoRepository.save(projeto);
        return Map.of("status", "RUNNING", "projectId", id);
    }

    @Transactional
    public Map<String, Object> stopSimulacao(Long id) {
        Projeto projeto = projetoRepository.findById(id).orElseThrow();
        projeto.setSimulacaoAtiva(false);
        projetoRepository.save(projeto);
        return Map.of("status", "STOPPED", "projectId", id);
    }

    public Map<String, Object> getSimulacaoStatus(Long id) {
        Projeto projeto = projetoRepository.findById(id).orElseThrow();
        String status = Boolean.TRUE.equals(projeto.getSimulacaoAtiva()) ? "RUNNING" : "STOPPED";
        return Map.of("status", status, "projectId", id);
    }

    public List<Long> getProjetosAtivos() {
        return projetoRepository.findBySimulacaoAtiva(true)
                .stream()
                .map(Projeto::getId)
                .collect(Collectors.toList());
    }

    /**
     * Persiste una lectura manual enviada por el operador vía UI (requiere JWT).
     * El campo origen se fuerza a "MANUAL".
     */
    @Transactional
    public Map<String, Object> saveLeituraManual(Long id, String email, LeituraDTO dto) {
        Projeto projeto = findOwnedProject(id, email);

        String valoresJson;
        try {
            valoresJson = objectMapper.writeValueAsString(dto.getValores());
        } catch (Exception e) {
            throw new IllegalArgumentException("Valores JSON inválido");
        }

        LocalDateTime ts = dto.getTimestamp() != null ? dto.getTimestamp() : LocalDateTime.now();

        LeituraSensor lectura = LeituraSensor.builder()
                .projeto(projeto)
                .componente(dto.getComponente())
                .valores(valoresJson)
                .timestamp(ts)
                .origen("MANUAL")
                .build();

        leituraRepository.save(lectura);

        // Evalúa umbrales y genera alertas
        alertaService.evaluarUmbral(projeto, dto.getComponente(), dto.getValores());

        log.info("[manual] Leitura MANUAL guardada: projeto={}, componente={}, ts={}",
                id, dto.getComponente(), ts);

        return Map.of(
                "status", "ok",
                "proyecto", id,
                "componente", dto.getComponente(),
                "origen", "MANUAL",
                "timestamp", ts.toString()
        );
    }

    /**
     * Devuelve mapa componenteId → modo (AUTO|MANUAL) para uso interno del simulador Python.
     * No requiere validación de ownership — llamado vía token interno.
     */
    public Map<String, String> getModoComponentes(Long projetoId) {
        Map<String, String> modos = new HashMap<>();
        // Por defecto: todos AUTO
        COMPONENTES.forEach(c -> modos.put(c, "AUTO"));
        // Sobreescribe con valores guardados
        equipamentoRepository.findByProjetoId(projetoId)
                .forEach(eq -> modos.put(eq.getComponenteId(), eq.getEstado()));
        return modos;
    }

    // -------------------------------------------------------------------------
    // Usado por el endpoint interno (Python) — no requiere comprobación de ownership
    // -------------------------------------------------------------------------
    public Projeto findOwnedProjectById(Long id) {
        return projetoRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Proyecto no encontrado: " + id));
    }

    // -------------------------------------------------------------------------
    // Usado por los endpoints de la API — valida que el proyecto pertenece al usuario o está compartido con él
    // -------------------------------------------------------------------------
    public Projeto findOwnedProject(Long id, String email) {
        Usuario usuario = findUsuario(email);
        // El propietario tiene acceso completo
        var ownerMatch = projetoRepository.findByIdAndUsuarioId(id, usuario.getId());
        if (ownerMatch.isPresent()) {
            return ownerMatch.get();
        }
        // Verificar rol compartido
        var rolEntry = usuarioProyectoRolRepository.findByUsuarioEmailAndProjetoId(email, id);
        if (rolEntry.isPresent()) {
            return projetoRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Proyecto no encontrado: " + id));
        }
        throw new ResourceNotFoundException("Proyecto no encontrado o sin acceso: " + id);
    }

    // -------------------------------------------------------------------------
    // Métodos de gestión de roles
    // -------------------------------------------------------------------------

    public RolProyecto getRolForUser(Long projetoId, String email) {
        // Verificar si es propietario
        Projeto projeto = projetoRepository.findById(projetoId)
                .orElseThrow(() -> new ResourceNotFoundException("Proyecto no encontrado: " + projetoId));
        if (projeto.getUsuario().getEmail().equals(email)) {
            return RolProyecto.ADMIN;
        }
        return usuarioProyectoRolRepository.findByUsuarioEmailAndProjetoId(email, projetoId)
                .map(UsuarioProyectoRol::getRol)
                .orElseThrow(() -> new AccessDeniedException("Sin acceso al proyecto: " + projetoId));
    }

    @Transactional
    public RolAssignmentDTO setRol(Long projetoId, String requesterEmail, String targetEmail, RolProyecto rol) {
        // Solo ADMIN puede asignar roles
        RolProyecto requesterRol = getRolForUser(projetoId, requesterEmail);
        if (requesterRol != RolProyecto.ADMIN) {
            throw new AccessDeniedException("Solo ADMIN puede asignar roles");
        }
        Projeto projeto = projetoRepository.findById(projetoId)
                .orElseThrow(() -> new ResourceNotFoundException("Proyecto no encontrado: " + projetoId));
        Usuario targetUser = usuarioRepository.findByEmail(targetEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado: " + targetEmail));

        UsuarioProyectoRol entry = usuarioProyectoRolRepository
                .findByUsuarioEmailAndProjetoId(targetEmail, projetoId)
                .orElseGet(() -> UsuarioProyectoRol.builder()
                        .usuario(targetUser)
                        .projeto(projeto)
                        .build());
        entry.setRol(rol);
        usuarioProyectoRolRepository.save(entry);

        return RolAssignmentDTO.builder()
                .usuarioId(targetUser.getId())
                .email(targetUser.getEmail())
                .nombre(targetUser.getNombre())
                .rol(rol)
                .build();
    }

    @Transactional
    public void removeRol(Long projetoId, String requesterEmail, Long targetUserId) {
        RolProyecto requesterRol = getRolForUser(projetoId, requesterEmail);
        if (requesterRol != RolProyecto.ADMIN) {
            throw new AccessDeniedException("Solo ADMIN puede eliminar roles");
        }
        usuarioProyectoRolRepository.deleteByProjetoIdAndUsuarioId(projetoId, targetUserId);
    }

    public List<RolAssignmentDTO> getRoles(Long projetoId, String requesterEmail) {
        RolProyecto requesterRol = getRolForUser(projetoId, requesterEmail);
        if (requesterRol != RolProyecto.ADMIN) {
            throw new AccessDeniedException("Solo ADMIN puede ver los roles del proyecto");
        }
        return usuarioProyectoRolRepository.findByProjetoId(projetoId).stream()
                .map(r -> RolAssignmentDTO.builder()
                        .usuarioId(r.getUsuario().getId())
                        .email(r.getUsuario().getEmail())
                        .nombre(r.getUsuario().getNombre())
                        .rol(r.getRol())
                        .build())
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // Métodos auxiliares privados
    // -------------------------------------------------------------------------

    private Usuario findUsuario(String email) {
        return usuarioRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado: " + email));
    }

    private ProjetoDTO toDTO(Projeto p) {
        // Última lectura de cualquier componente
        LocalDateTime ultimaLeitura = leituraRepository
                .findTopByProjetoIdOrderByTimestampDesc(p.getId())
                .map(LeituraSensor::getTimestamp)
                .orElse(null);

        // Estado basado en el tiempo desde la última lectura
        String estado;
        if (ultimaLeitura == null || ultimaLeitura.isBefore(LocalDateTime.now().minusSeconds(90))) {
            estado = "OFFLINE";
        } else {
            estado = "ONLINE";
        }

        // Recuento de alertas activas
        long alertasAtivas = alertaRepository.countByProjetoIdAndAtivaTrue(p.getId());

        // Resumen de sensores — última lectura de desinfeccion y reservorio
        ProjetoDTO.ResumenSensores resumen = buildResumen(p.getId());

        return ProjetoDTO.builder()
                .id(p.getId())
                .nombre(p.getNombre())
                .ubicacion(p.getUbicacion())
                .descripcion(p.getDescripcion())
                .creadoEn(p.getCreadoEn())
                .estado(estado)
                .alertasAtivas(alertasAtivas)
                .ultimaLeitura(ultimaLeitura)
                .resumenSensores(resumen)
                .simulacaoAtiva(Boolean.TRUE.equals(p.getSimulacaoAtiva()))
                .build();
    }

    private ProjetoDTO.ResumenSensores buildResumen(Long projetoId) {
        Double pH = null;
        Double cloroResidual = null;
        Double nivelReservorio = null;

        try {
            var desinfOpt = leituraRepository
                    .findTopByProjetoIdAndComponenteOrderByTimestampDesc(projetoId, "desinfeccion");
            if (desinfOpt.isPresent()) {
                Map<String, Double> v = objectMapper.readValue(desinfOpt.get().getValores(), new TypeReference<>() {});
                pH = v.get("ph");
                cloroResidual = v.get("cloroResidual");
            }

            var reservOpt = leituraRepository
                    .findTopByProjetoIdAndComponenteOrderByTimestampDesc(projetoId, "reservorio");
            if (reservOpt.isPresent()) {
                Map<String, Double> v = objectMapper.readValue(reservOpt.get().getValores(), new TypeReference<>() {});
                nivelReservorio = v.get("nivel");
            }
        } catch (Exception e) {
            log.warn("Error building resumen for project {}", projetoId, e);
        }

        return ProjetoDTO.ResumenSensores.builder()
                .pH(pH)
                .cloroResidual(cloroResidual)
                .nivelReservorio(nivelReservorio)
                .build();
    }

    private LeituraDTO toLeituraDTO(LeituraSensor l) {
        try {
            Map<String, Double> valores = objectMapper.readValue(
                    l.getValores(), new TypeReference<>() {});
            return LeituraDTO.builder()
                    .componente(l.getComponente())
                    .valores(valores)
                    .timestamp(l.getTimestamp())
                    .origen(l.getOrigen())
                    .build();
        } catch (Exception e) {
            log.warn("Error parsing valores for lectura {}", l.getId(), e);
            return LeituraDTO.builder()
                    .componente(l.getComponente())
                    .valores(Map.of())
                    .timestamp(l.getTimestamp())
                    .origen(l.getOrigen())
                    .build();
        }
    }
}
