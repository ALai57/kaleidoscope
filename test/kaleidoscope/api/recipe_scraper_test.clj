(ns kaleidoscope.api.recipe-scraper-test
  (:require [kaleidoscope.api.recipe-scraper :as scraper]
            [kaleidoscope.api.firecrawl :as firecrawl]
            [kaleidoscope.workflows.llm-executor :as llm]
            [kaleidoscope.persistence.rdbms]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.scrape-pipeline :as pipeline-db]
            [clj-http.client :as http]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test :refer [match?]]))

;; A literal public (RFC 5737 documentation) IP: InetAddress/getAllByName does
;; no DNS on a literal, and it is not site/link-local, so the SSRF guard passes
;; and we reach the stubbed HTTP boundary without a network dependency.
(def ^:private public-url "http://203.0.113.5/recipe")
(def ^:private host "andrewslai.com")

(defn- direct
  "Build the map fetch-direct now returns, for stubbing at the fetch boundary."
  [html]
  {:raw-html html :final-url public-url :http-status 200})

(deftest fetch-direct-status-classification-test
  (testing "fetch-direct maps HTTP status to scrape reasons at the clj-http boundary"
    (with-redefs [http/get (fn [_ _] {:status 200 :headers {} :body "<html>ok</html>"})]
      (is (match? {:raw-html "<html>ok</html>" :http-status 200}
                  (scraper/fetch-direct public-url))))
    (doseq [blocked-status [403 429 503]]
      (with-redefs [http/get (fn [_ _] {:status blocked-status :headers {} :body "nope"})]
        (is (match? {:reason :bot-blocked}
                    (try (scraper/fetch-direct public-url)
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))))))
    (with-redefs [http/get (fn [_ _] {:status 404 :headers {} :body "nope"})]
      (is (match? {:reason :fetch-failed}
                  (try (scraper/fetch-direct public-url)
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))))))

