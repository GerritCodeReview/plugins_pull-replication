def pluginName = 'pull-replication'
def checkSHA1 = '3852e64366bb37d13b8baf8af9b15cfd38eb9227'
def formatCheck = "gerritforge:${pluginName}-format-${checkSHA1}"
def buildCheck = "gerritforge:${pluginName}-${checkSHA1}"
def pluginScmUrl = "https://gerrit.googlesource.com/plugins/${pluginName}"
def gjfVersion = "1.7"

pipeline {
    options { skipDefaultCheckout true }
    agent { label 'bazel-debian' }
    stages {
        stage('Checkout') {
            steps {
                sh "git clone -b ${env.GERRIT_BRANCH} ${pluginScmUrl}"
                sh "cd ${pluginName} && git fetch origin refs/changes/${BRANCH_NAME} && git config user.name jenkins && git config user.email jenkins@gerritforge.com && git merge FETCH_HEAD"
            }
        }
        stage('Formatting') {
            steps {
                gerritCheck (checks: ["${formatCheck}": 'RUNNING'], url: "${env.BUILD_URL}console")
                sh "find ${pluginName} -name '*.java' | xargs /home/jenkins/format/google-java-format-${gjfVersion} -i"
                script {
                    def formatOut = sh (script: "cd ${pluginName} && git status --porcelain", returnStdout: true)
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
                    sh "cd plugins && ln -s ../../${pluginName} ."
                    sh "bazelisk build plugins/${pluginName}"
                    sh "bazelisk test --test_env DOCKER_HOST=$$DOCKER_HOST plugins/${pluginName}/..."
                }
            }
        }
    }
    post {
        success {
          gerritCheck (checks: ["${buildCheck}": 'SUCCESSFUL'], url: "${env.BUILD_URL}console")
          gerritReview labels: [Verified: 1]
        }
        unstable {
          gerritCheck (checks: ["${buildCheck}": 'FAILED'], url: "${env.BUILD_URL}console")
          gerritReview labels: [Verified: -1]
        }
        failure {
          gerritCheck (checks: ["${buildCheck}": 'FAILED'], url: "${env.BUILD_URL}console")
          gerritReview labels: [Verified: -1]
        }
    }
}
