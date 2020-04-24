(ns andrewslai.clj.articles-routes-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.utils :refer [parse-body]]
            [andrewslai.clj.test-utils :refer [defdbtest]]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]))

(comment
  (jdbc/with-db-connection [db ptest/db-spec]
    (jdbc/query db "select * from articles"))
  )


(def test-app (h/wrap-middleware h/bare-app
                                 {:db (-> ptest/db-spec
                                          postgres/->Postgres
                                          articles/->ArticleDatabase)}))

(defdbtest get-all-articles-test ptest/db-spec
  (testing "get-all-articles endpoint returns all articles"
    (let [response (->> "/articles"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= 5 (count (parse-body response)))))))

(defdbtest get-full-article-test ptest/db-spec
  (testing "get-article endpoint returns an article data structure"
    (let [response (->> "/articles/my-first-article"
                        (mock/request :get)
                        test-app)
          body (parse-body response)]
      (is (= 200 (:status response)))
      (is (= #{:article, :article-name} (set (keys body))))
      (is (coll? (get-in body [:article :content]))))))

(defdbtest get-resume-info-test  ptest/db-spec
  (testing "get-resume-info endpoint returns an resume-info data structure"
    (let [response (->> "/get-resume-info"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= #{:organizations, :projects, :skills}
             (set (keys (parse-body response))))))))
