---
documento: Análise Técnica Completa do Projeto
versão_análise: 1.0
data_análise: 2026-05-20
analisado_por: Claude Sonnet 4.6
fase: 1 de 2 (Análise Read-Only)
próxima_fase: Correção de falhas baseada neste documento
formato: Otimizado para consumo por IA
---

# ANÁLISE TÉCNICA COMPLETA — AquaSense

## 1. VISÃO GERAL DO PROJETO

### 1.1. Ideia Central

AquaSense é um sistema SCADA web para monitoramento e controlo de Estações de Tratamento de Água Potável (ETAP). Permite que operadores acompanhem em tempo real o estado de componentes industriais (bombas, filtros, decantadores, cloradores, etc.), recebam alertas automáticos, alternem entre modos AUTO/MANUAL e visualizem o layout da planta num sinóptico interativo baseado em SVG. O sistema suporta múltiplos projetos e utilizadores com papéis distintos por projeto.

### 1.2. Propósito de Negócio / Caso de Uso

Resolve a necessidade de monitoramento centralizado de plantas de tratamento de água, substituindo soluções SCADA locais por uma aplicação web acessível via browser. O caso de uso central é: um operador abre o dashboard, vê o estado atual de todos os sensores e equipamentos, age sobre anomalias (reconhecer alertas, mudar modos, inserir leituras manuais), e um simulador Python emula os sensores em desenvolvimento.

### 1.3. Stack Tecnológica

- **Linguagem(ns) principal(is):** Java 21 (backend), JavaScript/React 19 (frontend), Python 3 (simulador)
- **Framework(s):** Spring Boot 3.3.4, React 19.2.4 + React Router 7, Vite 8.0.4
- **Banco de dados:** H2 (dev, in-memory) / PostgreSQL (prod via `application-prod.properties`)
- **Dependências críticas:**
  - `jjwt 0.12.3` — autenticação JWT com cookie HttpOnly + header Bearer
  - `spring-boot-starter-security` — filtros JwtFilter + InternalTokenFilter
  - `spring-boot-starter-mail` — notificações SMTP opcionais
  - `commons-csv 1.10.0` — exportação CSV de histórico
  - `spring-cache` — cache do endpoint `/layout`
  - `axios 1.15.0` — cliente HTTP do frontend
  - `chart.js 4.5.1` — gráficos de histórico
  - `requests` (Python) — cliente HTTP do simulador
- **Ferramentas de build/deploy:** Maven (backend), Vite (frontend), Docker (Dockerfile em ambos), Railway (backend via `railway.toml`), Vercel (frontend via `vercel.json`), Nginx (`nginx.conf`)

### 1.4. Estrutura de Diretórios

```
AquaSense-main/
├── aquasense-backend/                  # Spring Boot 3.3.4, Java 21
│   ├── src/main/java/com/aquasense/backend/
│   │   ├── BackendApplication.java     # Ponto de entrada; @EnableAsync @EnableCaching
│   │   ├── config/                     # SecurityConfig, CorsConfig, JwtConfig, CacheConfig
│   │   ├── controller/                 # REST controllers (Auth, Projeto, Alerta, User, Tuberia, Health, Interno)
│   │   ├── dto/                        # DTOs de request e response
│   │   ├── filter/                     # JwtFilter, InternalTokenFilter
│   │   ├── model/                      # Entidades JPA (Usuario, Projeto, Alerta, Leitura, Equipamento, Tuberia, etc.)
│   │   ├── repository/                 # Spring Data JPA repositories
│   │   ├── service/                    # Lógica de negócio (Projeto, Alerta, Auth, User, Email, Notificacao, Auditoria, Tuberia)
│   │   └── security/                   # UserDetailsServiceImpl
│   └── src/main/resources/
│       ├── application.properties      # Config dev (H2, JWT 8h, SMTP opcional)
│       └── application-prod.properties # Config prod (PostgreSQL, Hikari pool 20)
├── aquasense-frontend/                 # React 19, Vite 8
│   ├── src/
│   │   ├── main.jsx                    # createRoot + App
│   │   ├── App.jsx                     # Providers + rotas
│   │   ├── context/                    # AuthContext, LanguageContext, ProjectContext
│   │   ├── hooks/                      # usePolling, useRole
│   │   ├── services/                   # api.js (axios)
│   │   ├── pages/                      # Proyectos, Dashboard, Alertas, Historico, Equipa, Auditoria, Notificaciones, Perfil
│   │   └── components/                 # Sinoptico, CanvasEditor, ModoPanel, AlertasList, AlertaModal, Topbar, etc.
│   ├── public/
│   └── nginx.conf                      # Configuração Nginx para SPA
├── python/                             # Simulador de sensores
│   ├── main.py                         # Loop principal; polling backend a cada 5s
│   ├── simulator.py                    # SensorSimulator com drift/noise/anomalias
│   ├── client.py                       # HTTP client; envio de leituras com retry
│   ├── automation.py                   # Validação por limites (código morto — ver Diagnóstico)
│   ├── config.py                       # Constantes, THRESHOLDS, ESTADO_INICIAL
│   └── tuberias.py                     # Simulação hidráulica de tubagens
├── db/
│   └── init.sql                        # Schema inicial + utilizador demo
├── docs/
│   └── conexiones-equipos.md           # Mapa físico de conexões e parâmetros por componente
├── aquasense-v2/
│   └── catalogo/
│       └── equipamentos.json           # 40+ tipos de equipamento em 6 categorias
├── .env.example                        # Template de variáveis de ambiente (raiz)
├── aquasense-backend/.env.example      # Template de variáveis (backend)
├── aquasense-frontend/.env             # ⚠️ Ficheiro .env REAL presente no repositório
├── docker-compose.yml                  # Orquestração (backend + postgres + python)
└── prompt_analise_projeto.md           # Prompt de auditoria (não é código do projeto)
```

---

## 2. ARQUITETURA E FLUXO

### 2.1. Diagrama Lógico (em texto)

```
[Browser / React SPA]
        │
        │  HTTPS (axios, withCredentials)
        │  Auth: HttpOnly cookie "aquasense_session" + Authorization: Bearer (fallback)
        ▼
[Spring Boot Backend — porta 8080]
        │
        ├── JwtFilter (valida token de cada request)
        ├── InternalTokenFilter (protege /interno/**)
        │
        ├── /auth/**        → AuthController → AuthService → JwtService
        ├── /api/**         → ProjetoController, AlertaController, UserController, TuberiaController
        └── /interno/**     → LeituraController, SimulacaoInternController, LecturaTuberiaInternController
                │
                ▼
        [H2 (dev) / PostgreSQL (prod)]
                ▲
        [Python Simulator]
                │
                │  POST /interno/proyectos/:id/lecturas
                │  X-Internal-Token header
                │  Polling GET /interno/simulacao/projetos-ativos a cada 10s
                │  Polling GET /interno/simulacao/projetos/:id/modos a cada 5s
                ▼
        [Spring Boot Backend]
```

**Fluxo de dados principais:**
1. Python → Backend: leituras de sensores a cada 5s por projeto ativo
2. Backend → BD: persiste leituras, avalia umbrais, cria alertas
3. Frontend → Backend: polling `/estado` e `/alertas` a cada 5s (usePolling com backoff)
4. Frontend → Backend: ações do operador (ack/silenciar/resolver alertas, toggle AUTO/MANUAL, leituras manuais)
5. Backend → Email: alertas CRITICA disparam notificação SMTP assíncrona (se configurado)

### 2.2. Ponto de Entrada

- **Backend:** `aquasense-backend/src/main/java/com/aquasense/backend/BackendApplication.java`
  - `@SpringBootApplication + @EnableAsync + @EnableCaching`
  - Ao iniciar: `DataInitializer` (ApplicationRunner) verifica se `admin@aquasense.com` existe; se não, cria utilizador demo + projeto ETAP Demo com 8 componentes + layout pré-construído
- **Frontend:** `aquasense-frontend/src/main.jsx`
  - `ReactDOM.createRoot(root).render(<App />)`
  - App.jsx: envolve com ErrorBoundary, LanguageProvider, AuthProvider, ProjectProvider, BrowserRouter e define todas as rotas
- **Simulador:** `python/main.py`
  - Função `main()`: loop infinito, polling backend, executa ciclo a cada 5s

### 2.3. Fluxo Principal de Execução

**Fluxo típico — Operador monitora e age sobre alerta:**

