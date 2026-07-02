(ns kaleidoscope.api.auth0-claims-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.auth0-claims :as auth0]))

(deftest namespaced-email-test
  (is (= "checkly-monitor@synthetic.kaleidoscope"
         (auth0/namespaced-email {(keyword "https://kaleidoscope.pub/email") "checkly-monitor@synthetic.kaleidoscope"})))
  (is (nil? (auth0/namespaced-email {}))))

(deftest namespaced-email-verified-test
  (is (true? (auth0/namespaced-email-verified {(keyword "https://kaleidoscope.pub/email_verified") true})))
  (is (nil? (auth0/namespaced-email-verified {}))))

(deftest m2m-token?-test
  (testing "gty=client-credentials is an M2M token"
    (is (true? (auth0/m2m-token? {:gty "client-credentials"}))))

  (testing "Other gty values are not M2M tokens"
    (is (false? (auth0/m2m-token? {:gty "refresh-token"}))))

  (testing "Missing gty is not an M2M token"
    (is (false? (auth0/m2m-token? {})))))
