(ns kaleidoscope.persistence.filesystem.in-memory-impl-test
  (:require [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.persistence.filesystem.in-memory-impl :as sut]
            [kaleidoscope.generators.files :as gen-file]
            [clojure.java.io :as io]
            [clojure.test :refer [is deftest]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [matcher-combinators.test :refer [match?]]))

(defn buffered-input-stream?
  [obj]
  (= (class obj) java.io.BufferedInputStream))

(deftest file-test
  (is (sut/file? (sut/tag-as {} 'file))))

(deftest memfs-test
  (let [db    (atom {})
        memfs (sut/map->MemFS {:store db})]
    (is (fs/does-not-exist? (fs/get memfs "var")))
    (is (match? {:name     "afile.txt"
                 :path     "var/afile.txt"
                 :version  "dfdfea2387423d4e92fcd1aa5f93c5f1"
                 :content  buffered-input-stream?
                 :metadata {:content-type "text/html"}}
                (fs/put-file memfs
                             "var/afile.txt"
                             (io/input-stream (.getBytes "<h1>Hello</h1>"))
                             {:content-type "text/html"})))
    (is (match? {:content buffered-input-stream?}
                (fs/get memfs "var/afile.txt")))))

(defspec memfs-spec {:reporter-fn (constantly nil)
                     ;;:num-tests   10000
                     }
  (prop/for-all [path     gen-file/gen-path
                 fname    gen-file/gen-filename
                 content  gen/simple-type-printable-equatable
                 metadata (gen/map gen/simple-type-printable-equatable
                                   gen/simple-type-printable-equatable)]
    (let [db    (atom {})
          memfs (sut/map->MemFS {:store db})

          fullpath (str path "/" fname)
          file     {:name     fname
                    :path     fullpath
                    :content  content
                    :metadata metadata}]
      (is (match? file (fs/put-file memfs fullpath content metadata)))
      (is (match? {:content content} (fs/get memfs fullpath))))))
