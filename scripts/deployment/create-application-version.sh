
function create-application-version {
    local SHA=`git rev-parse --short HEAD`

    aws elasticbeanstalk create-application-version \
        --application-name andrewslai_website \
        --version-label $SHA \
        --source-bundle S3Bucket="andrewslai-eb",S3Key="deployment-$SHA.zip" \
        --auto-create-application \
        --region us-east-1
}