1. Utilizador acede `/login` → `AuthController.login()` → valida credenciais, gera JWT, seta cookie `aquasense_session` + devolve token no body → `AuthContext.login()` guarda token em `localStorage`
2. Redireciona para `/proyectos` → `ProjetoController.listar()` devolve projetos do utilizador (próprios + partilhados) → renderiza cards com status ONLINE/OFFLINE
3. Clica num projeto → navega para `/proyectos/:id` (Dashboard) → `ProjetoController.getEstado()` devolve estado atual de todos os componentes
4. `usePolling` começa ciclo de 5s: GET `/api/proyectos/:id/estado` + GET `/api/proyectos/:id/alertas`
5. Python simulator: detecta projeto ativo → gera leituras → POST `/interno/proyectos/:id/lecturas` → `LeituraController.receberLeitura()` → `AlertaService.evaluarUmbral()` avalia limiares → se ultrapassado, `AlertaService.criarAlerta()` cria alerta (com deduplicação) → se CRITICA, `NotificacaoService.notificarAlertaCritica()` envia email async
6. Frontend polling recebe alerta → mostra em `AlertasList` + contador no `Topbar`
7. Operador clica no alerta → `AlertaModal` → clica "Reconhecer" → `AlertaController.ack()` → `AlertaService.ackAlerta()` preenche `reconocidaPor`/`reconocidaEn`
8. Operador pode silenciar (define `silenciadaHasta`), atribuir (define `asignadaA`), resolver (define `resueltaPor`/`resueltaEn`), comentar

---

## 3. ANÁLISE POR MÓDULO / ARQUIVO

### 3.1. `aquasense-backend/src/main/java/com/aquasense/backend/BackendApplication.java`
- **Propósito:** Ponto de entrada Spring Boot. Ativa execução assíncrona e cache.
- **Funções/classes:**
  - `BackendApplication` — `@SpringBootApplication`, `@EnableAsync`, `@EnableCaching`; `main()` chama `SpringApplication.run()`
- **Conexões:** Importa todas as configs Spring Boot via autoconfiguration
- **Estado:** ✅ Funcional
- **Observações:** Sem `@EnableScheduling` — o polling do simulador é externo (Python), não Spring @Scheduled

---

### 3.2. `aquasense-backend/src/main/resources/application.properties`
- **Propósito:** Configuração de desenvolvimento (H2, JWT, SMTP, internal token)
- **Configurações principais:**
  - `spring.datasource.url=jdbc:h2:mem:aquasense` — BD em memória, perde dados ao reiniciar
  - `jwt.expiration=28800000` — 8 horas em milissegundos
  - `jwt.secret` lido de `${JWT_SECRET}` — correto
  - `internal.token` lido de `${X_INTERNAL_TOKEN:dev-internal-token-change-in-prod}` — valor default "dev-internal-token-change-in-prod" é inseguro mas `InternalTokenFilter` alerta se usado em prod
  - `smtp.*` todos opcionais — backend arranca sem SMTP
- **Estado:** ✅ Funcional
- **Observações:** H2 console habilitado em dev (`spring.h2.console.enabled=true`) — nunca deve ir para prod

---

### 3.3. `aquasense-backend/src/main/resources/application-prod.properties`
- **Propósito:** Sobrescreve config para produção (PostgreSQL, pool, compressão HTTP)
- **Configurações:**
  - `spring.datasource.url=jdbc:postgresql://...` com `${POSTGRES_PASSWORD}`
  - Hikari pool: `maximum-pool-size=20`, `minimum-idle=5`
  - `server.compression.enabled=true` — compressão HTTP ativa em prod
  - `spring.h2.console.enabled=false` — H2 desabilitado em prod
- **Estado:** ✅ Funcional

---

### 3.4. `aquasense-backend/src/main/java/com/aquasense/backend/config/SecurityConfig.java`
- **Propósito:** Configuração Spring Security — endpoints permitidos, filtros JWT, CORS
- **Componentes:**
  - `filterChain()` — CSRF desabilitado, sessão STATELESS, permitAll para `/auth/**`, `/interno/**`, `/h2-console/**`, `/health`; autenticado para tudo mais
  - `JwtFilter` adicionado antes de `UsernamePasswordAuthenticationFilter`
  - `InternalTokenFilter` adicionado depois de `JwtFilter`
- **Estado:** ✅ Funcional
- **Observações:** `/interno/**` está em `permitAll` (sem JWT), protegido apenas pelo `InternalTokenFilter` por X-Internal-Token. Se o token interno vazar, acesso total ao endpoint de leituras.

---

### 3.5. `aquasense-backend/src/main/java/com/aquasense/backend/config/CorsConfig.java`
- **Propósito:** Define origens permitidas para CORS
- **Comportamento:** Registra `CorsFilter` apenas para `/auth/**` e `/api/**` — `/interno/**` não tem CORS (correto, é para uso interno)
- **Estado:** ✅ Funcional
- **Observações:** `allowedOriginPatterns("*")` em dev — deve ser restrito a domínio em prod via `FRONTEND_URL`

---

### 3.6. `aquasense-backend/src/main/java/com/aquasense/backend/filter/JwtFilter.java`
- **Propósito:** Extrai e valida JWT em cada request autenticado
- **Lógica:** Tenta cookie `aquasense_session` primeiro, depois header `Authorization: Bearer`; se válido, seta `SecurityContextHolder`
- **Estado:** ✅ Funcional

---

### 3.7. `aquasense-backend/src/main/java/com/aquasense/backend/filter/InternalTokenFilter.java`
- **Propósito:** Protege endpoints `/interno/**` exigindo header `X-Internal-Token`
- **Lógica:** `@PostConstruct` alerta se token é o default em prod; valida header em cada request `/interno/**`; retorna 403 se inválido
- **Estado:** ✅ Funcional

---

### 3.8. `aquasense-backend/src/main/java/com/aquasense/backend/service/JwtService.java`
- **Propósito:** Geração, validação e invalidação de tokens JWT
- **Funções:**
  - `generateToken(email)` — cria JWT com subject=email, expiry, assinado com HS256
  - `extractUsername(token)` — extrai subject
  - `isTokenValid(token, userDetails)` — verifica username + expiração + blacklist
  - `invalidateToken(token)` — adiciona ao `tokenBlacklist` (ConcurrentHashMap Set)
- **Estado:** ⚠️ Parcial
- **Observações:** **BUG CRÍTICO** — `tokenBlacklist` é in-memory. Perde todos os tokens invalidados ao reiniciar o servidor. Tokens de sessões terminadas (logout) tornam-se válidos novamente após restart.

---

### 3.9. `aquasense-backend/src/main/java/com/aquasense/backend/service/AlertaService.java`
- **Propósito:** Avaliação de umbrais de sensores e gestão do ciclo de vida de alertas
- **Funções:**
  - `evaluarUmbral(projetoId, componente, valores)` — `@Async`; switch com 8 casos de componente; avalia parâmetros contra limites hardcoded; chama `criarAlerta` ou `resolverAlertasSi`
  - `criarAlerta(...)` — deduplicação: só cria se não existe alerta ativo do mesmo `(projetoId, componente, tipo)`; chama `ejecutarAccionAutomatica` para alertas CRITICA; notifica via `NotificacaoService`
  - `resolverAlertasSi(projetoId, componente, tipo)` — marca como inativa se condição normalizada
  - `ejecutarAccionAutomatica(alerta)` — seta `equipamento.estado = accion` (e.g. "aumentarCloro")
  - `ackAlerta`, `silenciarAlerta`, `asignarAlerta`, `resolverAlerta`, `comentarAlerta` — operações de lifecycle
- **Estado:** ⚠️ Parcial
- **Observações:**
  - `ejecutarAccionAutomatica()` seta `estado` do equipamento para strings como "aumentarCloro" ou "cerrarValvulaEntrada" — não são valores válidos do enum `EstadoEquipamento`, quebrando a semântica de estado AUTO/MANUAL
  - Umbrais hardcoded no service — difícil de configurar por projeto/instalação

---

### 3.10. `aquasense-backend/src/main/java/com/aquasense/backend/service/ProjetoService.java`
- **Propósito:** CRUD de projetos, gestão de layout, simulação, leituras manuais, papéis, auditoria
- **Funções:**
  - `listByUsuario(email)` — une projetos próprios + projetos com role atribuído
  - `create(dto, email)` — cria projeto + 8 equipamentos canónicos + regista auditoria
  - `deleteById(id, email)` — delete em cascata (alertas, lecturas, equipamentos, roles) + regista auditoria
  - `getEstado(id)` — single query via `findLatestPerComponente()` (corrige N+1); devolve mapa `componente → Map<param, valor>`
  - `getHistorico(id, componente, from, to)` — limite de 2000 linhas
  - `exportHistoricoCsv(...)` — limite de 50000 linhas, usa Commons CSV
  - `getLayout(id)` — `@Cacheable("layout")`
  - `saveLayout(id, json, email)` — `@CacheEvict("layout")` + auditoria
  - `startSimulacao/stopSimulacao` — seta flag `simulacaoAtiva`; sem comunicação direta com Python (Python faz polling)
  - `toDTO(projeto)` — ⚠️ N+1 potencial: faz 2 queries extra por projeto ao listar
