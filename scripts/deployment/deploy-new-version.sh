
lein do clean, uberjar

zip --exclude '*.git*' \
    --exclude '*node_modules/*' \
    --exclude '*.elasticbeanstalk*' \
    --exclude '*deployment*.zip' \
    --exclude 'iac/*' \
    --exclude 'target/andrewslai-0.0.1.jar' \
    --exclude 'target/classes/*' \
    --exclude 'target/stale/*' \
    -r deployment-2019-07-19.zip .
aws s3 mb s3://andrewslai-eb --region us-east-1
aws s3 cp deployment-2019-07-19.zip s3://andrewslai-eb --region us-east-1

aws elasticbeanstalk create-application-version \
    --application-name andrewslai_website \
    --version-label v1.17 \
    --source-bundle S3Bucket="andrewslai-eb",S3Key="deployment-2019-07-19.zip" \
    --auto-create-application \
    --region us-east-1

aws elasticbeanstalk update-environment \
    --application-name andrewslai_website \
    --environment-name staging \
    --version-label v1.17 \
    --region us-east-1
