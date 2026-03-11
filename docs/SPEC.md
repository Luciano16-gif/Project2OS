# Project2OS - Especificacion Tecnica

Documento vivo para definir la arquitectura y las decisiones de modelado del proyecto 2 antes de implementarlas.

## 1. Documento

Este documento existe para:

- fijar decisiones de diseno antes de escribir codigo,
- reducir ambiguedades del enunciado,
- prevenir bugs estructurales desde el modelo,
- servir como base para dividir tareas y revisar implementaciones.

El enfoque inicial es definir un nucleo de dominio pequeno, estricto y facil de extender.

### 1.1 Objetivo

La version actual de este documento cubre el proyecto completo que se desea entregar:

- modelo de dominio,
- entidades principales,
- relaciones entre entidades,
- invariantes del sistema,
- scheduler de disco,
- procesos,
- locks,
- journaling,
- GUI,
- persistencia,
- concurrencia controlada,
- carga de escenarios externos.

Nota de alcance:

- este documento comenzo como una especificacion parcial,
- las secciones posteriores ya consolidan decisiones para el proyecto completo,
- si una seccion posterior agrega detalle o cierra una decision, prevalece sobre este resumen inicial.

### 1.2 Alcance de esta version

- La GUI no es duena del estado del sistema.
- El estado real vive en el dominio.
- Las vistas de GUI se derivan del dominio.
- Las estructuras deben ser simples y trazables.
- No se modelara mas detalle del necesario si no aporta claridad o seguridad.
- Las restricciones del enunciado tienen prioridad sobre soluciones mas comodas.

Regla de lectura del documento:

- el alcance real queda determinado por todas las secciones cerradas de este documento,
- las referencias a "version inicial" deben entenderse como contexto historico y no como exclusion automatica de secciones posteriores ya aprobadas.

### 1.3 Principios de modelado

- Separar estado real del dominio y vistas derivadas de la GUI.
- Preferir invariantes explicitos a validaciones dispersas y tardias.
- Centralizar decisiones criticas de concurrencia y transicion de estado.
- Reutilizar solo estructuras genericas que no arrastren acoplamiento del proyecto 1.
- Mantener el alcance alineado con el enunciado y evitar complejidad no defendible.

## 2. Modelo de dominio

### 2.1 Grupos del dominio

El dominio se divide en seis grupos:

1. Estructura del sistema de archivos.
2. Disco simulado y bloques.
3. Solicitudes y procesos de E/S.
4. Concurrencia y locks.
5. Journal y recuperacion.
6. Usuarios y sesion.

### 2.2 Estructura del sistema de archivos

#### 2.2.1 FsNode

Entidad base para cualquier nodo del sistema de archivos.

Campos minimos propuestos:

- `id`
- `name`
- `ownerUserId`
- `parent`
- `permissions`
- `type`

Notas:

- `type` distingue entre archivo y directorio.
- `parent` permite reconstruir rutas y soportar borrado recursivo.
- La raiz es un caso especial: existe pero no tiene padre.

#### 2.2.2 DirectoryNode

Representa un directorio.

Hereda de `FsNode` y agrega:

- `children`

Decisiones:

- `children` sera una estructura propia de nodos hijos.
- Un directorio puede contener archivos y subdirectorios.
- El orden de hijos no es semantico, salvo que luego se defina lo contrario para GUI.

#### 2.2.3 FileNode

Representa un archivo regular.

Hereda de `FsNode` y agrega:

- `sizeInBlocks`
- `firstBlockIndex`
- `colorId`
- `isSystemFile` opcional

Decisiones:

- `sizeInBlocks` es el tamano logico del archivo en bloques.
- `firstBlockIndex` referencia el primer bloque de la cadena encadenada.
- `colorId` existe para la visualizacion consistente del archivo en disco y tabla.

### 2.3 Disco simulado y bloques

#### 2.3.1 SimulatedDisk

Representa el disco completo usado por el simulador.

Campos minimos propuestos:

- `totalBlocks`
- `blocks[]`
- `head`

Decisiones:

- El disco se modela como un arreglo fijo de bloques.
- Cada bloque tiene indice propio entre `0` y `totalBlocks - 1`.
- El disco es la fuente de verdad sobre ocupacion real de bloques.

#### 2.3.2 DiskBlock

Representa un bloque del disco simulado.

Campos minimos propuestos:

- `index`
- `free`
- `ownerFileId`
- `nextBlockIndex`
- `occupiedByProcessId` opcional para visualizacion
- `isSystemReserved` opcional

Decisiones:

- `nextBlockIndex` implementa la asignacion encadenada.
- Si el bloque es el ultimo de la cadena, `nextBlockIndex` sera un valor nulo o sentinela.
- `ownerFileId` enlaza el bloque con su archivo dueno.

#### 2.3.3 DiskHead

Representa el cabezal del disco simulado.

Campos minimos propuestos:

- `currentBlock`
- `direction`

Decisiones:

- `currentBlock` simula la posicion actual del cabezal.
- `direction` se usa para politicas como `SCAN` y `C-SCAN`.
- En la version inicial, la distancia afecta el orden y las metricas, no el tiempo de servicio.

### 2.4 Solicitudes y procesos de E/S

#### 2.4.1 IoOperationType

Enumeracion propuesta:

- `CREATE`
- `READ`
- `UPDATE`
- `DELETE`

Nota:

- `UPDATE` forma parte de la enumeracion base de operaciones y su semantica concreta se fija en la seccion 3.4.

#### 2.4.2 ProcessState

Enumeracion minima propuesta:

- `NEW`
- `READY`
- `RUNNING`
- `BLOCKED`
- `TERMINATED`

Nota:

- Si el diseno final requiere mas granularidad, los estados podran extenderse despues.

#### 2.4.3 IoRequest

Representa la solicitud de E/S que una politica de planificacion debe ordenar.

Campos minimos propuestos:

- `requestId`
- `processId`
- `operationType`
- `targetPath`
- `targetNodeId`
- `targetBlock`
- `requestedSizeInBlocks`
- `issuerUserId`

Decisiones:

- `targetBlock` es el bloque de referencia usado por el scheduler de disco.
- En operaciones sobre archivos existentes, `targetBlock` sera normalmente el primer bloque del archivo.
- En `CREATE`, `targetBlock` sera el primer bloque libre elegido para iniciar la asignacion.

#### 2.4.4 ProcessControlBlock

Representa el proceso asociado a una solicitud de E/S.

Campos minimos propuestos:

- `processId`
- `state`
- `request`
- `ownerUserId`
- `targetBlock`
- `requiredLockType`
- `waitReason`

Decisiones:

