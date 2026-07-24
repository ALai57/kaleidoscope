(ns kaleidoscope.api.articles-test
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.api.articles :as articles]
            [kaleidoscope.api.groups :as groups]
            [kaleidoscope.api.groups-test :as groups-test]
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
                        :hostname      "andrewslai.com"
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
                        :hostname     "andrewslai.com"
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
                                                  :hostname     "andrewslai.com"
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
    (let [database       (embedded-h2/fresh-db!)
          article-branch {:article-tags "thoughts"
                          :article-url  "my-test-article"
                          :author       "Andrew Lai"
                          :hostname     "andrewslai.com"
                          :branch-name  "my-new-branch"}
          version        {:content "<p>Hello</p>"}

          [{:keys [version-id] :as v}] (articles/new-version! database
                                                              article-branch
                                                              version)]

      (testing "Create a new version"
        (is version-id))

      (testing "Can retrieve newly created version from the DB"
        (is (match? [version]
                    (articles/get-versions database {:version-id version-id}))))))
  (testing "Uses existing article and branch"
    (let [database       (embedded-h2/fresh-db!)
          article-branch {:article-tags "thoughts"
                          :article-url  "my-test-article"
                          :author       "Andrew Lai"
                          :hostname     "andrewslai.com"
                          :branch-name  "my-new-branch"}
          version        {:content "<p>Hello</p>"}

          [{:keys [branch-id] :as article-branch}] (articles/create-branch! database article-branch)
          [{:keys [version-id] :as v}]             (articles/new-version! database
                                                                          article-branch
                                                                          version)]

      (testing "Create a new version"
        (is version-id))

      (testing "Can retrieve newly created version from the DB"
        (is (match? [version]
                    (articles/get-versions database {:version-id version-id})))))))

(deftest multiple-branches-for-same-article
  (let [database       (embedded-h2/fresh-db!)
        article-branch {:article-tags "thoughts"
                        :article-url  "my-test-article"
                        :author       "Andrew Lai"
                        :hostname     "andrewslai.com"}

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
                        :author       "Andrew Lai"
                        :hostname     "andrewslai.com"}
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
                  (articles/publish-branch! database old-branch-id "andrewslai.com" "2000-01-01T00:00:00Z")))
      (is (match? [{:published-at inst?}]
                  (articles/publish-branch! database new-branch-id "andrewslai.com" "2050-01-01T00:00:00Z"))))

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
                  (articles/-get-published-articles database {:article-id article-id}))))

    (testing "Can retrieve article by URL"
      (is (match? [(assoc article-branch
                          :created-at #inst "2050-01-01T00:00:00Z"
                          :article-id article-id
                          :branch-id  new-branch-id
                          :version-id new-version-id)]
                  (articles/-get-published-articles database {:article-url (:article-url article-branch)}))))

    (testing "Can unpublish branch"
      (is (match? [{:published-at nil?}]
                  (articles/unpublish-branch! database old-branch-id "andrewslai.com")))
      (is (match? [{:published-at nil?}]
                  (articles/unpublish-branch! database new-branch-id "andrewslai.com")))
      (is (empty? (articles/-get-published-articles database {:article-url (:article-url article-branch)}))))
    ))


(deftest get-published-articles-seed-test
  (let [database       (embedded-h2/fresh-db!)]
    (testing "Seed works properly"
      (is (= 4 (count (articles/-get-published-articles database)))))))

;;;;;;;;;;;;;;;;;;;;;;;
(def example-group
  {:display-name "mygroup"
   :owner-id     "user-1"
   :hostname     "andrewslai.com"})

(deftest get-published-articles-audience-test
  (let [database                (embedded-h2/fresh-db!)
        [{new-group-id :id}]    (groups/create-group! database example-group)
        [{membership-id-1 :id}] (groups/add-users-to-group! database "user-1" new-group-id {:email "b@z.com"
                                                                                            :alias "foo"})
        [{audience-id :id}]     (articles/add-audience-to-article! database
                                                                   {:id       1 ;; Fixture -  my-first-article
                                                                    :hostname "andrewslai.com"}
                                                                   {:id new-group-id})
        [{audience-id :id}]     (articles/add-audience-to-article! database
                                                                   {:id       2 ;; Fixture -  my-first-article
                                                                    :hostname "andrewslai.com"}
                                                                   {:id new-group-id})
        ]

    ;; Fixture data
    (testing "User in `mygroup` can view articles with `mygroup` as an audience"
      (is (match? [{:article-tags "thoughts"
                    :article-url  "my-first-article"
                    :article-id   1
                    :author       "Andrew Lai"
                    :hostname     "andrewslai.com"}
                   {:article-tags "thoughts"
                    :article-url  "my-second-article"
                    :article-id   2
                    :author       "Andrew Lai"
                    :hostname     "andrewslai.com"}]
                  (articles/get-published-articles database
                                                   {:hostname "andrewslai.com"}
                                                   {:email "b@z.com"}))))
    (testing "User not in any group cannot view any articles"
      (is (match? []
                  (articles/get-published-articles database
                                                   {:hostname "andrewslai.com"}
                                                   {:email "missing-group@nowhere.com"}))))

    ))


