
**Deployment**

Step 1: build and test locally

`lein do clean, uberjar`


Step 2: Dockerized testing with live DB
```
docker build -t andrewslai .

docker run -d --rm --network host --env-file=.env.local -p 5000:5000 andrewslai

OR 

docker run -d --rm --env-file=.env.aws -p 5000:5000 andrewslai
```

Step 3: Upload artifact

```
./push-to-ecr
./deploy-image
```