- El PCB de este proyecto no modela CPU real ni registros como `PC` o `MAR`.
- El proceso existe para representar el ciclo de vida de una operacion de E/S dentro del simulador.

### 2.5 Concurrencia y locks

#### 2.5.1 LockType

Enumeracion propuesta:

- `SHARED`
- `EXCLUSIVE`

#### 2.5.2 FileLock

Campos minimos propuestos:

- `fileId`
- `type`
- `ownerProcessId`

#### 2.5.3 LockTable

Responsabilidades previstas:

- registrar locks activos,
- verificar compatibilidad,
- mantener espera por recurso.

Decision inicial:

- Los locks se aplicaran sobre archivos regulares.
- No se definiran locks de directorio en esta primera version del modelo.

### 2.6 Journal y recuperacion

#### 2.6.1 JournalEntry

Campos minimos propuestos:

- `transactionId`
- `operationType`
- `targetPath`
- `status`
- `undoData`

#### 2.6.2 JournalStatus

Enumeracion propuesta:

- `PENDING`
- `COMMITTED`
- `UNDONE`

Decision inicial:

- El journal se tratara como parte real del diseno, no como un extra opcional.

### 2.7 Usuarios y sesion

#### 2.7.1 User

Campos minimos propuestos:

- `userId`
- `username`
- `role`

#### 2.7.2 Role

Enumeracion propuesta:

- `ADMIN`
- `USER`

#### 2.7.3 SessionContext

Campos minimos propuestos:

- `currentUser`
- `currentRole`

Decision:

- Las validaciones de permisos deben depender del contexto de sesion y no de la GUI.

### 2.8 Relaciones principales

- Un `DirectoryNode` contiene multiples `FsNode`.
- Un `FileNode` referencia una cadena de bloques mediante `firstBlockIndex`.
- Un `DiskBlock` pertenece a cero o un archivo.
- Un `ProcessControlBlock` ejecuta una `IoRequest`.
- Una `IoRequest` apunta a un nodo y a un `targetBlock`.
- Un `LockTable` regula acceso concurrente a `FileNode`.
- Un `JournalEntry` registra operaciones criticas sobre nodos del sistema.

### 2.9 Invariantes del sistema

Estas reglas deben mantenerse siempre:

- La raiz no tiene padre.
- Todo nodo no raiz tiene exactamente un padre.
- No hay ciclos en el arbol de directorios.
- No puede haber dos hijos con el mismo nombre bajo un mismo directorio.
- Un bloque libre no tiene archivo dueno.
- Un bloque ocupado tiene exactamente un archivo dueno.
- La cadena de bloques de un archivo no puede repetir bloques.
- La cantidad real de bloques de una cadena debe coincidir con `sizeInBlocks`.
- Un archivo puede tener multiples locks compartidos o un lock exclusivo, nunca ambos.
- Toda entrada `PENDING` del journal debe tener informacion suficiente para deshacer la operacion.

## 3. Operaciones CRUD

Esta seccion define la semantica base de las operaciones del sistema de archivos.

### 3.1 Criterio general

Se distinguen dos tipos de lectura:

- lectura de inspeccion desde GUI,
- lectura formal como operacion de E/S.

La inspeccion simple de la GUI no genera proceso ni entra a la cola de disco.
La lectura formal si genera una solicitud de E/S y participa en planificacion y locks.

### 3.2 CREATE

La operacion `CREATE` permite crear:

- archivos,
- directorios.

Comportamiento base para `CREATE file`:

- valida nombre,
- valida permisos sobre el directorio padre,
- valida que no exista otro hijo con el mismo nombre,
- valida que haya bloques libres suficientes,
- crea el `FileNode`,
- asigna bloques libres,
- encadena los bloques en el disco,
- actualiza arbol, disco y tabla de asignacion.

Comportamiento base para `CREATE directory`:

- valida nombre,
- valida permisos sobre el directorio padre,
- valida unicidad del nombre bajo el mismo padre,
- crea un `DirectoryNode` vacio,
- actualiza la estructura del arbol.

Decision:

- En esta version, `CREATE` se considera una operacion critica y debe ser compatible con journaling.

### 3.3 READ

La operacion `READ` formal representa una lectura concurrente del archivo.

Comportamiento base:

- genera un `IoRequest`,
- entra a la cola de E/S,
- usa `SHARED lock`,
- puede coexistir con otros lectores del mismo archivo.

Decision:

- La navegacion normal de la interfaz y la visualizacion de propiedades no se modelan como `READ` formal.
- Solo las lecturas explicitamente simuladas como operacion de E/S pasan por scheduler y locks.

### 3.4 UPDATE

En esta version del proyecto, `UPDATE` queda definido como:

- renombrar un archivo, o
- renombrar un directorio.

No incluye:

- cambio de tamano,
- cambio de contenido,
- cambio de permisos,
- cambio de duenio,
- mover nodos entre directorios.

Comportamiento base:

- valida permisos,
- valida unicidad del nuevo nombre dentro del padre,
- actualiza el nombre del nodo objetivo,
- refleja el cambio en la GUI y vistas derivadas.

Nota para escenarios externos:

- como el formato JSON del enunciado solo aporta `pos` y `op`, un `UPDATE` cargado desde escenario externo no trae por si solo el nuevo nombre,
- por tanto, la implementacion podra aplicar una estrategia determinista y defendible de renombrado automatico para esos casos de prueba,
- esta flexibilizacion aplica solo al formato externo de demostracion y no cambia la semantica normal de `UPDATE` en la GUI.

Decision:

- `UPDATE` se tratara como operacion modificadora y usara lock exclusivo sobre el recurso afectado.

### 3.5 DELETE

La operacion `DELETE` elimina archivos o directorios.

Comportamiento base para `DELETE file`:

- valida permisos,
- toma control exclusivo del archivo,
- libera todos los bloques de su cadena,
- elimina el `FileNode` del arbol,
- actualiza disco y tabla de asignacion.

Comportamiento base para `DELETE directory`:

- valida permisos,
- elimina recursivamente todos sus hijos,
- elimina el directorio del arbol.

Decision:

- El borrado recursivo de directorios no vacios esta permitido.
- `DELETE` se considera operacion critica y debe ser compatible con journaling.

### 3.6 Politica de locking asociada a CRUD

Politica base aprobada para la primera version:

- `READ` formal usa `SHARED lock`.
- `UPDATE` usa `EXCLUSIVE lock`.
- `DELETE` usa `EXCLUSIVE lock`.

Semantica base:

- multiples lectores pueden acceder al mismo archivo al mismo tiempo,
- un escritor o modificador requiere acceso exclusivo,
- si existen lectores activos, la operacion exclusiva espera,
- si existe un lock exclusivo activo, lectores y escritores esperan.

