#!/bin/bash

# Prerequisites: postgresql installed
# terminal pwd is andrewslai/scripts/db

# The way the scripts are setup, you need the
# default 'postgres' user and their password

# The script will check for an existing db,
# If exists, prompt user for overwrite
# If not, create.

# Also creates a new table, fruits
# and a user with permissions on the table.

##########################################
# Setup
##########################################
export ANDREWSLAI_DB_NAME="andrewslai_db"
export ANDREWSLAI_DB_USER="andrewslai"
export ANDREWSLAI_DB_PASSWORD="andrewslai"
# POSTGRES_USER_PASSWORD exported  FROM .bashrc
export PGPASSWORD=$POSTGRES_USER_PASSWORD


##########################################
# Functions
##########################################
setup_default_database () {

  populate_default_database
  if ($does_user_exist); then
      overwrite_user_prompt
      create_new_db_user
  fi
}

overwrite_db_prompt () {
  echo "****************";
  echo "Do you want to overwrite the existing DB? (Y, N)";
  read input_variable;
  case $input_variable in
      Y ) echo "Continuing";;
      * ) echo "Aborting"; exit;;
  esac
}

overwrite_user_prompt () {
  echo "****************";
  echo "Do you want to overwrite the existing user: $ANDREWSLAI_DB_USER? (Y, N)"
  read input_variable;
  case $input_variable in
      Y ) echo "Yes - Continuing";;
      * ) echo "Aborting"; exit;;
  esac
}

populate_default_database () {
  echo "****************";
  echo "Populating default database";
  for f in ./defaults/*
  do
      #cat $f
      psql -U postgres -d $ANDREWSLAI_DB -f $f >> db_init_log.txt
      echo "Added $f to database"
  done
  # psql -U postgres -d $ANDREWSLAI_DB -f "./defaults/fruit.sql"
  # psql -U postgres -d $ANDREWSLAI_DB -f "./defaults/articles.sql"
  echo "\n"
}

create_new_db_user () {
  echo "Creating user... "
  echo "username: $ANDREWSLAI_DB_USER"
  echo "password: $ANDREWSLAI_DB_PASSWORD";
  echo "\n"

   psql -U postgres -d $ANDREWSLAI_DB -c \
       "DROP USER IF EXISTS $ANDREWSLAI_DB_USER;
       CREATE USER $ANDREWSLAI_DB_USER
              WITH ENCRYPTED PASSWORD '$ANDREWSLAI_DB_PASSWORD';
       GRANT ALL ON DATABASE $ANDREWSLAI_DB TO $ANDREWSLAI_DB_USER;
grant_db_permissions () {
  USER=${1:?"You must enter a username"}
  DB=${2:?"You must enter a database"}

  echo "Grant permissions... "

   psql -U postgres -d $DB -c \
       "GRANT ALL ON DATABASE $DB TO $USER;
       GRANT ALL PRIVILEGES ON ALL TABLES
             IN SCHEMA public TO $USER;"
  echo "\n"
}

does_user_exist () {
  if  psql -U postgres -d $ANDREWSLAI_DB -c "\du" |
          cut -d \| -f 1 | grep -qw $ANDREWSLAI_DB_USER; then
      echo "****************";
      echo "User exists"
      echo 1
  else
      echo "****************";
      echo "User does not exist"
      echo 0
  fi
}

##########################################
# Main executable commands
##########################################
echo "****************"
echo "Checking if DB exists:: $ANDREWSLAI_DB"
if  psql -U postgres -lqt | cut -d \| -f 1 | grep -qw $ANDREWSLAI_DB; then
  echo "Database found!\n\n"
  overwrite_db_prompt
  psql -U postgres -c \
       "DROP DATABASE $ANDREWSLAI_DB;"
  psql -U postgres -c \
       "CREATE DATABASE $ANDREWSLAI_DB;"
  echo "\n"
  setup_default_database
else
  echo "Database does not exist\n\n"
  psql -U postgres -c \
       "CREATE DATABASE $ANDREWSLAI_DB;"
  setup_default_database
fi
echo "****************"
echo "DONE"


## PORT CONTENT TO HEROKU - GET APP TO LOAD HEROKU CONTENT.
## GET DATABASE TO SEND JS THAT GETS LOADED INTO BROWSER.
