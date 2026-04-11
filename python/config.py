# La memoria de la planta
# Tiene los thresholds, URL del backend, el intervalo de 5 seg.

# Es como el .env del proyecto 

from typing import Any

# ======================================
# Para main.py:
# ======================================
# Intervalo del ciclo (5 seg)
# URL del backend

INTERVALO_CICLO = 5
URL_BACKEND = "http://localhost:8080"

# ======================================
# Para simulator.py:
# ======================================
# Valores base de cada parametro (punto de partida de simulacion)
# Rangos normales de cada sensor (verde, ambar, rojo)
# - "Los nombres de los campos usan camelCase"
# - He puesto min y max para que dentro de un estado estable pueda variar su estado inicial
ESTADO_INICIAL: dict[str,dict[str, Any]] = {
    "bombaCaptacion": {
        "caudal": {"min": 5, "max": 20},            # m^3/h
        "presionSuccion": {"min": 0.5, "max": 2.0}, # bar
        "temperaturaMotor": {"min": 20, "max": 55}  # ·C 
    },
    "rejaTamiz": {
        "diferencialPresion": {"min": 0, "max": 50}, # mbar
        "turbidezEntrada": {"min": 30, "max": 60}    # NTU
    },
    "coagulacion": {
        "phPostCoagulacion": {"min": 6.0, "max": 7.5},
        "turbidezSalida": {"min": 1, "max": 8}, # NTU
        "nivelTanquePAC": {"min": 20, "max": 100}, # %
        "caudalDosificacion": {"min": 0.5, "max": 5.0} # L/h
    },
    "decantador": {
        "turbidezSalida": {"min": 1, "max": 4}, # NTU
        "nivelLodo": {"min": 10, "max": 60}, # % 
        "caudalSalida": {"min": 4, "max": 18} # m^3/h
    },
    "filtracion": {
        "turbidezSalida": {"min": 0.1, "max": 0.9}, # NTU
        "perdidaCarga": {"min": 0.5, "max": 1.0}, # mca
        "horasDesdeLavado": {"min": 0, "max": 48} # horas 
    },
    "desinfeccion": {
        "cloroResidual": {"min": 0.2, "max": 1.0}, # mg/L
        "ph": {"min": 6.5, "max": 7.0}, 
        "nivelTanqueCloro": {"min": 20, "max": 90}, # %
        "orp": {"min": 650, "max": 700} # mV
    },
    "reservorio": {
        "nivel": {"min": 30, "max": 80}, # %
        "cloroResidual": {"min": 0.2, "max": 0.7}, # mg/L
        "temperatura": {"min": 5, "max": 20}, # ·C
        "turbidez": {"min": 0.2, "max": 0.9} # NTU
    },
    "bombaDistribucion": {
        "presionSalida": {"min": 2.0, "max": 3.5}, # bar
        "caudal": {"min": 3, "max": 16}, # m^3/h
        "corrienteMotor": {"min": 40, "max": 70} # %
    }
}

# Intensidad del ruido aleatorio 
RUIDO_GLOBAL = 0.05


# ======================================
# Para automation.py:
# ======================================
# Thresholds que disparan los flags — valores exactos del doc
# Cada campo puede tener min, max, flagMin, flagMax segun corresponda
THRESHOLDS: dict[str, dict[str, Any]] = {
    "bombaCaptacion": {
        "caudal": {"min": 5, "max": 20, "flagMin": "caudalBajo", "flagMax": "caudalAlto"},
        "presionSuccion": {"min": 0.5, "max": 2.0, "flagMin": "presionSuccionBaja", "flagMax": "presionSuccionAlta"},
        "temperaturaMotor": {"max": 60, "flagMax": "temperaturaMotorAlta"},
    },
    "rejaTamiz": {
        "diferencialPresion": {"max": 50, "flagMax": "diferencialPresionAlto"},
        "turbidezEntrada": {"max": 100, "flagMax": "turbidezEntradaAlta"},
    },
    "coagulacion": {
        "phPostCoagulacion": {"min": 6.0, "max": 7.5, "flagMin": "phCoagulacionBajo", "flagMax": "phCoagulacionAlto"},
        "turbidezSalida": {"max": 10, "flagMax": "turbidezSalidaCoagulacionAlta"},
        "nivelTanquePAC": {"min": 20, "flagMin": "nivelPACBajo"},
        "caudalDosificacion": {"min": 0.5, "max": 5.0, "flagMin": "caudalDosificacionBajo", "flagMax": "caudalDosificacionAlto"},
    },
    "decantador": {
        "turbidezSalida": {"max": 5, "flagMax": "turbidezSalidaDecantadorAlta"},
        "nivelLodo": {"max": 70, "flagMax": "nivelLodoAlto"},
        "caudalSalida": {"min": 4, "max": 18, "flagMin": "caudalSalidaBajo", "flagMax": "caudalSalidaAlto"},
    },
    "filtracion": {
        "turbidezSalida": {"max": 1, "flagMax": "turbidezSalidaFiltracionAlta"},
        "perdidaCarga": {"max": 2.0, "flagMax": "perdidaCargaAlta"},
        "horasDesdeLavado": {"max": 48, "flagMax": "horasDesdeLavadoAltas"},
    },
    "desinfeccion": {
        "cloroResidual": {"min": 0.2, "max": 2.0, "flagMin": "cloroResidualBajo", "flagMax": "cloroResidualAlto"},
        "ph": {"min": 6.5, "max": 7.5, "flagMin": "phDesinfeccionBajo", "flagMax": "phDesinfeccionAlto"},
        "nivelTanqueCloro": {"min": 20, "flagMin": "nivelCloroBajo"},
        "orp": {"min": 650, "max": 750, "flagMin": "orpBajo", "flagMax": "orpAlto"},
    },
    "reservorio": {
        "nivel": {"min": 30, "max": 90, "flagMin": "nivelReservorioBajo", "flagMax": "nivelReservorioAlto"},
        "cloroResidual": {"min": 0.2, "flagMin": "cloroReservorioBajo"},
        "temperatura": {"max": 25, "flagMax": "temperaturaReservorioAlta"},
        "turbidez": {"max": 1, "flagMax": "turbidezReservorioAlta"},
    },
    "bombaDistribucion": {
        "presionSalida": {"min": 2.0, "max": 4.0, "flagMin": "presionSalidaBaja", "flagMax": "presionSalidaAlta"},
        "caudal": {"min": 3, "max": 18, "flagMin": "caudalDistBajo", "flagMax": "caudalDistAlto"},
        "corrienteMotor": {"max": 90, "flagMax": "corrienteMotorAlta"},
    }
}