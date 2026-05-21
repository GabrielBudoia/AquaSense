package com.aquasense.backend.service;

import com.aquasense.backend.dto.LecturaTuberiaDTO;
import com.aquasense.backend.dto.TuberiaDTO;
import com.aquasense.backend.exception.ResourceNotFoundException;
import com.aquasense.backend.model.LecturaTuberia;
import com.aquasense.backend.model.Projeto;
import com.aquasense.backend.model.Tuberia;
import com.aquasense.backend.repository.LecturaTuberiaRepository;
import com.aquasense.backend.repository.TuberiaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TuberiaService {

    private final TuberiaRepository tuberiaRepository;
    private final LecturaTuberiaRepository lecturaTuberiaRepository;
    private final ProjetoService projetoService;

    public List<TuberiaDTO> listByProjeto(Long projetoId, String email) {
        projetoService.findOwnedProject(projetoId, email);
        return tuberiaRepository.findByProjetoId(projetoId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public TuberiaDTO create(Long projetoId, TuberiaDTO dto, String email) {
        Projeto projeto = projetoService.findOwnedProject(projetoId, email);

        Tuberia tuberia = Tuberia.builder()
                .projeto(projeto)
                .fromComponenteId(dto.getFromComponenteId())
                .toComponenteId(dto.getToComponenteId())
                .diametroMm(dto.getDiametroMm())
                .materialTuberia(dto.getMaterialTuberia())
                .longitudM(dto.getLongitudM())
                .build();

        return toDTO(tuberiaRepository.save(tuberia));
    }

    public TuberiaDTO getById(Long projetoId, Long tuberiaId, String email) {
        projetoService.findOwnedProject(projetoId, email);
        Tuberia tuberia = tuberiaRepository.findByIdAndProjetoId(tuberiaId, projetoId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tuberia no encontrada: " + tuberiaId));
        return toDTO(tuberia);
    }

    @Transactional
    public void delete(Long projetoId, Long tuberiaId, String email) {
        projetoService.findOwnedProject(projetoId, email);
        Tuberia tuberia = tuberiaRepository.findByIdAndProjetoId(tuberiaId, projetoId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tuberia no encontrada: " + tuberiaId));
        // Eliminar lecturas antes de eliminar la tubería
        lecturaTuberiaRepository.deleteByTuberiaId(tuberiaId);
        tuberiaRepository.delete(tuberia);
    }

    // -------------------------------------------------------------------------
    // Usado por el endpoint interno — sin verificación de ownership
    // -------------------------------------------------------------------------

    public List<TuberiaDTO> listByProjetoInterno(Long projetoId) {
        return tuberiaRepository.findByProjetoId(projetoId).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public LecturaTuberiaDTO saveLeitura(Long tuberiaId, LecturaTuberiaDTO dto) {
        Tuberia tuberia = tuberiaRepository.findById(tuberiaId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Tuberia no encontrada: " + tuberiaId));

        LocalDateTime ts = dto.getTimestamp() != null ? dto.getTimestamp() : LocalDateTime.now();

        LecturaTuberia lectura = LecturaTuberia.builder()
                .tuberia(tuberia)
                .timestamp(ts)
                .caudalM3h(dto.getCaudalM3h())
                .presionBarEntrada(dto.getPresionBarEntrada())
                .presionBarSaida(dto.getPresionBarSaida())
                .velocidadMs(dto.getVelocidadMs())
                .build();

        lecturaTuberiaRepository.save(lectura);

        log.debug("LecturaTuberia guardada: tuberiaId={}, ts={}", tuberiaId, ts);

        return toLeituraDTO(lectura);
    }

    // -------------------------------------------------------------------------
    // Métodos auxiliares de mapeo
    // -------------------------------------------------------------------------

    private TuberiaDTO toDTO(Tuberia t) {
        return TuberiaDTO.builder()
                .id(t.getId())
                .proyectoId(t.getProjeto().getId())
                .fromComponenteId(t.getFromComponenteId())
                .toComponenteId(t.getToComponenteId())
                .diametroMm(t.getDiametroMm())
                .materialTuberia(t.getMaterialTuberia())
                .longitudM(t.getLongitudM())
                .createdAt(t.getCreatedAt())
                .build();
    }

    private LecturaTuberiaDTO toLeituraDTO(LecturaTuberia l) {
        return LecturaTuberiaDTO.builder()
                .tuberiaId(l.getTuberia().getId())
                .timestamp(l.getTimestamp())
                .caudalM3h(l.getCaudalM3h())
                .presionBarEntrada(l.getPresionBarEntrada())
                .presionBarSaida(l.getPresionBarSaida())
                .velocidadMs(l.getVelocidadMs())
                .build();
    }
}
