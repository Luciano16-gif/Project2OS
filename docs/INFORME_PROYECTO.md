# Proyecto 2 - Simulador de Sistema Operativo

## 1. Resumen ejecutivo

Project2OS es un simulador academico de conceptos clave de Sistemas Operativos, enfocado en:

- sistema de archivos jerarquico,
- planificacion de solicitudes de disco,
- ciclo de vida de procesos de E/S,
- control de concurrencia con locks,
- journaling y recuperacion ante fallos,
- visualizacion integral mediante GUI en tiempo real.

El proyecto fue disenado para separar claramente la logica de dominio del frontend, de modo que la GUI refleje el estado del sistema sin convertirse en fuente de verdad.

## 2. Objetivo y alcance

El objetivo general es modelar y visualizar, de manera trazable, la interaccion entre archivos, disco, procesos y concurrencia.

Alcance implementado:

- CRUD de archivos y directorios via intents de aplicacion.
- Sesiones de usuario y validacion de permisos.
- Politicas de scheduling de disco: FIFO, SSTF, SCAN y C_SCAN.
- Estados de proceso: NEW, READY, RUNNING, BLOCKED, TERMINATED.
- Journal transaccional con soporte de recovery.
- Simulacion de fallos y cuarentena de recovery.
- Persistencia de estado y carga de escenarios JSON.
- GUI con paneles de sistema de archivos, bloques, procesos, locks, journal y log de eventos.

## 3. Arquitectura del sistema

### 3.1 Vision general

La arquitectura sigue un enfoque por capas:

1. Capa de dominio y simulacion: reglas de negocio, estado real y mecanicas del simulador.
2. Capa de coordinacion: orquestacion de comandos, colas internas, scheduler y publicacion de snapshots.
3. Capa de presentacion (GUI): renderizado del snapshot y captura de acciones del usuario.

Componente central: `SimulationCoordinator`.

Responsabilidades principales del coordinador:

- recibir intents/comandos de aplicacion,
- materializar solicitudes de E/S y procesos,
- ejecutar despacho de disco segun politica,
- gestionar locks y transiciones de estado,
- actualizar metricas (por ejemplo seek acumulado),
- registrar eventos de sistema,
- publicar snapshots inmutables para la GUI.

### 3.2 Snapshot como contrato GUI-backend

La GUI consume `SimulationSnapshot` como contrato de lectura unico. Este snapshot expone:

- politica activa y estado del cabezal,
- procesos en ejecucion y colas,
- resumen de locks,
- entradas de journal,
- eventos del sistema,
- nodos del sistema de archivos,
- bloques de disco.

Este diseno evita acoplamiento fuerte entre Swing y la logica de dominio.

## 4. Estructura modular del codigo

Paquetes principales en `src/main/java/ve/edu/unimet/so/project2`:

- `application`: intents y planificacion de operaciones de alto nivel.
- `coordinator`: nucleo de orquestacion, canales internos, snapshots y estado.
- `disk`: modelo de disco, bloques y cabezal.
- `filesystem`: nodos, directorios y archivos.
- `process`: solicitudes de E/S, PCB, estados y resultados.
- `scheduler`: politicas y algoritmo de seleccion de despacho.
- `locking`: tabla de locks, esperas y grants.
- `journal`: registro transaccional y estado de recovery.
- `persistence`: guardado/carga de estado y recuperacion.
- `scenario`: carga de escenarios externos JSON.
- `project2os/gui`: interfaz grafica, controlador y paneles visuales.
- `session`: modelo de sesion y roles.
- `datastructures`: estructuras propias usadas por el simulador.

## 5. Modelo funcional del simulador

### 5.1 Operaciones soportadas

Operaciones de E/S modeladas:

- CREATE
- READ
- UPDATE
- DELETE

Estas operaciones se inyectan al sistema por medio de intents y se transforman en procesos con ciclo de vida completo.

### 5.2 Flujo tipico de ejecucion

