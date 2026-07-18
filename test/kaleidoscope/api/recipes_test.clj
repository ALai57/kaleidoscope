(ns kaleidoscope.api.recipes-test
  (:require [kaleidoscope.api.recipes :as recipes]
            [kaleidoscope.api.groups :as groups]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.persistence.rdbms.embedded-postgres-impl :as embedded-pg]
            [kaleidoscope.persistence.scrape-pipeline :as pipeline-db]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match? thrown-match?]]
            [next.jdbc :as next]
            [taoensso.timbre :as log]))

;; Recipe tests run on embedded-postgres: the model depends on Postgres
;; features the test DB must actually enforce — the one-per-group UNIQUE
;; constraint and `jsonb_array_elements_text` ingredient search.
(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(def host "andrewslai.com")

(def example-content
  {:title             "Chana Masala"
   :sections          [{:name        nil
                        :ingredients ["2 cups chickpeas" "1 tbsp flour" "1 onion"]
                        :steps       ["Soak the chickpeas" "Cook"]}]
   :servings          "4"
   :prep-time-minutes 15
   :cook-time-minutes 30})

(defn example-recipe
  [& {:as overrides}]
  (merge {:hostname          host
          :recipe-url        "chana-masala"
          :content           example-content
          :author            "Andrew Lai"
          :public-visibility false}
         overrides))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Recipe CRUD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest create-and-retrieve-recipe-test
  (let [db (embedded-pg/fresh-db!)]
    (testing "recipe doesn't exist yet"
      (is (nil? (recipes/get-recipe db host "chana-masala"))))

    (testing "create returns the recipe with parsed content and empty labels"
      (is (match? {:recipe-url "chana-masala"
                   :hostname   host
                   :content    example-content
                   :labels     []}
                  (recipes/create-recipe! db (example-recipe)))))

    (testing "can retrieve it by slug, scoped to hostname"
      (is (match? {:recipe-url "chana-masala" :content example-content}
                  (recipes/get-recipe db host "chana-masala")))
      (is (nil? (recipes/get-recipe db "other.com" "chana-masala"))))))

(deftest original-content-is-immutable-test
  (let [db       (embedded-pg/fresh-db!)
        original (assoc example-content :title "Scraped Original")
        _        (recipes/create-recipe! db (example-recipe :original-content original))]
    (testing "original-content stored at create"
      (is (match? {:original-content original}
                  (recipes/get-recipe db host "chana-masala"))))

    (recipes/update-recipe! db host "chana-masala"
                            {:content (assoc example-content :title "My Edited Version")})

    (testing "update changes content but never original-content"
      (is (match? {:content          {:title "My Edited Version"}
                   :original-content original}
                  (recipes/get-recipe db host "chana-masala"))))))

(deftest hostname-scoping-test
  (let [db (embedded-pg/fresh-db!)]
    (recipes/create-recipe! db (example-recipe :hostname "andrewslai.com"))
    (recipes/create-recipe! db (example-recipe :hostname "caheriaguilar.com"))
    (testing "each tenant sees only its own recipes"
      (is (= 1 (count (recipes/get-recipes db {:hostname "andrewslai.com"}))))
      (is (= 1 (count (recipes/get-recipes db {:hostname "caheriaguilar.com"})))))))

(deftest delete-recipe-test
  (let [db (embedded-pg/fresh-db!)]
    (recipes/create-recipe! db (example-recipe))
    (recipes/delete-recipe! db host "chana-masala")
    (is (nil? (recipes/get-recipe db host "chana-masala")))))

(deftest rename-recipe-url-test
  (let [db (embedded-pg/fresh-db!)]
    (recipes/create-recipe! db (example-recipe))
    (testing "renaming the slug returns the recipe at its new address"
      (is (match? {:recipe-url "chana-masala-v2" :content example-content}
                  (recipes/update-recipe! db host "chana-masala" {:recipe-url "chana-masala-v2"}))))
    (testing "old slug no longer resolves; new one does; identity is preserved"
      (is (nil? (recipes/get-recipe db host "chana-masala")))
      (is (match? {:recipe-url "chana-masala-v2"} (recipes/get-recipe db host "chana-masala-v2"))))))

(deftest rename-to-existing-slug-conflicts-test
  (let [db (embedded-pg/fresh-db!)]
    (recipes/create-recipe! db (example-recipe :recipe-url "chana-masala"))
    (recipes/create-recipe! db (example-recipe :recipe-url "pad-thai"))
    (testing "renaming onto another recipe's slug throws a :conflict"
      (is (thrown-match? clojure.lang.ExceptionInfo
                         {:type :conflict}
                         (recipes/update-recipe! db host "chana-masala" {:recipe-url "pad-thai"}))))
    (testing "renaming a slug to itself is allowed (no-op collision)"
      (is (match? {:recipe-url "chana-masala"}
                  (recipes/update-recipe! db host "chana-masala" {:recipe-url "chana-masala"}))))))

