(ns kaleidoscope.api.portfolio-test
  (:require [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.api.portfolio :as portfolio]
            [kaleidoscope.test-main :as tm]
            [clojure.test :refer :all]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(deftest get-portfolio-test
  (let [db (embedded-h2/fresh-db!)]
    (is (portfolio/portfolio? (portfolio/get-portfolio db)))))
