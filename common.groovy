/* This file contains variables and functions that can be shared across different jobs */
import groovy.transform.Field
@Field boolean build_ok = true

def get_day_of_week() {
    def date = new Date()
    Calendar calendar = Calendar.getInstance()
    calendar.setTime(date)
    return calendar.get(Calendar.DAY_OF_WEEK)
}

def get_portafiducia_download_path() {
    /* Stable Portafiducia tarball */
    return "s3://libfabric-ci-607256510020-us-west-2/portafiducia/portafiducia.tar.gz"
}

def download_and_extract_portafiducia(outputDir) {
    /* Download PortaFiducia tarball from S3 and extract to outputDir */
    def tempPath = "/tmp/portafiducia.tar.gz"
    def downloadPath = this.get_portafiducia_download_path()

    def ret = sh (
        script: "mkdir -p ${outputDir} && aws s3 cp ${downloadPath} ${tempPath} && " +
            "tar xf ${tempPath} -C ${outputDir}",
        returnStatus: true,
    )

    if (ret != 0) {
        unstable('Failed to download and extract PortaFiducia')
    }
}

def get_test_operating_systems() {
    return ["alinux2", "alinux2023", "centos7", "rhel7", "rhel8", "rhel9", "debian10", "debian11", "ubuntu2004", "ubuntu2204", "opensuse15", "sles15", "rockylinux8", "rockylinux9"]
}

def get_test_instance_types() {
    return [
        "c5n.18xlarge",
        "c6gn.16xlarge",
        "dl1.24xlarge",
        "p4d.24xlarge",
        "trn1.32xlarge",
        "g4dn.12xlarge",
        "hpc6a.48xlarge",
        "p5.48xlarge",
        ]
}

def get_hpc6a_os_list() {
    /* Rocky Linux 8, Debian 10/11, OpenSUSE 15 AMIs do not support hpc6a */
    return ["alinux2", "alinux2023", "centos7", "rhel7", "rhel8", "rhel9", "ubuntu2004", "ubuntu2204", "rockylinux9", "sles15"]
}

def get_c5n_os_list() {
    return ["alinux2", "rockylinux8", "debian10", "debian11", "opensuse15"]
}

def get_disable_cma_test_instance_type_list() {
    return ["hpc6a.48xlarge", "c6gn.16xlarge"]
}

/*
 * Current p4d ODCR in libfabric-ci-dev account is purchased on Linux/Unix platform,
 * which makes it not applicable to run RHEL platform OSes without BYOL.
 * Temporarily skip these two OSes until this issue is fixed.
 * Skip opensuse15 as it does not support p4d instance type.
 * Skip sles15 as it does not support out-of-tree kmod (efa kmod with gdr support)
 */
def get_p4d_os_list() {
    return ["alinux2", "alinux2023", "centos7", "ubuntu2004", "ubuntu2204", "rockylinux8", "rockylinux9", "debian10", "debian11"]
}

/* Make sure an OS is tested on either hpc6a or c5n */
assert get_test_operating_systems() - get_hpc6a_os_list() - get_c5n_os_list() == []

def get_instance_prefix(instance) {
    /*
     * Derive the prefix (i.e. family + generation + add'l capabilities)
     * from an EC2 instance type
     */
    return instance.split('\\.')[0]
}

def get_skip_kmod_test_operating_systems() {
    /*
     * Test efa installer with --skip-kmod to validate the
     * in-box efa kmod in the OSes.
     * sles15 does not allow out-of-tree kmod installation
     * by default, do not run skip kmod test against this
     * os explicitly.
     * debian default AMI kernel does not include efa.ko,
     * so we cannot skip kmod.
     */
    return ["alinux2", "ubuntu2004", "ubuntu2204", "opensuse15"]
}
def install_porta_fiducia() {
    /*
     * Install PortaFiducia in a (new) virtual environment.
     */
    sh '''
        python3 -m venv venv
        . venv/bin/activate
        pip install --upgrade pip
        pip install --upgrade awscli
        pip install -e PortaFiducia
    '''
}