(deftest create-recipe-links-to-processing-run-test
  (let [db (embedded-pg/fresh-db!)
        {raw-id :id} (pipeline-db/create-raw-scrape!
                      db {:hostname host :source-kind "url" :request-url "http://x/r"
                          :final-url "http://x/r" :http-status 200
                          :fetch-tier "direct" :raw-content "<html/>"})
        {run-id :id} (pipeline-db/create-processing-run!
                      db {:hostname host :raw-scrape-id raw-id :pipeline-version "v"
                          :techniques {:acquire :direct :parse :json-ld :normalize :single-section}
                          :content example-content :facts {:labels []}
                          :outcome :success})]
    (testing "a recipe created with a run-id persists the FK and returns it on read"
      (recipes/create-recipe! db (example-recipe :scrape-processing-run-id run-id))
      (is (match? {:scrape-processing-run-id run-id}
                  (recipes/get-recipe db host "chana-masala"))))
    (testing "a recipe created without a run-id has a nil link"
      (recipes/create-recipe! db (example-recipe :recipe-url "no-link"))
      (is (match? {:scrape-processing-run-id nil?}
                  (recipes/get-recipe db host "no-link"))))
    (testing "deleting the linked run does NOT delete the recipe — the link is nulled (ON DELETE SET NULL)"
      (next/execute! db ["DELETE FROM processing_runs WHERE id = ?" run-id])
      (is (match? {:recipe-url "chana-masala" :scrape-processing-run-id nil?}
                  (recipes/get-recipe db host "chana-masala"))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Labels + groups
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest label-and-group-crud-test
  (let [db          (embedded-pg/fresh-db!)
        {gid :id}   (recipes/create-label-group! db {:hostname host :name "ethnicity"})
        {lid :id}   (recipes/create-label! db {:hostname host :name "indian" :group-id gid})
        {ungrouped :id} (recipes/create-label! db {:hostname host :name "baking"})]
    (testing "labels carry group name for qualified display"
      (is (match? [{:name "baking"  :group-name nil?}
                   {:name "indian"  :group-name "ethnicity"}]
                  (sort-by :name (recipes/get-labels db {:hostname host})))))

    (testing "rename label"
      (recipes/rename-label! db lid host "north-indian")
      (is (some #(= "north-indian" (:name %)) (recipes/get-labels db {:hostname host}))))

    (testing "deleting a group cascades its labels"
      (recipes/delete-label-group! db gid host)
      (is (match? [{:name "baking"}]
                  (recipes/get-labels db {:hostname host}))))))

(deftest label-assignment-test
  (let [db        (embedded-pg/fresh-db!)
        {gid :id} (recipes/create-label-group! db {:hostname host :name "ethnicity"})
        {indian :id}  (recipes/create-label! db {:hostname host :name "indian" :group-id gid})
        {baking :id}  (recipes/create-label! db {:hostname host :name "baking"})
        recipe    (recipes/create-recipe! db (example-recipe :label-ids [indian baking]))]
    (testing "labels attached on create"
      (is (match? #{"indian" "baking"}
                  (set (map :name (:labels recipe))))))

    (testing "label filter finds the recipe"
      (is (= 1 (count (recipes/get-recipes db {:hostname host :label-id indian})))))))

(deftest one-label-per-group-test
  (let [db        (embedded-pg/fresh-db!)
        {gid :id} (recipes/create-label-group! db {:hostname host :name "ethnicity"})
        {indian :id}  (recipes/create-label! db {:hostname host :name "indian" :group-id gid})
        {mexican :id} (recipes/create-label! db {:hostname host :name "mexican" :group-id gid})]
    (testing "two labels from the same group are rejected with a validation error"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"one label per group"
                            (recipes/create-recipe! db (example-recipe :label-ids [indian mexican])))))

    (testing "the rejected create rolled back — no recipe was written"
      (is (nil? (recipes/get-recipe db host "chana-masala"))))

    (testing "one label from the group is fine, and can be replaced within the group"
      (recipes/create-recipe! db (example-recipe :label-ids [indian]))
      (is (match? [{:name "indian"}] (:labels (recipes/get-recipe db host "chana-masala"))))
      (recipes/update-recipe! db host "chana-masala" {:label-ids [mexican]})
      (is (match? [{:name "mexican"}] (:labels (recipes/get-recipe db host "chana-masala")))))))

(deftest unknown-label-rejected-test
  (let [db (embedded-pg/fresh-db!)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"do not exist"
                          (recipes/create-recipe! db (example-recipe :label-ids [(java.util.UUID/randomUUID)]))))))

(deftest cross-tenant-label-rejected-test
  (let [db          (embedded-pg/fresh-db!)
        {other :id} (recipes/create-label! db {:hostname "caheriaguilar.com" :name "secret"})]
    (testing "a recipe on one host cannot be assigned another host's label"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"do not exist"
                            (recipes/create-recipe! db (example-recipe :hostname host :label-ids [other])))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Ingredient search
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest ingredient-search-test
  (let [db (embedded-pg/fresh-db!)]
    (recipes/create-recipe! db (example-recipe
                                :recipe-url "cake"
                                :content {:title    "Layer Cake"
                                          :sections [{:name        "Cake"
                                                      :ingredients ["2 cups flour" "1 cup sugar"]
                                                      :steps       ["Mix" "Bake"]}
                                                     {:name        "Frosting"
                                                      :ingredients ["1 cup butter" "powdered sugar"]
                                                      :steps       ["Whip"]}]}))
    (recipes/create-recipe! db (example-recipe
                                :recipe-url "salad"
                                :content {:title    "Salad"
                                          :sections [{:name        nil
                                                      :ingredients ["lettuce" "tomato"]
                                                      :steps       ["Toss"]}]}))
    (testing "matches an ingredient line inside the SECOND section"
      (is (match? [{:recipe-url "cake"}]
                  (recipes/get-recipes db {:hostname host :ingredient "butter"}))))
    (testing "text-contains match across all sections and recipes"
      (is (= 2 (count (recipes/get-recipes db {:hostname host :ingredient "o"})))))
    (testing "no match returns empty"
      (is (empty? (recipes/get-recipes db {:hostname host :ingredient "beef"}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Visibility / sharing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest visibility-filter-test
  (let [db          (embedded-pg/fresh-db!)
        public      (recipes/create-recipe! db (example-recipe :recipe-url "public" :public-visibility true))
        shared      (recipes/create-recipe! db (example-recipe :recipe-url "shared" :public-visibility false))
        _hidden     (recipes/create-recipe! db (example-recipe :recipe-url "hidden" :public-visibility false))
        [{gid :id}] (groups/create-group! db {:display-name "friends" :owner-id "user-1" :hostname host})
        _           (groups/add-users-to-group! db "user-1" gid {:email "member@x.com" :alias "m"})
        _           (recipes/add-audience-to-recipe! db (assoc shared :recipe-url "shared") {:id gid})]
    (testing "a group member sees public + shared, not hidden"
      (is (match? #{"public" "shared"}
                  (set (map :recipe-url (recipes/get-visible-recipes db {:hostname host} {:email "member@x.com"} false))))))

    (testing "a non-member sees only public"
      (is (match? #{"public"}
                  (set (map :recipe-url (recipes/get-visible-recipes db {:hostname host} {:email "stranger@x.com"} false))))))

    (testing "an anonymous caller (no email) sees only public"
      (is (match? #{"public"}
                  (set (map :recipe-url (recipes/get-visible-recipes db {:hostname host} {} false))))))

    (testing "a writer sees every recipe for the tenant, including hidden drafts"
      (is (match? #{"public" "shared" "hidden"}
                  (set (map :recipe-url (recipes/get-visible-recipes db {:hostname host} {:email "stranger@x.com"} true))))))))

(deftest audience-idempotency-test
  (let [db        (embedded-pg/fresh-db!)
        recipe    (recipes/create-recipe! db (example-recipe))
        [{gid :id}] (groups/create-group! db {:display-name "g" :owner-id "u" :hostname host})]
    (let [[{aid :id}] (recipes/add-audience-to-recipe! db recipe {:id gid})]
      (testing "adding the same audience twice does not create a duplicate"
        (is (match? [{:id aid}] (recipes/add-audience-to-recipe! db recipe {:id gid}))))
      (testing "can delete an audience"
        (recipes/delete-recipe-audience! db aid)
        (is (empty? (recipes/get-recipe-audiences db {:id aid})))))))

(deftest recipe-import-lineage-test
  (let [db       (embedded-pg/fresh-db!)
        raw-html "<html>recipe</html>"
        {raw-id :id} (pipeline-db/create-raw-scrape!
                      db {:hostname host :source-kind "url"
                          :request-url "http://example.com/r" :final-url "http://example.com/r"
                          :http-status 200 :fetch-tier "direct" :raw-content raw-html})
        {run-id :id} (pipeline-db/create-processing-run!
                      db {:hostname host :raw-scrape-id raw-id :pipeline-version "abc123"
                          :techniques {:acquire :direct :parse :json-ld :normalize :single-section}
                          :facts   {:title "Chana Masala" :ingredients ["2 cups chickpeas"]
                                    :steps ["Cook"] :section-signals [] :labels []}
                          :content example-content
                          :llm-calls [] :warnings [] :outcome :success :error-detail nil})
        _        (recipes/create-recipe! db (example-recipe :scrape-processing-run-id run-id))]

    (testing "assembles run + raw for a scraped recipe; raw body omitted by default"
      (is (match? {:recipe-url "chana-masala"
                   :recipe-id  uuid?
                   :run  {:pipeline-version "abc123"
                          :outcome          "success"
                          :techniques       {:parse "json-ld"}
                          :content          {:title "Chana Masala"}}
                   :raw  {:http-status   200
                          :fetch-tier    "direct"
                          :content-bytes (count raw-html)
                          :raw-content   nil?}}
                  (recipes/get-recipe-lineage db host "chana-masala" false))))

    (testing "include-raw? returns the stored raw body"
      (is (match? {:raw {:raw-content raw-html}}
                  (recipes/get-recipe-lineage db host "chana-masala" true))))

    (testing "a recipe with no linked run has no lineage"
      (recipes/create-recipe! db (example-recipe :recipe-url "no-run"))
      (is (nil? (recipes/get-recipe-lineage db host "no-run" false))))

    (testing "a nonexistent recipe has no lineage"
      (is (nil? (recipes/get-recipe-lineage db host "does-not-exist" false))))

    (testing "scoped to hostname — another host sees nothing"
      (is (nil? (recipes/get-recipe-lineage db "other.com" "chana-masala" false))))))

(deftest save-and-read-timeline-test
  (let [db (embedded-pg/fresh-db!)
        _  (recipes/create-recipe! db (example-recipe))
        tl {:version 1 :generator-version 1 :generated-at "t0" :total-minutes 30
            :overrides []
            :components [{:name "Section 1" :steps-hash "h"
                          :phases [{:id "Section 1/cook" :label "cook" :kind "active"
                                    :steps [0 1] :estimate 30 :deps [] :start 0}]}]}]
    (testing "save returns the recipe carrying the decoded timeline"
      (is (match? {:recipe-url "chana-masala" :timeline {:total-minutes 30 :overrides []}}
                  (recipes/save-timeline! db host "chana-masala" tl))))
    (testing "a subsequent read decodes the timeline to a map"
      (is (match? {:timeline {:components [{:name "Section 1"}]}}
                  (recipes/get-recipe db host "chana-masala"))))
    (testing "saving a timeline does not bump modified-at"
      (let [before (:modified-at (recipes/get-recipe db host "chana-masala"))]
        (recipes/save-timeline! db host "chana-masala" (assoc tl :total-minutes 31))
        (is (= before (:modified-at (recipes/get-recipe db host "chana-masala"))))))))

(deftest scoped-handle-confines-recipe-reads-across-tenants-test
  ;; Recipes already thread hostname explicitly into every read, but they must
  ;; also be safe to query through a tenant/scope handle (a TenantConn must not
  ;; crash the raw next/execute! reads, and find-by-keys reads must confine).
  (let [db      (embedded-pg/fresh-db!)
        andrew  (tenant/scope db "andrewslai.com")
        _       (recipes/create-recipe! db (example-recipe :hostname "andrewslai.com" :recipe-url "a-dish"))
        _       (recipes/create-recipe! db (example-recipe :hostname "caheriaguilar.com" :recipe-url "c-dish"))
        _       (recipes/create-label-group! db {:hostname "andrewslai.com" :name "cuisine"})
        _       (recipes/create-label-group! db {:hostname "caheriaguilar.com" :name "cuisine"})]

    (testing "raw-SQL read (get-recipes) is safe through a scoped handle and confines"
      (is (= #{"a-dish"} (set (map :recipe-url (recipes/get-recipes andrew {:hostname "andrewslai.com"})))))
      ;; the handle does not crash even when the query map omits hostname keys downstream
      (is (seq (recipes/get-recipes andrew {:hostname "andrewslai.com"}))))

    (testing "find-by-keys read (get-label-groups) confines through a scoped handle with no :hostname key"
      (is (match? [{:hostname "andrewslai.com" :name "cuisine"}]
                  (recipes/get-label-groups andrew {}))))))
