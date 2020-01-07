
**Deployment**

Step 1: build and test locally

`lein do clean, uberjar`


Step 2: Dockerized testing with live DB
```
docker build -t andrewslai .
use_db aws
sudo docker run --env-file=.env -p 5000:5000 andrewslai
```

Step 3: Upload artifact

```
copy-to-s3
create-application-version
update-environment
```
