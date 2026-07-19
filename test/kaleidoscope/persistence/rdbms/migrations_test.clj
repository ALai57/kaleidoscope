(ns kaleidoscope.persistence.rdbms.migrations-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kaleidoscope.persistence.rdbms.migrations :as sut]
            [migratus.core :as m]
            [next.jdbc :as next]
            [next.jdbc.sql :as sql]))

;; Migratus resolves :migration-dir on the classpath first, then falls back to a
;; path relative to CWD (repo root under Kaocha). An absolute path throws, so the
;; fixture dir is a repo-relative path under target/ that squash can rewrite in place.
(defn- write-fixture-migrations!
  "Three migrations: create alpha, create beta, then add a column to alpha.
  The column-add proves the squash preserves ordering (not just a table dump)."
  [dir]
  (io/make-parents (io/file dir ".keep"))
  (doseq [[id nm up down]
          [["20990101000001" "create-alpha" "CREATE TABLE alpha (id INT);" "DROP TABLE alpha;"]
           ["20990101000002" "create-beta"  "CREATE TABLE beta (id INT);"  "DROP TABLE beta;"]
           ["20990101000003" "extend-alpha" "ALTER TABLE alpha ADD COLUMN label VARCHAR(50);"
            "ALTER TABLE alpha DROP COLUMN label;"]]]
    (spit (io/file dir (str id "-" nm ".up.sql")) up)
    (spit (io/file dir (str id "-" nm ".down.sql")) down)))

(defn- h2-config
  [dir db-name]
  {:migration-dir dir
   :store         :database
   ;; A db-spec (not a live connection) so each migratus op opens/closes its own
   ;; connection — migratus closes the connection it is handed. DB_CLOSE_DELAY=-1
   ;; keeps the in-memory DB alive across those independent connections.
   :db            {:jdbcUrl (format "jdbc:h2:mem:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
                                    db-name)}})

(defn- applied-ids
  [config]
  (with-open [c (next/get-connection (:db config))]
    (->> (sql/query c ["SELECT id FROM schema_migrations WHERE id != -1 ORDER BY id"])
         (map (comp first vals))
         (map long)
         (sort))))

(defn- table-columns
  [config table]
  (with-open [c (next/get-connection (:db config))]
    (->> (sql/query c [(str "SELECT column_name FROM information_schema.columns "
                            "WHERE lower(table_name) = '" table "'")])
         (map (comp str/lower-case str first vals))
         (sort))))

(defn- delete-dir!
  [dir]
  (doseq [f (reverse (file-seq (io/file dir)))]
    (io/delete-file f true)))

(deftest coerce-arg-test
  (testing "integer-looking args become Long so squash id comparison works numerically"
    (is (= 20990101000001 (sut/coerce-arg "20990101000001")))
    (is (instance? Long (sut/coerce-arg "42"))))
  (testing "kebab-case names are left as strings"
    (is (= "consolidate-ai-workflow" (sut/coerce-arg "consolidate-ai-workflow")))))

(deftest result-lines-test
  (testing "nil (migrate/up/down log directly) prints nothing"
    (is (= [] (sut/result-lines nil))))
  (testing "a non-empty collection prints one item per line"
    (is (= ["20990101000001-a" "20990101000002-b"]
           (sut/result-lines ["20990101000001-a" "20990101000002-b"]))))
  (testing "an empty result is reported, not silently blank"
    (is (= ["(no migrations in range)"] (sut/result-lines []))))
  (testing "a scalar result is stringified"
    (is (= ["created"] (sut/result-lines "created")))))

(deftest squash-list-returns-names-test
  (testing "squash-list yields the migration names -main prints (not a silent nil)"
    (let [dir    (str "target/squash-test-" (random-uuid))
          config (h2-config dir "squash_list_names")]
      (try
        (write-fixture-migrations! dir)
        (m/migrate config)
        (let [names (m/squashing-list config 20990101000001 20990101000003)]
          (is (= ["create-alpha" "create-beta" "extend-alpha"] (vec names)))
          (is (seq (sut/result-lines names)) "result-lines renders the names for stdout"))
        (finally
          (delete-dir! dir))))))

(deftest migratus-commands-wiring-test
  (testing "squash subcommands dispatch to the real migratus fns present in the pinned version"
    (is (= m/squashing-list (get sut/MIGRATUS-COMMANDS "squash-list")))
    (is (= m/create-squash  (get sut/MIGRATUS-COMMANDS "squash-create")))
    (is (= m/squash-between (get sut/MIGRATUS-COMMANDS "squash-apply")))))

(deftest squash-collapses-and-reconciles-test
  (let [dir    (str "target/squash-test-" (random-uuid))
        config (h2-config dir "squash_collapse")]
    (try
      (write-fixture-migrations! dir)
      (m/migrate config)
      (is (= [20990101000001 20990101000002 20990101000003] (applied-ids config))
          "all three fixture migrations applied before squashing")

      (m/create-squash config 20990101000001 20990101000003 "squashed-fixture")
      (m/squash-between config 20990101000001 20990101000003 "squashed-fixture")

      (testing "the range collapses to one file pair named with the highest id"
        (let [names (->> (file-seq (io/file dir))
                         (filter #(.isFile %))
                         (map #(.getName %))
                         (sort))]
          (is (= ["20990101000003-squashed-fixture.down.sql"
                  "20990101000003-squashed-fixture.up.sql"]
                 names))))

      (testing "schema_migrations reconciles to only the new id"
        (is (= [20990101000003] (applied-ids config))))
      (finally
        (delete-dir! dir)))))

(deftest squashed-migration-reproduces-schema-on-fresh-db-test
  (testing "a fresh DB running only the squashed file reaches the same schema as the originals"
    (let [dir       (str "target/squash-test-" (random-uuid))
          original  (h2-config dir "squash_original")]
      (try
        (write-fixture-migrations! dir)
        (m/migrate original)
        (let [orig-alpha (table-columns original "alpha")
              orig-beta  (table-columns original "beta")]
          (m/create-squash original 20990101000001 20990101000003 "squashed-fixture")
          (m/squash-between original 20990101000001 20990101000003 "squashed-fixture")
          ;; A brand-new DB pointed at the now-squashed dir — as a fresh dev/CI/ephemeral
          ;; environment would be — must land on the identical schema.
          (let [fresh (h2-config dir "squash_fresh")]
            (m/migrate fresh)
            (is (= ["id" "label"] orig-alpha))
            (is (= orig-alpha (table-columns fresh "alpha")))
            (is (= orig-beta (table-columns fresh "beta")))))
        (finally
          (delete-dir! dir))))))

(deftest create-squash-refuses-unapplied-migrations-test
  (testing "create-squash throws rather than deleting files for a range not yet applied"
    (let [dir    (str "target/squash-test-" (random-uuid))
          config (h2-config dir "squash_unapplied")]
      (try
        (write-fixture-migrations! dir)
        ;; Intentionally do NOT migrate: nothing is applied.
        (is (thrown-with-msg? IllegalArgumentException #"not applied"
                              (m/create-squash config 20990101000001 20990101000003 "squashed-fixture")))
        (testing "the original files survive the refused squash"
          (is (= 6 (->> (file-seq (io/file dir)) (filter #(.isFile %)) count))))
        (finally
          (delete-dir! dir))))))