Decision inicial:

- Los locks reales se modelan sobre archivos regulares.
- Los cambios estructurales de directorio se serializaran a nivel del sistema y no mediante locks de directorio en esta primera version.

## 4. Scheduler de disco

Esta seccion define el modelo de planificacion de solicitudes de E/S sobre el disco simulado.

### 4.1 Responsabilidad

El scheduler de disco sera un componente dedicado cuya responsabilidad sera:

- recibir solicitudes de E/S pendientes,
- observar el estado actual del cabezal,
- seleccionar la siguiente solicitud a atender segun la politica activa.

Decision:

- El scheduler decide orden.
- La ejecucion real de la operacion pertenece a otra capa del sistema.

### 4.2 Entrada del scheduler

El scheduler opera sobre `IoRequest` formales.

Cada solicitud usada por el scheduler debe tener, como minimo:

- `requestId`
- `processId`
- `operationType`
- `targetBlock`
- `arrivalOrder`

Decisiones:

- `arrivalOrder` representa el orden de llegada a la cola.
- `arrivalOrder` se usara como criterio de desempate estable cuando haga falta.

### 4.3 Estado usado por el scheduler

El scheduler necesita observar:

- la politica activa,
- `currentBlock`,
- `direction`,
- la cola de solicitudes pendientes.

Formula base:

- `seekDistance = abs(currentBlock - targetBlock)`

Decision:

- En esta version, la distancia afecta el orden de atencion y las metricas.
- La distancia no modifica el tiempo de servicio logico de la solicitud.

### 4.4 Salida del scheduler

El resultado de una decision de scheduling debe incluir al menos:

- solicitud seleccionada,
- bloque anterior del cabezal,
- nuevo `currentBlock`,
- distancia recorrida.

Decision:

- El scheduler selecciona una solicitud por ciclo de servicio, no construye toda la secuencia futura de una sola vez.

### 4.5 Politicas soportadas

La enumeracion base sera:

- `FIFO`
- `SSTF`
- `SCAN`
- `C_SCAN`

### 4.6 FIFO

Semantica:

- atiende solicitudes en orden de llegada,
- ignora distancia,
- ignora direccion.

### 4.7 SSTF

Semantica:

- atiende la solicitud cuyo `targetBlock` este mas cerca de `currentBlock`.

Desempates aprobados:

1. menor `arrivalOrder`
2. menor `requestId`

### 4.8 SCAN

Semantica:

- el cabezal recorre el disco en una direccion actual,
- atiende primero las solicitudes ubicadas en esa direccion,
- cuando no quedan solicitudes en esa direccion, invierte el sentido.

Regla para direccion `UP`:

- se consideran primero requests con `targetBlock >= currentBlock`,
- se elige la mas cercana dentro de esa direccion.

Regla para direccion `DOWN`:

- se consideran primero requests con `targetBlock <= currentBlock`,
- se elige la mas cercana dentro de esa direccion.

Decision:

- `SCAN` se modelara usando los extremos reales del disco, no solo los requests presentes.

### 4.9 C-SCAN

Semantica:

- el cabezal recorre el disco siempre en la misma direccion,
- atiende solicitudes en ese sentido,
- cuando no quedan solicitudes hacia adelante, salta al extremo opuesto y continua en la misma direccion.

Decision:

- `C-SCAN` tambien usara los extremos reales del disco.

### 4.10 Direccion del cabezal

Se define una enumeracion base:

- `UP`
- `DOWN`

Decision:

- `direction` es parte del estado real del cabezal y debe estar visible en la GUI cuando aplique.

### 4.11 Metricas del scheduler

Metricas base previstas:

- distancia recorrida por solicitud,
- distancia total acumulada,
- orden efectivo de atencion.

Decision:

- La GUI debe poder mostrar el orden real de despacho y el desplazamiento del cabezal.

## 5. Procesos

Esta seccion define el modelo de procesos asociado al sistema de archivos concurrente.

### 5.1 Rol del proceso

En este proyecto, un proceso representa una operacion de sistema de archivos emitida por un usuario y gestionada por el simulador.

Decision:

- El proceso no modela CPU general.
- El proceso no modela registros como `PC` o `MAR`.
- El proceso modela el ciclo de vida de una solicitud de E/S dentro del sistema.

### 5.2 ProcessState

Los estados aprobados para la primera version son:

- `NEW`
- `READY`
- `RUNNING`
- `BLOCKED`
- `TERMINATED`

Interpretacion:

- `NEW`: proceso creado pero todavia no admitido a la cola activa.
- `READY`: proceso listo para ser seleccionado por el scheduler de disco.
- `RUNNING`: proceso cuya solicitud esta siendo atendida.
- `BLOCKED`: proceso detenido por espera de lock o condicion equivalente.
- `TERMINATED`: proceso finalizado con exito o con error controlado.

Decision:

- No se usaran `READY_SUSPENDED` ni `BLOCKED_SUSPENDED` en esta primera version.
- Los fallos del sistema se modelaran como eventos globales de crash y recuperacion, no como suspension de procesos.

### 5.3 ProcessControlBlock

Campos minimos aprobados para el PCB:

- `processId`
- `ownerUserId`
- `state`
- `request`
- `targetNodeId`
- `targetPath`
- `targetBlock`
- `requiredLockType`
- `waitReason`
- `resultStatus`
- `creationTick`
- `readyTick`
- `startTick`
- `endTick`

Campos opcionales utiles:

- `blockedByProcessId`
- `errorMessage`

Decision:

- El PCB y el `IoRequest` son entidades distintas.
- El `IoRequest` describe la solicitud planificable.
- El PCB describe el ciclo de vida completo del proceso asociado a esa solicitud.

### 5.4 WaitReason

Se define una enumeracion base para motivos de espera:

- `NONE`
- `WAITING_SCHEDULER`
- `WAITING_LOCK`
- `WAITING_RECOVERY`

Decision:

- En esta version no se agregara `WAITING_SPACE`.
- Si una operacion `CREATE` no tiene espacio suficiente, fallara de forma controlada en lugar de quedar bloqueada indefinidamente.

### 5.5 ResultStatus

Se define una enumeracion base para el resultado final del proceso:

- `SUCCESS`
- `FAILED`
- `CANCELLED`

Uso esperado de `FAILED`:

- falta de permisos,
- falta de espacio,
- inconsistencia detectada,
- error controlado durante la operacion.

### 5.6 Ciclo de vida esperado

Flujo base aprobado:

