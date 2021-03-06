/*
    To learn how to use this sample pipeline, follow the guide below and enter the
    corresponding values for your environment and for this repository:
    - https://github.com/ibm-cloud-architecture/refarch-cloudnative-devops-kubernetes
*/

// Environment
def clusterURL = env.CLUSTER_URL
def clusterAccountId = env.CLUSTER_ACCOUNT_ID
def clusterCredentialId = env.CLUSTER_CREDENTIAL_ID ?: "cluster-credentials"

// Pod Template
def podLabel = "auth"
def cloud = env.CLOUD ?: "kubernetes"
def registryCredsID = env.REGISTRY_CREDENTIALS ?: "registry-credentials-id"
def serviceAccount = env.SERVICE_ACCOUNT ?: "jenkins"

// Pod Environment Variables
def namespace = env.NAMESPACE ?: "default"
def registry = env.REGISTRY ?: "docker.io"
def imageName = env.IMAGE_NAME ?: "ibmcase/bluecompute-auth"
def imageTag = env.IMAGE_TAG ?: "latest"
def serviceLabels = env.SERVICE_LABELS ?: "app=auth,tier=backend" //,version=v1"
def microServiceName = env.MICROSERVICE_NAME ?: "auth"
def servicePort = env.MICROSERVICE_PORT ?: "8083"
def managementPort = env.MANAGEMENT_PORT ?: "8093"

// HS256_KEY Secret
def hs256Key = env.HS256_KEY

// URL for external inventory service
def customerURL = env.CUSTOMER_URL ?: "http://customer-customer:8082"

/*
  Optional Pod Environment Variables
 */
def helmHome = env.HELM_HOME ?: env.JENKINS_HOME + "/.helm"

podTemplate(label: podLabel, cloud: cloud, serviceAccount: serviceAccount, envVars: [
        envVar(key: 'CLUSTER_URL', value: clusterURL),
        envVar(key: 'CLUSTER_ACCOUNT_ID', value: clusterAccountId),
        envVar(key: 'NAMESPACE', value: namespace),
        envVar(key: 'REGISTRY', value: registry),
        envVar(key: 'IMAGE_NAME', value: imageName),
        envVar(key: 'IMAGE_TAG', value: imageTag),
        envVar(key: 'SERVICE_LABELS', value: serviceLabels),
        envVar(key: 'MICROSERVICE_NAME', value: microServiceName),
        envVar(key: 'MICROSERVICE_PORT', value: servicePort),
        envVar(key: 'MANAGEMENT_PORT', value: managementPort),
        envVar(key: 'HS256_KEY', value: hs256Key),
        envVar(key: 'CUSTOMER_URL', value: customerURL),
        envVar(key: 'HELM_HOME', value: helmHome)
    ],
    containers: [
        containerTemplate(name: 'kubernetes', image: 'ibmcase/jenkins-slave-utils:3.1.2', ttyEnabled: true, command: 'cat')
  ]) {

    node(podLabel) {
        checkout scm

        // Kubernetes
        container(name:'kubernetes', shell:'/bin/bash') {
            stage('Kubernetes - Deploy new Docker Image') {
                sh """
                #!/bin/bash

                # Get image
                if [ "${REGISTRY}" == "docker.io" ]; then
                    IMAGE=${IMAGE_NAME}:${IMAGE_TAG}
                else
                    IMAGE=${REGISTRY}/${NAMESPACE}/${IMAGE_NAME}:${IMAGE_TAG}
                fi

                # Get deployment
                DEPLOYMENT=`kubectl --namespace=${NAMESPACE} get deployments -l ${SERVICE_LABELS} -o name | head -n 1`

                # Check if deployment exists
                kubectl --namespace=${NAMESPACE} get \${DEPLOYMENT}

                if [ \${?} -ne "0" ]; then
                    echo 'No deployment to update'
                    exit 1
                fi

                # Update deployment
                kubectl --namespace=${NAMESPACE} set image \${DEPLOYMENT} ${MICROSERVICE_NAME}=\${IMAGE}
                """
            }
            stage('Kubernetes - Test') {
                sh """
                #!/bin/bash

                # Get deployment
                DEPLOYMENT=`kubectl --namespace=${NAMESPACE} get deployments -l ${SERVICE_LABELS} -o name | head -n 1`

                # Wait for deployment to be ready
                kubectl --namespace=${NAMESPACE} rollout status \${DEPLOYMENT}

                # Port forwarding & logs
                kubectl --namespace=${NAMESPACE} port-forward \${DEPLOYMENT} ${MICROSERVICE_PORT} ${MANAGEMENT_PORT} &
                kubectl --namespace=${NAMESPACE} logs -f \${DEPLOYMENT} &
                echo "Sleeping for 3 seconds while connection is established..."
                sleep 3

                # Let the application start
                bash scripts/health_check.sh "http://127.0.0.1:${MANAGEMENT_PORT}"

                # Run tests
                bash scripts/api_tests.sh 127.0.0.1 ${MICROSERVICE_PORT}

                # Kill port forwarding
                killall kubectl || true
                """
            }
        }
    }
}
