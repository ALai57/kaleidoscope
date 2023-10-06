(ns kaleidoscope.api.portfolio-test
  (:require [clojure.test :refer :all]
            [kaleidoscope.api.portfolio :as portfolio]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.test-main :as tm]
            [kaleidoscope.test-utils :as tu]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (tu/with-schema-enforcement 'kaleidoscope.api.portfolio
        (f)))))

(deftest get-portfolio-test
  (let [db (embedded-h2/fresh-db!)]
    (is (portfolio/get-portfolio db))))