- **Estado:** ⚠️ Parcial
- **Observações:** N+1 em `toDTO()` ao listar múltiplos projetos; `findOwnedProject` verifica dono OU role mas pode lançar AccessDeniedException sem mensagem clara

---

### 3.11. `aquasense-backend/src/main/java/com/aquasense/backend/service/EmailService.java`
- **Propósito:** Envio assíncrono de alertas críticos por email
- **Funções:**
  - `isEnabled()` — verifica se `JavaMailSender` foi injetado
  - `enviarAlertaCritica(alerta, destinatario)` — `@Async`; envia HTML com detalhes do alerta
- **Estado:** ⚠️ Parcial
- **Observações:** **BUG** — URL do alerta hardcoded: `"https://app.aquasense.io/proyectos/" + projetoId`. Deve ser variável de ambiente.

---

### 3.12. `aquasense-backend/src/main/java/com/aquasense/backend/service/UserService.java`
- **Propósito:** Gestão de perfil de utilizador (nome, idioma, password, email, delete)
- **Funções:**
  - `changeEmail(...)` — valida password atual + verifica unicidade do novo email + invalida token JWT (logout forçado)
  - `deleteAccount(...)` — valida password + deleta todos projetos próprios + o utilizador
- **Estado:** ✅ Funcional

---

### 3.13. `aquasense-backend/src/main/java/com/aquasense/backend/service/AuditoriaService.java`
- **Propósito:** Registo assíncrono de eventos de auditoria
- **Funções:**
  - `registrar(email, accion, entidade, antes, depois, ip, projetoId)` — `@Async`, `Propagation.REQUIRES_NEW` — garante persistência mesmo se a transação pai fizer rollback
- **Estado:** ✅ Funcional

---

### 3.14. `aquasense-backend/src/main/java/com/aquasense/backend/controller/ProjetoController.java`
- **Propósito:** Controller REST principal — CRUD projetos + todas as operações de monitoramento
- **Endpoints:** Ver secção 7.3 para lista completa
- **Estado:** ✅ Funcional
- **Observações:** `getEquipos()` retorna `List<Equipamento>` (entidade JPA direta, não DTO) — pode causar serialização de campos desnecessários e expor estrutura interna

---

### 3.15. `aquasense-backend/src/main/java/com/aquasense/backend/model/Alerta.java`
- **Propósito:** Entidade JPA para alertas com ciclo de vida completo
- **Campos:** `id, projeto, componente, nivel (NivelAlerta enum), mensagem, tipo, ativa, accionAutomatica, creadaEn` + lifecycle: `reconocidaPor, reconocidaEn, silenciadaHasta (LocalDateTime), asignadaA, resueltaPor, resueltaEn`
- **Estado:** ✅ Funcional

---

### 3.16. `aquasense-backend/src/main/java/com/aquasense/backend/model/Tuberia.java` + `LecturaTuberia.java`
- **Propósito:** Modelagem de tubagens físicas e suas leituras hidráulicas
- **Campos Tuberia:** `id, projeto, fromComponenteId, toComponenteId, diametroMm, materialTuberia, longitudM, createdAt`
- **Campos LecturaTuberia:** `id, tuberia, timestamp, caudalM3h, presionBarEntrada, presionBarSaida, velocidadMs`
- **Estado:** ✅ Funcional (entidades OK, mas ver BUG em tuberias.py e CanvasEditor)

---

### 3.17. `aquasense-backend/src/main/java/com/aquasense/backend/model/PreferenciasNotificacion.java`
- **Propósito:** Preferências de notificação email por utilizador por projeto
- **Campos:** `id, usuario, projeto, notificarCritica (default true), notificarAdvertencia (default false), emailDestino (nullable)`
- **Estado:** ✅ Funcional

---

### 3.18. `aquasense-backend/src/main/java/com/aquasense/backend/repository/LeituraRepository.java`
- **Propósito:** Acesso a dados de leituras de sensores
- **Queries relevantes:**
  - `findLatestPerComponente(projetoId)` — JPQL com subquery para obter última leitura por componente num único SQL — corrige o N+1 de `getEstado()`
  - `findByProjetoIdAndComponenteAndTimestampBetween` — para histórico
- **Estado:** ✅ Funcional

---

### 3.19. `db/init.sql`
- **Propósito:** Schema de base de dados e dados demo
- **Tabelas:** `usuarios, proyectos, lecturas_sensor, alertas, equipamentos, usuario_proyecto_rol, eventos_auditoria, comentarios_alerta, preferencias_notificacion, tuberias, lecturas_tuberia`
- **Dados demo:** Utilizador `admin@aquasense.com` com password em texto plano "password" (para referência apenas — o `DataInitializer.java` usa BCrypt)
- **Estado:** ✅ Funcional
- **Observações:** Índices em `(proyecto_id, componente, timestamp)` para performance de queries de leituras

---

### 3.20. `aquasense-frontend/src/App.jsx`
- **Propósito:** Root da aplicação React — providers + roteamento
- **Estrutura:** `ErrorBoundary > LanguageProvider > AuthProvider > ProjectProvider > BrowserRouter`
- **Rotas:** `/login`, `/proyectos`, `/proyectos/:id` (Dashboard), `/proyectos/:id/alertas`, `/historico`, `/equipa`, `/auditoria`, `/notificaciones`, `/perfil`
- **Lazy loading:** Historico, Equipa, Auditoria, Notificaciones, Perfil (Suspense com fallback null)
- **Estado:** ✅ Funcional
- **Observações:** Fallback de Suspense é `null` (sem spinner/loading state visível)

---

### 3.21. `aquasense-frontend/src/context/AuthContext.jsx`
- **Propósito:** Estado de autenticação global — user, login, logout, perfil
- **Funções:**
  - `useEffect` inicial — sincroniza idioma do backend via `GET /api/usuarios/me` ao restaurar sessão de localStorage (corrige stale language)
  - `login/register` — guarda token + user em localStorage + seta idioma
  - `logout` — chama `POST /auth/logout`, limpa cache de roles, remove localStorage
  - `updateProfile`, `changePassword`, `changeEmail`, `deleteAccount`
- **Estado:** ✅ Funcional

---

### 3.22. `aquasense-frontend/src/context/LanguageContext.jsx`
- **Propósito:** i18n — 5 idiomas (en, pt, es, fr, de)
- **Funções:**
  - `t(key)` — lookup com fallback: lang → 'en' → key (nunca quebra UI por tradução ausente)
  - `localeCode` — mapeamento para `Intl` (datas relativas)
  - `getInitialLang()` — lê localStorage ou 'en' como default
- **Estado:** ✅ Funcional

---

### 3.23. `aquasense-frontend/src/hooks/usePolling.js`
- **Propósito:** Hook de polling com backoff exponencial e pausa em tab oculta
- **Comportamento:**
  - Intervalo normal: configurável (5000ms no Dashboard)
  - Erro: backoff 5s → 10s → 20s → 30s (cap)
  - Tab oculta: pausa; retoma ao tornar visível
  - Cleanup no unmount
- **Estado:** ✅ Funcional

---

### 3.24. `aquasense-frontend/src/hooks/useRole.js`
- **Propósito:** Obtém e cache o papel do utilizador atual num projeto
- **Funções:**
  - Cache module-level por `projetoId` — persiste entre navegações na mesma sessão
  - `clearRoleCache()` — exportado para uso no logout
  - Expõe: `isAdmin, isOperador, isMantenimiento, canEdit, canControl`
- **Estado:** ⚠️ Parcial
- **Observações:** Cache não é invalidado quando o role do utilizador muda no servidor (sem mudança de projeto nem logout). Um admin que revoga o acesso de alguém — o frontend da vítima mantém o role antigo até logout.

---

### 3.25. `aquasense-frontend/src/services/api.js`
- **Propósito:** Instância axios configurada para comunicação com backend
- **Comportamento:**
  - `baseURL` = `VITE_API_URL` (env var)
  - `withCredentials: true` — envia cookie `aquasense_session`
  - Request interceptor: adiciona `Authorization: Bearer <token>` de localStorage
  - Response interceptor: redireciona para `/login` em 401 (exceto endpoints `/auth/`)
- **Estado:** ✅ Funcional

---

### 3.26. `aquasense-frontend/src/components/Sinoptico/Sinoptico.jsx`
- **Propósito:** Vista SCADA principal — renderiza o sinóptico SVG do projeto
- **Modos:**
  - `StaticSinoptico` — SVG com posições fixas para os 8 componentes canónicos (fallback sem layout guardado)
  - `ViewSinoptico` — renderiza a partir do layout JSON guardado no backend
  - `CanvasEditor` — modo de edição (drag-and-drop)
