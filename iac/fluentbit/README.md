# Metrics with Fluentbit

Metrics with Fluentbit are a mess. In order to get them set up, you need to create a separate Docker image and pull from that new image

Best resources
https://github.com/aws-samples/amazon-ecs-firelens-examples/tree/mainline/examples/fluent-bit/send-fb-internal-metrics-to-cw
https://github.com/aws-samples/amazon-ecs-firelens-examples/tree/mainline/examples/fluent-bit/config-file-type-file
https://github.com/aws/aws-for-fluent-bit/blob/mainline/troubleshooting/debugging.md#firelens-oomkill-prevention-guide

Secondary
https://aws.amazon.com/blogs/containers/how-to-set-fluentd-and-fluent-bit-input-parameters-in-firelens/
https://github.com/aws/containers-roadmap/issues/964

``` sh
docker build -t andrewslai_fluentbit .
```

``` sh
docker run --env FLUENT_BIT_METRICS_LOG_GROUP=fluent-bit-metrics-firelens-example-parsed \
           --env FLUENT_BIT_METRICS_LOG_REGION=us-east-1 \
           --env HOSTNAME=local-testing \
            --mount type=bind,source="/home/andrew/.aws",target="/root/.aws" \
           andrewslai_fluentbit
```

``` sh
docker exec -it 5514cad1a6cc sh

```


```sh
#!/bin/bash

aws ecr get-login-password \
  --region us-east-1 | \
docker login \
  --username AWS \
  --password-stdin 758589815425.dkr.ecr.us-east-1.amazonaws.com
docker tag andrewslai_fluentbit:latest 758589815425.dkr.ecr.us-east-1.amazonaws.com/andrewslai_fluentbit_ecr
docker push 758589815425.dkr.ecr.us-east-1.amazonaws.com/andrewslai_fluentbit_ecr

```
