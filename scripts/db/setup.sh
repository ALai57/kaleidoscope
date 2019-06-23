#!/bin/bash

# Check if database exists
export ANDREWSLAI_DB="andrewslai_db"
export ANDREWSLAI_DB_USER="andrewslai"
export ANDREWSLAI_DB_PASSWORD="andrewslai"


# Functions
setup_default_database () {

  populate_default_database
  if ($does_user_exist); then
      overwrite_user_prompt
      create_new_db_user
  fi
}

overwrite_db_prompt () {
    echo "Do you want to overwrite the existing DB? (Y, N)";
    read input_variable;
    case $input_variable in
        Y ) echo "Continuing";;
        * ) echo "Aborting"; exit;;
    esac
}

overwrite_user_prompt () {
    echo "Do you want to overwrite the existing user: $ANDREWSLAI_DB_USER? (Y, N)";

    read input_variable;
    case $input_variable in
        Y ) echo "Continuing";;
        * ) echo "Aborting"; exit;;
    esac
}

populate_default_database () {
  echo "****************";
  echo "Populating default database";
    sudo -u postgres psql -d $ANDREWSLAI_DB -c \
         "CREATE TABLE fruit(
                 name VARCHAR (32),
                 appearance VARCHAR (32),
                 cost INT,
                 grade REAL);
          INSERT INTO fruit VALUES
                 ('Apple', 'round', 105),
                 ('Banana', 'curved', 5),
                 ('Orange', 'round', 15),
                 ('Kiwi', 'egg', 10);"
}

create_new_db_user () {
  echo "Creating user... "
  echo "username: $ANDREWSLAI_DB_USER"
  echo "password: $ANDREWSLAI_DB_PASSWORD";
  echo "\n"

  sudo -u postgres psql -d $ANDREWSLAI_DB -c \
       "DROP USER IF EXISTS $ANDREWSLAI_DB_USER;
       CREATE USER $ANDREWSLAI_DB_USER
              WITH ENCRYPTED PASSWORD '$ANDREWSLAI_DB_PASSWORD';
       GRANT ALL ON DATABASE $ANDREWSLAI_DB TO $ANDREWSLAI_DB_USER;
       GRANT ALL PRIVILEGES ON ALL TABLES
             IN SCHEMA public TO $ANDREWSLAI_DB_USER;"
}

does_user_exist () {
    if sudo -u postgres psql -d $ANDREWSLAI_DB -c "\du" |
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

# Print if database exists
echo "****************"
echo "Checking if DB exists:: $ANDREWSLAI_DB"

if sudo -u postgres psql -lqt | cut -d \| -f 1 | grep -qw $ANDREWSLAI_DB; then
    echo "Database found!\n\n"
    overwrite_db_prompt
    sudo -u postgres dropdb $ANDREWSLAI_DB
    sudo -u postgres createdb $ANDREWSLAI_DB
    setup_default_database
else
   echo "Database does not exist\n\n"
   sudo -u postgres createdb $ANDREWSLAI_DB
   setup_default_database
fi



# sudo -u postgres psql -d $ANDREWSLAI_DB -c "DROP USER IF EXISTS $ANDREWSLAI_DB_USER;"
  # sudo -u postgres psql -d $ANDREWSLAI_DB -c "CREATE USER $ANDREWSLAI_DB_USER WITH ENCRYPTED PASSWORD '$ANDREWSLAI_DB_PASSWORD';"
  # sudo -u postgres psql -d $ANDREWSLAI_DB -c "GRANT ALL ON DATABASE $ANDREWSLAI_DB TO $ANDREWSLAI_DB_USER;"
  # sudo -u postgres psql -d $ANDREWSLAI_DB -c "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO $ANDREWSLAI_DB_USER;"
