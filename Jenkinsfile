pipeline {
    agent none

    options {
        timestamps()
        disableConcurrentBuilds()
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    stages {
        stage('Build matrix') {
            matrix {
                axes {
                    axis {
                        name 'JDK_VERSION'
                        values '21', '25'
                    }
                }

                agent { label 'linux' }
                tools {
                    jdk "${JDK_VERSION}"
                    maven '3.9.14'
                }

                environment {
                    MAVEN_SETTINGS = credentials('nexus-maven-settings-file')
                }

                stages {
                    stage('Checkout') {
                        steps {
                            checkout scm
                        }
                    }

                    stage('Build and verify') {
                        steps {
                            sh 'mvn --batch-mode -s "$MAVEN_SETTINGS" -DforkCount=1C clean verify'
                        }
                        post {
                            always {
                                junit allowEmptyResults: true,
                                      testResults: '**/target/surefire-reports/*.xml'
                            }
                        }
                    }

                    stage('Upload artifact') {
                        steps {
                            sh """
                                mkdir -p output
                                cp target/jenkinsaisynapse.hpi output/jenkinsaisynapse-jvm-${JDK_VERSION}.hpi
                            """
                            archiveArtifacts artifacts: "output/jenkinsaisynapse-jvm-${JDK_VERSION}.hpi",
                                             fingerprint: true
                            sh 'rm -rf output'
                        }
                    }

                    stage('Deploy Snapshot') {
                        when {
                            allOf {
                                branch 'master'
                                not { buildingTag() }
                                environment name: 'JDK_VERSION', value: '25'
                            }
                        }
                        steps {
                            sh '''
                                mvn --batch-mode -s "$MAVEN_SETTINGS" \
                                    -Dchangelist="-BUILD-${BUILD_NUMBER}-SNAPSHOT" \
                                    -DskipTests \
                                    clean deploy
                            '''
                        }
                    }
                }
            }
        }

        stage('Execute Maven Release') {
            when {
                allOf {
                    branch 'master'
                    not { buildingTag() }
                    changelog '.*\\[release\\].*'
                }
            }
            agent { label 'linux' }
            tools {
                jdk '25'
                maven '3.9.14'
            }
            environment {
                MAVEN_SETTINGS = credentials('nexus-maven-settings-file')
            }
            steps {
                cleanWs()
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: 'master']],
                    userRemoteConfigs: scm.userRemoteConfigs,
                    extensions: [
                        [$class: 'LocalBranch', localBranch: 'master'],
                        [$class: 'CloneOption', noTags: false, shallow: false]
                    ]
                ])

                withCredentials([usernamePassword(credentialsId: 'ghpat_personal',
                                                  usernameVariable: 'GH_USER',
                                                  passwordVariable: 'GH_TOKEN')]) {
                    sh '''
                        set -eu

                        git config user.name "Jenkins CI"
                        git config user.email "jenkins-ci@ohmvir.dev"

                        # The release plugin pushes to the URL in <developerConnection>,
                        # not to "origin", so auth has to be attached to git itself.
                        ASKPASS="${WORKSPACE_TMP:-/tmp}/git-askpass.sh"
                        cat > "$ASKPASS" <<'SCRIPT'
#!/bin/sh
case "$1" in
  Username*) echo "$GH_USER" ;;
  Password*) echo "$GH_TOKEN" ;;
  *) exit 1 ;;
esac
SCRIPT
                        chmod 700 "$ASKPASS"
                        export GIT_ASKPASS="$ASKPASS"

                        git fetch --tags --prune --prune-tags origin

                        mvn --batch-mode release:clean release:prepare release:perform \
                            -s "$MAVEN_SETTINGS" \
                            -Dresume=false \
                            -DlocalCheckout=true \
                            -Darguments="-DskipTests -s $MAVEN_SETTINGS"
                    '''
                }
            }
            post {
                always {
                    sh 'rm -f "${WORKSPACE_TMP:-/tmp}/git-askpass.sh" || true'
                }
            }
        }
    }
}