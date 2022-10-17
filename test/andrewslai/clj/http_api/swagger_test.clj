(ns andrewslai.clj.http-api.swagger-test
  (:require [andrewslai.clj.http-api.swagger :as swg]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]))

(deftest extract-swagger-specs-test
  (is (= {"article" {:spec :andrewslai.article/title}}
         (swg/extract-specs {:paths [["/" {:get
                                           {:components
                                            {:schemas
                                             {"article"
                                              {:spec :andrewslai.article/title}}}}}]]}))))

(deftest swagger-specs->components-test
  (is (= {"article" {:type "string"
                     :title "andrewslai.article/title"}}
         (swg/specs->components {"article" {:spec :andrewslai.article/title}}))))

(deftest valid-examples-test
  (for [[k {:keys [value]}] swg/example-data-2]
    (is (s/valid? k value))))