1. el usuario solicita una operacion,
2. se crea un proceso en `NEW`,
3. el proceso pasa a `READY`,
4. el scheduler puede seleccionarlo y pasarlo a `RUNNING`,
5. si el lock requerido no esta disponible, pasa a `BLOCKED`,
6. cuando puede continuar, vuelve a `READY`,
7. al completar la operacion, pasa a `TERMINATED`.

### 5.7 Estructuras de procesos

El sistema necesitara al menos estas estructuras conceptuales:

- cola de `NEW`,
- cola o estructura de `READY`,
- lista de `BLOCKED`,
- lista de `TERMINATED`,
- referencia unica a `RUNNING`.

Decision:

- Se modelara un proceso por operacion logica.
- No se modelaran procesos separados por bloque individual.

## 6. Locks

Esta seccion define el modelo de control de concurrencia sobre archivos compartidos.

### 6.1 Alcance de los locks

Decision base:

- Los locks reales se aplicaran sobre `FileNode`.
- No se definiran locks sobre bloques individuales.
- No se definiran locks sobre directorios en esta primera version.

Justificacion:

- El enunciado habla de concurrencia sobre archivos compartidos.
- Agregar locks por bloque o por directorio anadiria complejidad que no esta exigida de forma explicita.

### 6.2 Tipos de lock

Se aprueban dos tipos:

- `SHARED`
- `EXCLUSIVE`

Semantica:

- `SHARED` permite multiples lectores simultaneos.
- `EXCLUSIVE` requiere acceso exclusivo total al archivo.

### 6.3 Compatibilidad

Reglas de compatibilidad aprobadas:

- multiples `SHARED` sobre el mismo archivo son compatibles entre si,
- `EXCLUSIVE` no es compatible con ningun otro lock activo sobre el mismo archivo,
- no pueden coexistir `SHARED` y `EXCLUSIVE` sobre el mismo archivo.

### 6.4 Operaciones que requieren lock

Asignacion base aprobada:

- `READ` formal requiere `SHARED`
- `UPDATE` requiere `EXCLUSIVE`
- `DELETE` requiere `EXCLUSIVE`

Decision:

- `CREATE` no toma lock de archivo porque el archivo aun no existe.
- Los cambios estructurales asociados a `CREATE` se serializaran a nivel del sistema.

### 6.5 Cola de espera por archivo

Cada archivo podra tener una cola de espera propia para procesos bloqueados por lock.

Decision base:

- La espera se modelara con una cola FIFO por archivo.

Regla operativa:

- si al liberar un lock el primer proceso en espera es lector, podran reactivarse los lectores consecutivos compatibles,
- si el primer proceso en espera es escritor, solo se reactivara ese escritor.

Objetivo:

- mantener equidad,
- evitar inanicion de escritores,
- conservar una semantica explicable y defendible.

Regla de visibilidad para la simulacion:

- aun cuando el sistema ejecute una sola operacion de disco activa por vez, la GUI debe poder reflejar contencion real,
- la contencion por lock se hace visible durante la evaluacion de despacho del coordinador, cuando detecta incompatibilidad antes de entregar trabajo al hilo de disco,
- por ello, un proceso puede pasar de `READY` a `BLOCKED` durante el intento de despacho del coordinador, sin que el hilo de disco haya comenzado la ejecucion,
- esta decision existe para hacer observable la cola de espera por lock sin romper el modelo de coordinador unico.

### 6.6 Adquisicion del lock

Protocolo base:

1. el proceso es seleccionado para ejecucion,
2. intenta adquirir el lock requerido,
3. si el lock es compatible, se concede,
4. si no es compatible, el proceso pasa a `BLOCKED`.

Decision:

- un proceso bloqueado por lock tendra `waitReason = WAITING_LOCK`.
- el proceso bloqueado debera registrarse en la cola de espera del archivo correspondiente.

### 6.7 Liberacion del lock

Regla base:

- el lock se libera al terminar la operacion,
- el lock se libera si la operacion falla de forma controlada,
- tras un crash y reinicio, los locks previos se consideran invalidados y el estado se reconstruye desde el sistema y el journal.

### 6.8 Protocolo recomendado para operaciones criticas

Para operaciones que usen lock y journaling, el orden aprobado sera:

1. validar precondiciones,
2. intentar adquirir lock,
3. si no puede, bloquear el proceso,
4. si puede, registrar `PENDING` en journal cuando aplique,
5. ejecutar la operacion,
6. marcar `COMMITTED` cuando aplique,
7. liberar lock,
8. actualizar el estado final del proceso.

Decision:

- Este orden debe tratarse como contrato del sistema para reducir bugs por llamadas en orden incorrecto.

### 6.9 Reglas para reducir bugs de sincronizacion

Estas reglas se adoptan para disminuir la probabilidad de errores por orden de llamadas y estados inconsistentes:

- El manejo de locks debe estar centralizado en un componente responsable.
- El codigo de alto nivel no debe chequear y mutar locks manualmente en pasos separados.
- Toda adquisicion de lock debe tener una liberacion garantizada.
- La GUI no debe modificar estado interno de locks.
- Los tipos de lock, estados de espera y resultados deben representarse con enums, no con strings libres.
- Deben evitarse combinaciones de multiples locks anidados para el mismo flujo.

Decision:

- La implementacion favorecera un protocolo fijo y centralizado en lugar de logica dispersa entre varias clases.

### 6.10 Invariantes del subsistema de locks

Estas reglas deben mantenerse siempre:

- un archivo puede tener multiples lectores o un escritor, nunca ambos,
- un proceso `BLOCKED` por lock no debe figurar como dueno del lock que espera,
- un proceso `TERMINATED` no puede conservar locks activos,
- un proceso `RUNNING` no debe permanecer en la cola de espera de lock,
- un lock exclusivo activo tiene un solo propietario.

## 7. Journaling

Esta seccion define el mecanismo de journal para operaciones criticas del sistema.

### 7.1 Objetivo

El journal existe para registrar operaciones modificadoras antes de ejecutarlas y permitir recuperacion ante fallos.

Objetivo practico:

- detectar operaciones incompletas,
- restaurar consistencia al reiniciar,
- ofrecer evidencia visible durante la defensa.

### 7.2 Operaciones cubiertas

El journal registrara:

- `CREATE`
- `DELETE`
- `UPDATE`

Decision:

- `READ` no entra al journal.
- `UPDATE` si entra al journal aunque en esta version solo represente rename.

### 7.3 Estructura de JournalEntry

Campos minimos aprobados:

- `transactionId`
- `operationType`
- `targetPath`
- `status`
- `undoData`

Campos opcionales utiles:

- `targetNodeId`
- `ownerUserId`
- `description`