- **Funcionalidades:** Migração de layout legado (formato antigo `cards/connections` → novo `componentes/tuberias`), PIPE_TYPES com cores e estilos
- **Estado:** ✅ Funcional
- **Observações:** Componente muito grande; lógica de migração de layout embutida

---

### 3.27. `aquasense-frontend/src/components/Sinoptico/CanvasEditor.jsx`
- **Propósito:** Editor drag-and-drop de sinóptico SVG
- **Funcionalidades:**
  - Pan (arrastar fundo ou Space+arrastar), zoom (scroll)
  - Conexão de portas (click porta → linha fantasma → click porta destino)
  - Seletor de tipo de tubagem (PIPE_TYPES)
  - Palete lateral (colapsível + redimensionável)
  - Modo impressão (`window.print()`)
  - PropsPanel para instância selecionada
  - Guarda via POST `/api/proyectos/:id/layout`
  - Ao conectar portas: chama POST `/api/proyectos/:id/tuberias` para criar tuberia no BD
- **Estado:** ⚠️ Parcial
- **Observações:** **BUG** — `handleDeleteConnection()` remove a conexão do estado canvas mas **não chama DELETE `/api/proyectos/:id/tuberias/:tid`**. BD fica dessincronizado com o canvas após deletar conexões.

---

### 3.28. `aquasense-frontend/src/components/Sinoptico/ModoPanel.jsx`
- **Propósito:** Painel overlay de componente — toggle AUTO/MANUAL, leituras manuais, valores atuais
- **Funcionalidades:**
  - Toggle AUTO/MANUAL → `POST /api/proyectos/:id/control`
  - Formulário de leitura manual → `POST /api/proyectos/:id/lecturas`
  - Exibe valores atuais em modo AUTO (read-only)
  - PARAMS e UNITS maps por tipo de componente (8 tipos)
- **Estado:** ✅ Funcional

---

### 3.29. `aquasense-frontend/src/pages/Dashboard.jsx`
- **Propósito:** Página principal de monitoramento por projeto
- **Funcionalidades:**
  - Polling 5s para `/estado` e `/alertas`
  - Toggle simulação (botão)
  - Links de admin (Equipa, Auditoria, Notificaciones) — visíveis apenas para ADMIN
  - Usa `useRole` para rendering condicional
- **Estado:** ✅ Funcional

---

### 3.30. `aquasense-frontend/src/pages/Historico.jsx`
- **Propósito:** Gráficos de histórico com Chart.js e exportação CSV
- **Funcionalidades:** 4 gráficos (Cloro, pH, Nível Reservatório, Caudal); filtro por intervalo de datas; export CSV
- **Estado:** ⚠️ Parcial
- **Observações:** **BUG** — Labels do eixo X gerados de todos os timestamps únicos do histórico; datasets filtrados por componente. Se componentes não têm leituras nos mesmos timestamps exatos, os pontos no gráfico ficam desalinhados (ex: leitura de cloro às 10:00:01 e pH às 10:00:02 aparecem em posições diferentes).

---

### 3.31. `aquasense-frontend/src/pages/Alertas.jsx`
- **Propósito:** Gestão completa de alertas com filtros e ações
- **Funcionalidades:** Tabela paginada; filtros por nivel/estado/componente; linhas expansíveis com comentários; modais ack/silence/assign/resolve/comment
- **Estado:** ✅ Funcional

---

### 3.32. `aquasense-frontend/src/pages/Auditoria.jsx`
- **Propósito:** Log de auditoria paginado com filtros
- **Funcionalidades:** Filtros por ação/utilizador/intervalo de datas; paginação
- **Estado:** ⚠️ Parcial
- **Observações:** **UX BUG** — Não carrega dados automaticamente ao montar. Requer que o utilizador clique em "Pesquisar" para ver qualquer dado. Sem auto-load inicial, a tabela aparece vazia.

---

### 3.33. `aquasense-frontend/src/pages/Perfil.jsx`
- **Propósito:** Gestão de conta do utilizador
- **Funcionalidades:** Alterar nome/idioma, alterar password, alterar email (força logout), apagar conta (requer digitar "DELETE")
- **Estado:** ✅ Funcional

---

### 3.34. `aquasense-frontend/src/pages/Notificaciones.jsx`
- **Propósito:** Configuração de notificações email por projeto
- **Funcionalidades:** Toggle notificarCritica/notificarAdvertencia, email destino customizável
- **Estado:** ✅ Funcional

---

### 3.35. `python/main.py`
- **Propósito:** Loop principal do simulador
- **Lógica:**
  - A cada 10s: polling `/interno/simulacao/projetos-ativos` para lista de projetos ativos
  - A cada 5s por projeto: `atualizar_estado()` + `validacao()` + `enviar_para_projeto()` + `simular_ciclo_tuberias()`
  - Separação de threads por projeto com `threading.Thread`
- **Estado:** ✅ Funcional (com ressalva de automation.py)

---

### 3.36. `python/simulator.py`
- **Propósito:** Simulação de sensores com drift, ruído e anomalias
- **Classes:**
  - `SensorSimulator` — `drift_rate`, `noise_amplitude`; método `step(componente, parametro, valor_atual)` — aplica drift + ruído gaussiano
  - Sistema de anomalias: 4 anomalias programadas (ex: "bomba_falha_pressao", "contamination_spike") com duração e ciclos de recuperação
- **Estado:** ✅ Funcional

---

### 3.37. `python/automation.py`
- **Propósito:** Validação de leituras contra umbrais
- **Funções:**
  - `validacao(estado)` — itera sobre `THRESHOLDS` de `config.py`; adiciona flags ao dict `estado["flags"]`
- **Estado:** ❌ Código morto
- **Observações:** **BUG CRÍTICO** — `client.py` tem `if key_python == "flags": continue` — pula explicitamente as flags. Toda a lógica de `automation.py` é executada mas nunca tem efeito. A avaliação de umbrais que efetivamente age é feita pelo backend em `AlertaService.evaluarUmbral()`.

---

### 3.38. `python/client.py`
- **Propósito:** Cliente HTTP para envio de leituras ao backend
- **Funções:**
  - `enviar_para_projeto(estado, projeto_id)` — 3 retries + backoff exponencial; filtra componentes MANUAL (não envia leituras para eles); mapeamento `_COMPONENTE_MAP` para camelCase→snake_case de IDs
  - `obter_token()` — lê token de variável de ambiente; lazy (só lê uma vez)
- **Estado:** ✅ Funcional (com ressalva — ver automation.py)

---

### 3.39. `python/tuberias.py`
- **Propósito:** Simulação hidráulica de tubagens e envio de leituras ao backend
- **Funções:**
  - `simular_ciclo_tuberias(estado, projeto_id)` — chama `obter_tuberias()`, calcula leituras hidráulicas, envia
  - `obter_tuberias(projeto_id)` — `GET /interno/proyectos/{id}/tuberias` com cache de 60s
  - `_calcular_leitura_hidraulica(tuberia, estado)` — fórmulas simplificadas de Darcy-Weisbach
  - `enviar_leitura_tuberia(projeto_id, tuberia_id, leitura)` — POST `/interno/proyectos/:id/tuberias/:tid/lecturas`
- **Estado:** ❌ Quebrado
- **Observações:** **BUG** — `GET /interno/proyectos/{id}/tuberias` não existe no backend. `SimulacaoInternController` não tem este endpoint. `LecturaTuberiaInternController` só tem POST para lecturas. A função `obter_tuberias()` sempre retorna lista vazia silenciosamente, tornando `simular_ciclo_tuberias()` um no-op.

---

### 3.40. `python/config.py`
- **Propósito:** Constantes e configurações do simulador
- **Conteúdo:** `INTERVALO_CICLO=5`, `URL_BACKEND="http://localhost:8080"`, `PROJETO_ID=1` (hardcoded — sobrescrito pelo main.py que usa a lista dinâmica), `ESTADO_INICIAL`, `RUIDO_GLOBAL`, `THRESHOLDS`
- **Estado:** ✅ Funcional
- **Observações:** `PROJETO_ID=1` é ignorado pelo `main.py` (que usa lista dinâmica), mas `client.py` e outros módulos podem ainda referenciar este valor em alguns contextos

---

### 3.41. `docs/conexiones-equipos.md`
- **Propósito:** Documentação das conexões físicas entre equipamentos ETAP
- **Conteúdo:** Mapa de conexões, tipos de tubagem, geometria de portas, parâmetros críticos por conexão
- **Estado:** ✅ Documental

---

### 3.42. `aquasense-v2/catalogo/equipamentos.json`
- **Propósito:** Catálogo de 40+ tipos de equipamento em 6 categorias para o editor de sinóptico
- **Categorias:** bombas, tanques, valvulas, instrumentacion, tratamento, dosagem
- **Estado:** ✅ Funcional (usado pelo CanvasEditor para palheta lateral)

