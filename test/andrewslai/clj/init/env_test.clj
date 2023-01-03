(ns andrewslai.clj.init.env-test
  (:require [andrewslai.clj.init.env :as sut]
            [clojure.test :refer :all]))


(deftest parse-port-test
  (is (= 1000 (sut/env->port {"ANDREWSLAI_PORT" "1000"})))
  (is (= 5000 (sut/env->port {}))))

(deftest parse-log-level-test
  (is (= :info (sut/env->log-level {"ANDREWSLAI_LOG_LEVEL" "info"})))
  (is (= :info (sut/env->log-level {}))))

(deftest parse-db-type-test
  (is (= :memory (sut/env->db-type {"ANDREWSLAI_DB_TYPE" "memory"})))
  (is (= :postgres (sut/env->db-type {}))))


(deftest parse-wedding-static-content-type-test
  (is (= :s3 (sut/env->wedding-static-content-type {"ANDREWSLAI_WEDDING_STATIC_CONTENT_TYPE" "s3"})))
  (is (= :none (sut/env->wedding-static-content-type {}))))

(deftest parse-wedding-static-content-folder-test
  (is (= "src/something" (sut/env->wedding-local-static-content-folder {"ANDREWSLAI_WEDDING_STATIC_CONTENT_FOLDER" "src/something"})))
  (is (= "resources/public" (sut/env->wedding-local-static-content-folder {}))))

(deftest parse-wedding-auth-type-test
  (is (= :none (sut/env->wedding-authentication-type {"ANDREWSLAI_WEDDING_AUTH_TYPE" "none"})))
  (is (= :keycloak (sut/env->wedding-authentication-type {}))))


(deftest parse-andrewslai-static-content-type-test
  (is (= :s3 (sut/env->andrewslai-static-content-type {"ANDREWSLAI_STATIC_CONTENT_TYPE" "s3"})))
  (is (= :none (sut/env->andrewslai-static-content-type {}))))

(deftest parse-andrewslai-static-content-folder-test
  (is (= "src/something/else" (sut/env->andrewslai-local-static-content-folder {"ANDREWSLAI_STATIC_CONTENT_FOLDER" "src/something/else"})))
  (is (= "resources/public" (sut/env->andrewslai-local-static-content-folder {}))))

(deftest parse-auth-type-test
  (is (= :none (sut/env->andrewslai-authentication-type {"ANDREWSLAI_AUTH_TYPE" "none"})))
  (is (= :keycloak (sut/env->andrewslai-authentication-type {}))))
