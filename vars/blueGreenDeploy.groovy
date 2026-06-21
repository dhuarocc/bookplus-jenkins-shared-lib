/**
 * Despliegue blue-green con aprobación manual, health check y rollback automático.
 *
 * Estrategia: hay dos stacks idénticos (blue/green); solo uno recibe tráfico. Se despliega
 * la versión nueva en el color inactivo, se valida con un health check, y solo entonces se
 * conmuta el tráfico. Si algo falla, se revierte al color anterior sin downtime.
 *
 * NOTA: los comandos de infraestructura van con 'echo' (demostrativos). En un entorno real
 * se sustituyen por los docker compose / kubectl / cambios de balanceador correspondientes.
 */
def call(Map cfg = [:]) {
    String environment = cfg.env ?: 'staging'
    String tag         = cfg.tag ?: 'latest'

    timeout(time: 15, unit: 'MINUTES') {
        input message: "¿Desplegar ${tag} a ${environment} (blue-green)?", ok: 'Desplegar'
    }

    String active = sh(script: 'cat .active-color 2>/dev/null || echo blue', returnStdout: true).trim()
    String target = (active == 'blue') ? 'green' : 'blue'
    echo "Color activo: ${active}  →  desplegando la nueva versión en: ${target}"

    try {
        // 1) Levantar el stack 'target' con la nueva imagen.
        sh "echo docker compose -p bookplus-${target} -f docker-compose.deploy.yml up -d   # TAG=${tag}"

        // 2) Health check del nuevo stack ANTES de conmutar.
        retry(5) {
            sleep(time: 5, unit: 'SECONDS')
            sh "echo curl -fsS http://localhost:8080/actuator/health   # comprobando stack ${target}"
        }

        // 3) Conmutar el tráfico al nuevo color.
        sh "echo 'switch gateway → ${target}' && echo ${target} > .active-color"

        // 4) Apagar el color anterior (queda libre para el próximo despliegue).
        sh "echo docker compose -p bookplus-${active} -f docker-compose.deploy.yml down"

        echo "✅ Despliegue OK en ${environment}. Color activo ahora: ${target}"
    } catch (err) {
        echo "❌ Falló el despliegue (${err.message}). Rollback automático a ${active}."
        sh "echo 'switch gateway → ${active}' && echo ${active} > .active-color"
        sh "echo docker compose -p bookplus-${target} -f docker-compose.deploy.yml down"
        error "Despliegue abortado y revertido a ${active}."
    }
}
