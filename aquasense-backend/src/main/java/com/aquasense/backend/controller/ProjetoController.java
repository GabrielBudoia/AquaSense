package com.aquasense.backend.controller;

import com.aquasense.backend.dto.AlertaDTO;
import com.aquasense.backend.dto.EquipamentoDTO;
import com.aquasense.backend.dto.EstadoDTO;
import com.aquasense.backend.dto.EventoAuditoriaDTO;
import com.aquasense.backend.dto.LeituraDTO;
import com.aquasense.backend.dto.PreferenciasNotificacionDTO;
import com.aquasense.backend.dto.ProjetoDTO;
import com.aquasense.backend.dto.RolAssignmentDTO;
import com.aquasense.backend.model.RolProyecto;
import com.aquasense.backend.service.AlertaService;
import com.aquasense.backend.service.AuditoriaService;
import com.aquasense.backend.service.NotificacionService;
import com.aquasense.backend.service.ProjetoService;
import org.springframework.data.domain.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/proyectos")
@RequiredArgsConstructor
public class ProjetoController {

    private final ProjetoService projetoService;
    private final AlertaService alertaService;
    private final AuditoriaService auditoriaService;
    private final NotificacionService notificacionService;
    private final ObjectMapper objectMapper;

    // GET /api/proyectos
    @GetMapping
    public ResponseEntity<List<ProjetoDTO>> list(Authentication auth) {
        return ResponseEntity.ok(projetoService.listByUsuario(auth.getName()));
    }

    // POST /api/proyectos
    @PostMapping
    public ResponseEntity<ProjetoDTO> create(@Valid @RequestBody ProjetoDTO dto, Authentication auth) {
        return ResponseEntity.ok(projetoService.create(dto, auth.getName()));
    }

    // GET /api/proyectos/:id
    @GetMapping("/{id}")
    public ResponseEntity<ProjetoDTO> getById(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(projetoService.getById(id, auth.getName()));
    }

    // GET /api/proyectos/:id/estado
    @GetMapping("/{id}/estado")
    public ResponseEntity<EstadoDTO> getEstado(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(projetoService.getEstado(id, auth.getName()));
    }

    // GET /api/proyectos/:id/historico?componente=&desde=&hasta=
    @GetMapping("/{id}/historico")
    public ResponseEntity<List<LeituraDTO>> getHistorico(
            @PathVariable Long id,
            @RequestParam(required = false) String componente,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta,
            Authentication auth) {
        return ResponseEntity.ok(projetoService.getHistorico(id, auth.getName(), componente, desde, hasta));
    }

