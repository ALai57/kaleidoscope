(ns andrewslai.clj.http-api.andrewslai-test
  (:require [andrewslai.clj.api.portfolio :as portfolio]
            [andrewslai.clj.http-api.andrewslai :as andrewslai]
            [andrewslai.clj.http-api.auth.buddy-backends :as bb]
            [andrewslai.clj.init.config :as config]
            [andrewslai.clj.init.env :as env]
            [andrewslai.clj.api.articles-test :as a]
            [andrewslai.clj.persistence.filesystem.in-memory-impl :as memory]
            [andrewslai.clj.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [andrewslai.clj.test-utils :as tu]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :trace
      (f))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Predicates
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn article?
  [x]
  (s/valid? :andrewslai.article/article x))

(defn articles?
  [x]
  (s/valid? :andrewslai.article/articles x))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Testing HTTP routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest ping-test
  (let [handler (->> {"ANDREWSLAI_DB_TYPE"             "embedded-h2"
                      "ANDREWSLAI_AUTH_TYPE"           "always-unauthenticated"
                      "ANDREWSLAI_AUTHORIZATION_TYPE"  "use-access-control-list"
                      "ANDREWSLAI_STATIC_CONTENT_TYPE" "none"}
                     (env/start-system! env/ANDREWSLAI-BOOT-INSTRUCTIONS)
                     env/prepare-andrewslai
                     andrewslai/andrewslai-app
                     tu/wrap-clojure-response)]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    {:revision string?}}
                (handler (mock/request :get "/ping"))))))

(deftest home-test
  (let [handler (->> {"ANDREWSLAI_DB_TYPE"             "embedded-h2"
                      "ANDREWSLAI_AUTH_TYPE"           "always-unauthenticated"
                      "ANDREWSLAI_AUTHORIZATION_TYPE"  "use-access-control-list"
                      "ANDREWSLAI_STATIC_CONTENT_TYPE" "in-memory"}
                     (env/start-system! env/ANDREWSLAI-BOOT-INSTRUCTIONS)
                     env/prepare-andrewslai
                     andrewslai/andrewslai-app)]
    (is (match? {:status  200
                 :headers {"Content-Type" #"text/html"}
                 :body    "<div>Hello</div>"}
                (handler (mock/request :get "/"))))))

(deftest swagger-test
  (let [handler (->> {"ANDREWSLAI_DB_TYPE"             "embedded-h2"
                      "ANDREWSLAI_AUTH_TYPE"           "always-unauthenticated"
                      "ANDREWSLAI_AUTHORIZATION_TYPE"  "use-access-control-list"
                      "ANDREWSLAI_STATIC_CONTENT_TYPE" "none"}
                     (env/start-system! env/ANDREWSLAI-BOOT-INSTRUCTIONS)
                     env/prepare-andrewslai
                     andrewslai/andrewslai-app
                     tu/wrap-clojure-response)]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    map?}
                (handler (mock/request :get "/swagger.json"))))))

(deftest admin-routes-test
  (testing "Authenticated and Authorized happy path"
    (let [app (->> {"ANDREWSLAI_DB_TYPE"             "embedded-h2"
                    "ANDREWSLAI_AUTH_TYPE"           "custom-authenticated-user"
                    "ANDREWSLAI_AUTHORIZATION_TYPE"  "use-access-control-list"
                    "ANDREWSLAI_STATIC_CONTENT_TYPE" "none"}
                   (env/start-system! env/ANDREWSLAI-BOOT-INSTRUCTIONS)
                   env/prepare-andrewslai
                   andrewslai/andrewslai-app
                   tu/wrap-clojure-response)]
      (is (match? {:status 200 :body {:message "Got to the admin-route!"}}
                  (app (-> (mock/request :get "/admin/")
                           (mock/header "Authorization" "Bearer x")))))))
  (testing "Authenticated but not Authorized cannot access"
    (let [app (->> {"ANDREWSLAI_DB_TYPE"             "embedded-h2"
                    "ANDREWSLAI_AUTH_TYPE"           "always-unauthenticated"
                    "ANDREWSLAI_AUTHORIZATION_TYPE"  "use-access-control-list"
                    "ANDREWSLAI_STATIC_CONTENT_TYPE" "none"}
                   (env/start-system! env/ANDREWSLAI-BOOT-INSTRUCTIONS)
                   env/prepare-andrewslai
                   andrewslai/andrewslai-app
                   tu/wrap-clojure-response)]
      (is (match? {:status 401 :body "Not authorized"}
                  (app (-> (mock/request :get "/admin/")
                           (mock/header "Authorization" "Bearer x"))))))))