(def json-ld-html
  "<html><head>
   <script type=\"application/ld+json\">
   {\"@context\":\"https://schema.org\",\"@type\":\"Recipe\",\"name\":\"Chana Masala\",
    \"recipeIngredient\":[\"2 cups chickpeas\",\"1 tbsp flour\"],
    \"recipeInstructions\":[{\"@type\":\"HowToStep\",\"text\":\"Soak\"},{\"@type\":\"HowToStep\",\"text\":\"Cook\"}],
    \"recipeYield\":\"4\",\"prepTime\":\"PT15M\",\"cookTime\":\"PT30M\",
    \"recipeCuisine\":\"Indian\",\"keywords\":\"vegan, curry\"}
   </script></head><body>Blog exposition here.</body></html>")

(def valid-grouping-json
  "{\"sections\":[{\"name\":\"Cake\",\"ingredients\":[0,1],\"steps\":[0,1]},{\"name\":\"Frosting\",\"ingredients\":[2],\"steps\":[2]}]}")

(deftest acquire-produces-raw-scrape-artifact-test
  (testing "a successful direct fetch yields a RawScrape artifact tagged :direct"
    (with-redefs [scraper/fetch-direct (fn [_] (direct json-ld-html))]
      (is (match? {:artifact  {:request-url public-url :fetch-tier "direct"
                               :http-status 200 :raw-html json-ld-html}
                   :technique :direct}
                  (scraper/acquire {:fetcher nil :url public-url})))))
  (testing "an SSRF block yields an :outcome + partial raw (request-url only)"
    (with-redefs [scraper/fetch-direct (fn [_] (throw (ex-info "blocked" {:type :scrape :reason :blocked-url})))]
      (is (match? {:outcome :blocked-url
                   :error-detail {:reason :blocked-url}
                   :raw {:request-url "http://169.254.169.254/"}}
                  (scraper/acquire {:fetcher nil :url "http://169.254.169.254/"}))))))

(deftest json-ld-happy-path-test
  (is (match? {:title             "Chana Masala"
               :ingredients       ["2 cups chickpeas" "1 tbsp flour"]
               :steps             ["Soak" "Cook"]
               :section-signals   []
               :grouping          nil?
               :servings          "4"
               :prep-time-minutes 15
               :cook-time-minutes 30
               :labels            #(contains? (set %) "Indian")}
              (scraper/parse-json-ld json-ld-html))))

(deftest json-ld-graph-wrapper-test
  (let [html "<script type='application/ld+json'>{\"@graph\":[{\"@type\":\"WebPage\"},{\"@type\":\"Recipe\",\"name\":\"Soup\",\"recipeIngredient\":[\"water\"],\"recipeInstructions\":\"Boil water\"}]}</script>"]
    (is (match? {:title "Soup" :steps ["Boil water"] :section-signals []}
                (scraper/parse-json-ld html)))))

(deftest json-ld-howto-section-test
  (testing "HowToSection names become candidate section-signals; steps stay verbatim and ordered"
    (let [html "<script type='application/ld+json'>{\"@type\":\"Recipe\",\"name\":\"X\",\"recipeIngredient\":[],\"recipeInstructions\":[{\"@type\":\"HowToSection\",\"name\":\"Cake\",\"itemListElement\":[{\"@type\":\"HowToStep\",\"text\":\"A\"},{\"@type\":\"HowToStep\",\"text\":\"B\"}]},{\"@type\":\"HowToSection\",\"name\":\"Frosting\",\"itemListElement\":[{\"@type\":\"HowToStep\",\"text\":\"C\"}]}]}</script>"]
      (is (match? {:steps ["A" "B" "C"] :section-signals ["Cake" "Frosting"]}
                  (scraper/parse-json-ld html))))))

(deftest parse-stage-json-ld-then-llm-test
  (testing "PARSE prefers JSON-LD; without JSON-LD and without an api-key it fails"
    (is (match? {:technique :json-ld :artifact {:title "Chana Masala"}}
                (scraper/parse {:api-key "sk-test" :raw-html json-ld-html})))
    (is (match? {:outcome :no-recipe-found}
                (scraper/parse {:api-key nil :raw-html "<html>no structured data</html>"}))))
  (testing "without JSON-LD but with an api-key, PARSE returns :llm facts with flat lists + grouping + the call"
    (with-redefs [llm/post-anthropic-sync
                  (fn [_ _] {:content [{:text "{\"title\":\"Stew\",\"sections\":[{\"name\":null,\"ingredients\":[\"carrots\",\"beef\"],\"steps\":[\"Simmer\"]}],\"suggested_labels\":[\"comfort\"]}"}]})]
      (is (match? {:technique :llm
                   :artifact  {:title "Stew" :ingredients ["carrots" "beef"] :steps ["Simmer"]
                               :grouping [{:name nil? :ingredients [0 1] :steps [0]}]
                               :labels ["comfort"]}
                   :llm-calls [{:purpose :parse :model "claude-haiku-4-5"
                                :request map? :response map?}]}
                  (scraper/parse {:api-key "sk-test" :raw-html "<html>stew</html>"}))))))

(deftest parse-text-interprets-plain-text-test
  (testing "parse-text turns already-plain text into flat facts + grouping via the LLM"
    (with-redefs [llm/post-anthropic-sync
                  (fn [_ _] {:content [{:text "{\"title\":\"Stew\",\"sections\":[{\"name\":null,\"ingredients\":[\"carrots\",\"beef\"],\"steps\":[\"Simmer\"]}],\"suggested_labels\":[\"comfort\"]}"}]})]
      (is (match? {:technique :llm
                   :artifact  {:title "Stew" :ingredients ["carrots" "beef"] :steps ["Simmer"]
                               :grouping [{:name nil? :ingredients [0 1] :steps [0]}]
                               :labels ["comfort"]}
                   :llm-calls [{:purpose :parse :model "claude-haiku-4-5"}]}
                  (#'scraper/parse-text "sk-test" "Grandma's stew: carrots, beef. Simmer 2 hours."))))))

(deftest normalize-stage-dispatch-test
  (let [flat {:title "Cake" :ingredients ["2 cups flour" "1 cup sugar" "1 cup butter"]
              :steps ["Mix" "Bake" "Whip"] :section-signals [] :labels []}]
    (testing "grouping present -> :pre-grouped deterministic merge, no LLM call"
      (with-redefs [llm/post-anthropic-sync (fn [_ _] (throw (ex-info "must not call" {})))]
        (is (match? {:technique :pre-grouped
                     :artifact  {:sections [{:name "Cake"     :ingredients ["2 cups flour" "1 cup sugar"] :steps ["Mix" "Bake"]}
                                            {:name "Frosting" :ingredients ["1 cup butter"]               :steps ["Whip"]}]}}
                    (scraper/normalize {:api-key nil
                                        :facts (assoc flat :grouping
                                                      [{:name "Cake" :ingredients [0 1] :steps [0 1]}
                                                       {:name "Frosting" :ingredients [2] :steps [2]}])})))))
    (testing "no signals -> :single-section, no LLM call"
      (with-redefs [llm/post-anthropic-sync (fn [_ _] (throw (ex-info "must not call" {})))]
        (is (match? {:technique :single-section
                     :artifact  {:sections [{:name nil? :ingredients ["2 cups flour" "1 cup sugar" "1 cup butter"]}]}}
                    (scraper/normalize {:api-key "sk-test" :facts flat})))))
    (testing "section-signals + api-key -> :llm-grouping, records the call"
      (with-redefs [llm/post-anthropic-sync (fn [_ _] {:content [{:text valid-grouping-json}]})]
        (is (match? {:technique :llm-grouping
                     :llm-calls [{:purpose :normalize :request map? :response map?}]
                     :artifact  {:sections [{:name "Cake"} {:name "Frosting"}]}}
                    (scraper/normalize {:api-key "sk-test"
                                        :facts (assoc flat :section-signals ["Cake" "Frosting"])})))))))

(deftest unsectioned-scrape-assembles-single-section-test
  (testing "an unsectioned JSON-LD scrape yields one unnamed section and NO LLM call"
    (with-redefs [scraper/fetch-direct (fn [_] (direct json-ld-html))
                  llm/post-anthropic-sync (fn [_ _] (throw (ex-info "LLM must not be called" {})))]
      (is (match? {:recipe {:title    "Chana Masala"
                            :sections [{:name        nil?
                                        :ingredients ["2 cups chickpeas" "1 tbsp flour"]
                                        :steps       ["Soak" "Cook"]}]
                            :servings "4"}
                   :extraction-method "json-ld"
                   :warnings          []}
                  (scraper/extract {:api-key "sk-test"} public-url))))))

(deftest json-ld-type-as-array-test
  (let [html "<script type='application/ld+json'>{\"@type\":[\"Recipe\",\"Thing\"],\"name\":\"Y\",\"recipeIngredient\":[\"salt\"],\"recipeInstructions\":\"Mix\"}</script>"]
    (is (match? {:title "Y"} (scraper/parse-json-ld html)))))

(deftest json-ld-absent-test
  (testing "malformed JSON-LD and pages with no Recipe return nil (fallback signal)"
    (is (nil? (scraper/parse-json-ld "<html><body>just a blog</body></html>")))
    (is (nil? (scraper/parse-json-ld "<script type='application/ld+json'>{not valid json</script>")))))

(deftest json-ld-without-name-is-not-a-recipe-test
  (testing "a Recipe node with no name yields nil facts — falls through to the LLM path"
    (is (nil? (scraper/parse-json-ld "<script type='application/ld+json'>{\"@type\":\"Recipe\",\"recipeIngredient\":[\"salt\"],\"recipeInstructions\":\"Mix\"}</script>")))))

(deftest ssrf-rejection-test
  (testing "loopback, private, link-local, metadata, and non-http schemes are rejected"
    (is (match? {:reason :blocked-url}
                (try (scraper/fetch-direct "http://169.254.169.254/latest/meta-data/")
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))))
    (is (match? {:reason :blocked-url}
                (try (scraper/fetch-direct "http://localhost/")
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))))
    (is (match? {:reason :blocked-url}
                (try (scraper/fetch-direct "http://127.0.0.1:8080/admin")
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))))
    (is (match? {:reason :fetch-failed}
                (try (scraper/fetch-direct "file:///etc/passwd")
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))))))

(deftest llm-fallback-signal-test
  (testing "scrape with no JSON-LD and no api-key surfaces :no-recipe-found without a network fetch"
    (with-redefs [scraper/fetch-direct (fn [_] (direct "<html><body>no structured data</body></html>"))]
      (is (match? {:reason :no-recipe-found}
                  (try (scraper/extract {:api-key nil} "http://example.com/recipe")
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))))))

(deftest llm-fallback-invoked-test
  (testing "when JSON-LD is absent and an api-key is present, the LLM path returns sectioned JSON"
    (with-redefs [scraper/fetch-direct (fn [_] (direct "<html><body>Grandma's stew: carrots, beef. Simmer 2 hours.</body></html>"))
                  llm/post-anthropic-sync
                  (fn [_ _] {:content [{:text "{\"title\":\"Stew\",\"sections\":[{\"name\":null,\"ingredients\":[\"carrots\",\"beef\"],\"steps\":[\"Simmer\"]}],\"servings\":\"4\",\"prep_time_minutes\":10,\"cook_time_minutes\":120,\"suggested_labels\":[\"comfort\"]}"}]})]
      (is (match? {:recipe {:title    "Stew"
                            :sections [{:name nil? :ingredients ["carrots" "beef"] :steps ["Simmer"]}]
                            :cook-time-minutes 120}
                   :suggested-labels  ["comfort"]
                   :extraction-method "llm"}
                  (scraper/extract {:api-key "sk-test"} "http://example.com/stew"))))))

(deftest llm-fallback-empty-sections-guard-test
  (testing "an LLM response with no (or non-array) sections still satisfies the min-1-section shape"
    (doseq [sections-json ["\"sections\":[]" "\"sections\":\"none\""]]
      (with-redefs [scraper/fetch-direct (fn [_] (direct "<html><body>vague food blog</body></html>"))
                    llm/post-anthropic-sync
                    (fn [_ _] {:content [{:text (str "{\"title\":\"Mystery\"," sections-json ",\"suggested_labels\":[]}")}]})]
        (is (match? {:recipe {:title "Mystery" :sections [{:ingredients [] :steps []}]}
                     :warnings [#"no sections"]}
                    (scraper/extract {:api-key "sk-test"} "http://example.com/mystery")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Bot-block fallback to the rendering fetcher
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- fetcher-returning [html]
  (reify firecrawl/RecipeFetcher
    (fetch-rendered [_ _url] html)))

(deftest bot-blocked-falls-back-to-fetcher-test
  (testing "a direct 403 with a fetcher present retries via the fetcher and extracts its HTML"
    (with-redefs [scraper/fetch-direct (fn [_] (throw (ex-info "blocked" {:type :scrape :reason :bot-blocked})))]
      (is (match? {:recipe {:title "Chana Masala"} :extraction-method "json-ld"}
                  (scraper/extract {:api-key nil :fetcher (fetcher-returning json-ld-html)}
                                  "http://example.com/blocked"))))))

(deftest bot-blocked-without-fetcher-surfaces-test
  (testing "a direct bot block with no fetcher configured surfaces :bot-blocked"
    (with-redefs [scraper/fetch-direct (fn [_] (throw (ex-info "blocked" {:type :scrape :reason :bot-blocked})))]
      (is (match? {:reason :bot-blocked}
                  (try (scraper/extract {:api-key nil :fetcher nil} "http://example.com/blocked")
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))))))

(deftest fetcher-render-failure-propagates-test
  (testing "a fetcher failure is NOT swallowed into a scrape error the handler 422s — it propagates for Bugsnag"
    (let [failing (reify firecrawl/RecipeFetcher
                    (fetch-rendered [_ _url]
                      (throw (ex-info "firecrawl 500" {:type :scrape :reason :render-failed}))))]
      (with-redefs [scraper/fetch-direct (fn [_] (throw (ex-info "blocked" {:type :scrape :reason :bot-blocked})))]
        (is (match? {:reason :render-failed}
                    (try (scraper/extract {:api-key nil :fetcher failing} "http://example.com/blocked")
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section signals + LLM grouping
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def sectioned-json-ld-html
  "<script type='application/ld+json'>
   {\"@type\":\"Recipe\",\"name\":\"Layer Cake\",
    \"recipeIngredient\":[\"2 cups flour\",\"1 cup sugar\",\"1 cup butter\"],
    \"recipeInstructions\":[
      {\"@type\":\"HowToSection\",\"name\":\"Cake\",\"itemListElement\":[{\"@type\":\"HowToStep\",\"text\":\"Mix\"},{\"@type\":\"HowToStep\",\"text\":\"Bake\"}]},
      {\"@type\":\"HowToSection\",\"name\":\"Frosting\",\"itemListElement\":[{\"@type\":\"HowToStep\",\"text\":\"Whip\"}]}]}
   </script>")

(deftest sectioned-scrape-groups-with-llm-test
  (testing "HowToSections route through the grouping call; text is preserved verbatim by index"
    (with-redefs [scraper/fetch-direct    (fn [_] (direct sectioned-json-ld-html))
                  llm/post-anthropic-sync (fn [_ _] {:content [{:text valid-grouping-json}]})]
      (is (match? {:recipe {:title    "Layer Cake"
                            :sections [{:name "Cake"     :ingredients ["2 cups flour" "1 cup sugar"] :steps ["Mix" "Bake"]}
                                       {:name "Frosting" :ingredients ["1 cup butter"]               :steps ["Whip"]}]}
                   :extraction-method "json-ld+llm-sections"
                   :warnings          []}
                  (scraper/extract {:api-key "sk-test"} public-url))))))

(deftest invalid-grouping-falls-back-test
  (testing "a grouping that is not a partition (missing/duplicate/out-of-range index, bad JSON) flattens with a warning"
    (doseq [bad ["{\"sections\":[{\"name\":\"Cake\",\"ingredients\":[0,1],\"steps\":[0,1]}]}"          ;; missing step+ingredient
                 "{\"sections\":[{\"name\":\"A\",\"ingredients\":[0,1,2,2],\"steps\":[0,1,2]}]}"       ;; duplicate ingredient
                 "{\"sections\":[{\"name\":\"A\",\"ingredients\":[0,1,2],\"steps\":[0,1,2,99]}]}"      ;; out of range
                 "not json at all"]]
      (with-redefs [scraper/fetch-direct    (fn [_] (direct sectioned-json-ld-html))
                    llm/post-anthropic-sync (fn [_ _] {:content [{:text bad}]})]
        (is (match? {:recipe            {:sections [{:name        nil?
                                                     :ingredients ["2 cups flour" "1 cup sugar" "1 cup butter"]
                                                     :steps       ["Mix" "Bake" "Whip"]}]}
                     :extraction-method "json-ld"
                     :warnings          [#"grouping failed"]}
                    (scraper/extract {:api-key "sk-test"} public-url)))))))

(deftest header-ingredient-lines-trigger-grouping-test
  (testing "header-shaped ingredient lines are a sectioning signal even with flat instructions,
            and header lines are consumed as names, not ingredients"
    (let [html "<script type='application/ld+json'>{\"@type\":\"Recipe\",\"name\":\"Cake\",\"recipeIngredient\":[\"For the cake:\",\"2 cups flour\",\"For the frosting:\",\"1 cup butter\"],\"recipeInstructions\":[{\"@type\":\"HowToStep\",\"text\":\"Mix\"},{\"@type\":\"HowToStep\",\"text\":\"Whip\"}]}</script>"
          grouping "{\"sections\":[{\"name\":\"Cake\",\"ingredients\":[1],\"steps\":[0]},{\"name\":\"Frosting\",\"ingredients\":[3],\"steps\":[1]}]}"]
      (with-redefs [scraper/fetch-direct    (fn [_] (direct html))
                    llm/post-anthropic-sync (fn [_ _] {:content [{:text grouping}]})]
        (is (match? {:recipe {:sections [{:name "Cake"     :ingredients ["2 cups flour"]  :steps ["Mix"]}
                                         {:name "Frosting" :ingredients ["1 cup butter"] :steps ["Whip"]}]}
                     :extraction-method "json-ld+llm-sections"
                     :warnings          [#"For the cake:.*For the frosting:"]}
                    (scraper/extract {:api-key "sk-test"} public-url)))))))

(deftest sectioned-without-api-key-flattens-with-warning-test
  (with-redefs [scraper/fetch-direct (fn [_] (direct sectioned-json-ld-html))]
    (is (match? {:recipe            {:sections [{:name nil?}]}
                 :extraction-method "json-ld"
                 :warnings          [#"no LLM"]}
                (scraper/extract {:api-key nil} public-url)))))

(deftest dropped-header-lines-surface-as-warning-test
  (testing "every ingredient line the grouping omits is surfaced in warnings —
            a header-like false positive can't silently lose an ingredient"
    (let [html "<script type='application/ld+json'>{\"@type\":\"Recipe\",\"name\":\"Cake\",\"recipeIngredient\":[\"For the cake:\",\"2 cups flour\",\"Kosher salt to taste:\"],\"recipeInstructions\":[{\"@type\":\"HowToStep\",\"text\":\"Mix\"}]}</script>"
          grouping "{\"sections\":[{\"name\":\"Cake\",\"ingredients\":[1],\"steps\":[0]}]}"]
      (with-redefs [scraper/fetch-direct    (fn [_] (direct html))
                    llm/post-anthropic-sync (fn [_ _] {:content [{:text grouping}]})]
        (is (match? {:recipe            {:sections [{:name "Cake" :ingredients ["2 cups flour"] :steps ["Mix"]}]}
                     :extraction-method "json-ld+llm-sections"
                     :warnings          [#"For the cake:.*Kosher salt to taste:"]}
                    (scraper/extract {:api-key "sk-test"} public-url)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Orchestrator — run-pipeline persistence
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest run-pipeline-persists-success-test
  (testing "a successful scrape persists raw + run and returns a resolvable run-id"
    (let [db (embedded-h2/fresh-db!)]
      (with-redefs [scraper/fetch-direct (fn [_] (direct json-ld-html))]
        (let [{:keys [scrape-processing-run-id] :as result}
              (scraper/run-pipeline {:database db :hostname host :api-key nil :fetcher nil} public-url)]
          (is (match? {:recipe {:title "Chana Masala"}
                       :extraction-method "json-ld"
                       :scrape-processing-run-id uuid?}
                      result))
          (testing "the run is stored with a non-null pipeline_version, technique tags, and content"
            (let [run (pipeline-db/get-processing-run db scrape-processing-run-id host)]
              (is (match? {:pipeline-version string?
                           :techniques {:acquire "direct" :parse "json-ld" :normalize "single-section"}
                           :content {:title "Chana Masala"}
                           :outcome "success"}
                          run))
              (testing "its raw_scrape_id resolves to the stored HTML"
                (is (match? {:raw-content json-ld-html}
                            (pipeline-db/get-raw-scrape db (:raw-scrape-id run) host)))))))))))

(deftest run-pipeline-records-llm-calls-test
  (testing "the pre-grouped LLM path records :pre-grouped with a populated llm_calls storing the full request"
    (let [db (embedded-h2/fresh-db!)]
      (with-redefs [scraper/fetch-direct    (fn [_] (direct "<html>Grandma's stew: carrots, beef. Simmer.</html>"))
                    llm/post-anthropic-sync (fn [_ _] {:content [{:text "{\"title\":\"Stew\",\"sections\":[{\"name\":null,\"ingredients\":[\"carrots\"],\"steps\":[\"Simmer\"]}],\"suggested_labels\":[]}"}]})]
        (let [{:keys [scrape-processing-run-id]}
              (scraper/run-pipeline {:database db :hostname host :api-key "sk-test" :fetcher nil} public-url)
              run (pipeline-db/get-processing-run db scrape-processing-run-id host)]
          (is (match? {:techniques {:parse "llm" :normalize "pre-grouped"}
                       :llm-calls [{:purpose "parse" :model "claude-haiku-4-5"
                                    :request {:system string? :model "claude-haiku-4-5"}}]}
                      run)))))))

(deftest run-pipeline-persists-failure-and-rethrows-test
  (testing "a fetch failure persists a run with the outcome and no content, then re-throws for the handler"
    (let [db (embedded-h2/fresh-db!)]
      (with-redefs [scraper/fetch-direct (fn [_] (throw (ex-info "blocked" {:type :scrape :reason :bot-blocked})))]
        (is (match? {:reason :bot-blocked}
                    (try (scraper/run-pipeline {:database db :hostname host :api-key nil :fetcher nil} public-url)
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))))
        (testing "the failed run is in the corpus: outcome set, content null, request-url recorded"
          (let [runs (kaleidoscope.persistence.rdbms/find-by-keys db :processing-runs {:hostname host})
                run  (first runs)]
            (is (match? {:outcome "bot-blocked" :content nil?} run))
            (is (match? {:request-url public-url :raw-content nil?}
                        (pipeline-db/get-raw-scrape db (:raw-scrape-id run) host)))))))))