def run_test_orchestrator_once(run_name, build_tag, os, instance_type, instance_count, region, test_config_file, addl_args) {
    /*
     * Run PortaFiducia/tests/test_orchestrator.py with given command line arguments
     * param@ args: str, the command line arguments
     */
    def cluster_name = get_cluster_name(build_tag, os, instance_type)
    def args = "--config configs/${test_config_file} --os ${os} --instance-type ${instance_type} --instance-count ${instance_count} --region ${region} --cluster-name ${cluster_name} ${addl_args} --junit-xml outputs/${cluster_name}.xml"
    def ret = sh (
                    script: ". venv/bin/activate; cd PortaFiducia/tests && ./test_orchestrator.py ${args}",
                    returnStatus: true
                  )
    if (ret == 65)
        unstable('Scripts exited with status 65')
    else if (ret != 0)
        build_ok = false
    catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
        sh "exit ${ret}"
    }
}

def get_random_string(len) {
    def s = sh (
        script: "cat /dev/urandom | LC_ALL=C tr -dc A-Za-z0-9 | head -c ${len}",
        returnStdout: true
    )
    return s
}

def get_cluster_name(build_tag, os, instance_type) {
    /*
     * Compose the cluster name. Pcluster requires a cluster name under 60 characters.
     * cluster name cannot have ".".
     * Jenkins does not allow groovy to use the replace() method
     * of string. Therefore we used shell command sed to replace "." with ""
     */
    build_tag = sh(
                        script: "echo ${build_tag} | sed \"s/^jenkins-//g\"",
                        returnStdout: true
                )

    def cluster_name = sh(
                        script: "echo '${build_tag.take(28)}-${os.take(10)}-${instance_type.take(10)}-'${get_random_string(8)} | tr -d '.\\n'",
                        returnStdout: true
                     )

    return cluster_name
}

def get_test_stage(stage_name, build_tag, os, instance_type, instance_count, region, test_config, addl_args) {
    /*
     * Generate a single test stage that run test_orchestrator.py with the given parameters.
     * param@ stage_name: the name of the stage
     * param@ build_tag: the BUILD_TAG env generated by Jenkins
     * param@ os: the operating system for the test stage.
     * param@ instance_type: the instance type for the test stage.
     * param@ instance_count: number of intances to use
     * param@ region: the (default) aws region where the tests are run.
     * param@ test_config: the name of test config file in PortaFiducia/tests/configs/
     * param@ addl_args: additional arguments passed to test_orchestrator.py
     * return@: the test stage.
     */
    return {
        stage("${stage_name}") {
            this.run_test_orchestrator_once(stage_name, build_tag, os, instance_type, instance_count, region, test_config, addl_args)
        }
    }
}

def get_test_stage_with_multi_os(stage_name, build_tag, oses, instance_type, instance_count, region, test_config, lock_label = null, lock_count = null, addl_args = "") {
    /*
     * Generate a single test stage with multiple sub-stages that test multiple OSes sequentially
     * param@ stage_name: the name of the stage
     * param@ build_tag: the BUILD_TAG env generated by Jenkins
     * param@ oses: a list of operating system for the test stage.
     * param@ instance_type: the instance type for the test stage.
     * param@ instance_count: number of intances to use
     * param@ region: the (default) aws region where the tests are run.
     * param@ test_config: the name of test config file in PortaFiducia/tests/configs/
     * param@ lock_label: Optional lockable resource label.
     * param@ lock_count: Optional lockable resource count.
     * param@ addl_args: Optional additional arguments passed to test_orchestrator.py
     * return@: the test stage.
     */
    return {
        stage("${stage_name}") {
            for (int i = 0; i < oses.size(); ++i) {
                def os = oses[i]
                stage("${os}") {
                    if (lock_label != null) {
                        lock(label: lock_label, quantity: lock_count) {
                            this.run_test_orchestrator_once(stage_name, build_tag, os, instance_type, instance_count, region, test_config, addl_args)
                        }
                    } else {
                        this.run_test_orchestrator_once(stage_name, build_tag, os, instance_type, instance_count, region, test_config, addl_args)
                    }
                }
            }
        }
    }
}

