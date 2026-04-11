# Enriquece la foto
# Recibe los numeros, aplica reglas, añade flags, devuelve JSON final listo

from config import THRESHOLDS

def validacion(estado_actualizado) -> dict[str, dict[str, float]]:
    """  
    Recibe el resultado de simulator.py, recorre cada campo, si algun valor supera su threshold
    añade un flag al JSON
    Devuelve el estado enriquecido con esos flags listo para enviar al backend
    """
    estado_enriquecido = estado_actualizado.copy()
    estado_enriquecido["flags"] = {}

    # Cada condicion representa un threshold definido en el doc
    # Si un valor supera su limite se añade un flag correspondiente

    for key in THRESHOLDS: # bombaCaptacion
        for key2 in THRESHOLDS[key]: # caudal
            if "max" in THRESHOLDS[key][key2]:
                if estado_actualizado[key][key2] > THRESHOLDS[key][key2]["max"]:
                    estado_enriquecido["flags"][THRESHOLDS[key][key2]["flagMax"]] = True
            if "min" in THRESHOLDS[key][key2]:
                if estado_actualizado[key][key2] < THRESHOLDS[key][key2]["min"]:
                    estado_enriquecido["flags"][THRESHOLDS[key][key2]["flagMin"]] = True

    return estado_enriquecido
