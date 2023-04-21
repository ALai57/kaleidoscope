(ns kaleidoscope.api.articles-test
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.api.articles :as articles]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.test-main :as tm]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(def example-article
  {:article-tags  "thoughts"
   :article-url   "my-test-article"
   :article-title "My Test Article"
   :hostname      "andrewslai.com"
   :author        "Andrew Lai"})

(deftest create-and-retrieve-articles-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "example-article doesn't exist in the database"
      (is (empty? (articles/get-articles database (select-keys example-article [:article-url])))))

    (testing "Insert the example-article"
      (is (articles/create-article! database example-article)))

    (testing "Can retrieve example-article from the DB"
      (is (match? [example-article]
                  (articles/get-articles database (select-keys example-article [:article-url])))))))

(deftest create-and-retrieve-article-branches-test
  (let [database       (embedded-h2/fresh-db!)
        article-branch {:article-tags  "thoughts"
                        :article-url   "my-test-article"
                        :article-title "My Test Article"
                        :author        "Andrew Lai"
                        :branch-name   "my-new-branch"}]

    (testing "example-article-branch doesn't exist in the database"
      (is (empty? (articles/get-branches database {:branch-name (:branch-name article-branch)}))))

    (let [[{:keys [article-id branch-id]}] (articles/create-branch! database article-branch)]
      (testing "Insert the example-article-branch"
        (is (and branch-id article-id)))

      (testing "Can retrieve example-article from the DB"
        (is (match? [(merge article-branch
                            {:article-id article-id
                             :branch-id  branch-id})]
                    (articles/get-branches database {:branch-id branch-id})))
        (is (match? [(merge article-branch
                            {:article-id article-id
                             :branch-id  branch-id})]
                    (articles/get-branches database {:article-id article-id})))))))

;; CAREFUL - this uses redefs
(deftest create-and-retrieve-article-branches-can-handle-concurrency
  (let [database       (embedded-h2/fresh-db!)
        article-branch {:article-tags "thoughts"
                        :article-url  "my-test-article"
                        :author       "Andrew Lai"
                        :branch-name  "my-new-branch"}]

    (testing "example-article-branch doesn't exist in the database"
      (is (empty? (articles/get-branches database {:branch-name (:branch-name article-branch)}))))


    (with-redefs [rdbms/insert! (fn [& args] (throw (ex-info "Boom!" {})))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Boom!"
                            (articles/create-branch! database article-branch))))

    (testing "example-article-branch still doesn't exist in the database"
      (is (empty? (articles/get-branches database {:branch-name (:branch-name article-branch)}))))

    (articles/create-branch! database article-branch)

    (testing "example-article-branch is in the DB"
      (is (not-empty (articles/get-branches database {:branch-name (:branch-name article-branch)}))))))

(deftest create-and-retrieve-article-version-test
  (let [database                                 (embedded-h2/fresh-db!)
        article-branch                           {:article-tags "thoughts"
                                                  :article-url  "my-test-article"
                                                  :author       "Andrew Lai"
                                                  :branch-name  "my-new-branch"}
        version                                  {:content "<p>Hello</p>"}
        [{:keys [branch-id] :as article-branch}] (articles/create-branch! database article-branch)]

    (testing "No versions exist for the given branch to start"
      (is (empty? (articles/get-versions database {:branch-id branch-id}))))

    (let [[{:keys [version-id] :as v}] (articles/create-version! database
                                                                 {:branch-id branch-id}
                                                                 version)]
      (testing "Create a new version"
        (is version-id))

      (testing "Can retrieve newly created version from the DB"
        (is (match? [version]
                    (articles/get-versions database {:version-id version-id})))
        (is (match? [version]
                    (articles/get-versions database {:branch-id branch-id})))))))

(deftest new-version-test
  (testing "Creates article and branch when they don't already exist"
    (let [database                                 (embedded-h2/fresh-db!)
          article-branch                           {:article-tags "thoughts"
                                                    :article-url  "my-test-article"
                                                    :author       "Andrew Lai"
                                                    :branch-name  "my-new-branch"}
          version                                  {:content "<p>Hello</p>"}]

      (let [[{:keys [version-id] :as v}] (articles/new-version! database
                                                                article-branch
                                                                version)]
        (testing "Create a new version"
          (is version-id))

        (testing "Can retrieve newly created version from the DB"
          (is (match? [version]
                      (articles/get-versions database {:version-id version-id})))))))
  (testing "Uses existing article and branch"
    (let [database                                 (embedded-h2/fresh-db!)
          article-branch                           {:article-tags "thoughts"
                                                    :article-url  "my-test-article"
                                                    :author       "Andrew Lai"
                                                    :branch-name  "my-new-branch"}
          version                                  {:content "<p>Hello</p>"}
          [{:keys [branch-id] :as article-branch}] (articles/create-branch! database article-branch)]

      (let [[{:keys [version-id] :as v}] (articles/new-version! database
                                                                article-branch
                                                                version)]
        (testing "Create a new version"
          (is version-id))

        (testing "Can retrieve newly created version from the DB"
          (is (match? [version]
                      (articles/get-versions database {:version-id version-id}))))))))

