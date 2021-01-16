(ns andrewslai.clj.articles-routes-test
  (:require [andrewslai.clj.embedded-postgres :refer [with-embedded-postgres]]
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

(defn get-cookie [response]
  (-> response
      (get-in [:headers "Set-Cookie"])
      first))

(defn create-user!
  [components user]
  (tu/http-request :post "/users"
                   components {:body-params user}))

(defn login-user
  [components credentials]
  (tu/http-request :post "/sessions/login"
                   components {:body-params credentials}))

(defn create-article!
  [components options]
  (tu/http-request :post "/articles/"
                   components options))

(defn get-article
  [components article-url]
  (tu/http-request :get article-url components))

(deftest create-article-happy-path
  (with-embedded-postgres database
    (let [session     (atom {})
          components  {:database database
                       :session {:store (mem/memory-store session)}}

          _        (create-user! components u/new-user)
          response (login-user components (select-keys u/new-user [:username :password]))]

      (testing "Article retrieval fails"
        (is (match? {:status 404}
                    (get-article components (str "/articles/" (:article_url a/example-article))))))

      (testing "Article creation succeeds"
        (is (match? {:status 200
                     :body #(s/valid? :andrewslai.article/article %)}
                    (create-article! components
                                     {:body-params a/example-article
                                      :headers {"cookie" (get-cookie response)}}))))
      (testing "Article retrieval succeeds"
        (is (match? {:status 200
                     :body #(s/valid? :andrewslai.article/article %)}
                    (get-article components (str "/articles/" (:article_url a/example-article)))))))))

(deftest cannot-create-article-with-unauthorized-user
  (with-embedded-postgres database
    (let [session     (atom {})
          components  {:database database
                       :session {:cookie-attrs {:max-age 3600 :secure false}
                                 :store        (mem/memory-store session)}}]

      (testing "Article creation fails"
        (is (match? {:status 401
                     :body {:reason "Not authorized"}}
                    (create-article! components
                                     {:body-params a/example-article})))))))
