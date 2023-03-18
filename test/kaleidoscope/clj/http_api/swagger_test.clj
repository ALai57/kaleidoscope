(ns kaleidoscope.clj.http-api.swagger-test
  (:require [kaleidoscope.clj.http-api.swagger :as swg]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]))

(deftest extract-swagger-specs-test
  (is (= {"article" {:spec :kaleidoscope.article/title}}
         (swg/extract-specs {:paths [["/" {:get
                                           {:components
                                            {:schemas
                                             {"article"
                                              {:spec :kaleidoscope.article/title}}}}}]]}))))

(deftest swagger-specs->components-test
  (is (= {"article" {:type "string"
                     :title "kaleidoscope.article/title"}}
         (swg/specs->components {"article" {:spec :kaleidoscope.article/title}}))))

(deftest valid-examples-test
  (for [[k {:keys [value]}] swg/example-data-2]
    (is (s/valid? k value))))
