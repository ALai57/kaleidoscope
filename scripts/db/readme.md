

USEFUL SCRIPT FOR SWITCHING BETWEEN AWS AND LOCAL DEVELOPMENT
`source ./PATH/TO/andrewslai/scripts/db/use_db.sh`

examples: 
`use_db aws`     #Configure environment vars for aws
`use_db local`   #Configure environment vars for local db


LOCAL DATABASE SETUP (POSTGRES)
Run setup.sh in scripts/db



CONNECT TO REMOTE DB
```
use_db aws
psql \
   --host=$ANDREWSLAI_DB_HOST \
   --port=$ANDREWSLAI_DB_PORT \
   --username=$ANDREWSLAI_DB_USER \
   --dbname=$ANDREWSLAI_DB_NAME \
   --password
```
