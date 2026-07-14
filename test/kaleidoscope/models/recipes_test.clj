(ns kaleidoscope.models.recipes-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.models.recipes :as models]
            [malli.core :as m]))

(deftest raw-scrape-schema-test
  (testing "a full url scrape validates; a minimal one (source-kind + hostname) validates too"
    (is (m/validate models/RawScrape
                    {:hostname "andrewslai.com" :source-kind "url" :request-url "http://x/r"
                     :final-url "http://x/r" :http-status 200
                     :fetch-tier "direct" :raw-content "<html/>"}))
    (is (m/validate models/RawScrape
                    {:hostname "andrewslai.com" :source-kind "photo"}))
    (testing "source-kind is required and enumerated"
      (is (not (m/validate models/RawScrape {:hostname "andrewslai.com"})))
      (is (not (m/validate models/RawScrape {:hostname "andrewslai.com" :source-kind "audio"}))))))

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

(deftest update-recipe-request-accepts-recipe-url
  (is (m/validate models/UpdateRecipeRequest {:recipe-url "new-slug"}))
  (is (m/validate models/UpdateRecipeRequest {}))               ; still fully optional
  (is (not (m/validate models/UpdateRecipeRequest {:recipe-url 42}))))

(deftest scrape-result-carries-run-id-test
  (is (m/validate models/ScrapeResult
                  {:recipe {:title "X" :sections [{:ingredients [] :steps []}]}
                   :suggested-labels [] :warnings []
                   :techniques {:acquire :direct :parse :json-ld :normalize :single-section}
                   :scrape-processing-run-id (random-uuid)}))
  (testing "the run-id is required"
    (is (not (m/validate models/ScrapeResult
                         {:recipe {:title "X" :sections [{:ingredients [] :steps []}]}
                          :suggested-labels [] :warnings []
                          :techniques {:acquire :direct :parse :json-ld :normalize :single-section}})))))
