(ns kaleidoscope.http-api.recipes-test
  "HTTP-level tests for the recipes API: auth boundaries and a create/retrieve
  round-trip through the real router + middleware stack. Ingredient search
  (Postgres-only) is covered in kaleidoscope.api.recipes-test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.recipe-scraper :as scraper]
            [kaleidoscope.http-api.kaleidoscope :as kaleidoscope]
            [kaleidoscope.init.env :as env]
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
  [auth-type]
  (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
        "KALEIDOSCOPE_AUTH_TYPE"           auth-type
        "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
        "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
       (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
       env/prepare-kaleidoscope
       kaleidoscope/kaleidoscope-app
       tu/wrap-clojure-response))

(defn as-writer
  "custom-authenticated-user fakes a logged-in writer/admin, but authentication
  still only runs when an Authorization header is present."
  [request]
  (mock/header request "Authorization" "Bearer x"))

(def example-body
  {:content {:title             "Chana Masala"
             :ingredients       ["2 cups chickpeas" "1 tbsp flour"]
             :instructions-html "<ol><li>Cook</li></ol>"}
   :public-visibility true})

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

(deftest scrape-endpoint-test
  (let [app (make-app "custom-authenticated-user")]
    (testing "anonymous scrape is rejected"
      (is (match? {:status 401}
                  ((make-app "always-unauthenticated")
                   (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                       (mock/json-body {:url "http://example.com/r"}))))))

    (testing "a writer gets a draft back (scrape mocked to avoid network)"
      (with-redefs [scraper/scrape (fn [_ _] {:recipe {:title "Mocked" :ingredients ["a"]}
                                              :suggested-labels []
                                              :extraction-method "json-ld"
                                              :warnings []})]
        (is (match? {:status 200 :body {:recipe {:title "Mocked"} :extraction-method "json-ld"}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                             as-writer
                             (mock/json-body {:url "http://example.com/r"})))))))

    (testing "a scrape failure surfaces as 422 with a reason"
      (with-redefs [scraper/scrape (fn [_ _] (throw (ex-info "blocked" {:type :scrape :reason :blocked-url})))]
        (is (match? {:status 422 :body {:reason "blocked-url"}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                             as-writer
                             (mock/json-body {:url "http://169.254.169.254/"})))))))))

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
