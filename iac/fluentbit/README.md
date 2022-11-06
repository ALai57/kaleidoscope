# Metrics with Fluentbit

Metrics with Fluentbit are a mess. In order to get them set up, you need to create a separate Docker image and pull from that new image

Best resources
https://github.com/aws-samples/amazon-ecs-firelens-examples/tree/mainline/examples/fluent-bit/send-fb-internal-metrics-to-cw
https://github.com/aws-samples/amazon-ecs-firelens-examples/tree/mainline/examples/fluent-bit/config-file-type-file
https://github.com/aws/aws-for-fluent-bit/blob/mainline/troubleshooting/debugging.md#firelens-oomkill-prevention-guide

Secondary
https://aws.amazon.com/blogs/containers/how-to-set-fluentd-and-fluent-bit-input-parameters-in-firelens/
https://github.com/aws/containers-roadmap/issues/964

Build the Docker container for Fluentbit. We must build and push the container
to ECR so it can be downloaded and run when we start the `andrewslai` ECS service.
This is due to the fact that the ECR service pulls the image from a private ECR repo
(my repo) - this was the only way to customize FluentBit the way I wanted in ECS.
``` sh
docker build -t andrewslai_fluentbit .
```

Run the Docker image we just built locally.
``` sh
docker run -p 2020:2020 \
           --env FLUENT_BIT_METRICS_LOG_GROUP=fluent-bit-metrics-firelens-example-parsed \
           --env FLUENT_BIT_METRICS_LOG_REGION=us-east-1 \
           --env FLB_LOG_LEVEL=debug \
           --env HOSTNAME=local-testing \
            --mount type=bind,source="/home/andrew/.aws",target="/root/.aws" \
           andrewslai_fluentbit
```

Talk to the Docker image we just built over HTTP. This is not the normal mode by which
application containers ship logs to ECS; normally application containers will use Unix sockets.
``` sh
curl -XPOST -H "content-type: application/json" http://localhost:2020/app.log -d "{\"hi\": \"there\"}"
```

Attempt to talk to the Docker image we just built over a unix socket.
Must have a running FluentBit container, then shell into the container
``` sh
docker exec -it <CONTAINER ID> /bin/bash
nc -U /var/run/fluent.sock
```

``` sh
docker exec -it 5514cad1a6cc sh

```


Once we're happy, push the Docker image to the private ECR repo. After this, we need to
do a Terraform apply to restart the service. (It's possible this isn't strictly necessary
- I just cannot remember if we use the :latest tag or not)
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
