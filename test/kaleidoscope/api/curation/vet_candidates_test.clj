(ns kaleidoscope.api.curation.vet-candidates-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [honey.sql :as hsql]
            [kaleidoscope.api.curation.mock :as mock]
            [kaleidoscope.api.curation.vet :as vet]
            [kaleidoscope.api.curation.vetting :as vetting]
            [kaleidoscope.models.curation :as m]
            [kaleidoscope.persistence.curation :as persistence.curation]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.utils.core :as utils]
            [matcher-combinators.test :refer [match?]]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(def rubric (get m/default-rubric "standard"))

(defn- stage-runs
  "Raw read of curation_stage_runs for the run, optionally filtered by
  stage — the persistence ns only exposes latest-ok-stage (per-candidate,
  status=ok only), so the test reads the append-only trace directly to
  assert total row counts including :error rows."
  [db run-id & [stage]]
  (next/execute! (tenant/unwrap db)
                 (hsql/format {:select :*
                               :from   :curation-stage-runs
                               :where  (cond-> [:and [:= :run-id run-id] [:= :hostname (tenant/hostname-of db)]]
                                         stage (conj [:= :stage stage]))})
                 {:builder-fn rs/as-unqualified-kebab-maps}))

(deftest vet-candidates-end-to-end-funnel-test
  (testing "fetch -> parse -> vet, persisted per-stage, projected to the head; a
            live-but-wrong-target page is dropped off-taste, not shelved
            (original-bug regression)"
    (let [db         (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
          run-id     (utils/uuid)
          candidates (mock/discover {} {})
          config     {:fetch-policy [:mock] :vet-concurrency 6}]
      (is (= 6 (count candidates)) "3 good + WRONG + NOISE + one /dead/ candidate")

      (with-redefs [vet/vet (fn [content _taste rubric _config] (mock/vet content _taste rubric))]
        (let [pool (vetting/vet-candidates db run-id candidates {} rubric config)
              {:keys [shelved dropped]} (vetting/select-shelf pool rubric {:shelf-size 6 :min-viable 3})]

          (testing "pool has one entry per candidate"
            (is (= 6 (count pool))))

          (testing "exactly the 3 good candidates are shelved"
            (is (= #{"Deep Work primer" "Solitude" "Attention"}
                   (set (map :title shelved)))))

          (testing "the dead, wrong-target, and noise candidates are dropped with the right outcome"
            (let [by-title (fn [t] (first (filter #(= t (:title %)) dropped)))]
              (is (match? {:outcome "dead"} (by-title "Vanished post"))
                  "a fetch failure short-circuits to a terminal dead outcome")
              (is (match? {:outcome "off-taste"} (by-title "WRONG target"))
                  "ORIGINAL-BUG REGRESSION: a live but wrong-identity page is NOT shelved")
              (is (match? {:outcome "below-threshold"} (by-title "NOISE roundup")))))

          (testing "a curation_stage_runs row exists per resolved (candidate, stage)"
            (is (= 6 (count (stage-runs db run-id "fetch")))
                "every candidate is fetched, ok or error")
            (is (= 1 (count (filter #(= "error" (:status %)) (stage-runs db run-id "fetch"))))
                "only the /dead/ candidate's fetch errors")
            (is (= 5 (count (stage-runs db run-id "parse"))) "parse skips the dead candidate")
            (is (= 5 (count (stage-runs db run-id "vet"))) "vet skips the dead candidate"))

          (testing "the persisted fetch stage-run output has its raw blob replaced by a storage stub,
                    not the full page HTML (unbounded-growth regression)"
            (let [ok-fetches (filter #(= "ok" (:status %)) (stage-runs db run-id "fetch"))]
              (is (seq ok-fetches))
              (doseq [{:keys [output]} ok-fetches]
                (is (not= "<html>mock</html>" (:raw output))
                    "the raw HTML blob must not be persisted verbatim")
                (is (match? {:raw {:sha256 string? :size number?}} output)
                    "the raw field is replaced with the store-raw! stub descriptor"))))

          (testing "each candidate's head row is projected via upsert-candidate!"
            (let [heads (persistence.curation/run-candidates db run-id)]
              (is (= 6 (count heads)))

              (testing "the dead candidate's head has a terminal outcome + reason"
                (is (match? [{:outcome "dead" :reason some?}]
                            (filter #(= "https://example.com/dead/1" (get-in % [:ref :url])) heads))))

              (testing "the wrong-target candidate's head reflects the identity-fail verdict"
                (is (match? [{:matches? false}]
                            (filter #(= "https://example.com/ok/4" (get-in % [:ref :url])) heads))))

              (testing "a shelved candidate's head has scores + matches? true, no outcome yet
                        (select-shelf's outcome is projected by the caller, not vet-candidates)"
                (is (match? [{:matches? true :scores map?}]
                            (filter #(= "https://example.com/ok/1" (get-in % [:ref :url])) heads)))))))))))
