(ns andrewslai.clj.articles-routes-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.articles-test :as a]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.user-routes-test :as u]
            [andrewslai.clj.utils :refer [parse-body]]
            [andrewslai.clj.test-utils :refer [defdbtest] :as tu]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]
            [clojure.spec.alpha :as s]))

(comment
  (jdbc/with-db-connection [db ptest/db-spec]
    (jdbc/query db "select * from articles"))
  )


(defdbtest get-all-articles-test ptest/db-spec
  (testing "get-all-articles endpoint returns a collection of articles"
    (let [response (tu/get-request "/articles")]
      (is (= 200 (:status response)))
      (is (s/valid? ::articles/articles (parse-body response))))))

(defdbtest get-full-article-test ptest/db-spec
  (testing "get-article endpoint returns an article"
    (let [response (tu/get-request "/articles/my-first-article")]
      (is (= 200 (:status response)))
      (is (s/valid? ::articles/article (parse-body response))))))

(defn assemble-post-request [endpoint payload]
  (->> payload
       json/generate-string
       (mock/request :post endpoint)))

(defdbtest create-article-test ptest/db-spec
  (let [app (tu/test-app (atom {}))
        create-user (fn [user] (->> user
                                    (assemble-post-request "/users")
                                    app))
        unauthorized-request (assemble-post-request "/articles/"
                                                    a/example-article)]

    (testing "Can't create an article without an authenticated session"
      (is (= 401 (-> unauthorized-request
                     app
                     :status))))

    (create-user u/new-user)
    (let [{:keys [headers]} (->> [:username :password]
                                 (select-keys u/new-user)
                                 (assemble-post-request "/login")
                                 app)

          cookie (-> headers
                     (get "Set-Cookie")
                     first)]

      (testing "Can create an article with authenticated user"
        (let [{:keys [status] :as response}
              (app (assoc-in unauthorized-request
                             [:headers "cookie"] cookie))]
          (is (= 200 status))
          (is (s/valid? ::articles/article (parse-body response))))
        (let [{:keys [status] :as response}
              (tu/get-request "/articles/my-test-article")]
          (is (= 200 status))
          (is (= "my-test-article"
                 (:article_url (parse-body response)))))))))
