(ns kaleidoscope.api.recipe-scraper-test
  (:require [kaleidoscope.api.recipe-scraper :as scraper]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test :refer [match?]]))

(def json-ld-html
  "<html><head>
   <script type=\"application/ld+json\">
   {\"@context\":\"https://schema.org\",\"@type\":\"Recipe\",\"name\":\"Chana Masala\",
    \"recipeIngredient\":[\"2 cups chickpeas\",\"1 tbsp flour\"],
    \"recipeInstructions\":[{\"@type\":\"HowToStep\",\"text\":\"Soak\"},{\"@type\":\"HowToStep\",\"text\":\"Cook\"}],
    \"recipeYield\":\"4\",\"prepTime\":\"PT15M\",\"cookTime\":\"PT30M\",
    \"recipeCuisine\":\"Indian\",\"keywords\":\"vegan, curry\"}
   </script></head><body>Blog exposition here.</body></html>")

(deftest json-ld-happy-path-test
  (is (match? {:recipe {:title             "Chana Masala"
                        :ingredients       ["2 cups chickpeas" "1 tbsp flour"]
                        :instructions-html "<ol><li>Soak</li><li>Cook</li></ol>"
                        :servings          "4"
                        :prep-time-minutes 15
                        :cook-time-minutes 30}
               :suggested-labels  #(contains? (set %) "Indian")
               :extraction-method "json-ld"}
              (scraper/parse-json-ld json-ld-html))))

(deftest json-ld-graph-wrapper-test
  (let [html "<script type='application/ld+json'>{\"@graph\":[{\"@type\":\"WebPage\"},{\"@type\":\"Recipe\",\"name\":\"Soup\",\"recipeIngredient\":[\"water\"],\"recipeInstructions\":\"Boil water\"}]}</script>"]
    (is (match? {:recipe {:title "Soup" :instructions-html "<p>Boil water</p>"}}
                (scraper/parse-json-ld html)))))

(deftest json-ld-howto-section-test
  (let [html "<script type='application/ld+json'>{\"@type\":\"Recipe\",\"name\":\"X\",\"recipeIngredient\":[],\"recipeInstructions\":[{\"@type\":\"HowToSection\",\"itemListElement\":[{\"@type\":\"HowToStep\",\"text\":\"A\"},{\"@type\":\"HowToStep\",\"text\":\"B\"}]}]}</script>"]
    (is (match? {:recipe {:instructions-html "<ol><li>A</li><li>B</li></ol>"}}
                (scraper/parse-json-ld html)))))

(deftest json-ld-type-as-array-test
  (let [html "<script type='application/ld+json'>{\"@type\":[\"Recipe\",\"Thing\"],\"name\":\"Y\",\"recipeIngredient\":[\"salt\"],\"recipeInstructions\":\"Mix\"}</script>"]
    (is (match? {:recipe {:title "Y"}} (scraper/parse-json-ld html)))))

(deftest json-ld-absent-test
  (testing "malformed JSON-LD and pages with no Recipe return nil (fallback signal)"
    (is (nil? (scraper/parse-json-ld "<html><body>just a blog</body></html>")))
    (is (nil? (scraper/parse-json-ld "<script type='application/ld+json'>{not valid json</script>")))))

(deftest ssrf-rejection-test
  (testing "loopback, private, link-local, metadata, and non-http schemes are rejected"
    (is (match? {:reason :blocked-url}
                (try (scraper/fetch-html "http://169.254.169.254/latest/meta-data/")
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))))
    (is (match? {:reason :blocked-url}
                (try (scraper/fetch-html "http://localhost/")
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))))
    (is (match? {:reason :blocked-url}
                (try (scraper/fetch-html "http://127.0.0.1:8080/admin")
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))))
    (is (match? {:reason :fetch-failed}
                (try (scraper/fetch-html "file:///etc/passwd")
                     (catch clojure.lang.ExceptionInfo e (ex-data e)))))))

(deftest llm-fallback-signal-test
  (testing "scrape with no JSON-LD and no api-key surfaces :no-recipe-found without a network fetch"
    (with-redefs [scraper/fetch-html (fn [_] "<html><body>no structured data</body></html>")]
      (is (match? {:reason :no-recipe-found}
                  (try (scraper/scrape {:api-key nil} "http://example.com/recipe")
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))))))

(deftest llm-fallback-invoked-test
  (testing "when JSON-LD is absent and an api-key is present, the LLM path runs and its JSON is mapped"
    (with-redefs [scraper/fetch-html (fn [_] "<html><body>Grandma's stew: carrots, beef. Simmer 2 hours.</body></html>")
                  kaleidoscope.workflows.llm-executor/post-anthropic-sync
                  (fn [_ _] {:content [{:text "{\"title\":\"Stew\",\"ingredients\":[\"carrots\",\"beef\"],\"instructions_html\":\"<ol><li>Simmer</li></ol>\",\"servings\":\"4\",\"prep_time_minutes\":10,\"cook_time_minutes\":120,\"suggested_labels\":[\"comfort\"]}"}]})]
      (is (match? {:recipe {:title "Stew" :ingredients ["carrots" "beef"] :cook-time-minutes 120}
                   :suggested-labels ["comfort"]
                   :extraction-method "llm"}
                  (scraper/scrape {:api-key "sk-test"} "http://example.com/stew"))))))
