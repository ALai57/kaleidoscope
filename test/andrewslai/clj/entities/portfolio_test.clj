(ns andrewslai.clj.entities.portfolio-test
  (:require [andrewslai.clj.embedded-h2 :refer [with-embedded-h2]]
            [andrewslai.clj.entities.portfolio :as portfolio]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [clojure.test :refer [deftest is use-fixtures]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest get-portfolio-test
  (with-embedded-h2 datasource
    (is (portfolio/portfolio?
         (portfolio/get-portfolio (pg/->NextDatabase datasource))))))
