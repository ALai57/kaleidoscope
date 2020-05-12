(ns andrewslai.cljs.events.users-test
  (:require [andrewslai.cljs.events.users :as u]
            [cljs.test :refer-macros [deftest is testing]]))

(deftest login
  (let [user {:username "admin"
              :first_name "Andrew"
              :last_name "Lai"}]
    (testing "Login"
      (is (= {:user user}
             (u/load-user-profile {:user nil} [nil user]))))))

(deftest logout
  (testing "Logout"
    (is (= {:user nil} (u/logout {:user {:username "Andrew"}})))))


