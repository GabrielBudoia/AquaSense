# Genera la foto
# Calcula el estado actual de las 8 etapas con sus valores de sensores, devuelve los numeros

# Genera numeros creibles para cada uno de los 8 componentes
# - Parte de un estado anterior y le aplica un pequeño cambio
# - Necesita memoria, tiene que recordar como estaba la planta en el ciclo anterior
# - No recalcula de 0 se actualiza

# Devuelve una foto del estado actual de los 8 componenetes

import random
from config import ESTADO_INICIAL, RUIDO_GLOBAL



def inicializar_estado() -> dict[str, dict[str, float]]:
    """ 
    Recorre los valores del dict de config.py y los inicializa usando el minimo y el maximo estipulado.
    """
    # Dict donde se guardara el estado inicial
    estado_inicial = {}

    for key in ESTADO_INICIAL: # bombaCaptacion
        estado_inicial[key] = {}
        for key2 in ESTADO_INICIAL[key]: # caudal
            # Extremos los valores de min y max
            val_min = ESTADO_INICIAL[key][key2]["min"]
            val_max = ESTADO_INICIAL[key][key2]["max"]
            # Definimos aleatoriamente el valor de cada campo
            estado_inicial[key][key2] = random.uniform(val_min, val_max)

    return estado_inicial

def actualizar_estado(estado) -> dict[str, dict[str, float]]:
    """  
    Recibe un estado, actualiza sus campos añadiendo ruido, este añadido viene de la configuracion
    y se añade aleatoriamente dentro del rango estipulado.
    """

    estado_actualizado = {}

    # Recorremos la lista y llegamos al dato de interes para manipularlo
    for key in estado: # "bombaCaptacion"
        estado_actualizado[key] = {}
        for key2 in estado[key]: # "caudal"
            campos_actualizados = estado[key][key2] + random.uniform(-RUIDO_GLOBAL, RUIDO_GLOBAL)
            estado_actualizado[key][key2] = campos_actualizados

    return estado_actualizado