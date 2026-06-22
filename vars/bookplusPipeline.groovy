import com.bookplus.ci.Services

/**
 * Pipeline reutilizable de BookPlus (Shared Library). El Jenkinsfile del repo solo invoca
 * esta función, así toda la lógica de CI/CD vive versionada en un único sitio reutilizable
 * por cualquier proyecto/equipo. Es la forma en que los equipos senior evitan copiar
 * Jenkinsfiles de 200 líneas en cada repo.
 *
 * Uso (Jenkinsfile):
 *   @Library('bookplus-shared-lib@main') _
 *   bookplusPipeline(registry: 'ghcr.io/dhuarocc')
 */
def call(Map cfg = [:]) {
    String registry   = cfg.registry   ?: 'ghcr.io/dhuarocc'
    String mavenImage = cfg.mavenImage ?: 'maven:3.9-eclipse-temurin-21'
    List<String> services = (cfg.services ?: Services.ALL) as List<String>
    String ghcrCreds = cfg.ghcrCredentialsId ?: 'ghcr'   // 'github-app' para tokens efímeros (pro)
    String sonarOrg  = cfg.sonarOrganization ?: 'dhuarocc'
    // El Quality Gate bloqueante necesita que SonarCloud llame de vuelta a Jenkins por webhook.
    // En Jenkins local (no alcanzable desde internet) se omite la espera: el análisis igual se
    // sube a SonarCloud. En producción (Jenkins con URL pública) pásalo a true.
    boolean waitGate = cfg.waitForQualityGate != null ? cfg.waitForQualityGate : false

    pipeline {
        agent none

        parameters {
            booleanParam(name: 'RUN_SONAR',   defaultValue: true, description: 'Análisis + Quality Gate de SonarCloud')
            booleanParam(name: 'PUSH_IMAGES', defaultValue: true, description: 'Construir y publicar imágenes en GHCR')
            choice(name: 'DEPLOY_ENV', choices: ['none', 'staging', 'production'], description: 'Entorno de despliegue')
        }

        options {
            timestamps()
            ansiColor('xterm')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '20'))
            timeout(time: 60, unit: 'MINUTES')
            skipDefaultCheckout(true)
        }

        environment { REGISTRY = "${registry}" }

        stages {
            stage('Checkout') {
                agent any
                steps {
                    checkout scm
                    script { env.SHORT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim() }
                    stash name: 'source', useDefaultExcludes: false
                }
            }

            stage('Build & Test') {
                agent { docker { image mavenImage; args '-v $HOME/.m2:/root/.m2' } }
                steps {
                    unstash 'source'
                    script {
                        parallel services.collectEntries { s -> ["test:${s}", { sh "cd ${s} && mvn -B -q test" }] }
                    }
                }
                post { always { junit testResults: '**/target/surefire-reports/*.xml', allowEmptyResults: true } }
            }

            stage('SonarCloud + Quality Gate') {
                when { expression { params.RUN_SONAR } }
                agent { docker { image mavenImage; args '-v $HOME/.m2:/root/.m2' } }
                steps {
                    unstash 'source'
                    withSonarQubeEnv('SonarCloud') {
                        sh "cd book-plus-order-service && mvn -B -q verify sonar:sonar -Dsonar.organization=${sonarOrg}"
                    }
                    script {
                        if (waitGate) {
                            timeout(time: 10, unit: 'MINUTES') { waitForQualityGate abortPipeline: true }
                        } else {
                            echo 'Quality Gate: análisis subido a SonarCloud. Espera de gate omitida (Jenkins local sin webhook público). Actívala con waitForQualityGate:true en producción.'
                        }
                    }
                }
            }

            stage('Package') {
                agent { docker { image mavenImage; args '-v $HOME/.m2:/root/.m2' } }
                steps {
                    unstash 'source'
                    sh "set -e; for s in ${services.join(' ')}; do (cd \"\$s\" && mvn -B -q -DskipTests package); done"
                    // build-once: guardamos los JAR para reusarlos al construir las imágenes
                    stash name: 'jars', includes: '**/target/*.jar'
                }
            }

            stage('Build & Push images') {
                when { expression { params.PUSH_IMAGES } }
                agent any
                steps {
                    unstash 'source'
                    unstash 'jars'    // JAR ya construidos en Package (no se recompila)
                    withCredentials([usernamePassword(credentialsId: ghcrCreds, usernameVariable: 'U', passwordVariable: 'P')]) {
                        script {
                            sh 'echo "$P" | docker login ghcr.io -u "$U" --password-stdin'
                            parallel services.collectEntries { s -> ["image:${s}", {
                                // Dockerfile.runtime solo copia el JAR -> build en segundos
                                sh "docker build -f Dockerfile.runtime -t ${registry}/${s}:${env.SHORT_SHA} ${s} && docker push ${registry}/${s}:${env.SHORT_SHA}"
                            }] }
                        }
                    }
                }
            }

            stage('Security scan (Trivy)') {
                when { expression { params.PUSH_IMAGES } }
                agent any
                steps {
                    script {
                        parallel services.collectEntries { s -> ["trivy:${s}", {
                            sh "docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:latest image --severity HIGH,CRITICAL --exit-code 0 ${registry}/${s}:${env.SHORT_SHA}"
                        }] }
                    }
                }
            }

            stage('Deploy (blue-green)') {
                when { expression { params.DEPLOY_ENV != 'none' } }
                agent any
                steps { blueGreenDeploy(env: params.DEPLOY_ENV, tag: env.SHORT_SHA) }
            }
        }

        post {
            success  { notify('SUCCESS') }
            unstable { notify('UNSTABLE') }
            failure  { notify('FAILURE') }
        }
    }
}
