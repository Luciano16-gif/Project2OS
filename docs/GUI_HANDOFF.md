# GUI Handoff

Este documento resume que falta para terminar la GUI y como conectarla al backend.

## Objetivo

La GUI debe usar una sola instancia de `SimulationCoordinator` y refrescarse leyendo `SimulationSnapshot` por medio de `getLatestSnapshot()`.

Archivos clave:

- `src/main/java/ve/edu/unimet/so/project2/coordinator/core/SimulationCoordinator.java`
- `src/main/java/ve/edu/unimet/so/project2/coordinator/snapshot/SimulationSnapshot.java`
- `src/main/java/ve/edu/unimet/so/project2/project2os/gui/MainFrame.java`
- `src/main/java/ve/edu/unimet/so/project2/project2os/Project2OS.java`

## API del backend para la GUI

La GUI debe apoyarse en estos metodos:

- `submitIntent(...)`
- `switchSession(String userId)`
- `changePolicy(...)`
- `changeHeadDirection(...)`
- `saveSystem(Path path)`
- `loadSystem(Path path)`
- `loadScenario(Path path)`
- `armSimulatedFailure()`
- `recoverPendingJournalEntries()`
- `getLatestSnapshot()`

## Arquitectura recomendada

No meter toda la logica dentro de `MainFrame` ni dentro de `Project2OS`.

Sugerencia:

- `Project2OS` solo arranca la aplicacion
- `Project2OS` llama algo tipo `GuiLauncher.launch()`
- `GuiLauncher` crea el `SimulationCoordinator`, la vista y el controlador
- `GuiController` conecta botones, combos y dialogs con el coordinator
- `GuiController` toma un `SimulationSnapshot` y actualiza la vista
- `MainFrame` solo contiene componentes Swing

Ejemplo de responsabilidad:

- `Project2OS`: `main(...)`
- `GuiLauncher`: bootstrap de la GUI
- `MainFrame`: componentes Swing
- `GuiController`: listeners, acciones y refresh

## Refresh en tiempo real

Usar un `javax.swing.Timer` cada `100-250 ms`.

Flujo:

1. leer `SimulationSnapshot snapshot = coordinator.getLatestSnapshot()`
2. actualizar arbol, tablas y paneles
3. no reconstruir toda la ventana si no hace falta; actualizar modelos

Para guardar/cargar archivos usar `SwingWorker` o un hilo aparte para no congelar la interfaz.

## Que debe mostrar la GUI

### 1. JTree del sistema de archivos

Fuente: `snapshot.getFileSystemNodesSnapshot()`

Mostrar:

- estructura jerarquica
- nombre
- tipo
- owner
- tamano en bloques si es archivo

### 2. Panel de detalle del nodo seleccionado

Mostrar:

- nombre
- path
- owner
- tipo
- bloques
- primer bloque si es archivo
- publico/privado
- system file o no

### 3. Disco / bloques

Fuente: `snapshot.getDiskBlocksSnapshot()`

Mostrar por bloque:

- indice
- libre/ocupado
- `ownerFileId`
- `occupiedByProcessId`
- `nextBlockIndex`
- `systemReserved`

Idealmente con colores por archivo.

### 4. Tabla de archivos

Se puede llenar filtrando `FileSystemNodeSummary` de tipo archivo.

Columnas sugeridas:

- nombre
- path
- owner
- bloques
- primer bloque
- color
- publico/privado
- system file

### 5. Procesos

Fuentes:

- `getRunningProcessSnapshot()`
- `getNewProcessesSnapshot()`
- `getReadyProcessesSnapshot()`
- `getBlockedProcessesSnapshot()`
- `getTerminatedProcessesSnapshot()`

Mostrar:

- processId
- requestId
- operacion
- usuario
- estado
- waitReason
- requiredLockType
- targetPath
- targetBlock
- resultStatus
- errorMessage

### 6. Locks

Fuente: `snapshot.getLocksSnapshot()`

