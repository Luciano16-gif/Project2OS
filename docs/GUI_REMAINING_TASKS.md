# Pendientes de la Implementación GUI

Esta es la hoja de ruta actualizada después de haber construido la base de la interfaz (MainFrame, Paneles, Controller y Tema Oscuro).

## 1. Acciones de Archivo y Sistema
- [x] **Guardar/Cargar**: Implementar diálogos `JFileChooser` para `saveSystem` y `loadSystem`.
- [x] **Escenarios**: Carga de archivos JSON de escenario (`loadScenario`).
- [x] **Estadísticas**: Un diálogo que resuma: bloques usados, seek total, procesos completados y tiempo de simulación.

## 2. Control de Reproducción (Playback)
- [x] **Play/Pause**: Vincular los botones ▶/⏸ para pausar el refresco visual.
- [x] **Control de Velocidad**: Una opción para ajustar el retardo de actualización en tiempo real.

## 3. Mejoras Visuales y de UX
- [ ] **Iconos**: Carpetas y archivos reales en el `JTree`.
- [ ] **Persistencia del Árbol**: Evitar el colapso de nodos al refrescar el snapshot.
- [ ] **Detalles de Bloque**: Tooltips con formato HTML para legibilidad de bloques ocupados.

## 4. Estabilidad y Permisos
- [ ] **Manejo de Errores**: Envolver todas las llamadas al coordinador en `try-catch` para evitar crasheos de la UI.
- [ ] **Validación de Roles**: Habilitar/Deshabilitar botones de CRUD basándose en si la sesión actual es Admin o Usuario Regular.

## 5. Pruebas Finales
- [ ] **Simulación de Fallo**: Verificar que el rollback (recovery) restaure el estado del disco.
- [ ] **Políticas de Disco**: Asegurar que las políticas (SCAN, SSTF, etc.) se reflejen visualmente en el orden de despacho.
