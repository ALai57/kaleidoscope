(ns andrewslai.clj.articles-routes-test
  (:require [andrewslai.clj.embedded-postgres :refer [with-embedded-postgres]]
            [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.persistence.articles-test :as a]
            [andrewslai.clj.test-utils :as tu]
            [andrewslai.clj.user-routes-test :as u]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [are deftest is testing]]
            [matcher-combinators.test]
            [ring.middleware.session.memory :as mem]
            [ring.mock.request :as mock]))

(deftest article-retrieval-happy-path
  (with-embedded-postgres database
    (are [endpoint status spec]
      (testing (format "%s returns %s, matching schema %s" endpoint status spec)
        (is (match? {:status status :body #(s/valid? spec %)}
                    (tu/http-request :get endpoint {:database database}))))

      "/articles"                  200 :andrewslai.article/articles
      "/articles/my-first-article" 200 :andrewslai.article/article)))

(deftest article-does-not-exist
  (with-embedded-postgres database
    (is (match? {:status 404}
                (tu/http-request :get "/articles/does-not-exist"
                                 {:database database})))))

(defn create-article!
  [components options]
  (tu/http-request :post "/articles/"
                   components options))

(defn get-article
  [components article-url]
  (tu/http-request :get article-url components))

(deftest create-article-happy-path
  (with-embedded-postgres database
    (let [components  {:database database
                       :auth (keycloak/keycloak-backend (tu/authorized-backend))}]

      (testing "Article retrieval fails"
        (is (match? {:status 404}
                    (get-article components (str "/articles/" (:article_url a/example-article))))))

      (testing "Article creation succeeds"
        (is (match? {:status 200
                     :body #(s/valid? :andrewslai.article/article %)}
                    (create-article! components
                                     {:body-params a/example-article
                                      :headers {"Authorization"
                                                (str "Bearer " tu/valid-token)}}))))
      (testing "Article retrieval succeeds"
        (is (match? {:status 200
                     :body #(s/valid? :andrewslai.article/article %)}
                    (get-article components (str "/articles/" (:article_url a/example-article)))))))))

(deftest cannot-create-article-with-unauthorized-user
  (is (match? {:status 401
               :body {:reason "Not authorized"}}
              (create-article! {:database nil} {:body-params a/example-article}))))
