(ns andrewslai.clj.articles-routes-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.articles-test :as a]
            [andrewslai.clj.user-routes-test :as u]
            [andrewslai.clj.utils :refer [parse-body]]
            [andrewslai.clj.test-utils :refer [defdbtest] :as tu]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [deftest is testing are]]
            [ring.mock.request :as mock]
            [clojure.spec.alpha :as s]))

(comment
  (jdbc/with-db-connection [db ptest/db-spec]
    (jdbc/query db "select * from articles"))
  )

(defdbtest article-retrieval-test ptest/db-spec
  (are [endpoint status spec]
    (testing (format "%s returns %s, matching schema %s" endpoint status spec)
      (let [response (tu/get-request endpoint)
            body (parse-body response)]
        (is (= status (:status response)))
        (is (s/valid? spec body))))

    "/articles"                  200 :andrewslai.article/articles
    "/articles/my-first-article" 200 :andrewslai.article/article)

  (testing "Non-existent article"
    (let [response (tu/get-request "/articles/does-not-exist")]
      (is (= 404 (:status response))))))

(defn assemble-post-request [endpoint payload]
  (-> (mock/request :post endpoint)
      (assoc :body-params payload)))

(defn get-cookie [response]
  (-> response
      :headers
      (get "Set-Cookie")
      first))

(defdbtest create-article-test ptest/db-spec
  (let [handler              (h/configure-app h/app-routes
                                              (tu/test-app-component-config ptest/db-spec))
        create-user          (fn [user] (-> "/users"
                                            (assemble-post-request user)
                                            (assoc :content-type "application/json")
                                            handler))
        login-user           (fn [user] (->> [:username :password]
                                             (select-keys user)
                                             (assemble-post-request "/sessions/login")
                                             handler))
        article-url          (str "/articles/" (:article_url a/example-article))
        unauthorized-request (assemble-post-request "/articles/"
                                                    a/example-article)]

    (testing "Unauthorized user"
      (testing "Article creation fails"
        (is (= 401 (-> unauthorized-request handler :status))))
      (testing "Article retrieval fails"
        (is (= 404 (-> article-url tu/get-request :status)))))

    (testing "Authorized user"
      (let [_        (create-user u/new-user)
            response (login-user u/new-user)
            authorized-request
            (->> response
                 get-cookie
                 (assoc-in unauthorized-request [:headers "cookie"]))]
        (testing "Article creation succeeds"
          (let [response (handler authorized-request)]
            (is (= 200 (:status response)))
            (is (s/valid? :andrewslai.article/article
                          (parse-body response)))))
        (testing "Article retrieval succeeds"
          (let [response (tu/get-request article-url)]
            (is (= 200 (:status response)))
            (is (s/valid? :andrewslai.article/article
                          (parse-body response)))))))))
