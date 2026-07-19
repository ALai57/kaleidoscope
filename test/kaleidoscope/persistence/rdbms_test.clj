(ns kaleidoscope.persistence.rdbms-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.persistence.rdbms.embedded-postgres-impl :as embedded-postgres]
            [matcher-combinators.test :refer [match?]]
            [next.jdbc :as next]))

;; Close each test's embedded Postgres so its SysV shm segment is reclaimed
;; before the next test starts (macOS shmmni=32 otherwise starves initdb).
(use-fixtures :each embedded-postgres/with-clean-dbs)

;; These reproduce the exact incident that caused Bugsnag to bucket two
;; unrelated production errors as one: both a missing-relation error and a
;; uuid/varchar operator error bubbled up as a bare PSQLException from the
;; same generic call site. Run against real Postgres (not H2) because H2
;; doesn't enforce the same strict operator typing.

(deftest find-by-keys-wraps-missing-table-with-table-and-sql-state-test
  (let [database (embedded-postgres/fresh-db!)]
    (try
      (rdbms/find-by-keys database :this-table-does-not-exist {:id 1})
      (is false "expected find-by-keys to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (match? {:type      :PersistenceException
                     :table     :this-table-does-not-exist
                     :sql-state "42P01"} ;; undefined_table
                    (ex-data e)))))))

(deftest find-by-keys-wraps-uuid-varchar-mismatch-with-table-and-sql-state-test
  (let [database (embedded-postgres/fresh-db!)]
    (next/execute! database ["CREATE TABLE uuid_mismatch_test (id uuid, name varchar)"])
    (try
      ;; a plain string param binds as varchar, which has no `=` operator
      ;; against a uuid column — this is exactly how the production bug
      ;; (article-audiences group-id) surfaced.
      (rdbms/find-by-keys database :uuid-mismatch-test {:id "not-actually-a-uuid"})
      (is false "expected find-by-keys to throw")
      (catch clojure.lang.ExceptionInfo e
        (is (match? {:type      :PersistenceException
                     :table     :uuid-mismatch-test
                     :sql-state "42883"} ;; undefined_function (covers missing operators too)
                    (ex-data e)))))))

(deftest distinct-sql-errors-are-no-longer-indistinguishable-test
  (testing "the two failures above carry different :table and :sql-state, so
            Bugsnag can group them separately instead of merging them"
    (let [database (embedded-postgres/fresh-db!)
          _        (next/execute! database ["CREATE TABLE uuid_mismatch_test (id uuid, name varchar)"])
          ex-a     (try (rdbms/find-by-keys database :this-table-does-not-exist {:id 1})
                        nil
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))
          ex-b     (try (rdbms/find-by-keys database :uuid-mismatch-test {:id "not-a-uuid"})
                        nil
                        (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (not= (select-keys ex-a [:table :sql-state])
               (select-keys ex-b [:table :sql-state]))))))