Mostrar:

- fileId
- locks activos
- procesos duenos
- cola de espera
- pending grants

### 7. Journal

Fuente: `snapshot.getJournalEntriesSnapshot()`

Mostrar:

- transactionId
- operationType
- targetPath
- status
- ownerUserId
- description

### 8. Log del sistema

Fuente: `snapshot.getEventLogEntriesSnapshot()`

Mostrar:

- secuencia
- tick
- categoria
- mensaje

## Mapeo de acciones de la GUI al backend

### CRUD

- crear archivo -> `submitIntent(new CreateFileIntent(...))`
- crear directorio -> `submitIntent(new CreateDirectoryIntent(...))`
- leer -> `submitIntent(new ReadIntent(path))`
- renombrar -> `submitIntent(new RenameIntent(path, nuevoNombre))`
- eliminar -> `submitIntent(new DeleteIntent(path))`

### Sesion / modo

- admin -> `switchSession("admin")`
- user-1 -> `switchSession("user-1")`
- user-2 -> `switchSession("user-2")`

### Planificacion

- combo de politica -> `changePolicy(...)`
- combo de direccion -> `changeHeadDirection(...)`

### Persistencia y escenarios

- guardar -> `saveSystem(path)`
- cargar sistema -> `loadSystem(path)`
- cargar escenario -> `loadScenario(path)`

### Fallo y recovery

- boton `Simular fallo` -> `armSimulatedFailure()` antes de una operacion journaled
- luego ejecutar `CREATE`, `UPDATE` o `DELETE`
- recovery manual -> `recoverPendingJournalEntries()`

## Flujo sugerido para Simular fallo

1. usuario pulsa `Simular fallo`
2. GUI llama `armSimulatedFailure()`
3. usuario ejecuta create/update/delete
4. refrescar snapshot
5. mostrar proceso cancelado + journal pendiente + evento de crash
6. permitir recovery o load de sistema/escenario

## Validaciones minimas

Antes de llamar al backend validar:

- nombres no vacios
- tamano de archivo > 0
- rutas no vacias
- indices numericos en rango cuando aplique
- que exista seleccion de nodo cuando una accion lo requiera

Si el backend lanza `IllegalArgumentException` o `IllegalStateException`, mostrar el mensaje en dialog o barra de estado.

## Pendiente en MainFrame

La clase actual sigue siendo mayormente placeholder. Falta:

- conectar botones a acciones reales
- construir el `TreeModel` real
- construir `TableModel` reales
- reemplazar areas de texto placeholder por datos del snapshot
- corregir labels y valores de politica
- conectar selector de usuario/admin
- conectar file chooser para save/load/scenario
- conectar el control de `Simular fallo`

## Orden sugerido de implementacion

1. crear `SimulationCoordinator` en `Project2OS`
2. crear `GuiLauncher`
3. dentro de `GuiLauncher`, crear `MainFrame` y `GuiController`
4. poner timer de refresh con `getLatestSnapshot()`
5. llenar `JTree`
6. llenar tabla de archivos y panel de detalle
7. llenar panel de procesos
8. llenar panel de locks, journal y log
9. conectar CRUD
10. conectar save/load/scenario
11. conectar simular fallo y recovery

## Pruebas manuales recomendadas

- crear archivo y ver reflejo en arbol, tabla y bloques
- renombrar archivo y ver cambio en tiempo real
- eliminar archivo y verificar liberacion de bloques
- cambiar politica y ver orden de despacho
- cargar escenario y ver requests ejecutarse
- simular fallo y verificar journal pendiente
- ejecutar recovery y verificar undo
- cambiar de admin a user-1 y verificar restricciones

## Nota final

La GUI no necesita inventar logica de negocio. La mayor parte del trabajo pendiente es:

- capturar input del usuario
- llamar el metodo correcto del coordinator
- leer el snapshot actual
- renderizarlo bien en Swing

Si se respeta eso, la interfaz deberia salir bastante directo.
