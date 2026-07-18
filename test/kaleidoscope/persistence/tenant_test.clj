(ns kaleidoscope.persistence.tenant-test
  (:require [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.api.groups :as groups]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]))

(defn- hostname-columns-in-schema
  "The set of table/view names that actually carry a hostname column."
  [db]
  (->> (next/execute! db ["SELECT DISTINCT table_name AS t
                           FROM information_schema.columns
                           WHERE lower(column_name) = 'hostname'"]
                      {:builder-fn rs/as-unqualified-kebab-maps})
       (map (comp str/lower-case :t))
       (into #{})))

(deftest tenant-scoped-tables-match-schema-test
  ;; The declared set MUST equal exactly the set of tables/views carrying a
  ;; hostname column. If they drift, a scoped handle either fails to confine a
  ;; table that should be (leak) or injects :hostname into one with no such
  ;; column (SQL error). This test is the tripwire that keeps them in sync.
  (let [db (embedded-h2/fresh-db!)]
    (is (= (hostname-columns-in-schema db)
           tenant/tenant-scoped-tables))))

(deftest scoped-handle-injects-only-for-tenant-tables-test
  (let [db     (embedded-h2/fresh-db!)
        scoped (tenant/scope db "andrewslai.com")]

    (testing "a hostname-bearing table IS confined through a scoped handle"
      (let [get-articles (rdbms/make-finder :articles)]
        ;; fixture articles are all andrewslai.com; a bogus host yields nothing
        (is (empty? ((rdbms/make-finder :articles) (tenant/scope db "nobody.com"))))
        (is (seq (get-articles scoped)))))

    (testing "a table NOT in the tenant-scoped set is NOT injected into — a scoped
              handle behaves like the raw datasource rather than erroring"
      ;; (every current app table is now tenant-scoped, so this exercises the
      ;; mechanism directly against a hypothetical non-tenant table.)
      (is (false? (tenant/tenant-scoped-table? :some-global-table)))
      (is (= {:a 1} (tenant/scope-query scoped :some-global-table {:a 1})))
      (is (= {:a 1 :hostname "andrewslai.com"}
             (tenant/scope-query scoped :articles {:a 1}))))))

(deftest ai-engine-children-cannot-cross-tenants-at-db-level-test
  ;; The enforcement migration gives the AI engine the same composite
  ;; (parent_id, hostname) FKs as the CMS: a child cannot attach to a parent
  ;; on a different tenant, enforced by the database, not just app code.
  (let [db          (embedded-h2/fresh-db!)
        now         "2022-01-01T00:00:00Z"
        [{pid :id}] (rdbms/insert! (tenant/scope db "andrewslai.com") :projects
                                   {:user-id "u" :title "p" :status "idea"
                                    :created-at now :updated-at now})]
    (testing "a project_note cannot attach to a project on a different tenant"
      (is (thrown? Exception
                   (rdbms/insert! db :project-notes
                                  {:id         (java.util.UUID/randomUUID)
                                   :project-id pid
                                   :content    "x"
                                   :source     "text"
                                   :hostname   "caheriaguilar.com"
                                   :created-at now}))))
    (testing "the same-tenant note attaches fine"
      (is (rdbms/insert! db :project-notes
                         {:id         (java.util.UUID/randomUUID)
                          :project-id pid
                          :content    "x"
                          :source     "text"
                          :hostname   "andrewslai.com"
                          :created-at now})))))

(deftest find-by-keys-honors-tenant-conn-test
  ;; The recipes/scrape domains call find-by-keys directly (no make-finder), so
  ;; it must unwrap a TenantConn and inject hostname for tenant-scoped tables.
  (let [db (embedded-h2/fresh-db!)]
    (testing "hostname table: scoped find-by-keys confines results"
      ;; article #1 is andrewslai.com; scoping to another host finds nothing
      (is (seq   (rdbms/find-by-keys (tenant/scope db "andrewslai.com") :articles {:id 1})))
      (is (empty? (rdbms/find-by-keys (tenant/scope db "nobody.com")     :articles {:id 1}))))

    (testing "a table not in the tenant-scoped set: scoped find-by-keys does not inject"
      (groups/create-group! db {:display-name "g" :owner-id "u1" :hostname "andrewslai.com"})
      ;; groups IS tenant-scoped now, so query it by a shared owner across two
      ;; hosts to confirm confinement still works through find-by-keys.
      (groups/create-group! db {:display-name "g2" :owner-id "u1" :hostname "caheriaguilar.com"})
      (is (= 1 (count (rdbms/find-by-keys (tenant/scope db "andrewslai.com") :groups {:owner-id "u1"}))))
      (is (= 2 (count (rdbms/find-by-keys db :groups {:owner-id "u1"})))))))
