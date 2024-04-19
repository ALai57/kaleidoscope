# Deployment

## Pre-deployment testing

### Step 1: Build Docker Image

```bash
./bin/uberjar && ./bin/docker-build
```


### Step 2 (optional): Manual testing 

Against the cloud
```bash
docker run --platform linux/amd64 \
           -v $HOME/.aws:/root/.aws:ro \
           --env-file=.env.aws \
           -p 5000:5000 \
           kaleidoscope
```

Against local DB
```bash 
docker run -d --rm --network host \
    --env-file=.env.local \
    -p 5000:5000 \
    kaleidoscope
```

### Step 3: Deploy

```sh
./bin/release       # build and push to docker
./bin/deploy-image
```