### 7.4 Estados del journal

Estados aprobados:

- `PENDING`
- `COMMITTED`
- `UNDONE`

Interpretacion:

- `PENDING`: la operacion fue registrada pero aun no ha sido confirmada.
- `COMMITTED`: la operacion se completo correctamente.
- `UNDONE`: la operacion pendiente fue revertida durante recuperacion.

### 7.5 UndoData

Cada entrada de journal debe guardar informacion suficiente para restaurar el estado previo.

Casos base aprobados:

- `CREATE file`
  - nodo creado,
  - padre,
  - bloques asignados.

- `CREATE directory`
  - nodo creado,
  - padre.

- `DELETE file`
  - snapshot del archivo,
  - padre,
  - lista completa de bloques y enlaces.

- `DELETE directory`
  - snapshot del subarbol eliminado,
  - padre,
  - archivos y bloques asociados.

- `UPDATE`
  - nodo afectado,
  - nombre anterior,
  - nombre nuevo,
  - padre.

Decision:

- `undoData` no puede ser solo descriptivo; debe ser suficiente para revertir.

### 7.6 Protocolo de journaling

Orden base aprobado para operaciones journaled:

1. construir entrada de journal,
2. registrar entrada como `PENDING`,
3. ejecutar la operacion real en el sistema,
4. si todo sale bien, marcar la entrada como `COMMITTED`.

### 7.7 Punto de fallo simulado

El sistema debe permitir simular un fallo durante una operacion critica.

Decision aprobada:

- el evento de "Simular fallo" solo tendra efecto durante una operacion journaled,
- el punto de fallo base sera despues de aplicar el cambio real al sistema y antes de marcar `COMMITTED`.

Interpretacion:

- el sistema ya fue modificado,
- el journal aun indica `PENDING`,
- por eso al reiniciar existe una operacion incompleta que debe deshacerse.

### 7.8 Recuperacion al reiniciar

Al reiniciar el sistema:

1. se carga el estado persistido,
2. se carga el journal,
3. se revisan todas las entradas `PENDING`,
4. se aplica `undo` sobre cada una,
5. la entrada se marca como `UNDONE`.

Decision:

- las entradas `COMMITTED` no se revierten,
- la recuperacion se basara en `undo`, no en `redo`.

### 7.9 Relacion con locks

Para operaciones que requieran lock y journal, se mantiene el siguiente orden:

1. adquirir lock si aplica,
2. registrar `PENDING`,
3. ejecutar operacion,
4. marcar `COMMITTED`,
5. liberar lock.

### 7.10 Alcance de la primera version

No se implementara en esta etapa:

- redo avanzado,
- commits en multiples fases,
- dependencia entre transacciones,
- journal por bloque fino,
- recuperacion concurrente compleja.

Decision:

- El journaling de la primera version debe ser simple, consistente y defendible.

## 8. GUI

Esta seccion define la estructura funcional de la interfaz grafica del simulador.

### 8.1 Principio general

La GUI se define primero por su funcion y por la informacion que debe mostrar.

Decision:

- La arquitectura funcional de paneles se fija en esta especificacion.
- El estilo visual exacto podra ajustarse despues sin romper esta estructura.

### 8.2 Regla de arquitectura

Decision base:

- La GUI no sera duena del estado del sistema.
- La GUI enviara comandos al nucleo.
- La GUI renderizara vistas derivadas y snapshots del dominio.

Objetivo:

- reducir acoplamiento,
- disminuir errores por concurrencia,
- hacer mas segura la actualizacion en tiempo real.

### 8.3 Barra superior

La barra superior debe incluir al menos:

- selector de modo `ADMIN / USER`,
- selector de politica `FIFO / SSTF / SCAN / C-SCAN`,
- indicador de `currentBlock`,
- indicador de `direction`,
- boton `Crear archivo`,
- boton `Crear directorio`,
- boton `Leer`,
- boton `Renombrar`,
- boton `Eliminar`,
- boton `Guardar`,
- boton `Cargar`,
- boton `Simular fallo`.

Decision:

- La barra superior concentra controles globales del simulador.

### 8.4 Columna izquierda

La columna izquierda contendra:

- `JTree` del sistema de archivos,
- panel de detalles del nodo seleccionado.

El panel de detalles podra mostrar:

- nombre,
- ruta,
- tipo,
- duenio,
- permisos,
- tamano en bloques,
- primer bloque,
- informacion de lock si aplica.

Decision:

- navegar el arbol no genera procesos por si solo.
- seleccionar nodos actualiza el panel de detalles.

### 8.5 Panel central

El panel central sera el foco principal de la simulacion y contendra:

- vista del disco por bloques,
- visualizacion del cabezal,
- tabla de asignacion de archivos.

#### 8.5.1 Vista del disco

La vista de disco debe mostrar:

- bloques numerados,
- bloques libres y ocupados,
- color del archivo asociado cuando aplique,
- posicion actual del cabezal.

Decision:

- El disco debe ser el componente visual dominante del centro de la interfaz.

#### 8.5.2 Tabla de asignacion

La tabla de asignacion debe mostrar como minimo:

- archivo,
- duenio,
- cantidad de bloques,
- primer bloque,
- color asociado.

Decision:

- Esta tabla se actualiza en tiempo real a partir del estado del dominio.

### 8.6 Columna derecha

La columna derecha contendra:

- tabla o lista de procesos,
- panel de locks,
- area de journal y log.

#### 8.6.1 Procesos

La vista de procesos debe mostrar como minimo:

- id del proceso,
- operacion,
- archivo o ruta objetivo,
- estado,
- lock requerido,
- `targetBlock`,
- usuario.

#### 8.6.2 Locks

La vista de locks debe mostrar:

- archivo,
- tipo de lock,
- procesos duenos,
- cola de espera del recurso.

Decision:

- los locks tendran panel propio porque son parte explicita del enunciado y de la defensa.

#### 8.6.3 Journal y Log

Decision base:

- `Journal` y `Log` se mostraran en pestanas dentro de la columna derecha.

Justificacion:

- mejora legibilidad,
- ahorra espacio,
- evita sobrecargar la interfaz con demasiados paneles pequenos simultaneos.

### 8.7 Reglas de interaccion

Reglas aprobadas:

- las operaciones CRUD se disparan desde la GUI sobre el nodo seleccionado cuando aplique,
- `CREATE` toma como padre el directorio seleccionado,
- la navegacion e inspeccion simple no generan procesos,
- las operaciones formales de E/S si generan procesos y solicitudes,
- la GUI no modifica directamente estructuras internas del dominio.

### 8.8 Flexibilidad visual

