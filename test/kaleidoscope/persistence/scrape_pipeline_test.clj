(ns kaleidoscope.persistence.scrape-pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.scrape-pipeline :as pipeline-db]
            [matcher-combinators.test :refer [match?]]))

(def host "andrewslai.com")

(defn- seed-raw! [db & {:as overrides}]
  (pipeline-db/create-raw-scrape!
   db (merge {:hostname host :request-url "http://example.com/r"
              :final-url "http://example.com/r" :http-status 200
              :fetch-tier "direct" :raw-html "<html>recipe</html>"}
             overrides)))

(deftest raw-scrape-round-trip-test
  (let [db (embedded-h2/fresh-db!)
        big (apply str (repeat 200000 "x"))          ;; large raw_html (~200 KB)
        {:keys [id]} (seed-raw! db :raw-html big)]
    (testing "reads back by id+hostname, incl. a large raw_html and all fetch fields"
      (is (match? {:request-url "http://example.com/r" :final-url "http://example.com/r"
                   :http-status 200 :fetch-tier "direct" :raw-html big}
                  (pipeline-db/get-raw-scrape db id host))))
    (testing "scoped to hostname"
      (is (nil? (pipeline-db/get-raw-scrape db id "other.com"))))))

(deftest pre-fetch-failure-raw-scrape-test
  (let [db (embedded-h2/fresh-db!)
        {:keys [id]} (pipeline-db/create-raw-scrape!
                      db {:hostname host :request-url "http://169.254.169.254/"})]
    (testing "a raw scrape with only request-url persists (fetch fields null)"
      (is (match? {:request-url "http://169.254.169.254/"
                   :final-url nil? :http-status nil? :raw-html nil?}
                  (pipeline-db/get-raw-scrape db id host))))))

(deftest processing-run-round-trip-test
  (let [db (embedded-h2/fresh-db!)
        {raw-id :id} (seed-raw! db)
        {run-id :id}
        (pipeline-db/create-processing-run!
         db {:hostname host :raw-scrape-id raw-id :pipeline-version "abc123"
             :techniques {:acquire :direct :parse :json-ld :normalize :llm-grouping}
             :facts   {:title "Cake" :ingredients ["flour"] :steps ["Mix"]
                       :section-signals [] :labels ["dessert"]}
             :content {:title "Cake" :sections [{:name "Cake" :ingredients ["flour"] :steps ["Mix"]}]}
             :llm-calls [{:purpose "normalize" :model "claude-haiku-4-5"
                          :request {:system "group" :messages []} :response {:content []}}]
             :warnings ["a warning"]
             :outcome :success :error-detail nil})]
    (testing "JSONB fields round-trip decoded (techniques, facts, content, llm_calls, warnings)"
      (is (match? {:raw-scrape-id raw-id :pipeline-version "abc123"
                   :techniques {:acquire "direct" :parse "json-ld" :normalize "llm-grouping"}
                   :facts   {:title "Cake" :labels ["dessert"]}
                   :content {:title "Cake" :sections [{:name "Cake"}]}
                   :llm-calls [{:purpose "normalize" :model "claude-haiku-4-5"}]
                   :warnings ["a warning"]
                   :outcome "success"}
                  (pipeline-db/get-processing-run db run-id host))))))

(deftest processing-run-links-to-raw-scrape-test
  (let [db (embedded-h2/fresh-db!)
        {raw-id :id} (seed-raw! db)
        {run-id :id} (pipeline-db/create-processing-run!
                      db {:hostname host :raw-scrape-id raw-id :pipeline-version "v"
                          :techniques {} :outcome :bot-blocked
                          :error-detail {:message "blocked" :reason "bot-blocked"}})]
    (testing "the run's raw_scrape_id resolves to the stored raw scrape (composite FK link)"
      (let [{:keys [raw-scrape-id]} (pipeline-db/get-processing-run db run-id host)]
        (is (= raw-id raw-scrape-id))
        (is (some? (pipeline-db/get-raw-scrape db raw-scrape-id host)))))))
