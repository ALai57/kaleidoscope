(ns kaleidoscope.persistence.rdbms.embedded-h2-impl-test
  (:require
   [clojure.test :refer :all]
   [cheshire.core :as json]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [kaleidoscope.persistence.rdbms.embedded-h2-impl :as h2-impl]
   [kaleidoscope.test-main :as tm]
   [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(def example-payload
  {:display-name "another-theme"
   :id           "my-id"
   :config       (json/encode {:secondary {:something "else"}})})

(deftest hsql-upsert-test
  (testing "happy path"
    (is (= ["SELECT * FROM FINAL TABLE (MERGE INTO themes AS target USING (VALUES (?, ?, ?)) AS source(display_name, id, config) ON source.id = target.id WHEN MATCHED THEN UPDATE SET target.display_name = source.display_name,target.config = source.config WHEN NOT MATCHED THEN INSERT (display_name, id, config) VALUES (source.display_name, source.id, source.config))"
            "another-theme"
            "my-id"
            "{\"secondary\":{\"something\":\"else\"}}"]
           (h2-impl/hsql-upsert :themes example-payload)))))
