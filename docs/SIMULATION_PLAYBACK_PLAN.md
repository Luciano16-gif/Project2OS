# Plan: Observabilidad de Procesos y Políticas de Disco

Actualmente, el simulador procesa las solicitudes en milisegundos, lo que impide ver la transición de los procesos por las distintas colas (Listo, CPU, Bloqueado, Terminado) y cómo el planificador de disco reordena las peticiones según la política seleccionada.

## Objetivo
Hacer que la ejecución del sistema sea "viva" y observable para el usuario, permitiendo ralentizar el tiempo y cargar ráfagas de trabajo masivas.

## Cambios Propuestos

### 1. Control de Velocidad en el Núcleo
- Modificar el hilo principal del coordinador para que acepte un parámetro de "delay" ajustable.
- Implementar un método para cambiar esta velocidad en tiempo real sin reiniciar el simulador.

### 2. Conexión de la Interfaz (GUI)
- Vincular el selector de velocidad de la barra superior con el delay del coordinador.
- Permitir rangos desde "Instantáneo" (estado actual) hasta "Cámara Lenta" (1 tick por segundo).

### 3. Carga de Escenarios Masivos
- Implementar la funcionalidad del botón "Escenario JSON".
- Esto permitirá cargar archivos de prueba que inyecten docenas de operaciones de E/S en paralelo, forzando al disco a mostrar el comportamiento de los algoritmos (SSTF, SCAN, etc.).

## Cómo probarlo
1. Abrir la GUI.
2. Seleccionar una política de disco (ej. SCAN).
3. Ajustar la velocidad a "500 ms" o "1000 ms".
4. Cargar un escenario JSON.
5. Observar cómo los procesos se acumulan en la cola y cómo el cabezal del disco se mueve lógicamente según la política elegida.
