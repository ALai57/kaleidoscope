(ns andrewslai.clj.persistence.memory-test
  (:require [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.persistence.memory :refer :all]
            [clojure.java.io :as io]
            [clojure.test :refer [is deftest]]
            [matcher-combinators.test]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.clojure-test :refer [defspec]]
            [andrewslai.generators.files :as gen-file]
            [clojure.test.check.generators :as gen]))

(defn buffered-input-stream?
  [obj]
  (= (class obj) java.io.BufferedInputStream))

(deftest file-test
  (is (file? (tag-as {} 'file))))

(deftest memfs-test
  (let [db    (atom {})
        memfs (map->MemFS {:store db})]
    (is (nil? (fs/ls memfs "var")))
    (is (match? {:name "afile.txt"
                 :path "var/afile.txt"
                 :content buffered-input-stream?
                 :metadata {:content-type "text/html"}}
                (fs/put-file memfs
                             "var/afile.txt"
                             (io/input-stream (.getBytes "<h1>Hello</h1>"))
                             {:content-type "text/html"})))
    (is (match? {:name "afile.txt"
                 :path "var/afile.txt"
                 :content buffered-input-stream?
                 :metadata {:content-type "text/html"}}
                (fs/get-file memfs "var/afile.txt")))))

(defspec memfs-spec
  (prop/for-all [path     gen-file/gen-path
                 fname    gen-file/gen-filename
                 content  gen/any
                 metadata (gen/map gen/simple-type-printable-equatable
                                   gen/simple-type-printable-equatable)]
    (let [db    (atom {})
          memfs (map->MemFS {:store db})

          fullpath (str path "/" fname)
          file     {:name     fname
                    :path     fullpath
                    :content  content
                    :metadata metadata}]
      (is (nil? (fs/ls memfs "var")))
      (is (match? file (fs/put-file memfs fullpath content metadata)))
      (is (match? file (fs/get-file memfs fullpath))))))
