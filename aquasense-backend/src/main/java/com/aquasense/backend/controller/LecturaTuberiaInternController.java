package com.aquasense.backend.controller;

import com.aquasense.backend.dto.LecturaTuberiaDTO;
import com.aquasense.backend.dto.TuberiaDTO;
import com.aquasense.backend.service.TuberiaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Endpoint interno — llamado exclusivamente por el motor de simulación Python.
 * Sin JWT (ruta permitida en SecurityConfig bajo /interno/**).
 * Sin CORS (Python no envía cabecera Origin).
 * Requiere header X-Internal-Token (validado por InternalTokenFilter).
 */
@RestController
@RequestMapping("/interno/proyectos")
@RequiredArgsConstructor
@Slf4j
public class LecturaTuberiaInternController {

    private final TuberiaService tuberiaService;

    // GET /interno/proyectos/:id/tuberias — usado pelo simulador Python para obter a lista de tubagens
    @GetMapping("/{projetoId}/tuberias")
    public ResponseEntity<List<TuberiaDTO>> getTuberias(@PathVariable Long projetoId) {
        return ResponseEntity.ok(tuberiaService.listByProjetoInterno(projetoId));
    }

    // POST /interno/proyectos/:id/tuberias/:tid/lecturas
    @PostMapping("/{projetoId}/tuberias/{tuberiaId}/lecturas")
    public ResponseEntity<Map<String, Object>> receiveLeitura(
            @PathVariable Long projetoId,
            @PathVariable Long tuberiaId,
            @RequestBody LecturaTuberiaDTO dto) {

        LecturaTuberiaDTO saved = tuberiaService.saveLeitura(tuberiaId, dto);

        log.debug("LecturaTuberia recebida: proyecto={}, tuberia={}, ts={}",
                projetoId, tuberiaId, saved.getTimestamp());

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "proyecto", projetoId,
                "tuberia", tuberiaId,
                "timestamp", saved.getTimestamp().toString()
        ));
    }
}
