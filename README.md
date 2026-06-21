# bookplus-jenkins-shared-lib

Shared Library de Jenkins para el monorepo **BookPlus**. Contiene el pipeline CI/CD
reutilizable, de modo que el `Jenkinsfile` de cada repositorio queda en unas pocas líneas.

## Estructura

- `vars/bookplusPipeline.groovy` — pipeline declarativo completo (`bookplusPipeline(...)`).
- `vars/notify.groovy` — notificaciones a Slack/email.
- `vars/blueGreenDeploy.groovy` — despliegue blue-green con health check y rollback.
- `src/com/bookplus/ci/Services.groovy` — lista central de microservicios.

## Uso desde un Jenkinsfile

```groovy
@Library('bookplus-shared-lib@main') _
bookplusPipeline(registry: 'ghcr.io/dhuarocc')
```

## Configuración en Jenkins

Se registra como **Global Pipeline Library** (Manage Jenkins → System → Global Pipeline
Libraries) o por **JCasC** (`unclassified.globalLibraries`), con nombre `bookplus-shared-lib`
apuntando a este repositorio.
