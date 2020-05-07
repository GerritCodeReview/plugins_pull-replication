pipeline {
    options { skipDefaultCheckout true }
    agent { label 'bazel-debian' }
    stages {
        stage('Checkout') {
            steps {
                sh "git clone -b ${env.GERRIT_BRANCH} https://gerrit.googlesource.com/plugins/pull-replication"
                sh "cd pull-replication && git fetch origin refs/changes/${BRANCH_NAME} && git merge FETCH_HEAD"
            }
        }
        stage('Formatting') {
            steps {
                gerritCheck (checks: ['gerritforge:pull-replication-format-8b1e7fb8ce34448cc425': 'RUNNING'], url: "${env.BUILD_URL}console")
                sh "find pull-replication -name '*.java' | xargs /home/jenkins/format/google-java-format-1.7 -i"
                script {
                    def formatOut = sh (script: 'cd pull-replication && git status --porcelain', returnStdout: true)
                    if (formatOut.trim()) {
                        def files = formatOut.split('\n').collect { it.split(' ').last() }
                        files.each { gerritComment path:it, message: 'Needs reformatting with GJF' }
                        gerritCheck (checks: ['gerritforge:pull-replication-format-3852e64366bb37d13b8baf8af9b15cfd38eb9227': 'FAILED'], url: "${env.BUILD_URL}console")
                    } else {
                        gerritCheck (checks: ['gerritforge:pull-replication-format-3852e64366bb37d13b8baf8af9b15cfd38eb9227': 'SUCCESSFUL'], url: "${env.BUILD_URL}console")
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
                gerritCheck (checks: ['gerritforge:pull-replication-8b1e7fb8ce34448cc425': 'RUNNING'], url: "${env.BUILD_URL}console")
                sh 'git clone --recursive -b $GERRIT_BRANCH https://gerrit.googlesource.com/gerrit'
                dir ('gerrit') {
                    sh 'bazelisk build plugins/pull-replication'
                    sh 'bazelisk test --test_env DOCKER_HOST=$DOCKER_HOST plugins/pull-replication/...'
                }
            }
        }
    }
    post {
        success {
          gerritCheck (checks: ['gerritforge:pull-replication-3852e64366bb37d13b8baf8af9b15cfd38eb9227': 'SUCCESSFUL'], url: "${env.BUILD_URL}console")
        }
        unstable {
          gerritCheck (checks: ['gerritforge:pull-replication-3852e64366bb37d13b8baf8af9b15cfd38eb9227': 'FAILED'], url: "${env.BUILD_URL}console")
        }
        failure {
          gerritCheck (checks: ['gerritforge:pull-replication-3852e64366bb37d13b8baf8af9b15cfd38eb9227': 'FAILED'], url: "${env.BUILD_URL}console")
        }
    }
}
