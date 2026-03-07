# NOTAS

Notas internas del proyecto.

## Git workflow (lo que pide el profesor)
Lo que piden no es complicado: es basicamente un flujo tipo "equipo real".

### Ramas
- `main`: siempre estable (lo que se entrega).
- `develop`: integracion diaria.
- `feat/...`, `fix/...`, `docs/...`: ramas de trabajo para PRs.

### Como trabajamos (regla simple)
1. Cada tarea vive en una rama propia (ej: `feat/scheduler-edf`).
2. Se abre PR hacia `develop`.
3. Cuando `develop` esta estable, se hace PR de `develop` hacia `main`.

### Comandos tipicos
Crear `develop` (una vez):
```bash
git checkout -b develop
git push -u origin develop
```

Crear rama por feature:
```bash
git checkout develop
git pull
git checkout -b feat/gui-queues
git push -u origin feat/gui-queues
```

Eliminar rama local:
```bash
git branch -d nombre-rama
```

Eliminar rama remota:
```bash
git push origin --delete nombre-rama
```

### Commits
- Mensajes descriptivos: `feat: ...`, `fix: ...`, `docs: ...`
- Evitar commits gigantes; preferir pequenos y frecuentes.

### Issues
- Registrar issues cuando falte una funcionalidad, aparezca un bug o surja una duda de implementacion relevante.

## Preguntas abiertas / Dudas

## Nota de mantenimiento
Actualizar este archivo y `docs/SPEC.md` al cerrar PRs importantes.
