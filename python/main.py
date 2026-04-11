# Director de orquesta, loop cada 5 seg:
# 1. Pide datos al simulador
# 2. Pide flags al automation
# 3. Pasa el JSON listo al client para que lo mande

from config import INTERVALO_CICLO
from simulator import inicializar_estado, actualizar_estado
from automation import validacion
from client import enviar

import time


def ciclo():
    # Iniciamos el estado_inicial, solo una vez
    estado_inicial = inicializar_estado()

    # Bucle infinito cada 5 seg
    while(True):
        # Para actualizar el estado anterior:
        estado_inicial = actualizar_estado(estado_inicial)
        estado_validado = validacion(estado_inicial)

        enviar(estado_validado)

        time.sleep(INTERVALO_CICLO)

ciclo()