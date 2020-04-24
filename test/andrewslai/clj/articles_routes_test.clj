(ns andrewslai.clj.articles-routes-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.utils :refer [parse-response-body]]
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

(deftest get-all-articles-test
  (testing "get-all-articles endpoint returns all articles"
    (let [response (->> "/articles"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= 5 (count (parse-response-body response)))))))

(deftest get-full-article-test
  (testing "get-article endpoint returns an article data structure"
    (let [response (->> "/articles/test-article"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= #{:article
               :article-name}
             (set (keys (parse-response-body response))))))))

(deftest get-resume-info-test
  (testing "get-resume-info endpoint returns an resume-info data structure"
    (let [response (->> "/get-resume-info"
                        (mock/request :get)
                        test-app)]
      (is (= 200 (:status response)))
      (is (= #{:organizations, :projects, :skills}
             (set (keys (parse-response-body response))))))))