    // GET /api/proyectos/:id/historico/export?componente=&desde=&hasta=&formato=csv
    @GetMapping("/{id}/historico/export")
    public ResponseEntity<byte[]> exportHistorico(
            @PathVariable Long id,
            @RequestParam(required = false) String componente,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta,
            @RequestParam(defaultValue = "csv") String formato,
            Authentication auth) {

        byte[] csv = projetoService.exportHistoricoCsv(id, auth.getName(), componente, desde, hasta);
        String filename = "historico_" + id + "_" + System.currentTimeMillis() + ".csv";

        return ResponseEntity.ok()
                .header("Content-Type", "text/csv; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }

    // GET /api/proyectos/:id/alertas?activas=true
    @GetMapping("/{id}/alertas")
    public ResponseEntity<List<AlertaDTO>> getAlertas(
            @PathVariable Long id,
            @RequestParam(required = false) Boolean activas,
            Authentication auth) {
        // Validar propiedad
        projetoService.findOwnedProject(id, auth.getName());
        return ResponseEntity.ok(alertaService.getAlertas(id, activas));
    }

    // POST /api/proyectos/:id/lecturas  (lectura manual vía UI — requiere JWT)
    @PostMapping("/{id}/lecturas")
    public ResponseEntity<Map<String, Object>> saveLeituraManual(
            @PathVariable Long id,
            @Valid @RequestBody LeituraDTO dto,
            Authentication auth) {
        return ResponseEntity.ok(projetoService.saveLeituraManual(id, auth.getName(), dto));
    }

    // POST /api/proyectos/:id/control
    @PostMapping("/{id}/control")
    public ResponseEntity<Map<String, String>> sendControl(
            @PathVariable Long id,
            @RequestBody Map<String, Object> comando,
            Authentication auth) {
        return ResponseEntity.ok(projetoService.sendControl(id, auth.getName(), comando));
    }

    // GET /api/proyectos/:id/equipos
    @GetMapping("/{id}/equipos")
    public ResponseEntity<List<EquipamentoDTO>> getEquipos(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(projetoService.getEquipos(id, auth.getName()));
    }

    // GET /api/proyectos/:id/layout
    @GetMapping(value = "/{id}/layout", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getLayout(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(projetoService.getLayout(id, auth.getName()));
    }

    // DELETE /api/proyectos/:id
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id, Authentication auth) {
        projetoService.deleteById(id, auth.getName());
        return ResponseEntity.noContent().build();
    }

    // POST /api/proyectos/:id/simulacao/start
    @PostMapping("/{id}/simulacao/start")
    public ResponseEntity<?> startSimulacao(@PathVariable Long id, Authentication auth) {
        projetoService.findOwnedProject(id, auth.getName());
        return ResponseEntity.ok(projetoService.startSimulacao(id));
    }

    // POST /api/proyectos/:id/simulacao/stop
    @PostMapping("/{id}/simulacao/stop")
    public ResponseEntity<?> stopSimulacao(@PathVariable Long id, Authentication auth) {
        projetoService.findOwnedProject(id, auth.getName());
        return ResponseEntity.ok(projetoService.stopSimulacao(id));
    }

    // GET /api/proyectos/:id/simulacao/status
    @GetMapping("/{id}/simulacao/status")
    public ResponseEntity<?> getSimulacaoStatus(@PathVariable Long id, Authentication auth) {
        projetoService.findOwnedProject(id, auth.getName());
        return ResponseEntity.ok(projetoService.getSimulacaoStatus(id));
    }

    // POST /api/proyectos/:id/layout
    @PostMapping(value = "/{id}/layout", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> saveLayout(
            @PathVariable Long id,
            @RequestBody JsonNode layout,
            Authentication auth) {
        try {
            projetoService.saveLayout(id, auth.getName(), objectMapper.writeValueAsString(layout));
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            throw new IllegalArgumentException("Layout JSON inválido");
        }
    }

    // GET /api/proyectos/{id}/auditoria
    @GetMapping("/{id}/auditoria")
    public ResponseEntity<Page<EventoAuditoriaDTO>> getAuditoria(
            @PathVariable Long id,
            @RequestParam(required = false) String usuario,
            @RequestParam(required = false) String accion,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime hasta,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication auth) {
        projetoService.findOwnedProject(id, auth.getName());
        return ResponseEntity.ok(auditoriaService.getAuditoria(id, usuario, accion, desde, hasta, page, size));
    }

    // GET /api/proyectos/{id}/notificaciones
    @GetMapping("/{id}/notificaciones")
    public ResponseEntity<PreferenciasNotificacionDTO> getNotificaciones(
            @PathVariable Long id, Authentication auth) {
        projetoService.findOwnedProject(id, auth.getName());
        return ResponseEntity.ok(notificacionService.getPreferencias(id, auth.getName()));
    }

    // PUT /api/proyectos/{id}/notificaciones
    @PutMapping("/{id}/notificaciones")
    public ResponseEntity<PreferenciasNotificacionDTO> saveNotificaciones(
            @PathVariable Long id,
            @RequestBody PreferenciasNotificacionDTO dto,
            Authentication auth) {
        projetoService.findOwnedProject(id, auth.getName());
        return ResponseEntity.ok(notificacionService.savePreferencias(id, auth.getName(), dto));
    }

    // GET /api/proyectos/{id}/mirol
    @GetMapping("/{id}/mirol")
    public ResponseEntity<Map<String, String>> getMiRol(@PathVariable Long id, Authentication auth) {
        RolProyecto rol = projetoService.getRolForUser(id, auth.getName());
        return ResponseEntity.ok(Map.of("rol", rol.name()));
    }

    // GET /api/proyectos/{id}/roles  (solo ADMIN)
    @GetMapping("/{id}/roles")
    public ResponseEntity<List<RolAssignmentDTO>> getRoles(@PathVariable Long id, Authentication auth) {
        return ResponseEntity.ok(projetoService.getRoles(id, auth.getName()));
    }

    // POST /api/proyectos/{id}/roles  (solo ADMIN)
    @PostMapping("/{id}/roles")
    public ResponseEntity<RolAssignmentDTO> setRol(
            @PathVariable Long id,
            @RequestBody RolAssignmentDTO body,
            Authentication auth) {
        return ResponseEntity.ok(projetoService.setRol(id, auth.getName(), body.getEmail(), body.getRol()));
    }

    // DELETE /api/proyectos/{id}/roles/{uid}  (solo ADMIN)
    @DeleteMapping("/{id}/roles/{uid}")
    public ResponseEntity<Void> removeRol(
            @PathVariable Long id,
            @PathVariable Long uid,
            Authentication auth) {
        projetoService.removeRol(id, auth.getName(), uid);
        return ResponseEntity.noContent().build();
    }
}