(deftest create-and-retrieve-audience-test
  (let [database         (embedded-h2/fresh-db!)
        [{group-id :id}] (groups/create-group! database groups-test/example-group)]
    (testing "0 example audiences seeded in DB"
      (is (= 0 (count (articles/get-article-audiences database)))))

    (testing "Fail to add audience if hostname does not match article hostname"
      (is (nil? (articles/add-audience-to-article! database
                                                   {:id       1
                                                    :hostname "does-not-match"}
                                                   {:id group-id})))
      (is (empty? (articles/get-article-audiences database {}))))

    ;; Use article ID 1 below because it is seeded in the DB with hostname
    ;; `andrewslai.com`
    (let [[{:keys [id]}] (articles/add-audience-to-article! database
                                                            {:id       1
                                                             :hostname "andrewslai.com"}
                                                            {:id group-id})]
      (testing "Add an audience"
        (is (uuid? id)))

      (testing "Can retrieve the audience from the DB"
        (is (match? [{:id       id
                      :hostname "andrewslai.com"}]
                    (articles/get-article-audiences database {:id id}))))

      (testing "Duplicate audience insert does not blow up"
        (is (match? [{:id id}]
                    (articles/add-audience-to-article! database
                                                       {:id       1
                                                        :hostname "andrewslai.com"}
                                                       {:id group-id}))))

      (testing "Can delete a audience"
        (articles/delete-article-audience! database id)

        (is (empty? (articles/get-article-audiences database {:id id})))))))

(deftest article-children-cannot-cross-tenants-at-db-level-test
  ;; The cross-site-branch bug used to be preventable only in application code.
  ;; With composite (id, hostname) FKs the DATABASE now forbids attaching a
  ;; branch/version to a parent on a different tenant — defense in the schema,
  ;; not just the handler. These insert directly, bypassing the code checks.
  (let [database        (embedded-h2/fresh-db!)
        now             "2022-01-01T00:00:00Z"
        [{article-id :id}] (articles/create-article! database {:article-url "victim"
                                                               :hostname    "sahiltalkingcents.com"
                                                               :author      "victim"})]
    (testing "a branch cannot be attached to an article under a different hostname"
      (is (thrown? Exception
                   (rdbms/insert! database :article-branches
                                  {:branch-name "attacker" :article-id article-id
                                   :hostname    "andrewslai.com"
                                   :created-at  now :modified-at now}))))

    (testing "the legitimate same-hostname attachment succeeds"
      (let [[{branch-id :id}] (rdbms/insert! database :article-branches
                                             {:branch-name "legit" :article-id article-id
                                              :hostname    "sahiltalkingcents.com"
                                              :created-at  now :modified-at now})]
        (is branch-id)
        (testing "a version cannot cross to a different hostname than its branch"
          (is (thrown? Exception
                       (rdbms/insert! database :article-versions
                                      {:branch-id branch-id :content "x"
                                       :hostname  "andrewslai.com"
                                       :created-at now :modified-at now}))))))))

