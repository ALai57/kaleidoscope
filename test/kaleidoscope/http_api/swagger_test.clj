(ns kaleidoscope.http-api.swagger-test
  (:require [kaleidoscope.http-api.swagger :as swg]
            [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is]]))

(deftest extract-swagger-specs-test
  (is (= {"article" {:spec :kaleidoscope.article/article-title}}
         (swg/extract-specs {:paths [["/" {:get
                                           {:components
                                            {:schemas
                                             {"article"
                                              {:spec :kaleidoscope.article/article-title}}}}}]]}))))

(deftest swagger-specs->components-test
  (is (= {"article" {:type "string"
                     :title "kaleidoscope.article/article-title"}}
         (swg/specs->components {"article" {:spec :kaleidoscope.article/article-title}}))))

#_(deftest valid-examples-test
    (doseq [[k {:keys [value]}] swg/example-data-2]
      (is (s/valid? k value))))
