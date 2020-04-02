(ns andrewslai.clj.auth.crypto-test
  (:require [andrewslai.clj.auth.crypto :as crypto :refer :all]
            [clojure.test :refer [deftest testing is]]))

(deftest encryption-test
  (testing "I can encrypt and decrypt a password"
    (let [encrypted-password (encrypt (make-encryption) "foobar")]
      (is (check (make-encryption) "foobar" encrypted-password)))))


