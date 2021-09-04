# Deployment

### Step 1: Build Docker Image

```bash
lein do clean, uberjar
docker build -t andrewslai .
```


### Step 2 (optional): Manual testing 

Against the cloud
```bash
docker run -d --rm --env-file=.env.aws -p 5000:5000 andrewslai
```

Against local DB
```bash 
docker run -d --rm --network host --env-file=.env.local -p 5000:5000 andrewslai
```

### Step 3: Upload artifact

```bash
./scripts/deployment/push-to-ecr
./scripts/deployment/deploy-image
```