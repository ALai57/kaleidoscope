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

(deftest s3-static-content-launcher-adds-ephemeral-host-alias-when-configured
  (let [s3-launcher (get-in sut/kaleidoscope-static-content-adapter-boot-instructions [:launchers "s3"])
        base-hosts  #{"kaleidoscope.pub" "kaleidoscope.client" "andrewslai.com" "caheriaguilar.com"
                      "sahiltalkingcents.com" "caheriaguilar.and.andrewslai.com" "andrewslai.com.localhost"}]
    (testing "unset ephemeral env vars leave the adapter map unchanged"
      (is (= base-hosts (set (keys (s3-launcher {}))))))

    (testing "setting both ephemeral env vars adds an alias entry pointed at the given bucket"
      (let [adapters (s3-launcher {"KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS"  "kal-eph-foo.fly.dev"
                                   "KALEIDOSCOPE_EPHEMERAL_HOST_BUCKET" "kal-ephemeral"})]
        (is (= (conj base-hosts "kal-eph-foo.fly.dev") (set (keys adapters))))
        (is (= "kal-ephemeral" (:storage-root (get adapters "kal-eph-foo.fly.dev"))))
        (is (nil? (:prefix (get adapters "kal-eph-foo.fly.dev"))))))

    (testing "also setting the prefix env var scopes the ephemeral adapter to that key prefix"
      (let [adapters (s3-launcher {"KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS"  "kal-eph-foo.fly.dev"
                                   "KALEIDOSCOPE_EPHEMERAL_HOST_BUCKET" "kal-ephemeral"
                                   "KALEIDOSCOPE_EPHEMERAL_HOST_PREFIX" "eph-foo/"})]
        (is (= "eph-foo/" (:prefix (get adapters "kal-eph-foo.fly.dev"))))))

    (testing "setting only one of the two ephemeral env vars leaves the adapter map unchanged"
      (is (= base-hosts (set (keys (s3-launcher {"KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS" "kal-eph-foo.fly.dev"})))))
      (is (= base-hosts (set (keys (s3-launcher {"KALEIDOSCOPE_EPHEMERAL_HOST_BUCKET" "kal-ephemeral"}))))))))
