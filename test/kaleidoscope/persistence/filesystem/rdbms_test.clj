(ns kaleidoscope.persistence.filesystem.rdbms-test
  (:require
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [honey.sql.helpers :as hh]
   [kaleidoscope.persistence.rdbms :as rdbms]
   [kaleidoscope.test-main :as tm]
   [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(def example-honey-insert
  (let [m {:display-name "another-theme"
           :id           "my-id"
           :config       (json/encode {:secondary {:something "else"}})}]
    (-> (hh/insert-into :themes)
        (hh/values [m]))))

(def example-honey-upsert
  (let [m {:display-name "another-theme"
           :id           "my-id"
           :config       (json/encode {:secondary {:something "else"}})}]
    (-> (hh/insert-into :themes)
        (hh/values [m])
        (hh/on-conflict :id)
        (hh/do-update-set (dissoc m :id)))))

(deftest hsql-returning-*-test
  (testing "happy path"
    (is (= ["select * from final table (INSERT INTO themes (display_name, id, config) VALUES (?, ?, ?))"
            "another-theme"
            "my-id"
            "{\"secondary\":{\"something\":\"else\"}}"]
           (rdbms/hsql-insert example-honey-insert)))))

(deftest hsql-upsert-test
  (testing "happy path"
    (is (= ["SELECT * FROM FINAL TABLE (MERGE INTO themes AS target USING (VALUES (?, ?, ?)) AS source(display_name, id, config) ON source.id = target.id WHEN MATCHED THEN UPDATE SET target.display_name = source.display_name,target.config = source.config WHEN NOT MATCHED THEN INSERT (display_name, id, config) VALUES (source.display_name, source.id, source.config))"
            "another-theme"
            "my-id"
            "{\"secondary\":{\"something\":\"else\"}}"]
           (rdbms/hsql-upsert example-honey-upsert)))))


(comment
  ;; From HSQL documentation:
  ;; https://h2database.com/html/commands.html?highlight=merge%2Cusing&search=merge%20using#merge_into
  ;; MERGE INTO TARGET T USING (VALUES (1, 4), (2, 15)) S(ID, V)
  ;; ON T.ID = S.ID
  ;; WHEN MATCHED THEN UPDATE SET V = S.V
  ;; WHEN NOT MATCHED THEN INSERT VALUES (S.ID, S.V);

  (def example-raw
    ;; => {:insert-into [:themes],
    ;;     :values
    ;;     [{:display-name "another-theme",
    ;;       :id "my-id",
    ;;       :config "{\"secondary\":{\"something\":\"else\"}}"}],
    ;;     :on-conflict [:id],
    ;;     :do-update-set
    ;;     [{:display-name "another-theme",
    ;;       :config "{\"secondary\":{\"something\":\"else\"}}"}]}

    (let [m {:display-name "another-theme"
             :id           "my-id"
             :config       (json/encode {:secondary {:something "else"}})}]
      (-> (hh/insert-into :themes)
          (hh/values [m])
          (hh/on-conflict :id)
          (hh/do-update-set (dissoc m :id)))))

  (def example-formatted
    (hsql/format example-raw))

  (using example-raw)
  (on example-raw)
  (not-matched example-raw)
  )