def get_test_stage_with_lock(stage_name, build_tag, os, instance_type, region, test_config, lock_label, lock_count, addl_args) {
    /*
     * Generate a single test stage that run test_orchestrator.py with the given parameters.
     * The job will queue until it acquires the given number of locks. The locks will be released
     * after the job finishes.
     * param@ stage_name: the name of the stage
     * param@ build_tag: the BUILD_TAG env generated by Jenkins
     * param@ os: the operating system for the test stage.
     * param@ instance_type: the instance type for the test stage.
     * param@ region: the (default) aws region where the tests are run.
     * param@ test_config: the name of test config file in PortaFiducia/tests/configs/
     * param@ lock_label: str, the label of the lockable resources.
     * param@ lock_count: int, the quantity of the lockable resources.
     * param@ addl_args: additional arguments passed to test_orchestrator.py
     * return@: the test stage.
     */
    return {
        stage("${stage_name}") {
            lock(label: lock_label, quantity: lock_count) {
                this.run_test_orchestrator_once(stage_name, build_tag, os, instance_type, lock_count, region, test_config, addl_args)
            }
        }
    }
}
def get_test_stages_on_supported_platforms(build_tag, build_id, region, test_config, addl_args = "") {
    /*
     * Generate a series of test stages that run test_orchestrator.py on supported platforms
     * with different operating systems and instance types.
     * param@ build_tag: the BUILD_TAG env generated by Jenkins
     * param@ build_id: the BUILD_ID env generated by Jenkins and converted to an integer
     * param@ region: the (default) aws region where the tests are run.
     * param@ test_config: the name of test config file in PortaFiducia/tests/configs/
     * return@: the map of the test stages.
     */
    def test_operating_systems = this.get_test_operating_systems()
    def test_instance_types = this.get_test_instance_types()
    def test_instance_count = 2
    def stages = [:]
    for (instance_type in test_instance_types) {
        for (os in test_operating_systems) {
            def stage_name = "${os}-${instance_type}"
            if (instance_type == "c6gn.16xlarge") {
                /*
                 * EFA kernel module with ARM architecture is not shipped in the installer for CentOS/RHEL 7,
                 * which makes it not applicable to run libfabric's efa provider test on these OSes.
                 * opensuse15 ami currently does not support non-x86 instance type
                 */
                if (os == "centos7" || os == "rhel7" || os == "opensuse15") {
                    continue
                }
            }
            if (instance_type == "p4d.24xlarge") {
                if (!(os in get_p4d_os_list())) {
                    continue
                }

                /*
                 * Due to limited p4d capacity, we had to reduce the frequency of test on it.
                 * We test amazon linux 2 more frequently than other platforms
                 */
                int period = 7
                if (os == "alinux2") {
                    period = 1
                }

                if (os == "debian10" || os == "debian11") {
                    /* Known p4d customer */
                    period = 2
                }

                if (os == "ubuntu2004") {
                    /* Known p4d customer */
                    period = 3
                }

                if (build_id % period != 0)
                    continue
            }
            if (instance_type == "p5.48xlarge") {
                if (os != "alinux2" && os != "ubuntu2004") {
                    continue
                }
            }
            if (instance_type == "trn1.32xlarge") {
                int period = -1
                /* Neuron is supported on AL2, Ubuntu20 */
                if (os == "alinux2") {
                    period = 2
                }

                if (os == "ubuntu2004") {
                    period = 4
                }

                if (period < 0 || build_id % period != 0) {
                    continue
                }
            }
            if (instance_type == "g4dn.12xlarge" && os == "alinux2") {
                stages["al2_g4dn_libfabric_tcp"] = this.get_test_stage("al2_g4dn_libfabric_tcp", env.BUILD_TAG, os, instance_type, 3, "us-east-1", "libfabric_branch_nccl_tcp_test.yaml", "")
            }
            /*
             * Define temporary variables before passing them to the function
             * Otherwise the values passed to the function will be the last value of the for loop
             * See https://stackoverflow.com/questions/34283443/for-loop-works-different-in-groovy-and-java
             */
            def addl_args_tmp = addl_args
            def instance_type_tmp = instance_type
            def os_tmp = os
            def region_tmp = region
            def test_config_tmp = test_config
            /*
             * Due to the limitation of p4d capacity, all p4d tests must be running within the size of p4d ODCR (currently 16)
             * 16 lockable resources with label "p4d-2-16node" have been created in Jenkins's global config.
             * Each test stage will try to acquire 2 p4d locks as it will launch 2 p4d.
             */
            if (instance_type == "p4d.24xlarge") {
                addl_args_tmp = " --odcr cr-0a121f63d093b79d6 ${addl_args}"
                stages["${stage_name}"] = this.get_test_stage_with_lock(stage_name, build_tag, os_tmp, instance_type_tmp, "us-west-2", test_config_tmp, "p4d-2-16node", 2, addl_args_tmp)
                continue
            }
            else if (instance_type == "p5.48xlarge") {
                addl_args_tmp = " --odcr cr-0e05244cdb7cb9841 ${addl_args}"
                test_config_tmp = "libfabric_branch_p5_functional_test.yaml"
                stages["${stage_name}"] = this.get_test_stage_with_lock(stage_name, build_tag, os_tmp, instance_type_tmp, "us-east-2", test_config_tmp, "p5-1-8node", 2, addl_args_tmp)
                continue
            }
            else if (instance_type == "g4dn.12xlarge") {
                /* IAD currently has enough capacity for g4dn */
                region_tmp = "us-east-1"
            }
            else if (instance_type == "dl1.24xlarge") {
                /* Use Ubuntu 20.04 OS because it has newer kernel (>=5.15) that has required dmabuf support for peer direct on dl1 */
                if (os_tmp == "ubuntu2004") {
                    /* Run dl1 test with dedicated test config */
                    test_config_tmp = "libfabric_branch_dl1_functional_test.yaml"
                }
                else {
                    continue
                }
            }
            else if (instance_type == "trn1.32xlarge") {
                test_config_tmp = "libfabric_branch_function_test_neuron.yaml"
                region_tmp = "us-west-2"
            }
            else if (instance_type == "c5n.18xlarge") {
                if (!(os_tmp in get_c5n_os_list())) {
                    continue
                }
            }
            else if (instance_type == "hpc6a.48xlarge") {
                /* For frugality reasons, gradually migrate os from c5n to hpc6a */
                if (os_tmp in get_hpc6a_os_list()) {
                    /* Check hpc6a pool size before changing the region */
                    region_tmp = "eu-north-1"
                } else {
                    continue
                }
            }
            stages["${stage_name}"] = this.get_test_stage(stage_name, build_tag, os_tmp, instance_type_tmp, test_instance_count, region_tmp, test_config_tmp, addl_args_tmp)
            if (os_tmp == "ubuntu2004" && (instance_type in get_disable_cma_test_instance_type_list())) {
                /* Run test without CMA only for Ubuntu 20.04 on selected instance types */
                stages["${stage_name}-disable-cma"] = this.get_test_stage(stage_name, build_tag, os_tmp, instance_type_tmp, test_instance_count, region_tmp, test_config_tmp, "${addl_args_tmp} --enable-cma false")
            }
        }
    }
    return stages
}

