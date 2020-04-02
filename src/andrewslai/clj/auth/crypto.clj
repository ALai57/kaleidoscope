(ns andrewslai.clj.auth.crypto
  (:require [crypto.password.bcrypt :as password]))

(defprotocol Encryption
  (encrypt [_ password])
  (check [_ candidate-password db-password]))

(defn make-encryption []
  (reify Encryption
    (encrypt [_ password]
      (password/encrypt password (:work-factor @env/env)))
    (check [_ candidate-password db-password]
      (password/check candidate-password db-password))))

;; https://nakedsecurity.sophos.com/2013/11/20/serious-security-how-to-store-your-users-passwords-safely/
