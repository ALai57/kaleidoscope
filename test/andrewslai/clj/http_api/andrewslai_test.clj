(ns andrewslai.clj.http-api.andrewslai-test
  (:require [andrewslai.clj.auth.buddy-backends :as bb]
            [andrewslai.clj.entities.portfolio :as portfolio]
            [andrewslai.clj.http-api.andrewslai :as andrewslai]
            [andrewslai.clj.http-api.static-content :as sc]
            [andrewslai.clj.persistence.articles-test :as a]
            [andrewslai.clj.persistence.embedded-h2 :as embedded-h2]
            [andrewslai.clj.persistence.postgres :as pg]
            [andrewslai.clj.test-utils :as tu]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]
            [andrewslai.clj.config :as config]))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Testing HTTP routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest ping-test
  (let [handler (tu/wrap-clojure-response (andrewslai/andrewslai-app {:access-rules tu/public-access}))]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    {:revision string?}}
                (handler (mock/request :get "/ping"))))))

(deftest home-test
  (let [handler (andrewslai/andrewslai-app {:static-content (sc/classpath-static-content-wrapper "public" {})
                                            :access-rules   tu/public-access})]
    (is (match? {:status  200
                 :headers {"Content-Type" #"text/html"}
                 :body    tu/file?}
                (handler (mock/request :get "/"))))))

(deftest swagger-test
  (let [handler (tu/wrap-clojure-response (andrewslai/andrewslai-app {:access-rules tu/public-access}))]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    map?}
                (handler (mock/request :get "/swagger.json"))))))

(deftest admin-routes-test
  (let [app (-> {:auth         (bb/authenticated-backend {:realm_access {:roles ["andrewslai"]}})
                 :access-rules (config/configure-access nil)}
                (andrewslai/andrewslai-app)
                (tu/wrap-clojure-response))]
    (is (match? {:status 200 :body {:message "Got to the admin-route!"}}
                (app (-> (mock/request :get "/admin/")
                         (mock/header "Authorization" "Bearer x")))))))

#_(deftest logging-test
    (let [logging-atom (atom [])
          handler      (andrewslai/andrewslai-app {:logging (tu/captured-logging logging-atom)})]
      (handler (mock/request :get "/ping"))
      (is (= 1 (count @logging-atom)))))

(deftest access-rule-configuration-test
  (are [description expected request]
    (testing description
      (let [handler (andrewslai/andrewslai-app {:auth           bb/unauthenticated-backend
                                                :access-rules   (config/configure-access nil)
                                                :database       (pg/->NextDatabase (embedded-h2/fresh-db!))
                                                :static-content (sc/classpath-static-content-wrapper "public" {})})]
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

    "GET `/articles` is publicly accessible"
    {:status 200} (mock/request :get "/articles")

    "GET `/articles/does-not-exist` is publicly accessible"
    {:status 404} (mock/request :get "/articles/does-not-exist")

    "PUT `/articles/new-article` is not publicly accessible"
    {:status 401} (mock/request :put "/articles/new-article")))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test of Blogging API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest article-retrieval-test
  (are [endpoint expected]
    (testing (format "%s returns %s" endpoint expected)
      (let [app (andrewslai/andrewslai-app {:database     (pg/->NextDatabase (embedded-h2/fresh-db!))
                                            :access-rules tu/public-access})]
        (is (match? expected
                    (tu/app-request app (mock/request :get endpoint))))))

    "/articles"                  {:status 200 :body articles?}
    "/articles/my-first-article" {:status 200 :body article?}
    "/articles/does-not-exist"   {:status 404}))

(deftest create-article-happy-path
  (let [app (-> {:database     (pg/->NextDatabase (embedded-h2/fresh-db!))
                 :access-rules tu/public-access
                 :auth         (bb/authenticated-backend {:name "Andrew Lai"})}
                andrewslai/andrewslai-app
                tu/wrap-clojure-response)
        url (format "/articles/%s" (:article-url a/example-article))]

    (testing "404 when article not yet created"
      (is (match? {:status 404}
                  (app (mock/request :get url)))))

    (testing "Article creation succeeds"
      (is (match? {:status 200}
                  (app (-> (mock/request :put url)
                           (mock/json-body a/example-article)
                           (mock/header "Authorization" "Bearer x"))))))

    (testing "Article retrieval succeeds"
      (is (match? {:status 200 :body article?}
                  (app (mock/request :get url)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Test Resume API
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftest portfolio-test
  (let [app      (-> {:database     (pg/->NextDatabase (embedded-h2/fresh-db!))
                      :access-rules tu/public-access}
                     (andrewslai/andrewslai-app)
                     (tu/wrap-clojure-response))
        response (app (mock/request :get "/projects-portfolio"))]
    (is (match? {:status 200 :body portfolio/portfolio?}
                response)
        (s/explain-str :andrewslai/portfolio (:body response)))))