def create_ompi_branch_test_stages(build_tag, region, test_config, arch, cluster_type, ompi_branch, addl_args) {
    /*
     * Generate the ompi branch test stages
     * param@ build_tag: the tag of the Jenkins build
     * param@ region: the (default) aws region where the tests are run.
     * param@ test_config: the name of test config file in PortaFiducia/tests/configs/
     * param@ arch: instance architecture, aarch64 or x86_64
     * param@ cluster_type: manual_cluster or parallel_cluster
     * param@ ompi_branch: Open MPI branch to test
     * param@ addl_args: additional arguments passed to test_orchestrator.py
     * return@: the map of the test stages.
     */
    def instance_count = 2
    def instance_type = "hpc6a.48xlarge"
    def stage_name = ""
    def stages = [: ]

    for (os in get_test_operating_systems()) {
        if (arch == "aarch64" && os in ["centos7", "rhel7"]) {
            /* No EFA support for this platform */
            continue
        }

        if (cluster_type == "parallel_cluster" && !os in ["alinux2", "centos7", "rhel7", "rhel8", "ubuntu2004", "ubuntu2204"]) {
            /* Parallel cluster does not support the OS */
            continue
        }

        /* Fallback to non-HPC instance types if the AMI does not support it */
        if (arch == "aarch64") {
            instance_type = "hpc7g.16xlarge"
            if (os in ["debian10", "debian11", "opensuse15", "rockylinux8", "rockylinux9"]) {
                instance_type = "c6gn.16xlarge"
            }
        } else if (os in ["debian10", "debian11", "opensuse15", "rockylinux8"]) {
            instance_type = "c5n.18xlarge"
        }

        stage_name = "${os}-${instance_type}"

        stages[stage_name] = this.get_test_stage(stage_name, build_tag, os, instance_type, instance_count, region, test_config, "--test-openmpi-branch ${ompi_branch} --cluster-type ${cluster_type} ${addl_args}")
    }

    return stages
}