(deftest access-rule-configuration-test
  (are [description expected request]
    (testing description
      (let [handler (->> {"ANDREWSLAI_DB_TYPE"             "embedded-h2"
                          "ANDREWSLAI_AUTH_TYPE"           "always-unauthenticated"
                          "ANDREWSLAI_AUTHORIZATION_TYPE"  "use-access-control-list"
                          "ANDREWSLAI_STATIC_CONTENT_TYPE" "in-memory"}
                         (env/start-system! env/ANDREWSLAI-BOOT-INSTRUCTIONS)
                         env/prepare-andrewslai
                         andrewslai/andrewslai-app)]
        (is (match? expected (handler request)))))

    "GET `/ping` is publicly accessible"
    {:status 200} (mock/request :get "/ping")

    "GET `/` is publicly accessible"
    {:status 200} (mock/request :get "/")

    "POST `/swagger.json` is publicly accessible"
    {:status 200} (mock/request :get "/swagger.json")

    "GET `/admin` is not publicly accessible"
    {:status 401} (mock/request :get "/admin")

    "GET `/projects-portfolio` is publicly accessible"
    {:status 200} (mock/request :get "/projects-portfolio")

    "GET `/compositions` is publicly accessible"
    {:status 200} (mock/request :get "/compositions")

    "GET `/articles/does-not-exist` is not publicly accessible"
    {:status 401} (mock/request :get "/articles/does-not-exist")

    "PUT `/articles/new-article` is not publicly accessible"
    {:status 401} (mock/request :put "/articles/new-article")))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test of Blogging API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest published-article-retrieval-test
  (are [endpoint expected]
    (testing (format "%s returns %s" endpoint expected)
      (let [app (->> {"ANDREWSLAI_DB_TYPE"             "embedded-h2"
                      "ANDREWSLAI_AUTH_TYPE"           "always-unauthenticated"
                      "ANDREWSLAI_AUTHORIZATION_TYPE"  "use-access-control-list"
                      "ANDREWSLAI_STATIC_CONTENT_TYPE" "none"}
                     (env/start-system! env/ANDREWSLAI-BOOT-INSTRUCTIONS)
                     env/prepare-andrewslai
                     andrewslai/andrewslai-app)]
        (is (match? expected
                    (tu/app-request app (mock/request :get endpoint))))))

    "/compositions"                  {:status 200 :body articles?}
    "/compositions/my-first-article" {:status 200 :body article?}
    "/compositions/does-not-exist"   {:status 404}))

(defn has-count
  [n]
  (m/pred (fn [x] (= n (count x)))))

(deftest create-article-branch-happy-path
  (let [app     (->> {"ANDREWSLAI_DB_TYPE"             "embedded-h2"
                      "ANDREWSLAI_AUTH_TYPE"           "custom-authenticated-user"
                      "ANDREWSLAI_AUTHORIZATION_TYPE"  "use-access-control-list"
                      "ANDREWSLAI_STATIC_CONTENT_TYPE" "none"}
                     (env/start-system! env/ANDREWSLAI-BOOT-INSTRUCTIONS)
                     env/prepare-andrewslai
                     andrewslai/andrewslai-app
                     tu/wrap-clojure-response)
        article {:article-tags "thoughts"
                 :article-url  "my-test-article"
                 :author       "Andrew Lai"}]

    (let [create-result          (app (-> (mock/request :post "/branches")
                                          (mock/json-body (assoc article :branch-name "branch-1"))
                                          (mock/header "Authorization" "Bearer x")))
          [{:keys [article-id]}] (:body create-result)]
      (testing "Article creation succeeds for branch 1"
        (is (match? {:status 200 :body [{:article-id some?
                                         :branch-id  some?}]}
                    create-result)))

      (testing "Article creation succeeds for branch 2"
        (is (match? {:status 200 :body [{:article-id some?
                                         :branch-id  some?}]}
                    (app (-> (mock/request :post "/branches")
                             (mock/json-body (assoc article
                                                    :branch-name "branch-2"
                                                    :article-id  article-id))
                             (mock/header "Authorization" "Bearer x"))))))

      ;; PICK BACK UP HERE
      (testing "The 2 branches were created"
        (is (match? {:status 200 :body (has-count 2)}
                    (app (-> (mock/request :get "/branches")
                             (mock/header "Authorization" "Bearer x")
                             (mock/query-string {:article-id article-id})))))))))

