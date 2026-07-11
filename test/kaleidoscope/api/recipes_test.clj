(ns kaleidoscope.api.recipes-test
  (:require [kaleidoscope.api.recipes :as recipes]
            [kaleidoscope.api.groups :as groups]
            [kaleidoscope.persistence.rdbms.embedded-postgres-impl :as embedded-pg]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
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
   :ingredients       ["2 cups chickpeas" "1 tbsp flour" "1 onion"]
   :instructions-html "<ol><li>Cook</li></ol>"
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
    (recipes/create-recipe! db (example-recipe :recipe-url "chana"
                                               :content (assoc example-content :ingredients ["chickpeas" "flour"])))
    (recipes/create-recipe! db (example-recipe :recipe-url "salad"
                                               :content (assoc example-content :ingredients ["lettuce" "tomato"])))
    (testing "text-contains match over ingredients"
      (is (match? [{:recipe-url "chana"}]
                  (recipes/get-recipes db {:hostname host :ingredient "flour"})))
      (is (= 2 (count (recipes/get-recipes db {:hostname host :ingredient "o"}))))
      (is (empty? (recipes/get-recipes db {:hostname host :ingredient "beef"}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Visibility / sharing
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(deftest visibility-filter-test
  (let [db          (embedded-pg/fresh-db!)
        public      (recipes/create-recipe! db (example-recipe :recipe-url "public" :public-visibility true))
        shared      (recipes/create-recipe! db (example-recipe :recipe-url "shared" :public-visibility false))
        _hidden     (recipes/create-recipe! db (example-recipe :recipe-url "hidden" :public-visibility false))
        [{gid :id}] (groups/create-group! db {:display-name "friends" :owner-id "user-1"})
        _           (groups/add-users-to-group! db "user-1" gid {:email "member@x.com" :alias "m"})
        _           (recipes/add-audience-to-recipe! db (assoc shared :recipe-url "shared") {:id gid})]
    (testing "a group member sees public + shared, not hidden"
      (is (match? #{"public" "shared"}
                  (set (map :recipe-url (recipes/get-visible-recipes db {:hostname host} {:email "member@x.com"}))))))

    (testing "a non-member sees only public"
      (is (match? #{"public"}
                  (set (map :recipe-url (recipes/get-visible-recipes db {:hostname host} {:email "stranger@x.com"}))))))

    (testing "an anonymous caller (no email) sees only public"
      (is (match? #{"public"}
                  (set (map :recipe-url (recipes/get-visible-recipes db {:hostname host} {}))))))))

(deftest audience-idempotency-test
  (let [db        (embedded-pg/fresh-db!)
        recipe    (recipes/create-recipe! db (example-recipe))
        [{gid :id}] (groups/create-group! db {:display-name "g" :owner-id "u"})]
    (let [[{aid :id}] (recipes/add-audience-to-recipe! db recipe {:id gid})]
      (testing "adding the same audience twice does not create a duplicate"
        (is (match? [{:id aid}] (recipes/add-audience-to-recipe! db recipe {:id gid}))))
      (testing "can delete an audience"
        (recipes/delete-recipe-audience! db aid)
        (is (empty? (recipes/get-recipe-audiences db {:id aid})))))))
