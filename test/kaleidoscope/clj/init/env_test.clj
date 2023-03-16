(ns kaleidoscope.clj.init.env-test
  (:require [kaleidoscope.clj.init.env :as sut]
            [clojure.test :refer :all]
            [matcher-combinators.test :refer [match?]]
            [matcher-combinators.result :as result]))

(deftest env->pg-conn-throws-when-missing-env-vars
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Missing DB name. Set via ANDREWSLAI_DB_NAME environment variable."
                        (sut/env->pg-conn {})))
  (is (not (re-matches #"Missing DB name. Set via ANDREWSLAI_DB_NAME environment variable."
                       (try
                         (sut/env->pg-conn {"ANDREWSLAI_DB_NAME" "Hi"})
                         (throw (Exception. "Should blow up before this with ex-info"))
                         (catch clojure.lang.ExceptionInfo e
                           (ex-message e)))))))
