(ns andrewslai.clj.articles-routes-test
  (:require [andrewslai.clj.embedded-postgres :refer [with-embedded-postgres]]
            [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.articles-test :as a]
            [andrewslai.clj.test-utils :as tu]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [are deftest is testing use-fixtures]]
            [matcher-combinators.test]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(defn article?
  [x]
  (s/valid? :andrewslai.article/article x))

(defn articles?
  [x]
  (s/valid? :andrewslai.article/articles x))

(deftest article-retrieval-test
  (with-embedded-postgres database
    (are [endpoint expected]
      (testing (format "%s returns %s" endpoint expected)
        (is (match? expected
                    (tu/app-request (h/andrewslai-app {:database database})
                                    {:request-method :get
                                     :uri            endpoint}))))

      "/articles"                  {:status 200 :body articles?}
      "/articles/my-first-article" {:status 200 :body article?}
      "/articles/does-not-exist"   {:status 404})))

(deftest create-article-happy-path
  (with-embedded-postgres database
    (let [app (h/andrewslai-app {:database database
                                 :auth     (tu/authorized-backend)})
          url (str "/articles/" (:article_url a/example-article))]

      (testing "404 when article not yet created"
        (is (match? {:status 404}
                    (tu/app-request app
                                    {:request-method :get
                                     :uri            url}))))

      (testing "Article creation succeeds"
        (is (match? {:status 200
                     :body   article?}
                    (tu/app-request app
                                    {:request-method :put
                                     :uri            url
                                     :body-params    a/example-article
                                     :headers        {"Authorization"
                                                      (str "Bearer " tu/valid-token)}}))))

      (testing "Article retrieval succeeds"
        (is (match? {:status 200
                     :body   article?}
                    (tu/app-request app
                                    {:request-method :get
                                     :uri            url})))))))

(deftest cannot-create-article-with-unauthorized-user
  (let [app (h/andrewslai-app {:database nil
                               :auth     (tu/unauthorized-backend)})
        url (str "/articles/" (:article_url a/example-article))]
    (is (match? {:status 401
                 :body   {:reason "Not authorized"}}
                (tu/app-request app
                                {:request-method :put
                                 :uri            url
                                 :body-params    a/example-article
                                 :headers        {"Authorization"
                                                  (str "Bearer " tu/valid-token)}})))))
