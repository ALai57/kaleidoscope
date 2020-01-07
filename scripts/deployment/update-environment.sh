
function update-environment {
    local SHA=`git rev-parse --short HEAD`

    aws elasticbeanstalk update-environment \
        --application-name andrewslai_website \
        --environment-name staging \
        --version-label $SHA \
        --region us-east-1
}
