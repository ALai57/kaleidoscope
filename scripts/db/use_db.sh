
# Configure environment variables to use different
# databases (local postgres or aws rds).
use_db () {
    case $1 in
      aws ) export ANDREWSLAI_DB_PASSWORD=CrAceAticitHEAT;
          export ANDREWSLAI_DB_USER=andrewslai;
          export ANDREWSLAI_DB_NAME=andrewslai;
          export ANDREWSLAI_DB_HOST=andrewslai-db.cwvfukjbn65j.us-east-1.rds.amazonaws.com;
          export ANDREWSLAI_DB_PORT=5432;
          echo "Env vars configured for: AWS";;
      local ) export ANDREWSLAI_DB_PASSWORD=andrewslai
              export ANDREWSLAI_DB_USER=andrewslai
              export ANDREWSLAI_DB_NAME=andrewslai_db
              export ANDREWSLAI_DB_HOST=localhost
              export ANDREWSLAI_DB_PORT=5432;
              echo "Env vars configured for: local";;
      * ) echo "No profile chosen.";;
    esac
}
