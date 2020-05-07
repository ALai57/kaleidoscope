(ns andrewslai.cljs.events.login-test
  (:require [andrewslai.cljs.events.login :as l]
            [cljs.test :as t :refer-macros [deftest is testing]]))

(deftest login-events
  (let [user {:username "admin"
              :first_name "Andrew"
              :last_name "Lai"}]
    (testing "Login"
      (let [db {:user nil}]
        (is (= {:user user} (l/login db [nil user])))))
    (testing "Unsuccessful Login"
      (let [db {:user user}]
        (is (= db (l/invalid-login db [nil "admin" {}])))))))

(deftest logout-events
  (testing "Logout"
    (let [db {:user {:username "Andrew"}}]
      (is (= {:user nil} (l/logout db))))))


