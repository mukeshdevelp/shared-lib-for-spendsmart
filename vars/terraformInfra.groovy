def call(Map config = [:]){
    //configs
    def awsRegion = config.get('awsRegion' , 'us-east-1')
    def terraformDir = config.get('terraformDir', 'spendsmart')
    def stateBucketName = config.get('stateBucketName', '')
    def stateKey = config.get('stateKey', 'aws/infra/terraform.tfstate')
    def stateRegion = config.get('awsRegion', 'us-east-1')
    
    // setting the action destroy or apply
    def action = params.TERRAFORM_ACTION

    if(!action){
        error("terrraform action is required")

    }
    if(! (action in ['apply', 'destroy'])){
        error("terrraform action should be either apply or destroy!")
    }


    // start of pipeline
    try {
        stage('checkout github'){
            checkout scm

        }
        stage('verify tools'){
            sh '''
                set -e
                terraform --version
                aws --version
            '''
        }
        stage('validate aws account'){
            sh '''
                set -e
                aws sts get-caller-identity
            '''
        }
        stage('Bootstrap Terraform Backend') {
            if (!stateBucket?.trim()) { 
                error( "stateBucket must be provided to the shared library." ) 
            } 
            
            sh """ 
                set -e
                echo "Checking Terraform state bucket..." 
                if aws s3api head-bucket --bucket "${stateBucket}" 2>/dev/null then
                    echo "Terraform state bucket already exists." 
                else 
                    echo "Creating Terraform state bucket..." 
                    aws s3api create-bucket \ --bucket "${stateBucket}" \ --region "${stateRegion}" 
                    echo "Terraform state bucket created."
                fi 
            """ 
        }

        stage('Terraform Init'){
            dir("${terraformDir}"){
                sh """
                    set -e
                    terraform init \
                        -backend-config="bucket=${stateBucket}" \
                        -backend-config="key=${stateKey}" \
                        -backend-config="region=${stateRegion}"
                """
            }
        }

        stage('terraform formatting check'){
            dir(terraformDir){
                sh '''
                    set -e
                    terraform fmt -check -recursive
                '''
            }
        }

        stage('terraform validation'){
            dir(terraformDir){
                sh '''
                    set -e
                    terraform validate
                '''
            }
        }
        stage('Terraform Plan'){
            dir("${terraformDir}"){
                if(action == 'apply'){
                    sh '''
                        set -e
                        terraform plan -out=tfplan
                    '''
                } else {
                    sh '''
                        set -e
                        terraform plan -destroy -out=tfplan
                    '''
                }
            }
        }

        // execution

        stage('terraform apply'){
            if (action == 'apply'){
                echo "creating infrastructure..."

                dir(terraformDir){
                    sh '''
                        set -e
                        terraform apply -auto-approve tfplan
                    '''
                }
            } else {
                dir(terraformDir){
                    sh '''
                        set -e
                        terraform destroy -auto-approve tfplan
                    '''
                }
            }
        }

        stage('terraform output'){
            dir(terraformDir){
                sh '''
                    set -e
                    terraform output
                '''
            }
        } else {
            echo "terraform destroy completed successfully."

        }
    } catch(Exception e){
        echo "Error occurred: ${e.getMessage()}"
        currentBuild.result = 'FAILURE'
        throw e
    }
    finally {
        echo "terraform pipeline execution finished"
    }
}