(deftest multiple-branches-for-same-article
  (let [database       (embedded-h2/fresh-db!)
        article-branch {:article-tags "thoughts"
                        :article-url  "my-test-article"
                        :author       "Andrew Lai"}

        [{article-id    :article-id
          old-branch-id :branch-id}] (articles/create-branch! database
                                                              (assoc article-branch
                                                                     :branch-name "my-old-branch"))

        [{new-branch-id :branch-id}] (articles/create-branch! database
                                                              (assoc article-branch
                                                                     :branch-name "my-new-branch"
                                                                     :article-id  article-id))]
    (testing "Both branches are created"
      (is (match? [{:branch-id old-branch-id}
                   {:branch-id new-branch-id}]
                  (articles/get-branches database {:article-id article-id})))
      (is (= 2 (count (articles/get-branches database {:article-id article-id})))))

    (testing "Cannot create two branches with the same name for the same article"
      (log/with-min-level :fatal
        (is (thrown? clojure.lang.ExceptionInfo
                     (articles/create-branch! database
                                              (assoc article-branch
                                                     :branch-name "my-old-branch"))))))))

(deftest get-published-articles-test
  (let [database       (embedded-h2/fresh-db!)
        article-branch {:article-tags "thoughts"
                        :article-url  "my-test-article"
                        :author       "Andrew Lai"}
        version        {:content "<p>Hello</p>"}


        [{old-branch-id :branch-id
          article-id    :article-id}] (articles/create-branch! database
                                                               (assoc article-branch
                                                                      :branch-name  "my-old-branch"))
        [{new-branch-id :branch-id}]  (articles/create-branch! database
                                                               (assoc article-branch
                                                                      :branch-name  "my-new-branch"
                                                                      :article-id   article-id))

        _                              (articles/create-version! database
                                                                 {:branch-id  old-branch-id}
                                                                 (assoc version
                                                                        :created-at "1900-01-01T00:00:00Z"))
        [{old-version-id :version-id}] (articles/create-version! database
                                                                 {:branch-id  new-branch-id}
                                                                 (assoc version
                                                                        :created-at "2000-01-01T00:00:00Z"))
        [{new-version-id :version-id}] (articles/create-version! database
                                                                 {:branch-id  new-branch-id}
                                                                 (assoc version
                                                                        :created-at "2050-01-01T00:00:00Z"))]


    (testing "Can publish branches"
      (is (match? [{:published-at inst?}]
                  (articles/publish-branch! database old-branch-id "2000-01-01T00:00:00Z")))
      (is (match? [{:published-at inst?}]
                  (articles/publish-branch! database new-branch-id "2050-01-01T00:00:00Z"))))

    (testing "Cannot create new version on published branch"
      (is (thrown? clojure.lang.ExceptionInfo
                   (articles/new-version! database
                                          {:branch-id  new-branch-id}
                                          (assoc version
                                                 :created-at "4321-01-01T00:00:00Z")))))

    (testing "Only the newer published branch and version are found"
      (is (match? [(assoc article-branch
                          :created-at #inst "2050-01-01T00:00:00Z"
                          :article-id article-id
                          :branch-id  new-branch-id
                          :version-id new-version-id)]
                  (articles/get-published-articles database {:article-id article-id}))))

    (testing "Can retrieve article by URL"
      (is (match? [(assoc article-branch
                          :created-at #inst "2050-01-01T00:00:00Z"
                          :article-id article-id
                          :branch-id  new-branch-id
                          :version-id new-version-id)]
                  (articles/get-published-articles database {:article-url (:article-url article-branch)}))))

    (testing "Can unpublish branch"
      (is (match? [{:published-at nil?}]
                  (articles/unpublish-branch! database old-branch-id)))
      (is (match? [{:published-at nil?}]
                  (articles/unpublish-branch! database new-branch-id)))
      (is (empty? (articles/get-published-articles database {:article-url (:article-url article-branch)}))))
    ))


(deftest get-published-articles-seed-test
  (let [database       (embedded-h2/fresh-db!)]
    (testing "Seed works properly"
      (is (= 4 (count (articles/get-published-articles database)))))))
