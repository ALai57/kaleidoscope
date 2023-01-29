(ns andrewslai.clj.http-api.middleware-test
  (:require [amazonica.aws.s3 :as s3]
            [andrewslai.clj.http-api.auth.buddy-backends :as bb]
            [andrewslai.clj.http-api.middleware :as sut]
            [andrewslai.clj.persistence.filesystem.s3-impl :as s3-storage]
            [andrewslai.clj.test-utils :as tu]
            [andrewslai.clj.test-main :as tm]
            [biiwide.sandboxica.alpha :as sandbox]
            [cheshire.core :as json]
            [clojure.test :refer :all]
            [lambdaisland.deep-diff2 :as ddiff]
            [ring.mock.request :as mock]
            [ring.util.response :as response]
            [taoensso.timbre :as log]
            [matcher-combinators.test :refer [match?]]
            [lambdaisland.deep-diff2.diff-impl :refer [map->Insertion map->Deletion map->Mismatch]]
            ))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(deftest remove-unchanged-basic-test
  (testing "Addition"
    (is (= {(map->Insertion {:+ :b}) 2}
           (sut/remove-unchanged (ddiff/diff {}
                                             {:b 2})))))
  (testing "Deletion"
    (is (= {(map->Deletion  {:- :a}) 1}
           (sut/remove-unchanged (ddiff/diff {:a 1}
                                             {})))))

  (testing "Mismatch"
    (is (= {:a (map->Mismatch {:- 1 :+ 2})}
           (sut/remove-unchanged (ddiff/diff {:a 1}
                                             {:a 2}))))))

(deftest remove-unchanged-collections
  (is (= [(map->Deletion {:- 2}) (map->Insertion {:+ 4})]
         (sut/remove-unchanged (ddiff/diff [1 2 3]
                                           [1 3 4])))))

(deftest elements-are-not-changed
  (are [description duplicated-element]
    (testing description
      (is (nil?
           (sut/remove-unchanged (ddiff/diff duplicated-element
                                             duplicated-element)))))

    "Basic key-value pair is removed"
    {:a 1}

    "Empty map is removed"
    {:a {} :b {}}

    "Map is removed"
    {:a {:mw :b}}

    "Vector with empty maps is removed"
    {:a [{}]}

    "Vector with populated map is removed"
    {:a [{:a #{:b}}]}))

(deftest remove-unchanged-with-map
  (is (= {:b                        (map->Mismatch {:- 3 :+ 2})
          (map->Deletion {:- :foo}) "bar"
          (map->Insertion {:+ :c})  3
          :d                        {:e (map->Mismatch {:- "f" :+ "g"})}}
         (sut/remove-unchanged (ddiff/diff {:a   "1"
                                            :b   3
                                            :foo "bar"
                                            :d   {:e "f"}
                                            :x   [{:y :z}]}
                                           {:a "1"
                                            :b 2
                                            :c 3
                                            :d {:e "g"}
                                            :x [{:y :z}]})))))

(deftest standard-stack-test
  (let [captured-request (atom nil)
        app              (sut/standard-stack (fn [req]
                                               (reset! captured-request req)
                                               {:status 200
                                                :body   {:foo "bar"}}))]
    (is (match? {:status  200
                 :headers {"Content-Type" #"application/json"}
                 :body    (json/generate-string {:foo "bar"})}
                (app (mock/request :get "/"))))
    (is (match? {:uri        "/"
                 :request-id string?}
                @captured-request))))

(deftest auth-stack-happy-path-test
  (let [captured-request (atom nil)
        mw-stack         (sut/auth-stack (bb/authenticated-backend {:realm_access {:roles ["myrole"]}})
                                         (tu/restricted-access "myrole"))
        app              (mw-stack (fn [req]
                                     (reset! captured-request req)
                                     {:status 200
                                      :body   {:foo "bar"}}))]
    (is (match? {:status 200
                 :body   {:foo "bar"}}
                (app (-> (mock/request :get "/")
                         (mock/header "Authorization" "Bearer x")))))
    (is (match? {:identity {:realm_access {:roles ["myrole"]}}}
                @captured-request))))

(deftest auth-stack-wrong-role-test
  (let [captured-request (atom nil)
        mw-stack         (sut/auth-stack (bb/authenticated-backend {:realm_access {:roles ["myrole"]}})
                                         (tu/restricted-access "wrongrole"))
        app              (mw-stack (fn [req]
                                     (reset! captured-request req)
                                     {:status 200
                                      :body   {:foo "bar"}}))]
    (is (match? {:status 401}
                (app (-> (mock/request :get "/")
                         (mock/header "Authorization" "Bearer x")))))
    (is (nil? (:identity @captured-request)))))
