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
            [andrewslai.clj.test-main :as tm]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
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
(defn create-branch
  [article]
  (-> (mock/request :post "/branches")
      (mock/json-body article)
      (mock/header "Authorization" "Bearer x")))

(defn get-branches
  ([]
   (get-branches nil))
  ([query]
   (cond-> (mock/request :get "/branches")
     true  (mock/header "Authorization" "Bearer x")
     query (mock/query-string query))))

(defn create-version
  [article-url branch version]
  (-> (mock/request :post (format "/articles/%s/branches/%s/versions"
                                  article-url
                                  branch))
      (mock/json-body version)
      (mock/header "Authorization" "Bearer x")))

(defn get-version
  [branch-id]
  (-> (mock/request :get (format "/branches/%s/versions" branch-id))
      (mock/header "Authorization" "Bearer x")))

(defn get-composition
  [article-url]
  (mock/request :get (format "/compositions/%s" article-url)))

(defn publish-branch
  [article-url branch-name]
  (-> (mock/request :put (format "/articles/%s/branches/%s/publish"
                                 article-url
                                 branch-name))
      (mock/header "Authorization" "Bearer x")))

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

    (let [create-result          (app (create-branch (assoc article :branch-name "branch-1")))
          [{:keys [article-id]}] (:body create-result)]
      (testing "Article creation succeeds for branch 1"
        (is (match? {:status 200 :body [{:article-id some?
                                         :branch-id  some?}]}
                    create-result)))

      (testing "Article creation succeeds for branch 2"
        (is (match? {:status 200 :body [{:article-id some?
                                         :branch-id  some?}]}
                    (app (create-branch (assoc article
                                               :branch-name "branch-2"
                                               :article-id  article-id))))))

      (testing "The 2 branches were created"
        (is (match? {:status 200 :body (has-count 2)}
                    (app (get-branches {:article-id article-id}))))))))

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
        create-response (app (create-version (:article-url article)
                                             (:branch-name branch)
                                             (merge article version)))]

    (testing "Cannot retrieve an unpublished article by `/compositions` endpoint"
      (is (match? {:status 404}
                  (app (get-composition (:article-url article))))))

    (testing "Publish article"
      (is (match? {:status 200 :body [(merge article branch)]}
                  (app (publish-branch (:article-url article)
                                       (get-in create-response [:body 0 :branch-name]))))))

    (testing "Can retrieve an published article by `/compositions` endpoint"
      (is (match? {:status 200 :body (merge article branch version {:author "Test User"})}
                  (app (get-composition (:article-url article))))))

    (testing "Cannot commit to published branch"
      (log/with-min-level :fatal
        (is (match? {:status 409 :body "Cannot change a published branch"}
                    (app (create-version (:article-url article)
                                         (:branch-name branch)
                                         (merge article version)))))))))

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
        version-1 {:title "My Title" :content "<p>Hello</p>"}
        version-2 {:title "My Title 2" :content "<p>Hello</p>"}

        {[article-branch] :body :as create-result} (app (create-branch (assoc article :branch-name "branch-1")))
        ]

    (testing "Create article branch"
      (is (match? {:status 200 :body [{:article-id some? :branch-id some?}]}
                  create-result)))

    (testing "Commit to branch twice"
      (is (match? {:status 200 :body [(merge version-1 article)]}
                  (app (create-version (:article-url article) "branch-1" version-1))))
      (is (match? {:status 200 :body [(merge version-2 article)]}
                  (app (create-version (:article-url article) "branch-1" version-2))))
      (is (match? {:status 200 :body (has-count 2)}
                  (app (get-version (:branch-id article-branch))))))))

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
