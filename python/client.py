import os
import random
import time
import requests
from config import URL_BACKEND

# Respeta variable de entorno BACKEND_URL (Docker) o usa el default de config
_BACKEND_URL = os.getenv("BACKEND_URL", URL_BACKEND)

_MAX_RETRIES = 3
_RETRY_DELAYS = [1, 2, 4]  # segundos de espera con backoff exponencial

# Mapeo de claves Python (camelCase) -> IDs de componente del backend (snake_case)
_COMPONENTE_MAP = {
    "bombaCaptacao":     "bomba_captacao",
    "rejaTamiz":         "reja_tamiz",
    "coagulacion":       "coagulacion",
    "decantador":        "decantador",
    "filtracion":        "filtracion",
    "desinfeccion":      "desinfeccion",
    "reservorio":        "reservorio",
    "bombaDistribucion": "bomba_distribucion",
}

# IDs canónicos en snake_case — los 8 componentes con modelo completo en simulator.py
_CANONICAL_IDS = set(_COMPONENTE_MAP.values())

# Rangos de campos para componentes no canónicos — (min, max)
# Para tipos sin entrada explícita se usa el fallback "default"
_GENERIC_FIELDS: dict = {
    "default":            {"caudal": (0.5, 5.0), "presion": (0.5, 3.0)},
    "bomba_dosificadora": {"caudal": (0.5, 5.0), "presion": (0.5, 2.0), "corrienteMotor": (20.0, 60.0)},
    "valvula_motorizada": {"apertura": (0.0, 100.0), "presionDiferencial": (0.1, 2.0)},
    "filtro_cartucho":    {"perdidaCarga": (0.1, 1.5), "turbidezSalida": (0.05, 1.0)},
    "membrana_uf":        {"permeatoCaudal": (2.0, 8.0), "presionTrans": (0.5, 2.5), "turbidezSalida": (0.02, 0.3)},
    "reactor_biologico":  {"oxigenoDisuelto": (5.0, 9.0), "temperaturaBio": (15.0, 30.0)},
    "caudalimetro":       {"caudal": (1.0, 15.0)},
    "sensor_ph":          {"ph": (6.0, 8.5)},
    "sensor_cloro":       {"cloro": (0.1, 1.5)},
    "sensor_turbidez":    {"turbidez": (0.05, 5.0)},
    "sensor_nivel":       {"nivel": (10.0, 95.0)},
    "sensor_presion":     {"presion": (0.5, 4.0)},
}


def _generate_generic_reading(componente_id: str) -> dict:
    """Genera valores aleatorios dentro de rangos operacionales para componentes no canónicos."""
    fields = _GENERIC_FIELDS.get(componente_id, _GENERIC_FIELDS["default"])
    return {key: round(random.uniform(lo, hi), 3) for key, (lo, hi) in fields.items()}

_cached_token = None


def get_internal_token():
    """Lee X_INTERNAL_TOKEN de forma lazy y hace caché tras la primera lectura válida.
    Lanza RuntimeError solo cuando se llama, nunca en el import."""
    global _cached_token
    if _cached_token:
        return _cached_token
    token = os.environ.get("X_INTERNAL_TOKEN")
    if not token:
        raise RuntimeError(
            "Variável de ambiente 'X_INTERNAL_TOKEN' não encontrada. "
            "Defina a variável antes de iniciar o simulador."
        )
    _cached_token = token
    return token


def _send_one(url: str, token: str, componente_id: str, valores: dict, project_id):
    """Envía una única lectura al backend con reintentos."""
    payload = {"componente": componente_id, "valores": valores, "origen": "AUTO"}
    for attempt in range(_MAX_RETRIES):
        try:
            r = requests.post(url, json=payload, timeout=4,
                              headers={"X-Internal-Token": token})
            if r.status_code == 200:
                return
            print(f"[client] {componente_id} (proj {project_id}) -> HTTP {r.status_code}: {r.text[:120]}")
            return
        except requests.exceptions.ConnectionError:
            if attempt < _MAX_RETRIES - 1:
                time.sleep(_RETRY_DELAYS[attempt])
            else:
                print(f"[client] backend indisponível após {_MAX_RETRIES} tentativas — {componente_id} não enviado")
        except Exception as e:
            print(f"[client] erro ao enviar {componente_id}: {e}")
            return


def enviar_para_projeto(estado_validado, project_id, modos_manual=None, layout_ids=None):
    """
    Envía lecturas al backend para el proyecto indicado.

    layout_ids: lista de componenteIds del layout activo (snake_case). Si se
    proporciona, solo se envían lecturas para los componentes del layout:
      - Canónicos (los 8 del simulador): se usan los valores del estado.
      - No canónicos: se generan valores genéricos dentro de rangos normales.
    Si layout_ids es None se envían todos los canónicos (compatibilidad).

    modos_manual: componentes en modo MANUAL — el simulador no sobreescribe.
    """
    if modos_manual is None:
        modos_manual = set()

    try:
        token = get_internal_token()
    except RuntimeError as e:
        print(f"[client] WARN: {e} — ciclo de envio ignorado para proj {project_id}")
        return

    url = f"{_BACKEND_URL}/interno/proyectos/{project_id}/lecturas"

    # ── 1. Componentes canónicos ──────────────────────────────────────────────
    for key_python, valores in estado_validado.items():
        if key_python == "flags":
            continue
        componente_id = _COMPONENTE_MAP.get(key_python)
        if componente_id is None:
            continue
        # Si hay layout, solo enviar los que están en él
        if layout_ids is not None and componente_id not in layout_ids:
            continue
        if componente_id in modos_manual:
            continue
        _send_one(url, token, componente_id, valores, project_id)

    # ── 2. Componentes no canónicos del layout ────────────────────────────────
    if layout_ids is not None:
        for componente_id in layout_ids:
            if componente_id in _CANONICAL_IDS:
                continue  # ya enviado arriba
            if componente_id in modos_manual:
                continue
            valores = _generate_generic_reading(componente_id)
            _send_one(url, token, componente_id, valores, project_id)


# Alias heredado para compatibilidad con código anterior
def enviar(estado_validado):
    from config import PROYECTO_ID
    enviar_para_projeto(estado_validado, PROYECTO_ID)