Decision:

- la disposicion funcional aprobada no obliga a un estilo visual exacto,
- colores, tamanos finos, espaciado y detalles esteticos podran refinarse despues,
- cualquier refinamiento posterior debe preservar esta arquitectura de paneles y responsabilidades.

## 9. Persistencia

Esta seccion define como se almacenara y recuperara el estado del sistema.

### 9.1 Principio general

Decision base:

- La persistencia debe representar el estado real del sistema y no snapshots de GUI.
- El estado persistido debe ser suficiente para reconstruir el dominio y ejecutar recovery cuando haga falta.

### 9.2 Formato

Decision aprobada:

- la persistencia se hara en JSON.

Justificacion:

- esta alineado con el enunciado,
- facilita inspeccion manual,
- facilita pruebas y carga de escenarios.

### 9.3 Estado que si se persiste

Se persistira al menos:

- estructura del sistema de archivos,
- nodos y jerarquia,
- disco simulado,
- bloques y encadenamiento,
- estado del cabezal,
- politica de disco activa,
- usuarios,
- journal.

### 9.4 Estado que no se persiste

No se persistira en la primera version:

- snapshots de GUI,
- locks activos en caliente,
- procesos vivos en ejecucion,
- colas transitorias de procesos,
- log temporal de interfaz.

Decision:

- tras un reinicio, el sistema recupera consistencia estructural y no reanuda procesos previos.

### 9.5 Estructura de persistencia del sistema de archivos

Decision base:

- los nodos del sistema de archivos se persistiran como lista plana con `id` y `parentId`,
- no se persistiran como arbol JSON profundamente anidado.

Justificacion:

- simplifica validacion,
- simplifica reconstruccion,
- simplifica serializacion.

### 9.6 Estructura de persistencia del disco

Cada bloque persistido debera reflejar al menos:

- `index`
- `free`
- `ownerFileId`
- `nextBlockIndex`

Decision:

- la persistencia del disco debe ser casi espejo del estado real del dominio.

### 9.7 Persistencia del journal

El journal se persistira con sus entradas y estados.

Cada entrada debera reflejar al menos:

- `transactionId`
- `operationType`
- `targetPath`
- `status`
- `undoData`

Decision:

- el journal persistido es parte esencial del flujo de recovery.

### 9.8 Politica de guardado

Decision aprobada:

- el guardado normal del sistema sera manual mediante `Guardar`.

Interpretacion:

- el usuario decide cuando persistir el snapshot completo del sistema.

Diferenciacion explicita:

- `Guardar` y `Cargar sistema` pertenecen al flujo normal de persistencia,
- estas acciones trabajan con snapshots completos del estado del simulador,
- no deben confundirse con la carga de escenarios externos para pruebas.

### 9.9 Herramientas de demostracion

La interfaz podra incluir un menu o boton `Mas...` o equivalente para funciones especiales de showcase.

Herramientas posibles:

- forzar guardado,
- simular fallo,
- simular fallo con snapshot previo,
- ejecutar recovery,
- cargar escenario de prueba.

Decision:

- estas herramientas no forman parte del flujo normal de persistencia,
- existen para facilitar demostracion y defensa del proyecto.

Separacion operativa:

- `Cargar sistema` restaura un estado previamente guardado del simulador,
- `Cargar escenario` prepara un caso de prueba externo compatible con el PDF,
- ambos flujos deben permanecer separados en la GUI y en la implementacion.

### 9.10 Carga y reconstruccion

Al cargar un estado persistido, el sistema debera:

1. leer el JSON,
2. validar la estructura basica,
3. reconstruir el dominio,
4. validar invariantes relevantes,
5. ejecutar recovery del journal si aplica.

### 9.11 Validaciones al cargar

Se deben validar al menos:

- coherencia del arbol,
- existencia de padres validos,
- unicidad de nombres dentro de cada directorio,
- coherencia de bloques,
- coherencia de cadenas de archivos,
- coincidencia entre `sizeInBlocks` y cadena real,
- coherencia del journal.

Decision:

- si el estado cargado no es consistente, el sistema debe rechazarlo o reportarlo claramente.

## 10. Concurrencia y sincronizacion

Esta seccion define el modelo concurrente del simulador y las reglas para reducir bugs de sincronizacion.

### 10.1 Principio general

Decision base:

- el estado real del dominio tendra un unico escritor principal,
- la concurrencia se usara de forma controlada y con responsabilidades acotadas,
- no se distribuira la mutacion del estado entre multiples componentes sin coordinacion explicita.

Objetivo:

- reducir condiciones de carrera,
- disminuir bugs por orden de llamadas,
- mantener una arquitectura explicable en defensa.

### 10.2 Hilos aprobados

La arquitectura concurrente base usara tres hilos principales:

- `Swing EDT`
- `SimulationCoordinatorThread`
- `DiskExecutionThread`

#### 10.2.1 Swing EDT

Responsabilidad:

- renderizar la interfaz,
- recibir acciones del usuario,
- enviar comandos al sistema,
- consumir snapshots ya preparados.

Decision:

- el EDT no modifica directamente el dominio.

#### 10.2.2 SimulationCoordinatorThread

Responsabilidad:

- ser el dueno principal del estado del sistema,
- gestionar procesos,
- aplicar scheduler,
- gestionar locks,
- coordinar journaling,
- generar snapshots para GUI,
- decidir cuando enviar trabajo al hilo de disco.

Decision:

- este hilo sera el unico escritor principal del estado global del simulador.

#### 10.2.3 DiskExecutionThread

Responsabilidad:

- simular la ejecucion de la solicitud de disco seleccionada por el coordinador,
- ejecutar la fase operativa de la E/S bajo un protocolo controlado,
- devolver resultado al coordinador.

Decision:

- el hilo de disco no decide por su cuenta scheduling, locks ni journaling,
- el hilo de disco trabaja solo sobre tareas entregadas por el coordinador.

### 10.3 Protocolo general entre coordinador y disco

Flujo base aprobado:

1. la GUI envia un comando,
2. el coordinador crea o actualiza procesos y solicitudes,
3. el coordinador selecciona una request candidata,
4. el coordinador valida lock y precondiciones,
5. el coordinador entrega la tarea al hilo de disco,
6. el hilo de disco simula la ejecucion,
7. el coordinador recibe el resultado,
8. el coordinador aplica cambios finales, commit, liberacion de lock y cierre del proceso.

Decision:

- el orden final del sistema siempre lo impone el coordinador.

Aclaratoria de implementacion:

