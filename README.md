# AquaSense

**Web SCADA platform for monitoring and controlling drinking-water treatment plants (ETAP).**  
Operators follow every plant component in real time, receive automatic alerts and act on them from the browser, while a Python simulator emulates the field sensors.

Team project — final project of the Higher Technician degree in Multiplatform Application Development (DAM, UpgradeHub). My focus: the **Spring Boot backend** (REST endpoints, JWT, alert lifecycle, data model), plus the Python simulator and support on the React frontend.

![Java](https://img.shields.io/badge/Java-21-orange) ![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F) ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-prod-336791) ![React](https://img.shields.io/badge/React-19-61DAFB)

## Features

- **JWT authentication** with Spring Security — HttpOnly session cookie plus Bearer header, token blacklisting on logout
- **Multi-project, multi-user** — each user owns projects and can share them with roles per project
- **Sensor ingestion** — readings for every plant component (pH, chlorine, turbidity, pressure, temperature…) every 5 seconds
- **Alert engine** — threshold evaluation per component, WARNING / CRITICAL levels, deduplication and automatic actions
- **Alert lifecycle** — create, acknowledge, silence, assign and resolve, with a complete **audit log** of operator actions
- **Email notifications** (SMTP, async) for critical alerts
- **CSV export** of historical readings
- **Interactive synoptic** — SVG plant layout editor in React, layout stored per project
- **Python simulator** that posts realistic hydraulic data, so the whole system runs end to end without PLC hardware

## Architecture

```
[React SPA] ──HTTPS (JWT)──▶ [Spring Boot API :8080] ──▶ [PostgreSQL / H2]
                                    ▲
[Python simulator] ──internal token─┘  POST /interno/proyectos/{id}/lecturas
```

- `/auth/**` — login / logout
- `/api/**` — projects, state, alerts, history, layout, equipment control, users
- `/interno/**` — internal endpoints for the simulator, protected by an internal token filter

## Tech stack

| Layer | Technology |
| --- | --- |
| Backend | Java 21 · Spring Boot 3.3 · Spring Security 6 · jjwt · Spring Data JPA / Hibernate · Spring Cache · Spring Mail · Maven |
| Database | H2 (dev) · PostgreSQL (prod) |
| Frontend | React 19 · React Router 7 · Vite · Axios · Chart.js |
| Simulator | Python 3 · requests |
| Deploy | Docker · docker-compose · Nginx · Railway (API) · Vercel (frontend) |

## Project structure

```
aquasense-backend/    Spring Boot API (config, controller, service, repository, model, dto, filter)
aquasense-frontend/   React SPA (pages, components, context, hooks, services)
python/               Sensor simulator
db/                   Initial SQL schema
docs/                 Equipment connections and technical analysis
```

## Run locally

```bash
# Backend (H2 in-memory, demo user created on startup)
cd aquasense-backend
./mvnw spring-boot:run

# Frontend
cd aquasense-frontend
npm install && npm run dev

# Simulator
cd python
pip install requests
python main.py
```

Or everything with Docker: `docker compose up` (backend + PostgreSQL + simulator).

Copy `.env.example` to `.env` and fill in the values before running.

## Team

Built by a team of DAM students at UpgradeHub. REST endpoints, JWT authentication, alert lifecycle, sensor data model, simulator and parts of the frontend by [Gabriel Budoia](https://www.linkedin.com/in/gabrielbud).

A full technical analysis of the codebase is in [`docs/ANALISE_TECNICA.md`](docs/ANALISE_TECNICA.md).
