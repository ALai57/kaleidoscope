(ns andrewslai.clj.persistence.articles-test
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.entities.article :as article]
            [andrewslai.clj.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(def example-article
  {:article-tags "thoughts"
   :article-url  "my-test-article"
   :author       "Andrew Lai"})

(deftest create-and-retrieve-articles-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "example-article doesn't exist in the database"
      (is (nil? (article/get-article database (:article-url example-article)))))

    (testing "Insert the example-article"
      (is (article/create-article! database example-article)))

    (testing "Can retrieve example-article from the DB"
      (is (match? example-article (article/get-article database (:article-url example-article)))))))

(def example-article-branch
  {:branch-name "mybranch"})

(deftest create-and-retrieve-article-branches-test
  (let [database       (embedded-h2/fresh-db!)
        [{article-id :id}] (article/create-article! database example-article)]

    (testing "example-article-branch doesn't exist in the database"
      (is (empty? (article/get-article-branches database article-id))))

    (let [[{branch-id :id}] (article/create-article-branch! database (assoc example-article-branch
                                                                            :article-id article-id))]
      (testing "Insert the example-article-branch"
        (is branch-id))

      (testing "Can retrieve example-article from the DB"
        (is (match? {:article-id  article-id
                     :branch-id   branch-id
                     :branch-name "mybranch"}
                    (article/get-branch database branch-id)))
        (is (match? [{:article-id  article-id
                      :branch-id   branch-id
                      :branch-name "mybranch"}]
                    (article/get-article-branches database article-id)))))))

(def example-article-version
  {:title   "My Title"
   :content "<p>Hello</p>"})

(deftest create-and-retrieve-article-version-test
  (let [database           (embedded-h2/fresh-db!)
        [{article-id :id}] (article/create-article! database example-article)
        [{branch-id :id}]  (article/create-article-branch! database (assoc example-article-branch
                                                                           :article-id article-id))]

    (testing "example-article-version doesn't exist in the database"
      (is (empty? (article/get-branch-versions database branch-id))))

    (let [[{version-id :id}] (article/create-version! database (assoc example-article-version
                                                                      :branch-id branch-id))]
      (testing "Insert the example-article-version"
        (is version-id))

      (testing "Can retrieve example-article-version from the DB"
        (is (match? (assoc example-article-version :branch-id branch-id)
                    (article/get-version database version-id)))
        (is (match? [(assoc example-article-version :branch-id branch-id)]
                    (article/get-branch-versions database branch-id)))))))

(deftest create-and-retrieve-article-version-test
  (let [database           (embedded-h2/fresh-db!)
        [{article-id :id}] (article/create-article! database example-article)
        [{branch-id :id}]  (article/create-article-branch! database (assoc example-article-branch
                                                                           :article-id article-id))
        [{version-id :id}] (article/create-version! database (assoc example-article-version
                                                                    :branch-id branch-id))]
    (testing "Full version table works properly"
      (is (match? [(assoc example-article-version
                          :article-id article-id
                          :branch-id  branch-id
                          :version-id version-id)]
                  (article/get-article-versions database article-id))))))


(deftest get-published-articles-test
  (let [database                 (embedded-h2/fresh-db!)
        [{article-id :id}]       (article/create-article! database example-article)
        [{older-branch-id :id}]  (article/create-article-branch! database (assoc example-article-branch
                                                                                 :published-at "2000-01-01T00:00:00Z"
                                                                                 :article-id article-id))
        [{newer-branch-id :id}]  (article/create-article-branch! database (assoc example-article-branch
                                                                                 :published-at "2010-01-01T00:00:00Z"
                                                                                 :article-id article-id))
        _                        (article/create-version! database (assoc example-article-version
                                                                          :created-at "2020-01-01T00:00:00Z"
                                                                          :branch-id older-branch-id))
        [{older-version-id :id}] (article/create-version! database (assoc example-article-version
                                                                          :created-at "1900-01-01T00:00:00Z"
                                                                          :branch-id newer-branch-id))
        [{newer-version-id :id}] (article/create-version! database (assoc example-article-version
                                                                          :created-at "1910-01-01T00:00:00Z"
                                                                          :branch-id newer-branch-id))]
    (testing "Only the newer published branch and version are found"
      (is (match? (assoc example-article-version
                         :created-at #inst "1910-01-01T00:00:00Z"
                         :article-id article-id
                         :branch-id  newer-branch-id
                         :version-id newer-version-id)
                  (article/get-published-article database article-id))))

    (testing "Can retrieve article by URL"
      (is (match? (assoc example-article-version
                         :created-at #inst "1910-01-01T00:00:00Z"
                         :article-id article-id
                         :branch-id  newer-branch-id
                         :version-id newer-version-id)
                  (article/get-published-article-by-url database (:article-url example-article)))))))
