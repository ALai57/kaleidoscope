(ns andrewslai.clj.articles-routes-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.articles-test :as a]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.user-routes-test :as u]
            [andrewslai.clj.utils :refer [parse-body]]
            [andrewslai.clj.test-utils :refer [defdbtest]]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [deftest is testing]]
            [ring.middleware.session.memory :as mem]
            [ring.mock.request :as mock]
            [clojure.spec.alpha :as s]))

(comment
  (jdbc/with-db-connection [db ptest/db-spec]
    (jdbc/query db "select * from articles"))
  )

(def session-atom (atom {}))

(defn components []
  {:user (-> ptest/db-spec
             postgres/->Postgres
             users/->UserDatabase)
   :session {:store (mem/memory-store session-atom)}
   :db (-> ptest/db-spec
           postgres/->Postgres
           articles/->ArticleDatabase)})

(defn test-app []
  (h/wrap-middleware h/bare-app (components)))

(defdbtest get-all-articles-test ptest/db-spec
  (testing "get-all-articles endpoint returns all articles"
    (let [response (->> "/articles"
                        (mock/request :get)
                        ((test-app)))]
      (is (= 200 (:status response)))
      (is (= 5 (count (parse-body response)))))))

(defdbtest get-full-article-test ptest/db-spec
  (testing "get-article endpoint returns an article data structure"
    (let [response (->> "/articles/my-first-article"
                        (mock/request :get)
                        ((test-app)))
          body (parse-body response)]
      (is (= 200 (:status response)))
      (is (s/valid? ::articles/article body)))))

(defdbtest get-resume-info-test  ptest/db-spec
  (testing "get-resume-info endpoint returns an resume-info data structure"
    (let [response (->> "/get-resume-info"
                        (mock/request :get)
                        ((test-app)))]
      (is (= 200 (:status response)))
      (is (= #{:organizations, :projects, :skills}
             (set (keys (parse-body response))))))))

(defn create-user [user]
  ((test-app) (mock/request :post "/users" (json/generate-string user))))

(defdbtest create-article-test ptest/db-spec
  (let [article (json/generate-string a/example-article)
        request (mock/request :post "/articles/" article)]

    (testing "Can't create an article without an authenticated session"
      (is (= 401 (-> request
                     ((test-app))
                     :status))))

    (create-user u/new-user)
    (let [creds
          (json/generate-string (select-keys u/new-user [:username :password]))

          {:keys [headers]}
          ((test-app) (mock/request :post "/login" creds))

          cookie (-> headers
                     (get "Set-Cookie")
                     first)]

      (testing "Can create an article with authenticated user"
        (let [{:keys [status] :as response}
              ((test-app) (assoc-in request [:headers "cookie"] cookie))
              body (parse-body response)]
          (is (= 200 status))
          (is (s/valid? ::articles/article body)))
        (let [{:keys [status] :as response}
              (->> "/articles/my-test-article"
                   (mock/request :get)
                   ((test-app)))]
          (is (= 200 status))
          (is (= "my-test-article"
                 (:article_url (parse-body response)))))))))
