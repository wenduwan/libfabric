pipeline {
    agent {
        ecs {
            inheritFrom 'fargate-large'
        }
    }
    triggers {
        cron('H 0 * * *')
    }
    options {
        buildDiscarder(logRotator(daysToKeepStr: "90"))
        timeout(time: 8, unit: 'HOURS')
    }
    environment {
        // AWS region where the cluster is created
        REGION="us-west-2"
    }
    stages {
        // Cleanup workspace before job start.
        stage("Clean up workspace") {
            steps{
                deleteDir()
            }
        }
        stage("Checkout SCM repo") {
            steps {
                checkout scm
            }
        }
        stage("Download and extract PortaFiducia") {
            steps {
                script {
                    sh 'printenv'
                    def common = load "common.groovy"
                    common.download_and_extract_portafiducia('PortaFiducia')
                }
            }
        }
        stage("Install PortaFiducia") {
            steps {
                script {
                    def common = load "common.groovy"
                    common.install_porta_fiducia()
                }

            }
        }
        stage("Test EFA provider") {
            steps {
                script {
                    def common = load "common.groovy"
                    def stages = [:]
                    def addl_args = ""
                    stages["1_g4dn_al2"] = common.get_test_stage("1_g4dn_al2", env.BUILD_TAG, "alinux2", "g4dn.8xlarge", 1, "us-east-1", "libfabric_pr_test.yaml", "")
                    parallel stages
                }
            }
        }
        stage('check build_ok') {
            steps {
                script {
                    def common = load "common.groovy"
                    if (common.build_ok) {
                        currentBuild.result = "SUCCESS"
                    }
                    else {
                        currentBuild.result = "FAILURE"
                    }
                }
            }
        }
    }
    post {
        always {
            sh 'find PortaFiducia/tests/outputs -name "*.xml" | xargs du -shc'
            junit testResults: 'PortaFiducia/tests/outputs/**/*.xml', keepLongStdio: false
            archiveArtifacts artifacts: 'PortaFiducia/tests/outputs/**/*.*'
        }
        failure {
            sh '''
            echo "FAILURE!!!! BAD BAD BAD!!!!!
            '''
        }
        aborted {
            sh '. venv/bin/activate; ./PortaFiducia/scripts/delete_manual_cluster.py --cluster-name "$BUILD_TAG"\'*\' --region $REGION'
        }
        // Cleanup workspace after job completes.
        cleanup {
            deleteDir()
        }
    }
}
