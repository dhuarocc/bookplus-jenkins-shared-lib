/**
 * Notificación del resultado del pipeline a Slack (y email en fallos). Tolerante: si el
 * plugin/credencial no está configurado, no rompe el build, solo lo registra en el log.
 */
def call(String status) {
    String color = (status == 'SUCCESS') ? 'good' : (status == 'UNSTABLE' ? 'warning' : 'danger')
    String msg   = "BookPlus #${env.BUILD_NUMBER} [${env.JOB_NAME}] → ${status} (${env.SHORT_SHA ?: 'n/a'})"

    try {
        slackSend(color: color, message: msg)
    } catch (ignored) {
        echo "Slack no configurado — ${msg}"
    }

    if (status == 'FAILURE') {
        try {
            emailext(subject: "❌ ${env.JOB_NAME} #${env.BUILD_NUMBER}", body: msg, to: 'devops@bookplus.com')
        } catch (ignored) {
            echo 'emailext no configurado'
        }
    }
}