---

## 4. FUNCIONALIDADES VISÍVEIS AO USUÁRIO (UI ATUAL)

### 4.1. Tela: Login (`/login`)
- **Elementos visíveis:**
  - **Formulário Login:** campos email + password → `POST /auth/login` → redireciona para `/proyectos`
  - **Link "Registar":** alterna para formulário de registo
  - **Formulário Registo:** nome, email, password, seletor de idioma → `POST /auth/register`
  - **Seletor de idioma:** dropdown com 5 idiomas (en, pt, es, fr, de)
- **Estado:** ✅

---

### 4.2. Tela: Lista de Projetos (`/proyectos`)
- **Elementos visíveis:**
  - **Cards de projeto:** nome, localização, status ONLINE/OFFLINE (baseado na última leitura < 30s), contagem de alertas ativos, timestamp da última leitura (relativo), indicador de simulação ativa (ponto animado)
  - **Botão "Novo Projeto":** abre formulário inline com nome, localização (opcional), descrição (opcional) → `POST /api/proyectos`
  - **Botão "Eliminar":** confirmação inline (digitar nome do projeto) → `DELETE /api/proyectos/:id`
  - **Click no card:** navega para `/proyectos/:id`
- **Estado:** ✅

---

### 4.3. Tela: Dashboard (`/proyectos/:id`)
- **Elementos visíveis:**
  - **Topbar:** nome do projeto, badge de alertas ativos (vermelho), botão toggle simulação (LIGAR/DESLIGAR), links admin (Equipa, Auditoria, Notificações) — visíveis apenas para ADMIN
  - **Sinóptico SVG:** vista dos componentes com estado atual; clique abre ModoPanel
  - **AlertasList (sidebar):** lista de alertas ativos com nivel, componente, mensagem, timestamp
  - **ReadingsBar (barra inferior):** leituras-chave de sensores (pH, cloro, turbidez, pressão, etc.)
  - **ModoPanel (overlay):**
    - Toggle AUTO/MANUAL → `POST /api/proyectos/:id/control`
    - Em modo MANUAL: formulário de leitura manual → `POST /api/proyectos/:id/lecturas`
    - Em modo AUTO: exibe valores atuais (read-only)
  - **Botão "Editar Sinóptico"** (admin): abre CanvasEditor
  - **Botão "Alertas":** navega para `/proyectos/:id/alertas`
  - **Botão "Histórico":** navega para `/proyectos/:id/historico`
- **Estado:** ✅
- **Polling:** Atualiza automaticamente a cada 5s; pausa em tab oculta; backoff em erro

---

### 4.4. Tela: Editor de Sinóptico (CanvasEditor, modo edição)
- **Elementos visíveis:**
  - **Canvas SVG:** componentes arrastáveis, portas de conexão clicáveis, tubagens coloridas por tipo
  - **Palete lateral:** 40+ equipamentos em categorias, drag para adicionar ao canvas
  - **Seletor de tipo de tubagem:** dropdown com tipos (principal, secundária, retorno, pressão, etc.)
  - **PropsPanel:** painel de propriedades do elemento selecionado
  - **Botão "Guardar":** `POST /api/proyectos/:id/layout` (JSON do canvas)
  - **Botão "Imprimir":** ativa modo impressão + `window.print()`
  - **Controles de zoom e pan:** botões + scroll + Space+drag
