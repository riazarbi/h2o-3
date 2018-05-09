H2O_HADOOP_STARTUP_MODE_HADOOP='ON_HADOOP'
H2O_HADOOP_STARTUP_MODE_STANDALONE='STANDALONE'

def call(final pipelineContext, final stageConfig) {

    stageConfig.image = pipelineContext.getBuildConfig().getSmokeHadoopImage(stageConfig.customData.distribution, stageConfig.customData.version)
    withCredentials([usernamePassword(credentialsId: 'ldap-credentials', usernameVariable: 'LDAP_USERNAME', passwordVariable: 'LDAP_PASSWORD')]) {

        stageConfig.customBuildAction = """
            echo "Activating Python ${stageConfig.pythonVersion}"
            . /envs/h2o_env_python${stageConfig.pythonVersion}/bin/activate
        
            echo 'Initializing Hadoop environment...'
            sudo -E /usr/sbin/startup.sh
            
            echo 'Starting H2O on Hadoop'
            ${getH2OStartupCmd(stageConfig)}
            if [ -z \${CLOUD_IP} ]; then
                echo "CLOUD_IP must be set"
                exit 1
            fi
            if [ -z \${CLOUD_PORT} ]; then
                echo "CLOUD_PORT must be set"
                exit 1
            fi
            echo "Cloud IP:PORT ----> \$CLOUD_IP:\$CLOUD_PORT"
            
            echo "Running Make"
            make -f ${pipelineContext.getBuildConfig().MAKEFILE_PATH} test-hadoop-smoke
        """

        def defaultStage = load('h2o-3/scripts/jenkins/groovy/defaultStage.groovy')
        defaultStage(pipelineContext, stageConfig)
    }
}

/**
 * Returns the cmd used to start H2O in given mode (on Hadoop or standalone). The cmd <strong>must</strong> export
 * the CLOUD_IP and CLOUT_PORT env variables (they are checked afterwards).
 * @param stageConfig stage configuration to read mode and additional information from
 * @return the cmd used to start H2O in given mode
 */
private def getH2OStartupCmd(final stageConfig) {
    switch (stageConfig.customData.mode) {
        case H2O_HADOOP_STARTUP_MODE_HADOOP:
            return """
                hadoop jar h2o-hadoop/h2o-${stageConfig.customData.distribution}${stageConfig.customData.version}-assembly/build/libs/h2odriver.jar -libjars "\$(cat /opt/hive-jars/hive-libjars)" -n 1 -mapperXmx 2g -baseport 54445 -notify h2o_one_node -ea -disown -login_conf ${stageConfig.customData.ldapConfigPath} -ldap_login
                IFS=":" read CLOUD_IP CLOUD_PORT < h2o_one_node
                export CLOUD_IP=\$CLOUD_IP
                export CLOUD_PORT=\$CLOUD_PORT
            """
        case H2O_HADOOP_STARTUP_MODE_STANDALONE:
            return """
                java -cp build/h2o.jar:\$(cat /opt/hive-jars/hive-libjars | tr ',' ':') water.H2OApp -port 54321 >standalone_h2o.log 2>&1 &
                export CLOUD_IP=localhost
                export CLOUD_PORT=54321
            """
        default:
            error("Startup mode ${stageConfig.customData.mode} for H2O with Hadoop is not supported")
    }
}

return this
