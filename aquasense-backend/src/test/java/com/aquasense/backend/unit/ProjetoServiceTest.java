package com.aquasense.backend.unit;

import com.aquasense.backend.dto.EquipamentoDTO;
import com.aquasense.backend.dto.ProjetoDTO;
import com.aquasense.backend.exception.ResourceNotFoundException;
import com.aquasense.backend.model.*;
import com.aquasense.backend.repository.*;
import com.aquasense.backend.service.AlertaService;
import com.aquasense.backend.service.AuditoriaService;
import com.aquasense.backend.service.ProjetoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjetoServiceTest {

    @Mock private ProjetoRepository projetoRepository;
    @Mock private UsuarioRepository usuarioRepository;
    @Mock private LeituraRepository leituraRepository;
    @Mock private AlertaRepository alertaRepository;
    @Mock private EquipamentoRepository equipamentoRepository;
    @Mock private AlertaService alertaService;
    @Mock private ObjectMapper objectMapper;
    @Mock private UsuarioProyectoRolRepository usuarioProyectoRolRepository;
    @Mock private AuditoriaService auditoriaService;

    @InjectMocks
    private ProjetoService projetoService;

    private Usuario usuario;
    private Projeto projeto;

    @BeforeEach
    void setUp() {
        usuario = Usuario.builder().id(1L).email("admin@etap.com").build();
        projeto = Projeto.builder().id(10L).nombre("ETAP Test").usuario(usuario).build();
    }

    // --- listByUsuario ---

    @Test
    void debe_retornarProyectosDelUsuario_cuando_esProprietario() {
        when(usuarioRepository.findByEmail("admin@etap.com")).thenReturn(Optional.of(usuario));
        when(projetoRepository.findByUsuarioId(1L)).thenReturn(List.of(projeto));
        when(usuarioProyectoRolRepository.findByUsuarioId(1L)).thenReturn(List.of());
        when(leituraRepository.findTopByProjetoIdOrderByTimestampDesc(10L)).thenReturn(Optional.empty());
        when(alertaRepository.countByProjetoIdAndAtivaTrue(10L)).thenReturn(0L);
        when(leituraRepository.findTopByProjetoIdAndComponenteOrderByTimestampDesc(anyLong(), any()))
                .thenReturn(Optional.empty());

        List<ProjetoDTO> result = projetoService.listByUsuario("admin@etap.com");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getNombre()).isEqualTo("ETAP Test");
    }

    @Test
    void debe_retornarEstadoOffline_cuando_sinLeituras() {
        when(usuarioRepository.findByEmail("admin@etap.com")).thenReturn(Optional.of(usuario));
        when(projetoRepository.findByUsuarioId(1L)).thenReturn(List.of(projeto));
        when(usuarioProyectoRolRepository.findByUsuarioId(1L)).thenReturn(List.of());
        when(leituraRepository.findTopByProjetoIdOrderByTimestampDesc(10L)).thenReturn(Optional.empty());
        when(alertaRepository.countByProjetoIdAndAtivaTrue(10L)).thenReturn(0L);
        when(leituraRepository.findTopByProjetoIdAndComponenteOrderByTimestampDesc(anyLong(), any()))
                .thenReturn(Optional.empty());

        List<ProjetoDTO> result = projetoService.listByUsuario("admin@etap.com");

        assertThat(result.get(0).getEstado()).isEqualTo("OFFLINE");
    }

    // --- create ---

    @Test
    void debe_crearProyectoConOchoEquipamentos_cuando_create() {
        when(usuarioRepository.findByEmail("admin@etap.com")).thenReturn(Optional.of(usuario));
        when(projetoRepository.save(any())).thenReturn(projeto);
        when(equipamentoRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(leituraRepository.findTopByProjetoIdOrderByTimestampDesc(10L)).thenReturn(Optional.empty());
        when(alertaRepository.countByProjetoIdAndAtivaTrue(10L)).thenReturn(0L);
        when(leituraRepository.findTopByProjetoIdAndComponenteOrderByTimestampDesc(anyLong(), any()))
                .thenReturn(Optional.empty());

        ProjetoDTO dto = ProjetoDTO.builder().nombre("Nuevo").build();
        projetoService.create(dto, "admin@etap.com");

        verify(equipamentoRepository, times(8)).save(any(Equipamento.class));
    }

    // --- findOwnedProject ---

    @Test
    void debe_retornarProyecto_cuando_usuarioEsProprietario() {
        when(usuarioRepository.findByEmail("admin@etap.com")).thenReturn(Optional.of(usuario));
        when(projetoRepository.findByIdAndUsuarioId(10L, 1L)).thenReturn(Optional.of(projeto));

        Projeto result = projetoService.findOwnedProject(10L, "admin@etap.com");

        assertThat(result.getId()).isEqualTo(10L);
    }

    @Test
    void debe_lanzarException_cuando_usuarioSinAcceso() {
        Usuario otro = Usuario.builder().id(99L).email("otro@etap.com").build();
        when(usuarioRepository.findByEmail("otro@etap.com")).thenReturn(Optional.of(otro));
        when(projetoRepository.findByIdAndUsuarioId(10L, 99L)).thenReturn(Optional.empty());
        when(usuarioProyectoRolRepository.findByUsuarioEmailAndProjetoId("otro@etap.com", 10L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> projetoService.findOwnedProject(10L, "otro@etap.com"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void debe_retornarProyecto_cuando_usuarioTieneRolCompartido() {
        Usuario otro = Usuario.builder().id(99L).email("op@etap.com").build();
        UsuarioProyectoRol rol = UsuarioProyectoRol.builder()
                .usuario(otro).projeto(projeto).rol(RolProyecto.OPERADOR).build();
        when(usuarioRepository.findByEmail("op@etap.com")).thenReturn(Optional.of(otro));
        when(projetoRepository.findByIdAndUsuarioId(10L, 99L)).thenReturn(Optional.empty());
        when(usuarioProyectoRolRepository.findByUsuarioEmailAndProjetoId("op@etap.com", 10L))
                .thenReturn(Optional.of(rol));
        when(projetoRepository.findById(10L)).thenReturn(Optional.of(projeto));

        Projeto result = projetoService.findOwnedProject(10L, "op@etap.com");

        assertThat(result.getId()).isEqualTo(10L);
    }

    // --- sendControl ---

    @Test
    void debe_cambiarModoAManual_cuando_sendControl() {
        Equipamento equip = Equipamento.builder()
                .componenteId("bomba_captacao").estado("AUTO").projeto(projeto).build();
        when(usuarioRepository.findByEmail("admin@etap.com")).thenReturn(Optional.of(usuario));
        when(projetoRepository.findByIdAndUsuarioId(10L, 1L)).thenReturn(Optional.of(projeto));
        when(equipamentoRepository.findByProjetoIdAndComponenteId(10L, "bomba_captacao"))
                .thenReturn(Optional.of(equip));
        when(equipamentoRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Map<String, Object> cmd = Map.of("componenteId", "bomba_captacao", "modo", "MANUAL");
        Map<String, String> result = projetoService.sendControl(10L, "admin@etap.com", cmd);

        assertThat(result.get("modo")).isEqualTo("MANUAL");
        assertThat(equip.getEstado()).isEqualTo("MANUAL");
    }

    @Test
    void debe_lanzarException_cuando_modoInvalido() {
        when(usuarioRepository.findByEmail("admin@etap.com")).thenReturn(Optional.of(usuario));
        when(projetoRepository.findByIdAndUsuarioId(10L, 1L)).thenReturn(Optional.of(projeto));

        Map<String, Object> cmd = Map.of("componenteId", "bomba_captacao", "modo", "ENCENDIDO");

        assertThatThrownBy(() -> projetoService.sendControl(10L, "admin@etap.com", cmd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AUTO");
    }

    // --- getEquipos ---

    @Test
    void debe_retornarDTOs_cuando_getEquipos() {
        Equipamento equip = Equipamento.builder()
                .id(5L).componenteId("filtracion").estado("AUTO").projeto(projeto).build();
        when(usuarioRepository.findByEmail("admin@etap.com")).thenReturn(Optional.of(usuario));
        when(projetoRepository.findByIdAndUsuarioId(10L, 1L)).thenReturn(Optional.of(projeto));
        when(equipamentoRepository.findByProjetoId(10L)).thenReturn(List.of(equip));

        List<EquipamentoDTO> result = projetoService.getEquipos(10L, "admin@etap.com");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getComponenteId()).isEqualTo("filtracion");
        assertThat(result.get(0).getEstado()).isEqualTo("AUTO");
        assertThat(result.get(0).getId()).isEqualTo(5L);
    }

    // --- deleteById ---

    @Test
    void debe_eliminarCascada_cuando_deleteById() {
        when(usuarioRepository.findByEmail("admin@etap.com")).thenReturn(Optional.of(usuario));
        when(projetoRepository.findByIdAndUsuarioId(10L, 1L)).thenReturn(Optional.of(projeto));

        projetoService.deleteById(10L, "admin@etap.com");

        verify(alertaRepository).deleteByProjetoId(10L);
        verify(leituraRepository).deleteByProjetoId(10L);
        verify(equipamentoRepository).deleteByProjetoId(10L);
        verify(projetoRepository).delete(projeto);
    }
}
