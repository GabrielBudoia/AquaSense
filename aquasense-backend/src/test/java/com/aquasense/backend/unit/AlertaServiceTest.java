package com.aquasense.backend.unit;

import com.aquasense.backend.model.*;
import com.aquasense.backend.repository.AlertaRepository;
import com.aquasense.backend.repository.ComentarioAlertaRepository;
import com.aquasense.backend.repository.EquipamentoRepository;
import com.aquasense.backend.service.AlertaService;
import com.aquasense.backend.service.AuditoriaService;
import com.aquasense.backend.service.NotificacionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertaServiceTest {

    @Mock private AlertaRepository alertaRepository;
    @Mock private ComentarioAlertaRepository comentarioAlertaRepository;
    @Mock private EquipamentoRepository equipamentoRepository;
    @Mock private AuditoriaService auditoriaService;
    @Mock private NotificacionService notificacionService;

    @InjectMocks
    private AlertaService alertaService;

    private Projeto projeto;

    @BeforeEach
    void setUp() {
        projeto = Projeto.builder().id(1L).nombre("Test ETAP").build();
    }

    // --- evaluarUmbral: desinfeccion ---

    @Test
    void debe_crearAlertaCritica_cuando_cloroMenorA02() {
        when(alertaRepository.findByProjetoIdAndComponenteAndTipoAndAtivaTrue(anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(alertaRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(equipamentoRepository.findByProjetoIdAndComponenteId(anyLong(), any()))
                .thenReturn(Optional.empty());

        alertaService.evaluarUmbral(projeto, "desinfeccion", Map.of("cloroResidual", 0.1));

        ArgumentCaptor<Alerta> captor = ArgumentCaptor.forClass(Alerta.class);
        verify(alertaRepository, atLeastOnce()).save(captor.capture());
        Alerta alerta = captor.getAllValues().stream()
                .filter(a -> "cloro_critico".equals(a.getTipo())).findFirst().orElseThrow();
        assertThat(alerta.getNivel()).isEqualTo(NivelAlerta.CRITICA);
        assertThat(alerta.isAtiva()).isTrue();
    }

    @Test
    void debe_crearAlertaAdvertencia_cuando_cloroEntre02y05() {
        when(alertaRepository.findByProjetoIdAndComponenteAndTipoAndAtivaTrue(anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(alertaRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(equipamentoRepository.findByProjetoIdAndComponenteId(anyLong(), any()))
                .thenReturn(Optional.empty());

        alertaService.evaluarUmbral(projeto, "desinfeccion", Map.of("cloroResidual", 0.3));

        ArgumentCaptor<Alerta> captor = ArgumentCaptor.forClass(Alerta.class);
        verify(alertaRepository, atLeastOnce()).save(captor.capture());
        Alerta alerta = captor.getAllValues().stream()
                .filter(a -> "cloro_bajo".equals(a.getTipo())).findFirst().orElseThrow();
        assertThat(alerta.getNivel()).isEqualTo(NivelAlerta.ADVERTENCIA);
    }

    @Test
    void debe_noCrearAlerta_cuando_cloroNormal() {
        when(alertaRepository.findByProjetoIdAndComponenteAndAtivaTrue(anyLong(), any()))
                .thenReturn(List.of());

        alertaService.evaluarUmbral(projeto, "desinfeccion", Map.of("cloroResidual", 0.8));

        verify(alertaRepository, never()).save(any());
    }

    @Test
    void debe_notificarEmail_cuando_alertaEsCritica() {
        when(alertaRepository.findByProjetoIdAndComponenteAndTipoAndAtivaTrue(anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(alertaRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(equipamentoRepository.findByProjetoIdAndComponenteId(anyLong(), any()))
                .thenReturn(Optional.empty());

        alertaService.evaluarUmbral(projeto, "desinfeccion", Map.of("cloroResidual", 0.1));

        verify(notificacionService).notificarAlertaCritica(eq(projeto), eq("desinfeccion"), anyString());
    }

    @Test
    void debe_noNotificarEmail_cuando_alertaEsAdvertencia() {
        when(alertaRepository.findByProjetoIdAndComponenteAndTipoAndAtivaTrue(anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(alertaRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        alertaService.evaluarUmbral(projeto, "desinfeccion", Map.of("cloroResidual", 0.3));

        verify(notificacionService, never()).notificarAlertaCritica(any(), any(), any());
    }

    // --- Deduplicación ---

    @Test
    void debe_noCrearAlerta_cuando_yaExisteAlertaActivaDelMismoTipo() {
        Alerta existente = Alerta.builder().tipo("cloro_critico").ativa(true).build();
        when(alertaRepository.findByProjetoIdAndComponenteAndTipoAndAtivaTrue(anyLong(), any(), any()))
                .thenReturn(Optional.of(existente));

        alertaService.evaluarUmbral(projeto, "desinfeccion", Map.of("cloroResidual", 0.05));

        verify(alertaRepository, never()).save(any());
    }

    // --- Acción automática ---

    @Test
    void debe_cambiarEquipamentoAManual_cuando_alertaCriticaDisparaAccion() {
        Equipamento equip = Equipamento.builder()
                .componenteId("desinfeccion").estado("AUTO").build();
        when(alertaRepository.findByProjetoIdAndComponenteAndTipoAndAtivaTrue(anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(alertaRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(equipamentoRepository.findByProjetoIdAndComponenteId(anyLong(), any()))
                .thenReturn(Optional.of(equip));

        alertaService.evaluarUmbral(projeto, "desinfeccion", Map.of("cloroResidual", 0.1));

        assertThat(equip.getEstado()).isEqualTo("MANUAL");
        verify(equipamentoRepository).save(equip);
    }

    // --- ackAlerta ---

    @Test
    void debe_marcarReconocida_cuando_ackAlerta() {
        Alerta alerta = Alerta.builder().id(10L).projeto(projeto).ativa(true).build();
        when(alertaRepository.findByProjetoIdAndId(1L, 10L)).thenReturn(Optional.of(alerta));
        when(alertaRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(comentarioAlertaRepository.findByAlertaIdOrderByCreadoEnAsc(any())).thenReturn(List.of());

        alertaService.ackAlerta(1L, 10L, "op@etap.com", null);

        assertThat(alerta.getReconocidaPor()).isEqualTo("op@etap.com");
        assertThat(alerta.getReconocidaEn()).isNotNull();
    }

    // --- resolverAlerta ---

    @Test
    void debe_marcarInactiva_cuando_resolverAlerta() {
        Alerta alerta = Alerta.builder().id(10L).projeto(projeto).ativa(true).build();
        when(alertaRepository.findByProjetoIdAndId(1L, 10L)).thenReturn(Optional.of(alerta));
        when(alertaRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(comentarioAlertaRepository.findByAlertaIdOrderByCreadoEnAsc(any())).thenReturn(List.of());

        alertaService.resolverAlerta(1L, 10L, "admin@etap.com", "Resuelto");

        assertThat(alerta.isAtiva()).isFalse();
        assertThat(alerta.getResueltaPor()).isEqualTo("admin@etap.com");
        assertThat(alerta.getResueltaEn()).isNotNull();
    }

    // --- evaluarUmbral: reservorio ---

    @Test
    void debe_crearAlertaCritica_cuando_nivelReservorioMayorA95() {
        when(alertaRepository.findByProjetoIdAndComponenteAndTipoAndAtivaTrue(anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(alertaRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(equipamentoRepository.findByProjetoIdAndComponenteId(anyLong(), any()))
                .thenReturn(Optional.empty());

        alertaService.evaluarUmbral(projeto, "reservorio", Map.of("nivel", 97.0));

        ArgumentCaptor<Alerta> captor = ArgumentCaptor.forClass(Alerta.class);
        verify(alertaRepository, atLeastOnce()).save(captor.capture());
        Alerta alerta = captor.getAllValues().stream()
                .filter(a -> "nivel_critico".equals(a.getTipo())).findFirst().orElseThrow();
        assertThat(alerta.getNivel()).isEqualTo(NivelAlerta.CRITICA);
    }

    // --- evaluarUmbral: bomba_captacao ---

    @Test
    void debe_crearAlertaCritica_cuando_temperaturaMotorMayorA80() {
        when(alertaRepository.findByProjetoIdAndComponenteAndTipoAndAtivaTrue(anyLong(), any(), any()))
                .thenReturn(Optional.empty());
        when(alertaRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        alertaService.evaluarUmbral(projeto, "bomba_captacao", Map.of("temperaturaMotor", 85.0));

        ArgumentCaptor<Alerta> captor = ArgumentCaptor.forClass(Alerta.class);
        verify(alertaRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getNivel()).isEqualTo(NivelAlerta.CRITICA);
    }
}
