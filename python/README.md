 # Módulo de Simulación — AquaSense                                                                                
                                                                                                                    
  Genera datos de sensores del ciclo de tratamiento de agua y los envía al backend cada 5 segundos.                 
                  
  ## Archivos                                                                                                       
                  
  | Archivo | Función |
  |---|---|
  | `config.py` | Rangos iniciales, umbrales y configuración general |
  | `simulator.py` | Genera y actualiza el estado de los 8 componentes |
  | `automation.py` | Detecta valores fuera de rango y añade flags |                                                
  | `client.py` | Envía el payload al backend via POST |
  | `main.py` | Bucle principal — orquesta el ciclo completo |                                                      
                  
  ## Ejecución                                                                                                      
   
  ```bash                                                                                                           
  python -m venv .venv
  source .venv/bin/activate  # Windows: .venv\Scripts\activate
  pip install -r requirements.txt                                                                                   
  python main.py
                                                                                                                    
  Requisitos      

  - Backend corriendo en localhost:8080
  - Si no hay backend disponible, los datos se imprimen por consola