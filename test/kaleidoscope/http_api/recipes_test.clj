(ns kaleidoscope.http-api.recipes-test
  "HTTP-level tests for the recipes API: auth boundaries and a create/retrieve
  round-trip through the real router + middleware stack. Ingredient search
  (Postgres-only) is covered in kaleidoscope.api.recipes-test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.recipe-scraper :as scraper]
            [kaleidoscope.http-api.kaleidoscope :as kaleidoscope]
            [kaleidoscope.http-api.recipes :as recipes-http]
            [kaleidoscope.init.env :as env]
            [kaleidoscope.workflows.llm-executor :as llm]
            [kaleidoscope.test-main :as tm]
            [kaleidoscope.test-utils :as tu]
            [matcher-combinators.test :refer [match?]]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(defn make-app
  ([auth-type] (make-app auth-type {}))
  ([auth-type extra-env]
   (->> (merge {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                "KALEIDOSCOPE_AUTH_TYPE"           auth-type
                "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
               extra-env)
        (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
        env/prepare-kaleidoscope
        kaleidoscope/kaleidoscope-app
        tu/wrap-clojure-response)))

(defn as-writer
  "custom-authenticated-user fakes a logged-in writer/admin, but authentication
  still only runs when an Authorization header is present."
  [request]
  (mock/header request "Authorization" "Bearer x"))

(def example-body
  {:content {:title    "Chana Masala"
             :sections [{:ingredients ["2 cups chickpeas" "1 tbsp flour"]
                         :steps       ["Cook"]}]}
   :public-visibility true})

(def json-ld-html
  "<html><head>
   <script type=\"application/ld+json\">
   {\"@context\":\"https://schema.org\",\"@type\":\"Recipe\",\"name\":\"Chana Masala\",
    \"recipeIngredient\":[\"2 cups chickpeas\"],
    \"recipeInstructions\":[{\"@type\":\"HowToStep\",\"text\":\"Cook\"}]}
   </script></head><body>Blog exposition.</body></html>")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Auth boundaries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest anonymous-reads-allowed-test
  (let [app (make-app "always-unauthenticated")]
    (testing "GET /recipes is public (access-filtered internally)"
      (is (match? {:status 200} (app (mock/request :get "https://andrewslai.com/recipes")))))
    (testing "GET /recipe-labels is public"
      (is (match? {:status 200} (app (mock/request :get "https://andrewslai.com/recipe-labels")))))
    (testing "GET /recipe-label-groups is public"
      (is (match? {:status 200} (app (mock/request :get "https://andrewslai.com/recipe-label-groups")))))))

(deftest anonymous-writes-rejected-test
  (let [app (make-app "always-unauthenticated")]
    (testing "POST /recipes requires a writer"
      (is (match? {:status 401}
                  (app (-> (mock/request :post "https://andrewslai.com/recipes")
                           (mock/json-body example-body))))))
    (testing "POST /recipe-labels requires a writer"
      (is (match? {:status 401}
                  (app (-> (mock/request :post "https://andrewslai.com/recipe-labels")
                           (mock/json-body {:name "baking"}))))))
    (testing "PUT /recipe-audiences requires a writer"
      (is (match? {:status 401}
                  (app (-> (mock/request :put "https://andrewslai.com/recipe-audiences")
                           (mock/json-body {:recipe-id (str (java.util.UUID/randomUUID))
                                            :group-id  (str (java.util.UUID/randomUUID))}))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Round-trip through the router
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest create-and-retrieve-recipe-http-test
  (let [app (make-app "custom-authenticated-user")]
    (testing "a writer can create a recipe; slug is derived from the title"
      (is (match? {:status 200
                   :body   {:recipe-url "chana-masala"
                            :content    {:title "Chana Masala"}}}
                  (app (-> (mock/request :post "https://andrewslai.com/recipes")
                           as-writer
                           (mock/json-body example-body))))))

    (testing "the created (public) recipe is retrievable anonymously"
      (is (match? {:status 200 :body {:recipe-url "chana-masala"}}
                  (app (mock/request :get "https://andrewslai.com/recipes/chana-masala")))))))

(deftest writer-sees-own-private-recipe-http-test
  (let [app (make-app "custom-authenticated-user")]
    (testing "a writer creates a PRIVATE (non-public) recipe"
      (is (match? {:status 200 :body {:recipe-url "chana-masala"}}
                  (app (-> (mock/request :post "https://andrewslai.com/recipes")
                           as-writer
                           (mock/json-body (assoc example-body :public-visibility false)))))))

    (testing "the writer can read their own private draft back (writer-sees-all)"
      (is (match? {:status 200 :body {:recipe-url "chana-masala"}}
                  (app (-> (mock/request :get "https://andrewslai.com/recipes/chana-masala")
                           as-writer)))))

    (testing "the private draft is in the writer's list view"
      (is (match? {:status 200 :body [{:recipe-url "chana-masala"}]}
                  (app (-> (mock/request :get "https://andrewslai.com/recipes")
                           as-writer)))))

    (testing "an anonymous caller cannot see the private draft"
      (is (match? {:status 404}
                  (app (mock/request :get "https://andrewslai.com/recipes/chana-masala"))))
      (is (match? {:status 200 :body []}
                  (app (mock/request :get "https://andrewslai.com/recipes")))))))

(deftest scrape-endpoint-test
  (let [app (make-app "custom-authenticated-user")]
    (testing "anonymous scrape is rejected"
      (is (match? {:status 401}
                  ((make-app "always-unauthenticated")
                   (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                       (mock/json-body {:url "http://example.com/r"}))))))

    (testing "a writer gets a draft back with a run-id (pipeline mocked to avoid network)"
      (with-redefs [scraper/scrape-url (fn [_ _] {:recipe {:title "Mocked"
                                                           :sections [{:name nil :ingredients ["a"] :steps ["Mix"]}]}
                                                  :suggested-labels []
                                                  :techniques {:acquire :direct :parse :json-ld :normalize :single-section}
                                                  :warnings []
                                                  :scrape-processing-run-id (random-uuid)})]
        (is (match? {:status 200 :body {:recipe {:title "Mocked"}
                                        :techniques {:parse "json-ld"}
                                        :scrape-processing-run-id string?}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                             as-writer
                             (mock/json-body {:url "http://example.com/r"})))))))

    (testing "a scrape failure surfaces as 422 with a reason"
      (with-redefs [scraper/scrape-url (fn [_ _] (throw (ex-info "blocked" {:type :scrape :reason :blocked-url})))]
        (is (match? {:status 422 :body {:reason "blocked-url"}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                             as-writer
                             (mock/json-body {:url "http://169.254.169.254/"})))))))

    (testing "a rendering-fetcher failure is NOT a 422 — it propagates to the exception reporter (500)"
      (with-redefs [scraper/scrape-url (fn [_ _] (throw (ex-info "firecrawl 500" {:type :scrape :reason :render-failed})))]
        (is (match? {:status 500}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                             as-writer
                             (mock/json-body {:url "http://example.com/r"})))))))))

(deftest scrape-then-create-links-lineage-http-test
  (let [app (make-app "custom-authenticated-user")
        json-ld "<script type=\"application/ld+json\">{\"@type\":\"Recipe\",\"name\":\"Chana Masala\",\"recipeIngredient\":[\"2 cups chickpeas\"],\"recipeInstructions\":\"Cook\"}</script>"]
    (with-redefs [scraper/fetch-direct (fn [_] {:raw-html json-ld :final-url "http://x/r" :http-status 200})]
      (let [{scrape-body :body} (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                                         as-writer
                                         (mock/json-body {:url "http://x/r"})))
            run-id (:scrape-processing-run-id scrape-body)]
        (testing "the scrape response carries a run-id"
          (is (string? run-id)))
        (testing "creating a recipe with the run-id persists the FK; it round-trips via GET"
          (app (-> (mock/request :post "https://andrewslai.com/recipes")
                   as-writer
                   (mock/json-body {:content {:title "Chana Masala"
                                              :sections [{:ingredients ["2 cups chickpeas"] :steps ["Cook"]}]}
                                    :public-visibility true
                                    :scrape-processing-run-id run-id})))
          (is (match? {:status 200 :body {:recipe-url "chana-masala"
                                          :scrape-processing-run-id run-id}}
                      (app (mock/request :get "https://andrewslai.com/recipes/chana-masala")))))))))

(deftest one-per-group-returns-400-test
  (let [app (make-app "custom-authenticated-user")]
    (let [{gid :body} (app (-> (mock/request :post "https://andrewslai.com/recipe-label-groups")
                               as-writer
                               (mock/json-body {:name "ethnicity"})))
          group-id    (:id gid)
          {l1 :body}  (app (-> (mock/request :post "https://andrewslai.com/recipe-labels")
                               as-writer
                               (mock/json-body {:name "indian" :group-id group-id})))
          {l2 :body}  (app (-> (mock/request :post "https://andrewslai.com/recipe-labels")
                               as-writer
                               (mock/json-body {:name "mexican" :group-id group-id})))]
      (testing "assigning two labels from the same group is a 400"
        (is (match? {:status 400 :body {:error #"one label per group"}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes")
                             as-writer
                             (mock/json-body (assoc example-body
                                                    :label-ids [(:id l1) (:id l2)]))))))))))

(deftest put-recipe-slug-collision-returns-409
  (let [app (make-app "custom-authenticated-user")]
    (testing "create two recipes for the tenant"
      (is (match? {:status 200 :body {:recipe-url "chana-masala"}}
                  (app (-> (mock/request :post "https://andrewslai.com/recipes")
                           as-writer
                           (mock/json-body example-body)))))
      (is (match? {:status 200 :body {:recipe-url "pad-thai"}}
                  (app (-> (mock/request :post "https://andrewslai.com/recipes")
                           as-writer
                           (mock/json-body (assoc example-body :recipe-url "pad-thai")))))))
    (testing "renaming one onto the other's slug is a 409"
      (is (match? {:status 409 :body {:error string?}}
                  (app (-> (mock/request :put "https://andrewslai.com/recipes/chana-masala")
                           as-writer
                           (mock/json-body {:recipe-url "pad-thai"}))))))))

(deftest named-sections-round-trip-http-test
  (let [app  (make-app "custom-authenticated-user")
        body {:content {:title    "Layer Cake"
                        :sections [{:name "Cake" :ingredients ["2 cups flour"] :steps ["Mix" "Bake"]}
                                   {:name "Frosting" :ingredients ["1 cup butter"] :steps ["Whip"]}]}
              :public-visibility true}]
    (testing "named sections survive create → retrieve through the router"
      (is (match? {:status 200 :body {:recipe-url "layer-cake"}}
                  (app (-> (mock/request :post "https://andrewslai.com/recipes")
                           as-writer
                           (mock/json-body body)))))
      (is (match? {:status 200
                   :body   {:content {:sections [{:name "Cake" :ingredients ["2 cups flour"] :steps ["Mix" "Bake"]}
                                                 {:name "Frosting" :ingredients ["1 cup butter"] :steps ["Whip"]}]}}}
                  (app (mock/request :get "https://andrewslai.com/recipes/layer-cake")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Photo import — multipart validation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- temp-file-of [bytes]
  (let [f (java.io.File/createTempFile "recipe-img" ".bin")]
    (with-open [o (java.io.FileOutputStream. f)] (.write o ^bytes bytes))
    (.deleteOnExit f)
    f))

(defn- upload [content-type bytes]
  {:filename "x" :content-type content-type :tempfile (temp-file-of bytes) :size (alength bytes)})

(deftest multipart-images-validation-test
  (testing "reads uploaded images into {:content-type :bytes}"
    (is (match? [{:content-type "image/jpeg" :bytes bytes?}]
                (recipes-http/multipart-images {"file0" (upload "image/jpeg" (.getBytes "img"))}))))
  (testing "no image -> :no-image"
    (is (match? {:reason :no-image}
                (try (recipes-http/multipart-images {"desc" "not-a-file"})
                     (catch clojure.lang.ExceptionInfo e (ex-data e))))))
  (testing "unsupported type -> :unsupported-type"
    (is (match? {:reason :unsupported-type}
                (try (recipes-http/multipart-images {"f" (upload "application/pdf" (.getBytes "x"))})
                     (catch clojure.lang.ExceptionInfo e (ex-data e))))))
  (testing "too many images -> :too-many-images"
    (let [six (into {} (for [i (range 6)] [(str "f" i) (upload "image/png" (.getBytes "x"))]))]
      (is (match? {:reason :too-many-images}
                  (try (recipes-http/multipart-images six)
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Photo import — end-to-end through the router
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest scrape-photo-endpoint-test
  (testing "anonymous scrape-photo is rejected"
    (is (match? {:status 401}
                ((make-app "always-unauthenticated")
                 (mock/request :post "https://andrewslai.com/recipes/scrape-photo")))))
  (let [app (make-app "custom-authenticated-user")]
    (testing "a writer gets a draft back with a run-id (transcriber+LLM mocked)"
      (with-redefs [recipes-http/multipart-images
                    (fn [_] [{:content-type "image/jpeg" :bytes (.getBytes "img")}])
                    scraper/scrape-photo
                    (fn [_ _] {:recipe {:title "Mocked" :sections [{:name nil :ingredients ["a"] :steps ["Mix"]}]}
                               :suggested-labels []
                               :techniques {:acquire :claude-vision :parse :llm :normalize :single-section}
                               :warnings []
                               :scrape-processing-run-id (random-uuid)})]
        (is (match? {:status 200 :body {:recipe {:title "Mocked"}
                                        :techniques {:acquire "claude-vision" :parse "llm"}
                                        :scrape-processing-run-id string?}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape-photo") as-writer))))))
    (testing "no-recipe-found surfaces as 422"
      (with-redefs [recipes-http/multipart-images (fn [_] [{:content-type "image/jpeg" :bytes (.getBytes "img")}])
                    scraper/scrape-photo (fn [_ _] (throw (ex-info "no recipe" {:type :scrape :reason :no-recipe-found})))]
        (is (match? {:status 422 :body {:reason "no-recipe-found"}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape-photo") as-writer))))))
    (testing "an invalid upload surfaces as 400"
      (with-redefs [recipes-http/multipart-images
                    (fn [_] (throw (ex-info "no image" {:type :validation :reason :no-image})))]
        (is (match? {:status 400 :body {:reason "no-image"}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape-photo") as-writer))))))))

(deftest scrape-photo-then-create-links-lineage-http-test
  ;; Boot the llm workflow executor so the pipeline has an api-key (the photo path
  ;; always interprets via the LLM — there is no JSON-LD shortcut). The Anthropic
  ;; call itself is mocked below, so no network access occurs.
  (let [app (make-app "custom-authenticated-user"
                      {"KALEIDOSCOPE_WORKFLOW_EXECUTOR_TYPE" "llm"
                       "ANTHROPIC_API_KEY"                   "sk-test"})]
    (with-redefs [recipes-http/multipart-images
                  (fn [_] [{:content-type "image/jpeg" :bytes (.getBytes "img")}])
                  ;; default mock transcriber returns a canned transcript; the LLM
                  ;; interpretation is mocked here to yield structured facts.
                  llm/post-anthropic-sync
                  (fn [_ _] {:content [{:text "{\"title\":\"Chana Masala\",\"sections\":[{\"name\":null,\"ingredients\":[\"2 cups chickpeas\"],\"steps\":[\"Cook\"]}],\"suggested_labels\":[]}"}]})]
      (let [{scrape-body :body} (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape-photo") as-writer))
            run-id (:scrape-processing-run-id scrape-body)]
        (testing "the scrape-photo response carries a run-id and techniques"
          (is (match? {:recipe {:title "Chana Masala"} :techniques {:acquire "claude-vision" :parse "llm"}}
                      scrape-body))
          (is (string? run-id)))
        (testing "creating a recipe with the run-id persists the FK; round-trips via GET"
          (app (-> (mock/request :post "https://andrewslai.com/recipes")
                   as-writer
                   (mock/json-body {:content (:recipe scrape-body)
                                    :public-visibility true
                                    :scrape-processing-run-id run-id})))
          (is (match? {:status 200 :body {:recipe-url "chana-masala"
                                          :scrape-processing-run-id run-id}}
                      (app (mock/request :get "https://andrewslai.com/recipes/chana-masala")))))))))

(deftest import-lineage-http-test
  (let [app (make-app "custom-authenticated-user")]
    (with-redefs [scraper/fetch-direct (fn [_] {:raw-html json-ld-html
                                                :final-url "http://example.com/r"
                                                :http-status 200})]
      ;; 1. scrape persists a raw scrape + processing run, returns the run id
      (let [scrape (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                            as-writer
                            (mock/json-body {:url "http://example.com/r"})))
            run-id (get-in scrape [:body :scrape-processing-run-id])]

        (testing "precondition: the scrape returned a run id"
          (is (string? run-id)))

        ;; 2. create the recipe linked to that run
        (app (-> (mock/request :post "https://andrewslai.com/recipes")
                 as-writer
                 (mock/json-body (assoc example-body :scrape-processing-run-id run-id))))

        (testing "a writer reads the assembled lineage; raw body omitted by default"
          (is (match? {:status 200
                       :body   {:recipe-url "chana-masala"
                                :run  {:outcome    "success"
                                       :techniques {:parse "json-ld"}}
                                :raw  {:http-status   200
                                       :content-bytes pos-int?
                                       :raw-content   nil?}}}
                      (app (-> (mock/request :get "https://andrewslai.com/recipes/chana-masala/lineage")
                               as-writer)))))

        (testing "include-raw=true returns the stored raw html"
          (is (match? {:status 200 :body {:raw {:raw-content json-ld-html}}}
                      (app (-> (mock/request :get "https://andrewslai.com/recipes/chana-masala/lineage?include-raw=true")
                               as-writer)))))

        (testing "a recipe with no linked run has no lineage (404)"
          (app (-> (mock/request :post "https://andrewslai.com/recipes")
                   as-writer
                   (mock/json-body {:content {:title "Manual" :sections [{:ingredients ["x"] :steps ["y"]}]}
                                    :recipe-url "manual" :public-visibility true})))
          (is (match? {:status 404}
                      (app (-> (mock/request :get "https://andrewslai.com/recipes/manual/lineage")
                               as-writer)))))))

    (testing "a non-writer cannot see lineage (404, no existence leak)"
      (is (match? {:status 404}
                  ((make-app "always-unauthenticated")
                   (mock/request :get "https://andrewslai.com/recipes/chana-masala/lineage")))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cook timeline — generate + override
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn create-recipe! [app]
  (app (-> (mock/request :post "https://andrewslai.com/recipes")
           as-writer
           (mock/json-body example-body))))

(deftest timeline-generate-and-override-test
  (let [app (make-app "custom-authenticated-user")]
    (create-recipe! app)
    (testing "POST generates + persists a packed timeline"
      (let [resp (app (-> (mock/request :post "https://andrewslai.com/recipes/chana-masala/timeline")
                          as-writer))]
        (is (match? {:status 200
                     :body {:timeline {:total-minutes pos? :overrides []
                                       :components [{:name "Section 1"}]}}}
                    resp))))
    (testing "GET returns the persisted timeline"
      (is (match? {:status 200 :body {:timeline {:total-minutes pos?}}}
                  (app (mock/request :get "https://andrewslai.com/recipes/chana-masala")))))
    (testing "PUT overrides re-packs without regenerating"
      (is (match? {:status 200 :body {:timeline {:overrides [{:phase "Section 1/Section 1" :minutes 99}]
                                                 :total-minutes 99}}}
                  (app (-> (mock/request :put "https://andrewslai.com/recipes/chana-masala/timeline")
                           as-writer
                           (mock/json-body {:overrides [{:phase "Section 1/Section 1" :minutes 99}]}))))))))

(deftest timeline-writer-only-test
  (let [app (make-app "always-unauthenticated")]
    ;; Every POST/PUT under /recipes* is gated writer-only at the ACL layer
    ;; (KALEIDOSCOPE-ACCESS-CONTROL-LIST), which rejects with 401 before the
    ;; handler ever runs — same as anonymous-writes-rejected-test above. The
    ;; handler's own `authz/writer?` check is defense-in-depth (matches the
    ;; brief/DESIGN's stated 404-no-leak intent) but is unreachable via HTTP
    ;; while that ACL rule is in place.
    (testing "non-writer POST timeline ⇒ 401 (ACL blocks before the handler)"
      (is (match? {:status 401}
                  (app (mock/request :post "https://andrewslai.com/recipes/chana-masala/timeline")))))))

(deftest timeline-generation-failure-leaves-recipe-saved-test
  (let [app (make-app "custom-authenticated-user"
                      {"KALEIDOSCOPE_TIMELINE_GENERATOR_TYPE" "mock"})]
    (create-recipe! app)
    (with-redefs [kaleidoscope.api.recipe-timeline/generate!
                  (fn [_] (throw (ex-info "boom" {:type :generation})))]
      (testing "generator failure ⇒ 502, recipe untouched"
        (is (match? {:status 502 :body {:reason "generation-failed"}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/chana-masala/timeline")
                             as-writer))))))
    (testing "the recipe itself is still retrievable and un-timelined"
      (is (match? {:status 200 :body {:recipe-url "chana-masala" :timeline nil?}}
                  (app (mock/request :get "https://andrewslai.com/recipes/chana-masala")))))))