(deftest tenant-scoped-reads-do-not-leak-across-sites
  ;; The complement to cross-site-branch-manipulation-test: instead of
  ;; remembering to thread :hostname into every query map (and leaking the
  ;; moment someone forgets — see http_api GET /articles/:url, /branches,
  ;; and /branches/:id/versions), a `tenant/scope` handle carries the
  ;; hostname structurally, so a read simply CANNOT observe another site.
  (let [raw (embedded-h2/fresh-db!)
        ;; article_url is globally unique, so each site owns a distinct url.
        ;; The leak is that a visitor on site A can pull site B's article by
        ;; url — the scoped handle must instead return nothing for B's url.
        _   (articles/new-version! raw {:article-url "andrew-post" :hostname "andrewslai.com"
                                        :branch-name "main" :author "andrew"}
                                   {:content "andrew's content"})
        _   (articles/new-version! raw {:article-url "caheri-post" :hostname "caheriaguilar.com"
                                        :branch-name "main" :author "caheri"}
                                   {:content "caheri's content"})
        andrew (tenant/scope raw "andrewslai.com")]

    (testing "a raw (unscoped) handle happily pulls the OTHER site's article — the leak"
      (is (= 1 (count (articles/get-articles raw {:article-url "caheri-post"})))))

    (testing "get-articles through a scoped handle cannot see the other site's article,
              even though the query map omits :hostname"
      (is (empty? (articles/get-articles andrew {:article-url "caheri-post"})))
      (is (match? [{:hostname "andrewslai.com" :author "andrew"}]
                  (articles/get-articles andrew {:article-url "andrew-post"}))))

    (testing "get-branches through a scoped handle is confined to this site"
      (is (empty? (articles/get-branches andrew {:article-url "caheri-post"})))
      (is (match? [{:hostname "andrewslai.com"}]
                  (articles/get-branches andrew {:article-url "andrew-post"}))))

    (testing "get-versions for another site's branch-id returns nothing through this handle"
      (let [other-branch-id (->> (articles/get-versions raw {})
                                 (filter #(= "caheriaguilar.com" (:hostname %)))
                                 first
                                 :branch-id)]
        (is (some? other-branch-id))
        (is (empty? (articles/get-versions andrew {:branch-id other-branch-id})))))

    (testing "the no-arg (fetch-all) finder is also confined to the scoped site"
      (let [rows (articles/get-articles andrew)]
        (is (seq rows))
        (is (every? #(= "andrewslai.com" (:hostname %)) rows))))))

(deftest tenant-scoped-writes-survive-transactions
  ;; create-branch! opens an internal next/with-transaction; the scoped
  ;; handle must survive it (Transactable), and the internal reads it does
  ;; (article-id lookups) must stay confined to the handle's hostname.
  (let [raw    (embedded-h2/fresh-db!)
        andrew (tenant/scope raw "andrewslai.com")
        [branch] (articles/create-branch! andrew {:article-url   "scoped-write"
                                                  :branch-name   "main"
                                                  :article-tags  "thoughts"
                                                  :article-title "Scoped"
                                                  :hostname      "andrewslai.com"
                                                  :author        "andrew"})]
    (testing "create-branch! succeeds when handed a scoped handle"
      (is (some? branch)))

    (testing "the branch is visible to a handle for the same site"
      (is (match? [{:hostname "andrewslai.com"}]
                  (articles/get-branches andrew {:article-url "scoped-write"}))))

    (testing "the branch is invisible to a handle for a different site"
      (is (empty? (articles/get-branches (tenant/scope raw "caheriaguilar.com")
                                         {:article-url "scoped-write"}))))))

(deftest cross-site-branch-manipulation-test
  (let [database        (embedded-h2/fresh-db!)
        victim-hostname "sahiltalkingcents.com"
        attacker-host   "andrewslai.com"
        [victim-article] (articles/create-article! database {:article-url "victim-post"
                                                              :hostname    victim-hostname
                                                              :author      "victim"})]

    (testing "A writer on a different site cannot attach a branch to this site's article
              by supplying its article-id"
      (is (nil? (articles/create-branch! database {:article-id  (:id victim-article)
                                                    :branch-name "attacker-branch"
                                                    :hostname    attacker-host
                                                    :author      "attacker"})))
      (is (empty? (articles/get-branches database {:article-id (:id victim-article)}))))

    (testing "The same site's writer can legitimately attach a branch to its own article"
      (is (some? (articles/create-branch! database {:article-id  (:id victim-article)
                                                     :branch-name "legit-branch"
                                                     :hostname    victim-hostname
                                                     :author      "victim"}))))

    (articles/new-version! database {:article-url "victim-draft"
                                     :hostname    victim-hostname
                                     :branch-name "main"
                                     :author      "victim"}
                           {:content "unpublished draft content"})

    (testing "A writer on a different site cannot force-publish this site's draft branch"
      (let [target (first (articles/get-branches database {:article-url "victim-draft"}))]
        (is (nil? (articles/publish-branch! database (:branch-id target) attacker-host)))
        (is (nil? (:published-at (first (articles/get-branches database
                                                                {:article-url "victim-draft"
                                                                 :hostname    victim-hostname})))))))

    (testing "The site's own writer can publish its own draft branch"
      (let [target (first (articles/get-branches database {:article-url "victim-draft"}))]
        (is (some? (:published-at (first (articles/publish-branch! database (:branch-id target) victim-hostname)))))))

    (testing "A writer on a different site cannot unpublish this site's now-published branch"
      (let [target (first (articles/get-branches database {:article-url "victim-draft"}))]
        (is (nil? (articles/unpublish-branch! database (:branch-id target) attacker-host)))
        (is (some? (:published-at (first (articles/get-branches database
                                                                 {:article-url "victim-draft"
                                                                  :hostname    victim-hostname})))))))))
