(ns andrewslai.clj.init.env-test
  (:require [andrewslai.clj.init.env :as sut]
            [clojure.test :refer :all]
            [matcher-combinators.test :refer [match?]]
            [matcher-combinators.result :as result]))

(deftest environment->launch-options-defaults-test
  (is (= {:port  5000
          :level :info

          :database   {:db-type             :postgres}
          :andrewslai {:authentication-type :keycloak
                       :authorization-type  :use-access-control-list
                       :static-content-type :none}
          :wedding    {:authentication-type :keycloak
                       :authorization-type  :use-access-control-list
                       :static-content-type :none}}
         (sut/environment->launch-options {}))))

(deftest environment->launch-options-parsing-test
  (are [expected environment]
    (is (match? expected (sut/environment->launch-options environment)))

    {:port 4001}    {"ANDREWSLAI_PORT" "4001"}
    {:level :debug} {"ANDREWSLAI_LOG_LEVEL" "debug"}

    {:database {:db-type :h2}} {"ANDREWSLAI_DB_TYPE" "h2"}

    {:andrewslai {:authentication-type :always-unauthenticated
                  :authorization-type  :use-access-control-list
                  :static-content-type :s3}}
    {"ANDREWSLAI_AUTH_TYPE"           "always-unauthenticated"
     "ANDREWSLAI_AUTHORIZATION_TYPE"  "use-access-control-list"
     "ANDREWSLAI_STATIC_CONTENT_TYPE" "s3"}


    {:wedding {:authentication-type :always-unauthenticated
               :authorization-type  :use-access-control-list
               :static-content-type :s3}}
    {"ANDREWSLAI_WEDDING_AUTH_TYPE"           "always-unauthenticated"
     "ANDREWSLAI_WEDDING_AUTHORIZATION_TYPE"  "use-access-control-list"
     "ANDREWSLAI_WEDDING_STATIC_CONTENT_TYPE" "s3"}
    ))

(deftest env->pg-conn-throws-when-missing-env-vars
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Missing DB name. Set via ANDREWSLAI_DB_NAME environment variable."
                        (sut/env->pg-conn {})))
  (is (re-matches #"Missing DB name. Set via ANDREWSLAI_DB_NAME environment variable."
       (try
         (sut/env->pg-conn {"ANDREWSLAI_DB_NAME" "Hi"})
         (throw (Exception. "Should blow up before this with ex-info"))
         (catch clojure.lang.ExceptionInfo e
           (ex-message e))))))
