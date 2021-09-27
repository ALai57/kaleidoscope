(ns andrewslai.clj.http-api.andrewslai-test
  (:require [andrewslai.clj.embedded-postgres :refer [with-embedded-postgres]]
            [andrewslai.clj.persistence.articles-test :as a]
            [andrewslai.clj.http-api.andrewslai :as andrewslai]
            [andrewslai.clj.static-content :as sc]
            [andrewslai.clj.test-utils :as tu]
            [buddy.auth.middleware :refer [wrap-authentication]]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
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

(defn portfolio?
  [x]
  (s/valid? :andrewslai.portfolio/portfolio x))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Testing HTTP routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ping-test
  (let [handler (tu/wrap-clojure-response (andrewslai/andrewslai-app {}))]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    {:revision string?}}
                (handler (mock/request :get "/ping"))))))

(deftest home-test
  (let [handler (andrewslai/andrewslai-app {:static-content (sc/classpath-static-content-wrapper "public" {})})]
    (is (match? {:status  200
                 :headers {"Content-Type" #"text/html"}
                 :body    tu/file?}
                (handler (mock/request :get "/"))))))

(deftest swagger-test
  (let [handler (tu/wrap-clojure-response (andrewslai/andrewslai-app {}))]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    map?}
                (handler (mock/request :get "/swagger.json"))))))

(deftest admin-routes-test
  (let [app (-> {:auth (tu/authenticated-backend)}
                (andrewslai/andrewslai-app)
                (tu/wrap-clojure-response))]
    (is (match? {:status 200 :body {:message "Got to the admin-route!"}}
                (app (-> (mock/request :get "/admin/")
                         (mock/header "Authorization" (tu/bearer-token "an-authenticated-user"))))))))

(deftest logging-test
  (let [logging-atom (atom [])
        handler      (andrewslai/andrewslai-app {:logging (tu/captured-logging logging-atom)})]
    (handler (mock/request :get "/ping"))
    (is (= 1 (count @logging-atom)))))

(deftest access-rule-configuration-test
  (with-embedded-postgres database
    (are [description expected request]
      (testing description
        (let [handler (andrewslai/andrewslai-app {:auth           (tu/unauthenticated-backend)
                                                  :database       database
                                                  :static-content (sc/classpath-static-content-wrapper "public" {})})]
          (is (match? expected (handler request)))))

      "GET `/ping` is publically accessible"
      {:status 200} (mock/request :get "/ping")

      "GET `/` is publically accessible"
      {:status 200} (mock/request :get "/")

      "POST `/swagger.json` is publically accessible"
      {:status 200} (mock/request :get "/swagger.json")

      "GET `/admin` is not publically accessible"
      {:status 401} (mock/request :get "/admin")

      "GET `/projects-portfolio` is publically accessible"
      {:status 200} (mock/request :get "/projects-portfolio")

      "GET `/articles` is publically accessible"
      {:status 200} (mock/request :get "/articles")

      "GET `/articles/does-not-exist` is publically accessible"
      {:status 404} (mock/request :get "/articles/does-not-exist")

      "PUT `/articles/new-article` is not publically accessible"
      {:status 401} (mock/request :put "/articles/new-article"))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test of Blogging API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest article-retrieval-test
  (with-embedded-postgres database
    (are [endpoint expected]
      (testing (format "%s returns %s" endpoint expected)
        (is (match? expected
                    (tu/app-request (andrewslai/andrewslai-app {:database database})
                                    (mock/request :get endpoint)))))

      "/articles"                  {:status 200 :body articles?}
      "/articles/my-first-article" {:status 200 :body article?}
      "/articles/does-not-exist"   {:status 404})))

(deftest create-article-happy-path
  (with-embedded-postgres database
    (let [app (-> {:database database
                   :auth     (tu/authenticated-backend)}
                  andrewslai/andrewslai-app
                  tu/wrap-clojure-response)
          url (format "/articles/%s" (:article_url a/example-article))]

      (testing "404 when article not yet created"
        (is (match? {:status 404} (app (mock/request :get url)))))

      (testing "Article creation succeeds"
        (is (match? {:status 200 :body article?}
                    (app (-> (mock/request :put url)
                             (mock/json-body a/example-article)
                             (mock/header "Authorization" (str "Bearer " tu/valid-token)))))))

      (testing "Article retrieval succeeds"
        (is (match? {:status 200 :body article?} (app (mock/request :get url))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Resume API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest portfolio-test
  (with-embedded-postgres database
    (let [app (-> {:database database}
                  (andrewslai/andrewslai-app)
                  (tu/wrap-clojure-response))]
      (is (match? {:status 200 :body portfolio?}
                  (app (mock/request :get "/projects-portfolio")))))))
