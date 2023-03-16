(ns kaleidoscope.cljc.specs.albums
  (:require
   #?@(:clj  [[clojure.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]]
       :cljs [[cljs.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]])))

(def timestamp (s/or :date spec/inst? :string spec/string?))

(s/def :andrewslai.albums/id spec/integer?)
(s/def :andrewslai.albums/album-name spec/string?)
(s/def :andrewslai.albums/created-at timestamp)
(s/def :andrewslai.albums/modified-at timestamp)

(s/def :andrewslai.albums/album
  (s/keys :req-un [:andrewslai.albums/album-name]
          :opt-un [:andrewslai.albums/id
                   :andrewslai.albums/created-at
                   :andrewslai.albums/modified-at]))

(s/def :andrewslai.albums/albums
  (s/coll-of :andrewslai.albums/album))

(def example-album
  {:album-name     "hello"
   :description    "first album"
   :cover-photo-id #uuid "d947c6b0-679f-4067-9747-3d282833a27d"
   :created-at     "2020-10-28T02:55:27Z",
   :modified-at    "2020-10-28T02:55:27Z",})

(def example-album-2
  {:album-name     "bye"
   :description    "secondalbum"
   :cover-photo-id #uuid "d947c6b0-679f-4067-9747-999999999999"
   :created-at     "2022-10-01T02:55:27Z",
   :modified-at    "2022-10-01T02:55:27Z",})

(comment
  ;; Example data for article spec

  (gen/sample (s/gen :andrewslai.albums/album))

  (s/valid? :andrewslai.albums/album example-album)
  )
