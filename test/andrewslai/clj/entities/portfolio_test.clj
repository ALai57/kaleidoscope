(ns andrewslai.clj.entities.portfolio-test
  (:require [andrewslai.clj.persistence.rdbms.embedded-h2-impl :as rdbms.embedded-h2-impl]
            [andrewslai.clj.entities.portfolio :as portfolio]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [clojure.test :refer [deftest is use-fixtures]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest get-portfolio-test
  (let [db (embedded-h2/fresh-db!)]
    (is (portfolio/portfolio? (portfolio/get-portfolio db)))))
