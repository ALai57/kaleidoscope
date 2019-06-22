#!/bin/bash

# Check if database exists
export ANDREWSLAI_DB="andrewslai-db"
export ANDREWSLAI_DB_USER="andrewslai"
export ANDREWSLAI_DB_PASSWORD="andrewslai"


# Functions
setup_default_database () {
  echo "****************";
  echo "Creating user... "
  echo "username: $ANDREWSLAI_DB_USER"
  echo "password: $ANDREWSLAI_DB_PASSWORD";
  echo "\n"

  sudo -u postgres psql -d $ANDREWSLAI_DB -c "CREATE USER $ANDREWSLAI_DB_USER WITH ENCRYPTED PASSWORD '$ANDREWSLAI_DB_PASSWORD';"
  sudo -u postgres psql -d $ANDREWSLAI_DB -c "GRANT ALL ON DATABASE $ANDREWSLAI_DB TO $ANDREWSLAI_DB_USER;"
  sudo -u postgres psql -d $ANDREWSLAI_DB -c "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO $ANDREWSLAI_DB_USER;"

  echo "****************";
  echo "Populating default database";
}

overwrite_db_prompt () {
    echo "Do you want to overwrite the existing DB? (Y, N)";
    read input_variable;
    case $input_variable in
        Y ) echo "Continuing";;
        * ) echo "Aborting"; exit;;
    esac

}

# Print if database exists
echo "****************"
echo "Checking if DB exists:: $ANDREWSLAI_DB"

if psql -lqt | cut -d \| -f 1 | grep -qw $ANDREWSLAI_DB; then
    echo "Database found!\n\n"

    # Prompt - do you want to delete the DB?
    overwrite_db_prompt
    sudo -u postgres dropdb $ANDREWSLAI_DB

else
   echo "Database does not exist\n\n"

   sudo -u postgres createdb $ANDREWSLAI_DB

fi

setup_default_database

### Add tables, populate default data

# Prompt for overwrite if exist