1. Usuario ejecuta una accion en GUI (ej. CREATE o READ).
2. La GUI envia un intent al coordinador.
3. El coordinador valida, materializa y encola el trabajo.
4. El scheduler selecciona siguiente proceso segun politica activa.
5. Se ejecuta tarea de disco y se actualizan estados.
6. Se liberan locks, se registra evento, se actualiza journal si aplica.
7. Se publica nuevo snapshot para refresco visual.

### 5.3 Concurrencia y control de acceso

- Locks compartidos/exclusivos por archivo.
- Colas de espera para conflictos de acceso.
- Liberacion y otorgamiento de locks con trazabilidad.
- Validaciones de permisos por usuario/rol para operaciones sensibles.

## 6. GUI y experiencia de usuario

La GUI se construyo en Swing y se organiza en un `MainFrame` con paneles especializados.

Elementos visuales relevantes:

- arbol del sistema de archivos,
- detalle de nodo seleccionado,
- visualizacion de bloques de disco,
- colas de procesos por estado,
- panel de locks,
- panel de journal,
- log de eventos del sistema,
- controles de sesion, politica, persistencia y simulacion de fallo.

La logica de interaccion vive en `GuiController`, manteniendo `MainFrame` como capa de componentes.

## 7. Tolerancia a fallos y recovery

El proyecto integra flujo controlado de fallo simulado:

- armado de fallo antes de operaciones journaled,
- cancelacion del proceso afectado,
- persistencia de estado pendiente en journal,
- activacion de cuarentena de recovery,
- recuperacion manual via `recoverPendingJournalEntries()`.

Esto permite demostrar, en entorno academico, el valor de journaling para consistencia ante interrupciones.

## 8. Persistencia y escenarios

Capacidades implementadas:

- guardar estado completo del sistema,
- cargar estado previamente serializado,
- cargar escenarios JSON para pruebas guiadas.

Dependencia tecnica utilizada: Jackson (`jackson-databind`) para serializacion/deserializacion.

## 9. Stack tecnologico

- Lenguaje: Java 22.
- Build: Maven.
- Testing: JUnit Jupiter 5.
- GUI: Swing.
- Serializacion: Jackson.

Clase de entrada de la aplicacion: `ve.edu.unimet.so.project2.project2os.Project2OS`.

## 10. Estado de pruebas y calidad

Con base en la ultima ejecucion local de pruebas automatizadas:

- 77 pruebas aprobadas.
- 2 fallas en `SimulationCoordinatorTest`.

Observacion tecnica:

- una de las fallas reportadas esta asociada al orden esperado de dispatch en un escenario de intents manuales y operaciones posteriores.

Interpretacion:

- el sistema se mantiene funcional y estable para la mayoria de escenarios,
- existe una regresion puntual en criterios de ordenamiento/precedencia que debe cerrarse para dejar la suite completamente en verde.

## 11. Cambios recientes de mayor impacto

### 11.1 Procesamiento de intents manuales (READ) con carga concurrente

Se ajusto la logica de materializacion para evitar que operaciones manuales como READ queden bloqueadas por procesos activos, permitiendo su entrada al flujo de ejecucion de manera consistente con el modelo operativo esperado.

Impacto:

- mejor respuesta de la interfaz,
- menor sensacion de bloqueo para el usuario,
- comportamiento mas coherente del pipeline de intents.

### 11.2 Notificacion de errores asincronos en GUI

Se agrego deteccion y popup de fallos asincronos en operaciones que pueden fallar despues de haber sido aceptadas inicialmente.

Impacto:

- visibilidad inmediata de errores reales de ejecucion,
- mensajes amigables para usuario final,
- reduccion de fallos silenciosos en la experiencia de uso.

## 12. Conclusion

Project2OS presenta una implementacion completa y didactica de mecanismos esenciales de Sistemas Operativos, con una arquitectura limpia basada en separacion dominio-GUI y observabilidad por snapshots. El proyecto combina valor pedagogico con una base tecnica solida, integrando planificacion de disco, concurrencia, recovery, persistencia y visualizacion en tiempo real.

Como cierre de calidad, el siguiente paso recomendado es resolver la regresion puntual de orden en pruebas del coordinador para consolidar consistencia total del comportamiento esperado.