- en la primera implementacion defendible, el hilo de disco atiende una sola tarea activa por vez,
- la concurrencia observable del proyecto se concentra en colas, estados, locks y transiciones coordinadas,
- no se requiere ejecutar multiples operaciones de disco simultaneas para que exista contencion valida sobre recursos.

### 10.4 Recursos compartidos a proteger

La proteccion concurrente se limitara en esta version a tres regiones principales:

- cola de comandos o eventos entrantes,
- canal de trabajo entre coordinador y disco,
- snapshot publicado para GUI si no se maneja como objeto inmutable de reemplazo atomico.

Decision:

- no se protegeran con semaforos separados el arbol, el disco, el journal y los locks si ya estan bajo control del coordinador.

### 10.5 Uso de semaforos

Se aprueba el uso minimo de semaforos o mecanismos equivalentes para:

- proteger la cola de comandos,
- sincronizar la entrega y finalizacion de trabajo del hilo de disco,
- proteger la publicacion de snapshots solo si es necesario.

Objetivo:

- usar sincronizacion donde agrega valor real,
- evitar repetir el error de proteger demasiadas partes del sistema sin necesidad.

### 10.6 Reglas anti-bug

Estas reglas se adoptan como contrato de implementacion:

- la GUI nunca muta directamente el dominio,
- el coordinador centraliza transiciones de estado,
- el hilo de disco no toma decisiones de negocio,
- no se deben anidar semaforos o locks sin necesidad,
- toda adquisicion de recurso debe tener liberacion garantizada,
- las transiciones criticas deben seguir orden fijo,
- los snapshots para GUI deben ser inmutables o reemplazables de forma segura.

### 10.7 Relacion con procesos y locks

Decision base:

- un proceso puede cambiar de `READY` a `RUNNING` solo por decision del coordinador,
- un proceso puede pasar a `BLOCKED` por lock solo por decision del coordinador,
- la liberacion y reactivacion de procesos bloqueados se decide desde el coordinador.

### 10.8 Relacion con journaling

Decision base:

- el journaling y el commit final se cierran en el coordinador,
- el hilo de disco no confirma transacciones por su cuenta.

## 11. Carga de escenarios JSON externos

Esta seccion define la carga de archivos JSON externos para pruebas y defensa.

### 11.1 Objetivo

El sistema debe poder cargar escenarios externos compatibles con el formato mostrado en el enunciado.

Decision:

- la carga de escenarios externos es distinta de la persistencia normal del sistema,
- el formato externo de pruebas debe ser compatible con el ejemplo del PDF.

### 11.2 Uso en GUI

Decision base:

- la interfaz tendra una accion separada para `Cargar escenario`,
- esta accion no debe confundirse con `Cargar sistema`.

### 11.3 Formato externo esperado

El formato externo soportado debera aceptar al menos:

- `test_id`
- `initial_head`
- `requests`
- `system_files`

Cada request externa debe aceptar:

- `pos`
- `op`

Cada entrada en `system_files` debe aceptar:

- clave numerica como bloque inicial,
- `name`
- `blocks`

Decision:

- la clave del objeto en `system_files` se interpretara como `startBlock`.

Limitacion explicita del formato:

- este formato externo describe bien operaciones referidas a archivos ya existentes en `system_files`,
- el formato no aporta datos suficientes para construir una operacion `CREATE` completa sin extensiones adicionales,
- por ello, la compatibilidad obligatoria del cargador se concentra en `READ`, `UPDATE` y `DELETE` sobre archivos reconstruibles desde el escenario.

### 11.4 Transformacion en dos pasos

La carga del escenario no se hara directo al dominio.

Paso 1:

- JSON externo -> modelo intermedio de escenario.

Paso 2:

- modelo intermedio -> dominio del simulador.

Decision:

- esta separacion se adopta para reducir acoplamiento y hacer validaciones previas de forma segura.

### 11.5 Modelo intermedio propuesto

El parser debera construir un modelo intermedio conceptual equivalente a:

- `ExternalScenario`
  - `testId`
  - `initialHead`
  - `requests[]`
  - `systemFiles[]`

- `ExternalRequestSpec`
  - `pos`
  - `op`

- `ExternalSystemFileSpec`
  - `startBlock`
  - `name`
  - `blocks`

Decision:

- `startBlock` no se leera como campo normal del JSON, sino a partir de la clave numerica de cada entrada de `system_files`.

### 11.6 Aplicacion al dominio

Al aplicar un escenario validado, el sistema debera:

1. resetear el escenario actual,
2. fijar `initial_head` como `currentBlock`,
3. cargar los archivos del bloque `system_files`,
4. crear las requests y procesos correspondientes,
5. refrescar la GUI.

Decision:

- las requests cargadas desde escenario externo entraran inicialmente en `READY`.

Decision adicional de usabilidad:

- la GUI podra dejar la simulacion en pausa inmediatamente despues de cargar un escenario para permitir escoger politica, direccion o velocidad antes de ejecutar,
- esta pausa inicial no altera el estado logico de las requests, solo el momento en que empieza el despacho.

### 11.7 Regla de construccion de archivos del escenario

Cuando un `system_file` externo indique:

- bloque inicial,
- nombre,
- cantidad de bloques,

el cargador construira la cadena del archivo a partir de ese bloque inicial.

Decision aprobada:

- el escenario fija el bloque inicial del archivo,
- la cadena completa se construira automaticamente de forma determinista a partir de ese inicio y de los bloques libres disponibles.

Objetivo:

- respetar el formato del PDF,
- mantener reproducibilidad,
- evitar ambiguedad al reconstruir archivos.

### 11.8 Validaciones obligatorias

Antes de aplicar el escenario, se validara al menos:

- que `initial_head` este en rango,
- que cada `pos` este en rango,
- que cada `op` sea valido,
- que cada archivo tenga nombre valido,
- que `blocks > 0`,
- que el escenario no exceda la capacidad del disco,
- que no se formen colisiones imposibles,
- que la construccion de cadenas sea posible,
- que no se exija una operacion cuyo significado no pueda reconstruirse con el formato externo soportado.

Decision:

- si el escenario externo no es valido, no se aplicara parcialmente.

### 11.9 Componentes conceptuales

La implementacion debera separar al menos estos roles:

- cargador JSON,
- parser de escenario,
- validador de escenario,
- aplicador del escenario.

Objetivo:

- evitar mezclar parsing con logica del dominio,
- mejorar testabilidad,
- facilitar mensajes de error claros en defensa.

## 12. Permisos y modos de usuario

Esta seccion fija la politica provisional de permisos vigente para la implementacion actual.

### 12.1 Modo administrador

Decision base:

- `ADMIN` puede ejecutar todas las operaciones del sistema sobre cualquier recurso permitido por el simulador.

