(ns kaleidoscope.persistence.curation-migration-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [next.jdbc :as next]))

(deftest curation-tables-exist-after-migration
  (testing "the three curation tables and the recommendations.vetting column exist"
    (let [db (embedded-h2/fresh-db!)]
      (doseq [t ["curation_run_candidates" "curation_stage_runs" "curation_feedback"]]
        (is (some? (next/execute-one!
                     db [(format "SELECT 1 FROM information_schema.tables WHERE table_name = '%s'" t)]))
            (str t " should exist")))
      (is (some? (next/execute-one!
                   db ["SELECT 1 FROM information_schema.columns WHERE table_name = 'recommendations' AND column_name = 'vetting'"]))
          "recommendations.vetting should exist"))))
