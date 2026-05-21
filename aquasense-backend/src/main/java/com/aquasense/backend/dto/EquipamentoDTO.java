package com.aquasense.backend.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EquipamentoDTO {
    private Long id;
    private String componenteId;
    private String estado;
    private String configuracion;
    private LocalDateTime ultimaActualizacion;
}
