def formatCheck = 'gerritforge:pull-replication-format-3852e64366bb37d13b8baf8af9b15cfd38eb9227'
def buildCheck = 'gerritforge:pull-replication-3852e64366bb37d13b8baf8af9b15cfd38eb9227'
pipeline {
    options { skipDefaultCheckout true }
    agent { label 'bazel-debian' }
    stages {
        stage('Checkout') {
            steps {
                sh "git clone -b ${env.GERRIT_BRANCH} https://gerrit.googlesource.com/plugins/pull-replication"
                sh "cd pull-replication && git fetch origin refs/changes/${BRANCH_NAME} && git config user.name jenkins && git config user.email jenkins@gerritforge.com && git merge FETCH_HEAD"
            }
        }
        stage('Formatting') {
            steps {
                gerritCheck (checks: ["${formatCheck}": 'RUNNING'], url: "${env.BUILD_URL}console")
                sh "find pull-replication -name '*.java' | xargs /home/jenkins/format/google-java-format-1.7 -i"
                script {
                    def formatOut = sh (script: 'cd pull-replication && git status --porcelain', returnStdout: true)
                    if (formatOut.trim()) {
                        def files = formatOut.split('\n').collect { it.split(' ').last() }
                        files.each { gerritComment path:it, message: 'Needs reformatting with GJF' }
                        gerritCheck (checks: ["${formatCheck}": 'FAILED'], url: "${env.BUILD_URL}console")
                    } else {
                        gerritCheck (checks: ["${formatCheck}": 'SUCCESSFUL'], url: "${env.BUILD_URL}console")
                    }
                }
            }
        }
        stage('build') {
             environment {
                 DOCKER_HOST = """${sh(
                     returnStdout: true,
                     script: "/sbin/ip route|awk '/default/ {print  \"tcp://\"\$3\":2375\"}'"
                 )}"""
            }
            steps {
                gerritCheck (checks: ["${buildCheck}": 'RUNNING'], url: "${env.BUILD_URL}console")
                sh 'git clone --recursive -b $GERRIT_BRANCH https://gerrit.googlesource.com/gerrit'
                dir ('gerrit') {
                    sh 'cd plugins && ln -s ../../pull-replication .'
                    sh 'bazelisk build plugins/pull-replication'
                    sh 'bazelisk test --test_env DOCKER_HOST=$DOCKER_HOST plugins/pull-replication/...'
                }
            }
        }
    }
    post {
        success {
          gerritCheck (checks: ["${buildCheck}": 'SUCCESSFUL'], url: "${env.BUILD_URL}console")
        }
        unstable {
          gerritCheck (checks: ["${buildCheck}": 'FAILED'], url: "${env.BUILD_URL}console")
        }
        failure {
          gerritCheck (checks: ["${buildCheck}": 'FAILED'], url: "${env.BUILD_URL}console")
        }
    }
}
