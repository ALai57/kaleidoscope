(ns andrewslai.clj.auth.core-test
  (:require [andrewslai.clj.auth.core :refer :all]
            [clojure.test :refer [deftest is]]))

(def example-header "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9")
(def example-body "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ==")
(def example-signature "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c")

(def valid-token
  (clojure.string/join "." [(clj->b64 {:alg "HS256" :typ "JWT"})
                            (clj->b64 {:sub "1234567890", :name "John Doe", :iat 1516239022})
                            example-signature]))

(deftest encoding-test
  (is (= example-header (clj->b64 {:alg "HS256" :typ "JWT"})))
  (is (= example-body (clj->b64 {:sub "1234567890", :name "John Doe", :iat 1516239022}))))

(deftest encoding-decoding-test
  (let [m {:alg "HS256" :typ "JWT"}]
    (is (= m (b64->clj (clj->b64 m))))))

(deftest jwt-header-test
  (let [m {:alg "HS256" :typ "JWT"}]
    (is (= m (jwt-header (format "%s.junk.junk" (clj->b64 m)))))
    (is (= m (jwt-header valid-token)))))

(deftest jwt-body-test
  (let [m {:sub "1234567890", :name "John Doe", :iat 1516239022}]
    (is (= m (jwt-body (format "junk.%s.junk" (clj->b64 m)))))
    (is (= m (jwt-body valid-token)))))
