(ns kaleidoscope.api.authentication-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.authentication :as oidc]))

(deftest get-email-test
  (testing "Flat :email claim (e.g. regular user ID tokens)"
    (is (= "a@a.com" (oidc/get-email {:email "a@a.com"}))))

  (testing "Namespaced claim (e.g. M2M access tokens, which require namespaced custom claims)"
    (is (= "checkly-monitor@synthetic.kaleidoscope"
           (oidc/get-email {(keyword "https://kaleidoscope.pub/email") "checkly-monitor@synthetic.kaleidoscope"}))))

  (testing "Flat claim takes precedence when both are present"
    (is (= "a@a.com" (oidc/get-email {:email                                        "a@a.com"
                                       (keyword "https://kaleidoscope.pub/email")   "other@example.com"}))))

  (testing "No claim present"
    (is (nil? (oidc/get-email {})))))

(deftest email-verified?-test
  (testing "Flat :email_verified claim"
    (is (true? (oidc/email-verified? {:email_verified true})))
    (is (false? (oidc/email-verified? {:email_verified false}))))

  (testing "Namespaced claim"
    (is (true? (oidc/email-verified? {(keyword "https://kaleidoscope.pub/email_verified") true}))))

  (testing "No claim present"
    (is (nil? (oidc/email-verified? {})))))

(deftest get-verified-email-test
  (testing "Verified flat email is returned"
    (is (= "a@a.com" (oidc/get-verified-email {:email "a@a.com" :email_verified true}))))

  (testing "Verified namespaced email is returned"
    (is (= "checkly-monitor@synthetic.kaleidoscope"
           (oidc/get-verified-email {(keyword "https://kaleidoscope.pub/email")          "checkly-monitor@synthetic.kaleidoscope"
                                      (keyword "https://kaleidoscope.pub/email_verified") true}))))

  (testing "Unverified email returns nil"
    (is (nil? (oidc/get-verified-email {:email "a@a.com" :email_verified false}))))

  (testing "Missing email_verified claim returns nil"
    (is (nil? (oidc/get-verified-email {:email "a@a.com"})))))

(deftest classify-identity-test
  (testing "Verified email produces :verified-user with email as :user-id"
    (is (= {:type    :verified-user
            :user-id "a@a.com"
            :roles   #{"admin"}}
           (oidc/classify-identity {:email          "a@a.com"
                                    :email_verified true
                                    :realm_access   {:roles ["admin"]}}))))

  (testing "gty=client-credentials produces :service-account with :sub as :user-id, regardless of email_verified"
    (is (= {:type    :service-account
            :user-id "client-id-123"
            :roles   #{"andrewslai.com:writer"}}
           (oidc/classify-identity {:sub            "client-id-123"
                                    :gty            "client-credentials"
                                    :email_verified false
                                    :realm_access   {:roles ["andrewslai.com:writer"]}}))))

  (testing "Missing gty produces a human classification, not :service-account"
    (is (not= :service-account
              (:type (oidc/classify-identity {:sub "svc" :realm_access {:roles []}})))))

  (testing "Unverified email with no gty produces :unverified-user with email as :user-id"
    (is (= {:type    :unverified-user
            :user-id "a@a.com"
            :roles   #{}}
           (oidc/classify-identity {:email          "a@a.com"
                                    :email_verified false
                                    :realm_access   {:roles []}}))))

  (testing "A gty other than client-credentials does not produce :service-account"
    (is (= :verified-user
           (:type (oidc/classify-identity {:email          "a@a.com"
                                           :email_verified true
                                           :gty            "refresh-token"
                                           :realm_access   {:roles []}})))))

  (testing "Namespaced email_verified claim is respected"
    (is (= :verified-user
           (:type (oidc/classify-identity
                   {(keyword "https://kaleidoscope.pub/email")          "m@m.com"
                    (keyword "https://kaleidoscope.pub/email_verified") true
                    :realm_access {:roles []}})))))

  (testing ":roles is always a set"
    (is (set? (:roles (oidc/classify-identity {:email_verified false :realm_access {:roles []}}))))
    (is (set? (:roles (oidc/classify-identity {:email "a@a.com" :email_verified true :realm_access {:roles ["r"]}}))))))