def get_prod_canary_test_types() {
    return ["default", "skip-kmod", "minimal", "upgrade-prev1", "upgrade-prev2"]
}

def get_prod_canary_test_stages(build_tag, build_id, region, test_config, test_type, test_installer_url) {
    /*
     * Generate the test stages in prod canary for different test type.
     * param@ build_tag: the BUILD_TAG env generated by Jenkins
     * param@ build_id: the BUILD_ID env generated by Jenkins and converted to an integer
     * param@ region: the (default) aws region where the tests are run.
     * param@ test_config: the name of test config file in PortaFiducia/tests/configs/
     * param@ test_type: the type of the test, expected values are default, skip-kmod,
     * minimal, upgrade-prev1, upgrade-prev2.
     * return@: the map of the test stages.
     */

    def test_operating_systems = this.get_test_operating_systems()
    if (test_type == "skip-kmod") {
        test_operating_systems = this.get_skip_kmod_test_operating_systems()
    }
    int test_instance_count = 2
    if (test_type == "upgrade-prev1" || test_type == "upgrade-prev2") {
        test_instance_count = 1
    }
    def test_instance_types = this.get_test_instance_types()
    def stages = [:]
    def addl_args = "--test-type ${test_type} --test-efa-installer-url ${test_installer_url}"
    for (instance_type in test_instance_types) {
        for (os in test_operating_systems) {
            if (instance_type == "g4dn.12xlarge" && test_type == "default" && os == "alinux2") {
                stages["al2_g4dn_libfabric_tcp"] = this.get_test_stage("al2_g4dn_libfabric_tcp", env.BUILD_TAG, os, instance_type, 3, "us-east-1", "efa_installer_nccl_tcp_test.yaml", "")
            }
            /*
             * Define temporary variables inside the loop for "os" and "instance_type" before passing them to the function
             * Otherwise the values passed to the function will be the last value of the for loop
             * See https://stackoverflow.com/questions/34283443/for-loop-works-different-in-groovy-and-java
             */
            def os_tmp = os
            def instance_type_tmp = instance_type
            def addl_args_tmp = addl_args
            def test_config_tmp = test_config
            def region_tmp = region

            if (instance_type == "c6gn.16xlarge") {
                /*
                 * opensuse15 ami currently does not support non-x86 instance type
                 * skip rhel7 arm testing assuming it has the same functionality as centos7
                 */
                if (os == "opensuse15" || os == "rhel7") {
                    continue
                }
                /*
                 * The goal of minimal test_type is for testing Intel MPI, which does not
                 * support ARM platform yet.
                 */
                if (test_type == "minimal") {
                    continue
                }
                /*
                 * CentOS/RHEL 7 is not supported by Graviton-2 and newer platforms.
                 * Run efa installer test on c6g.16xlarge without efa.
                 * The goal of this test is only to validate the installer installation/upgrade,
                 * and Open MPI's functionality with tcp.
                 */
                if (os == "centos7") {
                    instance_type_tmp = "c6g.16xlarge"
                    addl_args_tmp = "${addl_args_tmp} --enable-efa=False"
                    /* Run in IAD to mitigate ICE issue */
                    region_tmp = "us-east-1"
                }
            }
            if (instance_type == "dl1.24xlarge") {
                /* Use Ubuntu 20.04 OS because it has newer kernel (>=5.15) that has required dmabuf support for peer direct on dl1 */
                if (os_tmp == "ubuntu2004" && test_type == "default") {
                    /* Run dl1 test with dedicated test config */
                    test_config_tmp = "efa_installer_dl1_test.yaml"
                }
                else {
                    continue
                }
            }
            if (instance_type == "p4d.24xlarge") {
                if (!(os in get_p4d_os_list())) {
                    continue
                }
                /*
                 * The goal of minimal test_type is for testing Intel MPI, which is not a typical use case
                 * on p4d platform
                 */
                if (test_type == "minimal") {
                    continue
                }
                /*
                 * The OS in-box efa kmod does not support gdr functionality
                 */
                if (test_type == "skip-kmod") {
                    continue
                }
                /*
                 * The upgrade path has been validated for the same OS/arch
                 * on non-GPU instances. It is not necessary to run it on p4d again.
                 */
                if (test_type == "upgrade-prev1" || test_type == "upgrade-prev2") {
                    continue
                }
                /*
                 * Due to limited p4d capacity, we had to reduce the frequency of test on it.
                 * We test amazon linux 2 more frequently than other platforms
                 */
                int period = 7
                if (os == "alinux2") {
                    period = 1
                }

                if (os == "debian10" || os == "debian11") {
                    /* Known p4d customer */
                    period = 2
                }

                if (os == "ubuntu2004") {
                    /* Known p4d customer */
                    period = 3
                }

                if (build_id % period != 0)
                    continue
            }
            if (instance_type == "p5.48xlarge") {
                if (test_type != "default") {
                    continue
                }
                if (os != "alinux2" && os != "ubuntu2004") {
                    continue
                }
                test_config_tmp = "efa_installer_p5_test.yaml"
            }
            if (instance_type == "trn1.32xlarge") {
                test_config_tmp = "efa_installer_functional_test_neuron.yaml"

                if (test_type != "default") {
                    continue
                }

                int period = -1
                /* Neuron is supported on AL2, Ubuntu20 */
                if (os == "alinux2") {
                    period = 2
                }

                if (os == "ubuntu2004") {
                    period = 7
                }

                if (period < 0 || build_id % period != 0) {
                    continue
                }
            }
            if (instance_type == "c5n.18xlarge") {
                if (!(os_tmp in get_c5n_os_list())) {
                    continue
                }
            }
            if (instance_type == "hpc6a.48xlarge") {
                /* For frugality reasons, gradually migrate os from c5n to hpc6a */
                if (os_tmp in get_hpc6a_os_list()) {
                    /* Check hpc6a pool size before changing the region */
                    region_tmp = "eu-north-1"
                } else {
                    continue
                }
            }

            def stage_name = "${os_tmp}-${test_type}-${instance_type_tmp}"
            /*
             * Due to the limitation of p4d capacity, all p4d tests must be running within the size of p4d ODCR (currently 4)
             * 4 lockable resources with label "p4d-1-4node" have been created in Jenkins's global config.
             * Each test stage will try to acquire 2 p4d locks as it will launch 2 p4d.
             */
            if (instance_type == "p4d.24xlarge") {
                addl_args_tmp = "${addl_args_tmp} --odcr cr-0e5eebb3c896f6af0"
                stages["${stage_name}"] = this.get_test_stage_with_lock(stage_name, build_tag, os_tmp, instance_type_tmp, "us-east-2", test_config_tmp, "p4d-1-4node", test_instance_count, addl_args_tmp)
            }
            else if (instance_type == "p5.48xlarge") {
                addl_args_tmp = "${addl_args_tmp} --odcr cr-0e05244cdb7cb9841"
                stages["${stage_name}"] = this.get_test_stage_with_lock(stage_name, build_tag, os_tmp, instance_type_tmp, "us-east-2", test_config_tmp, "p5-1-8node", test_instance_count, addl_args_tmp)
            }
            else if (instance_type == "trn1.32xlarge") {
                stages["${stage_name}"] = this.get_test_stage(stage_name, build_tag, os_tmp, instance_type_tmp, test_instance_count, "us-west-2", test_config_tmp, addl_args_tmp)
            }
            else {
                stages["${stage_name}"] = this.get_test_stage(stage_name, build_tag, os_tmp, instance_type_tmp, test_instance_count, region_tmp, test_config_tmp, addl_args_tmp)
                if (os_tmp == "ubuntu2004" && (instance_type in get_disable_cma_test_instance_type_list())) {
                    /* Run test without CMA only for Ubuntu 20.04 on selected instance types */
                    stages["${stage_name}-disable-cma"] = this.get_test_stage(stage_name, build_tag, os_tmp, instance_type_tmp, test_instance_count, region_tmp, test_config_tmp, "${addl_args_tmp} --enable-cma false")
                }
            }
        }
    }
    return stages
}
return this

