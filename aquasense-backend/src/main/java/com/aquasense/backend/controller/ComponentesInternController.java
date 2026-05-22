package com.aquasense.backend.controller;

import com.aquasense.backend.service.ProjetoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Endpoint interno — llamado exclusivamente por el motor de simulación Python.
 * Devuelve la lista de componenteIds presentes en el layout de un proyecto.
 * No requiere JWT (la ruta /interno/** está permitida en SecurityConfig).
 */
@RestController
@RequestMapping("/interno/proyectos")
@RequiredArgsConstructor
public class ComponentesInternController {

    private final ProjetoService projetoService;

    // GET /interno/proyectos/:id/componentes
    // Devuelve lista de componenteIds del layout activo del proyecto
    @GetMapping("/{id}/componentes")
    public ResponseEntity<List<String>> getComponentes(@PathVariable Long id) {
        return ResponseEntity.ok(projetoService.getComponentesFromLayout(id));
    }
}
