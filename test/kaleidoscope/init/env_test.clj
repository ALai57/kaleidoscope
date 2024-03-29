(ns kaleidoscope.init.env-test
  (:require [clojure.test :refer :all]
            [kaleidoscope.init.env :as sut]
            [kaleidoscope.main :as main]
            [malli.instrument :as mi]))

(deftest env->pg-conn-throws-when-missing-env-vars
  (main/initialize-schema-enforcement!)
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Missing DB name. Set via KALEIDOSCOPE_DB_NAME environment variable."
                        (sut/env->pg-conn {})))
  (is (not (re-matches #"Missing DB name. Set via KALEIDOSCOPE_DB_NAME environment variable."
                       (try
                         (sut/env->pg-conn {"KALEIDOSCOPE_DB_NAME" "Hi"})
                         (throw (Exception. "Should blow up before this with ex-info"))
                         (catch clojure.lang.ExceptionInfo e
                           (ex-message e))))))

  (mi/unstrument!))