(deftest publish-branch-test
  (let [app             (->> {"ANDREWSLAI_DB_TYPE"             "embedded-h2"
                              "ANDREWSLAI_AUTH_TYPE"           "custom-authenticated-user"
                              "ANDREWSLAI_AUTHORIZATION_TYPE"  "use-access-control-list"
                              "ANDREWSLAI_STATIC_CONTENT_TYPE" "none"}
                             (env/start-system! env/ANDREWSLAI-BOOT-INSTRUCTIONS)
                             env/prepare-andrewslai
                             andrewslai/andrewslai-app
                             tu/wrap-clojure-response)
        article         {:article-url "my-test-article"}
        branch          {:branch-name "mybranch"}
        version         {:content "<p>Hi</p>"
                         :title   "My Article"}
        create-response (app (-> (mock/request :post (format "/articles/%s/branches/%s/versions"
                                                             (:article-url article)
                                                             (:branch-name branch)))
                                 (mock/json-body (merge article version))
                                 (mock/header "Authorization" "Bearer x")))
        published-url   (format "/compositions/%s" (:article-url article))]

    (testing "Cannot retrieve an unpublished article by `/compositions` endpoint"
      (is (match? {:status 404}
                  (app (mock/request :get published-url)))))

    (testing "Publish article"
      (is (match? {:status 200 :body [(merge article branch)]}
                  (app (-> (mock/request :put (format "/articles/%s/branches/%s/publish"
                                                      (:article-url article)
                                                      (get-in create-response [:body 0 :branch-name])))
                           (mock/header "Authorization" "Bearer x"))))))

    (testing "Can retrieve an published article by `/compositions` endpoint"
      (is (match? {:status 200 :body (merge article branch version {:author "Test User"})}
                  (app (-> (mock/request :get published-url)
                           (mock/header "Authorization" "Bearer x"))))))

    (testing "Cannot commit to published branch"
      (is (match? {:status 409 :body "Cannot change a published branch"}
                  (app (-> (mock/request :post (format "/articles/%s/branches/%s/versions"
                                                       (:article-url article)
                                                       (:branch-name branch)))
                           (mock/json-body (merge article version))
                           (mock/header "Authorization" "Bearer x"))))))))

(deftest get-versions-test
  (let [app       (->> {"ANDREWSLAI_DB_TYPE"             "embedded-h2"
                        "ANDREWSLAI_AUTH_TYPE"           "custom-authenticated-user"
                        "ANDREWSLAI_AUTHORIZATION_TYPE"  "use-access-control-list"
                        "ANDREWSLAI_STATIC_CONTENT_TYPE" "none"}
                       (env/start-system! env/ANDREWSLAI-BOOT-INSTRUCTIONS)
                       env/prepare-andrewslai
                       andrewslai/andrewslai-app
                       tu/wrap-clojure-response)
        article   {:article-tags "thoughts"
                   :article-url  "my-test-article"}
        version-1 {:title   "My Title"
                   :content "<p>Hello</p>"}
        version-2 {:title   "My Title 2"
                   :content "<p>Hello</p>"}

        {[article-branch] :body :as create-result} (app (-> (mock/request :post "/branches")
                                                            (mock/json-body (assoc article :branch-name "branch-1"))
                                                            (mock/header "Authorization" "Bearer x")))
        ]

    (testing "Create article branch"
      (is (match? {:status 200 :body [{:article-id some?
                                       :branch-id  some?}]}
                  create-result)))

    (testing "Commit to branch twice"
      (is (match? {:status 200 :body [(merge version-1
                                             article
                                             {:branch-name "branch-1"})]}
                  (app (-> (mock/request :post (format "/articles/%s/branches/%s/versions"
                                                       (:article-url article)
                                                       "branch-1"))
                           (mock/json-body version-1)
                           (mock/header "Authorization" "Bearer x")))))
      (is (match? {:status 200 :body [(merge version-2
                                             article
                                             {:branch-name "branch-1"})]}
                  (app (-> (mock/request :post (format "/articles/%s/branches/%s/versions"
                                                       (:article-url article)
                                                       "branch-1"))
                           (mock/json-body version-2)
                           (mock/header "Authorization" "Bearer x")))))
      (is (match? {:status 200 :body (has-count 2)}
                  (app (mock/request :get (format "/branches/%s/versions"
                                                  (:branch-id article-branch)))))))
    ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Resume API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest portfolio-test
  (let [app      (->> {"ANDREWSLAI_DB_TYPE"             "embedded-h2"
                       "ANDREWSLAI_AUTH_TYPE"           "always-unauthenticated"
                       "ANDREWSLAI_AUTHORIZATION_TYPE"  "use-access-control-list"
                       "ANDREWSLAI_STATIC_CONTENT_TYPE" "none"}
                      (env/start-system! env/ANDREWSLAI-BOOT-INSTRUCTIONS)
                      env/prepare-andrewslai
                      andrewslai/andrewslai-app
                      tu/wrap-clojure-response)
        response (app (mock/request :get "/projects-portfolio"))]
    (is (match? {:status 200 :body portfolio/portfolio?}
                response)
        (s/explain-str :andrewslai/portfolio (:body response)))))
