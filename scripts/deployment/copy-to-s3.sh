
function copy-to-s3 {
    local SHA=`git rev-parse --short HEAD`

    # Zip up the files to be included in deployment
    #
    zip --include '/target/andrewslai.jar' 'resources/public/*' \
        -r deployment-$SHA.zip .
    #zip --exclude '*.git*' \
        #--exclude '*node_modules/*' \
        #--exclude '*.elasticbeanstalk*' \
        #--exclude '*deployment*.zip' \
        #--exclude 'iac/*' \
        #--exclude 'target/andrewslai-0.0.1.jar' \
        #--exclude 'target/classes/*' \
        #--exclude 'target/stale/*' \
        #-r deployment-$SHA.zip .

    # Copy zip file to S3
    aws s3 mb s3://andrewslai-artifacts --region us-east-1
    aws s3 cp deployment-$SHA.zip s3://andrewslai-artifacts --region us-east-1
}
