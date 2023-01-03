(ns andrewslai.clj.api.portfolio-test
  (:require [andrewslai.clj.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [andrewslai.clj.api.portfolio :as portfolio]
            [clojure.test :refer :all]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest get-portfolio-test
  (let [db (embedded-h2/fresh-db!)]
    (is (portfolio/portfolio? (portfolio/get-portfolio db)))))