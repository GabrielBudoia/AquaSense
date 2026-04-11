# Solo sabe hacer el POST, no sabe nada mas, recibe un JSON ya preparado, lo manda, fin
import requests
from config import URL_BACKEND

def enviar(payload):
    try:
        requests.post(URL_BACKEND + "/api/leituras", json=payload, timeout=2)
    except:
        print(payload)