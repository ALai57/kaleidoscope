(ns andrewslai.clj.routes.swagger-test
  (:require [andrewslai.clj.routes.swagger :as swg]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]))

(deftest extract-swagger-specs-test
  (let [swagger {:paths [["/" {:get
                               {:components
                                {:schemas
                                 {"user"
                                  {:spec :andrewslai.user/user-profile}}}}}]]}]
    (is (= {"user" {:spec :andrewslai.user/user-profile}}
           (swg/extract-specs swagger)))))

(deftest swagger-specs->components-test
  (is (= {"user" {:type "string"
                  :x-allOf [{:type "string"} {} {}]
                  :title "andrewslai.user/username"}}
         (swg/specs->components {"user" {:spec :andrewslai.user/username}}))))

(deftest valid-examples-test
  (for [[k {:keys [value]}] swg/example-data-2]
    (is (s/valid? k value))))
