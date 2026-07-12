(ns kaleidoscope.models.recipes-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.models.recipes :as models]
            [malli.core :as m]))

(deftest raw-scrape-schema-test
  (testing "a full raw scrape validates; a pre-fetch-failure (only request-url) validates too"
    (is (m/validate models/RawScrape
                    {:hostname "andrewslai.com" :request-url "http://x/r"
                     :final-url "http://x/r" :http-status 200
                     :fetch-tier "direct" :raw-html "<html/>"}))
    (is (m/validate models/RawScrape
                    {:hostname "andrewslai.com" :request-url "http://x/r"}))
    (is (not (m/validate models/RawScrape {:request-url "http://x/r"})))))

(deftest extracted-facts-schema-test
  (testing "json-ld facts (no grouping) and llm facts (with grouping) both validate"
    (is (m/validate models/ExtractedFacts
                    {:title "Soup" :ingredients ["water"] :steps ["Boil"]
                     :section-signals [] :grouping nil :labels []}))
    (is (m/validate models/ExtractedFacts
                    {:title "Cake" :ingredients ["flour" "sugar"] :steps ["Mix"]
                     :section-signals [] :labels ["dessert"]
                     :grouping [{:name "Cake" :ingredients [0 1] :steps [0]}]}))
    (is (not (m/validate models/ExtractedFacts
                         {:title "x" :steps ["a"] :section-signals [] :labels []})))))

(deftest scrape-result-carries-run-id-test
  (is (m/validate models/ScrapeResult
                  {:recipe {:title "X" :sections [{:ingredients [] :steps []}]}
                   :suggested-labels [] :extraction-method "json-ld" :warnings []
                   :scrape-processing-run-id (random-uuid)}))
  (is (not (m/validate models/ScrapeResult
                       {:recipe {:title "X" :sections [{:ingredients [] :steps []}]}
                        :suggested-labels [] :extraction-method "json-ld" :warnings []}))))