- **Estado:** ⚠️ — Delete de conexão não sincroniza com BD (ver BUG #4)

---

### 4.5. Tela: Alertas (`/proyectos/:id/alertas`)
- **Elementos visíveis:**
  - **Filtros:** por nível (ADVERTENCIA/CRITICA), estado (ativo/inativo), componente
  - **Tabela de alertas:** componente, nivel, mensagem, timestamp, estado, ações
  - **Linha expansível:** mostra comentários do alerta
  - **Botão "Reconhecer":** modal → `POST /api/alertas/:aid/ack`
  - **Botão "Silenciar":** modal com seletor de duração → `POST /api/alertas/:aid/silence`
  - **Botão "Atribuir":** modal com input de email → `POST /api/alertas/:aid/assign`
  - **Botão "Resolver":** modal com nota → `POST /api/alertas/:aid/resolve`
  - **Campo "Comentar":** inline → `POST /api/alertas/:aid/comentarios`
- **Estado:** ✅

---

### 4.6. Tela: Histórico (`/proyectos/:id/historico`)
- **Elementos visíveis:**
  - **4 gráficos Chart.js:** Cloro residual, pH, Nível Reservatório, Caudal
  - **Filtros de datas:** date pickers para intervalo
  - **Botão "Exportar CSV":** `GET /api/proyectos/:id/historico/export` → download ficheiro CSV
- **Estado:** ⚠️ — Desalinhamento de dados em gráficos com leituras de timestamps díspares (ver BUG #5)

---

### 4.7. Tela: Equipa (`/proyectos/:id/equipa`) — Admin only
- **Elementos visíveis:**
  - **Tabela de membros:** email, nome, role (dropdown), botão "Remover"
  - **Dropdown de role:** ADMIN / OPERADOR / MANTENIMIENTO / VISUALIZADOR → `POST /api/proyectos/:id/roles`
  - **Formulário "Adicionar Membro":** input email + seletor role → `POST /api/proyectos/:id/roles`
- **Estado:** ✅

---

### 4.8. Tela: Auditoria (`/proyectos/:id/auditoria`) — Admin only
- **Elementos visíveis:**
  - **Filtros:** por ação, utilizador, data início, data fim
  - **Botão "Pesquisar":** dispara GET com filtros
  - **Tabela paginada:** timestamp, utilizador, ação, entidade, valores antes/depois
- **Estado:** ⚠️ — Sem auto-load; tabela vazia até utilizador clicar "Pesquisar" manualmente

---

### 4.9. Tela: Notificações (`/proyectos/:id/notificaciones`)
- **Elementos visíveis:**
  - **Toggle "Notificar Crítica":** liga/desliga emails para alertas CRITICA
  - **Toggle "Notificar Advertência":** liga/desliga emails para ADVERTENCIA
  - **Campo "Email destino":** email customizável (usa email da conta se vazio)
  - **Botão "Guardar":** `PUT /api/proyectos/:id/notificaciones`
- **Estado:** ✅

---

### 4.10. Tela: Perfil (`/perfil`)
- **Elementos visíveis:**
  - **Formulário Nome/Idioma:** atualiza → `PUT /api/usuarios/me`
  - **Formulário Alterar Password:** password atual + nova → `PUT /api/usuarios/me/password`
  - **Formulário Alterar Email:** novo email + password → `PUT /api/usuarios/me/email` (força logout)
  - **Botão "Eliminar Conta":** requer digitar "DELETE" → `DELETE /api/usuarios/me`
- **Estado:** ✅

---

## 5. COMO O PROGRAMA DEVERIA FUNCIONAR

### 5.1. Fluxos esperados

- **Fluxo A — Monitoramento em tempo real:** Simulador envia leituras a cada 5s → backend avalia umbrais → cria alertas automaticamente → frontend recebe via polling → operador vê estado atualizado no sinóptico
- **Fluxo B — Resposta a alerta:** Alerta criado → frontend mostra na sidebar e topbar → operador reconhece → atribui a técnico → técnico resolve → alerta fechado
- **Fluxo C — Operação manual:** Operador muda componente para MANUAL → insere leitura manual → simulador Python ignora o componente (filtra MANUAL) → operador insere valores → muda de volta para AUTO
- **Fluxo D — Gestão de equipa:** ADMIN convida utilizador por email → atribui role → utilizador acede ao projeto com permissões corretas
- **Fluxo E — Notificação automática:** Alerta CRITICA criado → `NotificacaoService` coleta utilizadores do projeto com `notificarCritica=true` → envia email HTML assíncrono

### 5.2. Regras de negócio identificadas

1. Só o dono do projeto pode apagá-lo (`findOwnedProject` verifica dono OU role para acesso, mas delete exige dono)
2. Deduplicação de alertas: não cria novo alerta se já existe ativo com mesmo `(projetoId, componente, tipo)`
3. Componentes em modo MANUAL não recebem leituras do simulador Python (filtro em `client.py`)
4. Alertas CRITICA disparam ações automáticas no equipamento (ex: fechar válvula)
5. Papéis ADMIN e OPERADOR têm acesso de escrita (`canEdit=true`); MANTENIMIENTO tem controlo (`canControl=true`); VISUALIZADOR apenas lê
6. Alterar email invalida a sessão atual (logout forçado por segurança)
7. Exportação CSV limitada a 50000 linhas; histórico normal a 2000 linhas
8. Layout do sinóptico é cacheado em memória Spring Cache (invalidado ao guardar novo layout)
9. JWT expira em 8 horas; tokens de logout são blacklistados em memória

---

## 6. DIAGNÓSTICO DE SAÚDE DO PROJETO

### 6.1. O que ESTÁ funcionando corretamente

- Autenticação JWT completa com cookie HttpOnly + header Bearer como fallback
- Registo e login de utilizadores com BCrypt
- CRUD completo de projetos com isolamento por utilizador
- Sistema de roles por projeto (ADMIN/OPERADOR/MANTENIMIENTO/VISUALIZADOR)
- Polling de estado com backoff exponencial no frontend
- Ciclo completo de alertas (criação → ack → silence → assign → resolve → comentar)
- Deduplicação de alertas por `(projetoId, componente, tipo)`
- Editor de sinóptico drag-and-drop com guardado de layout
- i18n em 5 idiomas com fallback chain (lang→en→key)
- Simulação Python com drift, ruído e anomalias
- Auditoria assíncrona de todas as ações (com `REQUIRES_NEW` para garantir persistência)
- Exportação CSV de histórico
- Notificações email opcionais (graceful degradation se SMTP não configurado)
- Deploy containerizado com Docker e configuração Railway/Vercel
- Separação correta de ambientes dev (H2) e prod (PostgreSQL)
- Sincronização de idioma ao restaurar sessão do localStorage

### 6.2. O que está PARCIALMENTE funcionando

- **Simulação hidráulica de tubagens:**
  - **Localização:** `python/tuberias.py` + backend sem endpoint GET `/interno/proyectos/:id/tuberias`
  - **Impacto:** `simular_ciclo_tuberias()` é chamado mas retorna sempre lista vazia; leituras hidráulicas nunca são enviadas

- **Validação de umbrais no Python (`automation.py`):**
  - **Localização:** `python/automation.py` + `python/client.py`
  - **Impacto:** Código de validação executado mas resultado completamente ignorado; não causa erro, mas é trabalho desperdiçado

- **Cache de roles no frontend (`useRole.js`):**
  - **Localização:** `aquasense-frontend/src/hooks/useRole.js`
  - **Impacto:** Role do utilizador não atualizado em tempo real; requer logout para refletir mudanças de role feitas por admin

- **`toDTO()` com N+1 potencial:**
  - **Localização:** `ProjetoService.java` (método `toDTO()`)
  - **Impacto:** Para N projetos, executa N×2 queries adicionais; aceitável com poucos projetos, problemático com muitos

### 6.3. O que está QUEBRADO ou COM BUG

- **Bug 1: Delete de conexão no CanvasEditor não sincroniza com BD**
  - **Localização:** `aquasense-frontend/src/components/Sinoptico/CanvasEditor.jsx` — `handleDeleteConnection()`
  - **Comportamento atual:** Conexão removida do canvas SVG mas `DELETE /api/proyectos/:id/tuberias/:tid` nunca chamado
  - **Comportamento esperado:** Deletar conexão do canvas deve também deletar a Tuberia correspondente no BD
  - **Severidade:** Alta (BD dessincronizado; leituras de tubagens órfãs continuam a acumular)

- **Bug 2: Blacklist JWT in-memory perdida no restart**
  - **Localização:** `aquasense-backend/src/main/java/com/aquasense/backend/service/JwtService.java` — `tokenBlacklist`
  - **Comportamento atual:** Após restart do servidor, tokens de sessões com logout tornam-se válidos
  - **Comportamento esperado:** Tokens invalidados permanecem inválidos até expirar
  - **Severidade:** Alta (risco de segurança)

- **Bug 3: `evaluarAccionAutomatica()` seta estado inválido em Equipamento**
  - **Localização:** `AlertaService.java` — `ejecutarAccionAutomatica()`
  - **Comportamento atual:** `equipamento.estado` recebe strings como "aumentarCloro", "cerrarValvulaEntrada" — valores não previstos na lógica de modo AUTO/MANUAL
  - **Comportamento esperado:** Ações automáticas devem usar valores de estado bem definidos
  - **Severidade:** Média

- **Bug 4: Endpoint GET `/interno/proyectos/:id/tuberias` não existe**
  - **Localização:** `python/tuberias.py` — `obter_tuberias()` + backend (ausência de endpoint)
  - **Comportamento atual:** Retorna 404 silenciosamente; simulação hidráulica desativada de facto
  - **Comportamento esperado:** Endpoint deveria existir em `SimulacaoInternController` ou `TuberiaController` (com auth interna)
  - **Severidade:** Alta (feature completa do sistema não funciona)

- **Bug 5: Gráficos de histórico com eixo X desalinhado**
  - **Localização:** `aquasense-frontend/src/pages/Historico.jsx` — geração de labels
  - **Comportamento atual:** Labels gerados de todos os timestamps únicos; datasets filtrados por componente; timestamps diferentes por componente causam desalinhamento
  - **Comportamento esperado:** Dados de cada componente alinhados ao mesmo eixo temporal
  - **Severidade:** Média (dados visualmente incorretos, não perda de dados)

- **Bug 6: URL hardcoded no EmailService**
  - **Localização:** `aquasense-backend/src/main/java/com/aquasense/backend/service/EmailService.java`
  - **Comportamento atual:** Link no email aponta para `https://app.aquasense.io/proyectos/{id}` independente de onde o sistema está implantado
  - **Comportamento esperado:** URL deve ser configurável via variável de ambiente
  - **Severidade:** Baixa (funcional, mas incorreto em deployments customizados)

- **Bug 7: Página de Auditoria sem auto-load**
  - **Localização:** `aquasense-frontend/src/pages/Auditoria.jsx`
  - **Comportamento atual:** Tabela vazia ao entrar na página; utilizador precisa clicar "Pesquisar"
  - **Comportamento esperado:** Carregar dados recentes automaticamente ao montar o componente
  - **Severidade:** Baixa (UX ruim, não é crash)

### 6.4. O que está FALTANDO ou INCOMPLETO

- **Endpoint GET `/interno/proyectos/:id/tuberias`:** Necessário para simulação hidráulica; Python já o chama mas backend não o tem
- **Persistência de blacklist JWT:** Não há tabela de tokens revogados nem Redis; necessário para robustez pós-restart
- **Testes automatizados:** Apenas `BackendApplicationTests.java` (context load). Zero testes de unidade para lógica de negócio (AlertaService, ProjetoService), zero testes de integração, zero testes de frontend
- **DTOs para `getEquipos()`:** `ProjetoController` retorna entidades JPA diretamente
- **Configuração de umbrais por projeto:** Atualmente hardcoded em `AlertaService` — não configurável por instalação
- **Ação automática tipada:** `ejecutarAccionAutomatica()` usa strings livres em vez de enum
- **Invalidação do cache de roles ao mudar role:** `useRole.js` cache não invalida quando admin muda role de alguém

### 6.5. Problemas de SEGURANÇA detectados

- **[ALTA] `aquasense-frontend/.env` presente no repositório:** O ficheiro `.env` (não `.env.example`) existe no frontend. Deve ser verificado manualmente — se contiver variáveis sensíveis além de `VITE_API_URL`, é necessário remoção do histórico git.
- **[ALTA] Blacklist JWT in-memory:** Tokens de logout tornam-se válidos após restart (detalhado em Bug #2)
- **[MÉDIA] Token interno com valor default inseguro:** `X_INTERNAL_TOKEN` tem default `dev-internal-token-change-in-prod` no `application.properties`. `InternalTokenFilter` alerta em prod, mas o risco existe se alguém esquece de definir a variável de ambiente
- **[BAIXA] H2 console habilitado no perfil dev:** `spring.h2.console.enabled=true` — aceitável em dev, perigoso se perfil prod não sobrescrever (e sobrescreve: `application-prod.properties` tem `false`)
- **[BAIXA] `allowedOriginPatterns("*")` em dev:** CORS permite qualquer origem em dev; deve ser restrito em prod via `FRONTEND_URL`

### 6.6. Problemas de PERFORMANCE detectados

- **N+1 em `ProjetoService.toDTO()`:** Para cada projeto na lista, executa 2 queries adicionais (última leitura + alertas ativos). Com 50 projetos = 100 queries extras. Fixável com query única + GROUP BY ou join fetch.
- **Histórico sem paginação real no frontend:** `Historico.jsx` carrega até 2000 linhas de uma vez (limite do backend), sem lazy loading ou virtualização. Com gráficos Chart.js renderizando 2000 pontos, pode ser lento em máquinas modestas.
- **Cache de layout in-memory:** `@Cacheable("layout")` usa cache em memória Spring. Em deploy com múltiplas instâncias, cada instância tem seu cache — inconsistência possível. Aceitável em deploy single-instance.

### 6.7. Problemas de QUALIDADE DE CÓDIGO

- **`automation.py` — código morto:** Todo o ficheiro é executado mas o resultado é descartado por `client.py`. Confunde leitores e desperdiça CPU (marginal)
- **Umbrais hardcoded em `AlertaService`:** Dificultam configuração por cliente/instalação; deveriam ser configuráveis por projeto
- **`Sinoptico.jsx` muito grande:** Lógica de migração de layout legado + renderização + modos de edição no mesmo ficheiro; candidato a split
- **Fallback de Suspense é `null`:** `<Suspense fallback={null}>` em `App.jsx` para lazy routes — sem indicador visual de carregamento
- **Erros silenciosos em `Equipa.jsx`:** `handleChangeRol` e `handleRemove` têm `catch {}` vazio — falhas não são reportadas ao utilizador
- **`ProjetoController.getEquipos()` retorna entidade JPA:** Sem DTO, expõe estrutura interna e pode causar issues de serialização com lazy loading
- **Comentários misturados PT/ES:** Código tem comentários em português (maioria) e espanhol (alguns); inconsistente com CLAUDE.md que especifica PT para comentários

---

## 7. INVENTÁRIO TÉCNICO

### 7.1. Dependências Backend (pom.xml)

| Pacote | Versão | Uso | Status |
|--------|--------|-----|--------|
| spring-boot-starter-web | 3.3.4 (BOM) | REST API | ✅ |
| spring-boot-starter-security | 3.3.4 (BOM) | Auth/filtros | ✅ |
| spring-boot-starter-data-jpa | 3.3.4 (BOM) | ORM | ✅ |
| spring-boot-starter-mail | 3.3.4 (BOM) | Email SMTP | ✅ |
| spring-boot-starter-cache | 3.3.4 (BOM) | Cache layout | ✅ |
| h2 | 3.3.4 (BOM) | BD dev | ✅ |
| postgresql | 3.3.4 (BOM) | BD prod | ✅ |
| jjwt-api | 0.12.3 | JWT | ✅ |
| jjwt-impl | 0.12.3 | JWT | ✅ |
| jjwt-jackson | 0.12.3 | JWT serialization | ✅ |
| lombok | 3.3.4 (BOM) | Boilerplate | ✅ |
| commons-csv | 1.10.0 | Exportação CSV | ✅ |
| spring-boot-starter-test | 3.3.4 (BOM) | Testes | ⚠️ (sem testes reais) |

### 7.2. Dependências Frontend (package.json)

| Pacote | Versão | Uso | Status |
|--------|--------|-----|--------|
| react | 19.2.4 | UI | ✅ |
| react-dom | 19.2.4 | Renderização | ✅ |
| react-router-dom | 7.14.1 | Roteamento | ✅ |
| axios | 1.15.0 | HTTP client | ✅ |
| chart.js | 4.5.1 | Gráficos histórico | ✅ |
| vite | 8.0.4 | Build/dev server | ✅ |
| @vitejs/plugin-react | 4.5.1 | Plugin React Vite | ✅ |

### 7.3. Variáveis de ambiente esperadas

- `JWT_SECRET` — chave para assinatura JWT (mínimo 256 bits)
- `X_INTERNAL_TOKEN` — token para comunicação Python→Backend
- `POSTGRES_PASSWORD` — password BD PostgreSQL (prod)
- `DB_PASSWORD` — idem (alias usado nalguns contextos)
- `FRONTEND_URL` — URL do frontend para CORS em prod
- `VITE_API_URL` — URL do backend (frontend; só `VITE_` vars são expostas pelo Vite)
- `SMTP_HOST` — hostname do servidor SMTP (opcional — sistema funciona sem)
- `SMTP_PORT` — porta SMTP (opcional)
- `SMTP_USER` — utilizador SMTP (opcional)
- `SMTP_PASS` — password SMTP (opcional)

### 7.4. Endpoints/Rotas

| Método | Rota | Função | Status |
|--------|------|--------|--------|
| POST | /auth/register | Registo de utilizador | ✅ |
| POST | /auth/login | Login + set cookie JWT | ✅ |
| POST | /auth/logout | Logout + invalidate token | ✅ |
| GET | /api/proyectos | Lista projetos do utilizador | ✅ |
| POST | /api/proyectos | Criar projeto | ✅ |
| DELETE | /api/proyectos/:id | Apagar projeto | ✅ |
| GET | /api/proyectos/:id/estado | Estado atual de sensores | ✅ |
| GET | /api/proyectos/:id/historico | Histórico de leituras | ✅ |
| GET | /api/proyectos/:id/historico/export | Export CSV | ✅ |
| GET | /api/proyectos/:id/alertas | Lista alertas | ✅ |
| POST | /api/proyectos/:id/lecturas | Leitura manual | ✅ |
| POST | /api/proyectos/:id/control | Mudar modo AUTO/MANUAL | ✅ |
| GET | /api/proyectos/:id/equipos | Lista equipamentos | ✅ |
| GET | /api/proyectos/:id/layout | Obter layout | ✅ |
| POST | /api/proyectos/:id/layout | Guardar layout | ✅ |
| POST | /api/proyectos/:id/simulacao/start | Iniciar simulação | ✅ |
| POST | /api/proyectos/:id/simulacao/stop | Parar simulação | ✅ |
| GET | /api/proyectos/:id/simulacao/status | Status simulação | ✅ |
| GET | /api/proyectos/:id/auditoria | Log de auditoria | ✅ |
| GET | /api/proyectos/:id/notificaciones | Preferências notif. | ✅ |
| PUT | /api/proyectos/:id/notificaciones | Guardar prefs. notif. | ✅ |
| GET | /api/proyectos/:id/mirol | Role do utilizador atual | ✅ |
| GET | /api/proyectos/:id/roles | Lista membros | ✅ |
| POST | /api/proyectos/:id/roles | Adicionar/alterar role | ✅ |
| DELETE | /api/proyectos/:id/roles/:uid | Remover membro | ✅ |
| POST | /api/alertas/:aid/ack | Reconhecer alerta | ✅ |
| POST | /api/alertas/:aid/silence | Silenciar alerta | ✅ |
| POST | /api/alertas/:aid/assign | Atribuir alerta | ✅ |
| POST | /api/alertas/:aid/resolve | Resolver alerta | ✅ |
| POST | /api/alertas/:aid/comentarios | Comentar alerta | ✅ |
| GET | /api/usuarios/me | Perfil do utilizador | ✅ |
| PUT | /api/usuarios/me | Atualizar perfil | ✅ |
| PUT | /api/usuarios/me/password | Alterar password | ✅ |
| PUT | /api/usuarios/me/email | Alterar email | ✅ |
| DELETE | /api/usuarios/me | Apagar conta | ✅ |
| GET | /api/proyectos/:id/tuberias | Lista tubagens | ✅ |
| POST | /api/proyectos/:id/tuberias | Criar tubagem | ✅ |
| GET | /api/proyectos/:id/tuberias/:tid | Obter tubagem | ✅ |
| DELETE | /api/proyectos/:id/tuberias/:tid | Apagar tubagem | ✅ |
| POST | /interno/proyectos/:id/lecturas | Leitura do simulador | ✅ |
| GET | /interno/simulacao/projetos-ativos | Projetos ativos (Python) | ✅ |
| GET | /interno/simulacao/projetos/:id/modos | Modos componentes (Python) | ✅ |
| POST | /interno/proyectos/:id/tuberias/:tid/lecturas | Leitura hidráulica | ✅ |
| GET | /interno/proyectos/:id/tuberias | Lista tubagens (Python) | ❌ FALTANDO |
| GET | /health | Health check | ✅ |

### 7.5. Tabelas/Schemas de banco

- **`usuarios`:** id, email (unique), password (BCrypt), nombre, language (default 'en')
- **`proyectos`:** id, nombre, ubicacion, descripcion, usuario_id (FK), layout (TEXT JSON), simulacao_ativa (boolean), creado_en
- **`lecturas_sensor`:** id, proyecto_id (FK), componente, valores (TEXT JSON), timestamp, origen (AUTO/MANUAL). Índice: `(proyecto_id, componente, timestamp)`
- **`alertas`:** id, proyecto_id (FK), componente, nivel (ADVERTENCIA/CRITICA), mensagem, tipo, ativa, accion_automatica, creada_en, reconocida_por, reconocida_en, silenciada_hasta, asignada_a, resuelta_por, resuelta_en
- **`equipamentos`:** id, proyecto_id (FK), componente_id, estado (AUTO/MANUAL/custom), configuracion (TEXT), ultima_actualizacion
- **`usuario_proyecto_rol`:** id, usuario_id (FK), proyecto_id (FK). UNIQUE(usuario_id, proyecto_id)
- **`eventos_auditoria`:** id, usuario (email string), accion, entidade, valor_antes (TEXT), valor_despues (TEXT), timestamp, ip, proyecto_id (nullable)
- **`comentarios_alerta`:** id, texto, autor_email, creado_en, alerta_id (FK)
- **`preferencias_notificacion`:** id, usuario_id (FK), proyecto_id (FK), notificar_critica (default true), notificar_advertencia (default false), email_destino. UNIQUE(usuario_id, proyecto_id)
- **`tuberias`:** id, proyecto_id (FK), from_componente_id, to_componente_id, diametro_mm, material_tuberia, longitud_m, created_at
- **`lecturas_tuberia`:** id, tuberia_id (FK), timestamp, caudal_m3h, presion_bar_entrada, presion_bar_saida, velocidad_ms

---

## 8. MAPA DE PRIORIDADES PARA A FASE 2 (CORREÇÕES)

### Prioridade CRÍTICA (bloqueia uso correto do sistema)

1. **Criar endpoint GET `/interno/proyectos/:id/tuberias`** — sem ele, simulação hidráulica é um no-op; Python chama o endpoint e falha silenciosamente (`python/tuberias.py:obter_tuberias`)
2. **Corrigir `handleDeleteConnection()` no CanvasEditor** — deletar conexão no canvas deve chamar `DELETE /api/proyectos/:id/tuberias/:tid`; atualmente BD fica dessincronizado com o canvas

### Prioridade ALTA (afeta funcionalidades principais)

1. **Persistir blacklist JWT** — usar tabela no BD (ou Redis) para tokens revogados; in-memory é vulnerabilidade de segurança pós-restart (`JwtService.tokenBlacklist`)
2. **Remover `automation.py` ou conectá-lo ao pipeline** — código morto confunde e `client.py` ignora explicitamente as flags; ou remover ou fazer com que as flags cheguem ao backend
3. **Corrigir desalinhamento de gráficos em `Historico.jsx`** — alinhar datasets de componentes diferentes no mesmo eixo temporal
4. **Auto-load na página de Auditoria** — carregar primeira página de dados ao montar o componente sem requer clique manual

### Prioridade MÉDIA (melhorias importantes)

1. **Corrigir `ejecutarAccionAutomatica()`** — definir enum ou constantes para ações automáticas válidas em vez de strings livres
2. **Criar DTO para `getEquipos()`** — substituir entidade JPA direta por DTO adequado
3. **Adicionar testes unitários para lógica de negócio** — pelo menos `AlertaService.evaluarUmbral()` e `ProjetoService`
4. **Verificar e adicionar `.env` ao `.gitignore` do frontend** — confirmar se `aquasense-frontend/.env` está ou deve estar no repo; se contiver dados sensíveis, remover do histórico git
5. **URL do email configurável via env** — substituir hardcoded `https://app.aquasense.io` por variável de ambiente em `EmailService`
6. **Invalidação de cache de roles ao mudar role** — quando admin muda role, o utilizador afetado deve ver a mudança sem logout

### Prioridade BAIXA (polimento)

1. **Tratar erros silenciosos em `Equipa.jsx`** — `handleChangeRol` e `handleRemove` têm `catch {}` vazios; mostrar feedback ao utilizador em caso de erro
2. **Adicionar fallback visual ao Suspense** — substituir `fallback={null}` por spinner ou skeleton em `App.jsx`
3. **Padronizar idioma dos comentários** — uniformizar para PT conforme CLAUDE.md; alguns comentários estão em ES
4. **Fixar N+1 em `ProjetoService.toDTO()`** — otimizar com queries batch para reduzir carga em DB com muitos projetos

---

## 9. PONTOS QUE NECESSITAM ESCLARECIMENTO DO USUÁRIO

1. **`aquasense-frontend/.env`:** O ficheiro `.env` (não `.env.example`) está presente no repositório. Qual é o conteúdo real? Contém apenas `VITE_API_URL=http://localhost:8080` (seguro) ou tem variáveis sensíveis? Se sensível, precisa ser removido do histórico git com `git filter-branch` ou BFG.
2. **Persistência de blacklist JWT:** Qual o ambiente de deploy pretendido? Single-instance (blacklist in-memory é parcialmente aceitável com expiração curta) ou multi-instance/container orquestrado (Redis necessário)?
3. **Umbrais de alerta:** Os valores hardcoded em `AlertaService` (ex: pH < 6.5 → ADVERTENCIA, pH < 5.5 → CRITICA) são corretos para o contexto de negócio real? Devem ser configuráveis por projeto ou são fixos?
4. **`automation.py`:** O código de validação Python era intencional (rascunho de funcionalidade que foi movida para backend)? Pode ser removido sem perda de funcionalidade?
5. **Ações automáticas:** O que deve acontecer concretamente quando uma ação automática é disparada (ex: "cerrarValvulaEntrada")? Deve apenas mudar o modo do equipamento para MANUAL com um estado específico, ou integrar com um sistema externo real?
6. **Simulação hidráulica de tubagens:** A funcionalidade de simulação de caudal/pressão em tubagens é um requisito de negócio ativo ou exploratório? (O endpoint necessário não existe no backend)
7. **Histórico com timestamps dessincronizados:** Os diferentes componentes são sempre lidos no mesmo ciclo de 5s (mesmos timestamps)? Se sim, o bug do gráfico pode ser menos severo do que parece.

---

## 10. NOTAS PARA A IA DA PRÓXIMA FASE

- **Não altere:** `db/init.sql` (schema base), `application-prod.properties` (configuração prod validada), `LanguageContext.jsx` + `translations.js` (i18n completo em 5 idiomas, recém implementado), `AuthContext.jsx` (lógica de sincronização de idioma implementada na última sessão)
- **Cuidado com:**
  - `AlertaService.evaluarUmbral()` — lógica de switch com 8 casos e umbrais delicados; qualquer mudança pode criar alertas espúrios
  - `ProjetoService.findOwnedProject()` — verificação de acesso central; alterar pode criar brechas de segurança
  - `CanvasEditor.jsx` — componente complexo com estado de pan/zoom/conexão; mudanças localizadas apenas
  - `JwtFilter.java` e `InternalTokenFilter.java` — filtros de segurança; testar exaustivamente após qualquer alteração
  - `DataInitializer.java` — executa no startup; mudanças afetam dados de demo em desenvolvimento
- **Convenções do projeto a respeitar:**
  - Comentários em PT (alguns existentes em ES por inconsistência histórica)
  - Nomes de variáveis/funções em inglês; nomes de campos BD em snake_case
  - `@Async` para operações de auditoria e email
  - Validação de acesso sempre via `findOwnedProject()` ou `getRolForUser()` — nunca bypass
  - Frontend: polling via `usePolling` hook — não criar polling ad-hoc
  - i18n: toda string visível ao utilizador deve usar `t('chave')` — não hardcodar strings em PT/ES
- **Contexto adicional crítico:**
  - O simulador Python é externo ao Spring Boot — não tem `@Scheduled`, comunica via HTTP
  - A simulação é ativada via flag `simulacaoAtiva` no BD; Python faz polling desta lista
  - O layout do sinóptico é um JSON opaco guardado em TEXT no BD — o backend não parseia, apenas guarda/devolve
  - Roles são por projeto, não globais — um ADMIN num projeto pode ser VISUALIZADOR noutro
  - O endpoint `/api/proyectos/:id/roles` usa POST tanto para adicionar como para atualizar role (upsert)

---

## 11. RESUMO EXECUTIVO (TL;DR)

AquaSense é um sistema SCADA web bem estruturado para monitoramento de plantas de tratamento de água, com arquitetura clara (React + Spring Boot + Python), autenticação robusta por JWT, sistema de alertas com ciclo de vida completo, editor de sinóptico drag-and-drop e i18n em 5 idiomas. O núcleo funcional está operacional: autenticação, CRUD de projetos, polling de sensores, alertas, papéis por projeto e exportação de dados funcionam corretamente. Os problemas mais urgentes são: (1) a simulação hidráulica de tubagens está completamente inativa porque o endpoint backend que o Python precisa não existe; (2) deletar conexões no editor de sinóptico não persiste no BD; (3) a blacklist JWT in-memory representa uma vulnerabilidade de segurança pós-restart. Há também código morto no simulador Python (`automation.py`), ausência quase total de testes automatizados, e um bug UX na página de auditoria (sem auto-load). Com 3 bugs de prioridade crítica/alta a corrigir e nenhum bloqueador funcional no fluxo principal de monitoramento, o sistema está em estado "produção possível mas com funcionalidades incompletas". A Fase 2 deve atacar pela ordem: endpoint de tubagens → sincronização de delete no canvas → persistência de blacklist JWT → testes unitários → polimento de UX.
