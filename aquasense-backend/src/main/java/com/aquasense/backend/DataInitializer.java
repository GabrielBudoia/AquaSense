package com.aquasense.backend;

import com.aquasense.backend.model.Equipamento;
import com.aquasense.backend.model.Projeto;
import com.aquasense.backend.model.Usuario;
import com.aquasense.backend.repository.EquipamentoRepository;
import com.aquasense.backend.repository.ProjetoRepository;
import com.aquasense.backend.repository.UsuarioRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataInitializer implements CommandLineRunner {

    // Los 8 componenteIds canónicos de la planta ETAP, en orden de proceso
    private static final List<String> COMPONENTES = List.of(
            "bomba_captacao",
            "reja_tamiz",
            "coagulacion",
            "decantador",
            "filtracion",
            "desinfeccion",
            "reservorio",
            "bomba_distribucion"
    );

    // El proyecto demo arranca sin layout — el usuario lo construye desde cero
    private static final String LAYOUT_VAZIO = "{\"componentes\":[],\"tuberias\":[]}";

    private final UsuarioRepository usuarioRepository;
    private final ProjetoRepository projetoRepository;
    private final EquipamentoRepository equipamentoRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        // Solo se ejecuta si el usuario de demostración aún no existe
        if (usuarioRepository.existsByEmail("admin@aquasense.com")) {
            log.info("=== Seed ya aplicado — nada que hacer ===");
            return;
        }

        // 1. Crear usuario de demostración
        Usuario operador = usuarioRepository.save(Usuario.builder()
                .email("admin@aquasense.com")
                .password(passwordEncoder.encode("password"))
                .nombre("Administrador Demo")
                .language("es")
                .build());

        // 2. Crear proyecto de demostración
        Projeto demo = projetoRepository.save(Projeto.builder()
                .nombre("ETAP Demo")
                .descripcion("Planta de tratamento de água — ambiente demo")
                .ubicacion("Lisboa, Portugal")
                .usuario(operador)
                .simulacaoAtiva(true)
                .layout(LAYOUT_VAZIO)
                .build());

        // 3. Crear los 8 equipos con componenteIds canónicos
        for (int i = 0; i < COMPONENTES.size(); i++) {
            equipamentoRepository.save(Equipamento.builder()
                    .projeto(demo)
                    .componenteId(COMPONENTES.get(i))
                    .estado("AUTO")
                    .build());
        }

        log.info("=== Seed aplicado: admin@aquasense.com | proyectoId={} ===", demo.getId());
    }
}
