(ns andrewslai.cljc.specs.albums
  (:require
   #?@(:clj  [[clojure.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]]
       :cljs [[cljs.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]])))

(s/def :andrewslai.album/id spec/integer?)
(s/def :andrewslai.album/album-name spec/string?)
(s/def :andrewslai.album/created-at (s/or :date spec/inst? :string spec/string?))
(s/def :andrewslai.album/modified-at (s/or :date spec/inst? :string spec/string?))

(s/def :andrewslai.album/album
  (s/keys :req-un [:andrewslai.album/album-name]
          :opt-un [:andrewslai.album/id
                   :andrewslai.album/created-at
                   :andrewslai.album/modified-at]))

(s/def :andrewslai.album/albums
  (s/coll-of :andrewslai.album/album))

(def example-album
  {:album-name  "hello"
   :created-at  "2020-10-28T02:55:27Z",
   :modified-at "2020-10-28T02:55:27Z",})

(comment
  ;; Example data for article spec

  (gen/sample (s/gen :andrewslai.album/album))

  (s/valid? :andrewslai.album/album example-album)
  )