### 12.2 Modo usuario

Asuncion provisional de implementacion:

- `USER` puede ejecutar operaciones completas sobre recursos propios,
- `USER` puede leer recursos externos solo cuando sean publicos o legibles segun la politica aplicada,
- `USER` no puede modificar recursos de otros usuarios,
- `USER` no puede modificar archivos del sistema.

Interpretacion operativa provisional:

- sobre archivos propios, `USER` puede hacer `CREATE`, `READ`, `UPDATE` y `DELETE`,
- sobre archivos ajenos, `USER` queda limitado a lectura cuando corresponda,
- sobre archivos del sistema, `USER` no puede hacer operaciones modificadoras.

Justificacion de la asuncion:

- cuando el enunciado resume que el modo usuario es "solo lectura", esa restriccion debe entenderse respecto a recursos ajenos o del sistema,
- esa frase no invalida la capacidad del usuario de operar completamente sobre sus propios recursos dentro del simulador.

Nota de cierre pendiente:

- esta interpretacion debe considerarse provisional hasta que la preparadora confirme explicitamente el alcance esperado del modo `USER`.

## 13. Validaciones de entrada y manejo de errores

Esta seccion fija la politica de validacion de entradas de usuario y la forma de reportar errores.

### 13.1 Principio general

Decision base:

- toda entrada de usuario debe validarse antes de modificar el dominio,
- ningun input invalido puede romper el flujo del simulador,
- toda validacion fallida debe producir retroalimentacion visible y especifica.

### 13.2 Regla de interfaz

Decision aprobada:

- cuando una entrada sea invalida, la GUI debe mostrar un mensaje emergente claro tipo pop-up,
- el mensaje debe explicar que ocurrio y, cuando aplique, que se esperaba.

Objetivo:

- cumplir el requerimiento del proyecto,
- mejorar usabilidad,
- evitar perdida de puntos por errores silenciosos o mensajes vagos.

### 13.3 Calidad del mensaje de error

Los mensajes deben ser:

- especificos,
- breves,
- comprensibles,
- orientados al campo o accion que fallo.

Ejemplos de mensajes aceptables:

- "El tamano del archivo debe ser un entero mayor que 0."
- "El bloque inicial debe estar entre 0 y 199."
- "Ya existe un archivo con ese nombre en el directorio seleccionado."
- "El usuario actual no tiene permisos para eliminar ese archivo."

Ejemplos no aceptables:

- "Error"
- "Entrada invalida"
- "No se pudo"

### 13.4 Sin cambios parciales

Decision base:

- si una validacion falla, no se modifica el dominio,
- no se crea proceso,
- no se toca el journal,
- no se altera el disco,
- no se aplican cambios parciales.

### 13.5 Validacion por capas

La validacion se realizara en dos niveles:

- validacion preliminar en GUI,
- validacion final en la capa de aplicacion o coordinador.

Decision:

- el dominio no confiara ciegamente en la GUI.

### 13.6 Tipos de validacion

Se validara al menos:

- tipo de dato,
- rango,
- obligatoriedad del campo,
- seleccion requerida,
- existencia del recurso,
- unicidad de nombre,
- permisos del usuario,
- coherencia con el estado actual del sistema.

### 13.7 Entradas que deben validarse explicitamente

En acciones de GUI normal:

- nombre de archivo o directorio,
- tamano en bloques,
- directorio padre seleccionado,
- politica elegida,
- modo activo,
- archivo JSON a cargar.

En carga de escenarios JSON:

- presencia de campos requeridos,
- operaciones validas,
- rangos de bloques,
- nombres validos,
- capacidad del disco,
- consistencia del escenario.

### 13.8 Comportamiento esperado ante error

Cuando ocurra un error de usuario:

- se muestra el pop-up,
- la aplicacion permanece estable,
- el usuario puede corregir la entrada y continuar,
- no se reinicia ni se bloquea el simulador por errores recuperables.

### 13.9 Categorias de error

Las categorias minimas a diferenciar son:

- error de entrada del usuario,
- error de permisos,
- error de estado del sistema,
- error de carga JSON,
- error interno inesperado.

Decision:

- cada categoria debe producir mensajes acordes a su causa real.

## 14. Reutilizacion desde Project1OS

Esta seccion fija que piezas del proyecto 1 se consideran reutilizables para el proyecto 2.

### 14.1 Estructuras aprobadas para reutilizacion

Se aprueba reutilizar, con adaptaciones menores, estas estructuras base:

- `Compare`
- `LinkedQueue`
- `SimpleList`
- `OrderedList`

Justificacion:

- resuelven necesidades genericas del proyecto 2,
- cumplen la restriccion de no usar colecciones del framework,
- son mejores candidatas a reutilizacion que el nucleo del proyecto 1.

### 14.2 Adaptaciones aprobadas

Las estructuras reutilizadas podran adaptarse para el proyecto 2 siempre que mantengan simplicidad y trazabilidad.

Adaptaciones ya aprobadas:

- limpieza de codigo heredado del proyecto 1,
- operaciones de snapshot o recorrido seguras,
- soporte para remocion por identidad cuando haga falta,
- pequenas utilidades que eviten depender de colecciones externas.

Patrones adicionales aprobados para adaptar, pero no copiar literalmente:

- patron de `EventQueue` para colas de comandos o eventos sincronizados,
- patron de `GUIUpdater` para refresco por snapshots desde el dominio,
- patron de `AbstractTableModel` personalizado para tablas Swing,
- patron de panel de grafica con buffer circular si luego se muestran metricas de scheduler o cabezal.

### 14.3 Piezas no aprobadas para reutilizacion directa

No se reutilizaran directamente desde el proyecto 1:

- el nucleo `OperatingSystem`,
- el `MemoryManager`,
- el modelo RTOS de `PCB`,
- estados suspendidos del proyecto 1,
- la logica de metricas de CPU,
- la GUI principal del simulador de satelite.

Justificacion:

- esas piezas estaban acopladas al dominio del proyecto 1,
- su reutilizacion directa arrastraria complejidad y deuda tecnica innecesaria.

### 14.4 Regla general de reutilizacion

Decision base:

- se reutilizan estructuras genericas,
- se adaptan patrones utiles,
- se reescriben los componentes acoplados al dominio del satelite.

## 15. Decisiones abiertas

Estado actual:

- no queda ninguna decision abierta necesaria para implementar la version objetivo de entrega del proyecto,
- futuras aclaratorias podran refinar detalles menores sin romper la arquitectura ya aprobada,
- si aparece una nueva decision abierta durante desarrollo, debera registrarse aqui de forma explicita con fecha y motivo.
