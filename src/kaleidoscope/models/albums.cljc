(ns kaleidoscope.models.albums
  (:require
   #?@(:clj  [[clojure.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]]
       :cljs [[cljs.spec.alpha :as s]
              [clojure.test.check.generators :as gen]
              [spec-tools.spec :as spec]])))

(def timestamp (s/or :date spec/inst? :string spec/string?))

(s/def :kaleidoscope.albums/id spec/integer?)
(s/def :kaleidoscope.albums/album-name spec/string?)
(s/def :kaleidoscope.albums/created-at timestamp)
(s/def :kaleidoscope.albums/modified-at timestamp)

(s/def :kaleidoscope.albums/album
  (s/keys :req-un [:kaleidoscope.albums/album-name]
          :opt-un [:kaleidoscope.albums/id
                   :kaleidoscope.albums/created-at
                   :kaleidoscope.albums/modified-at]))

(s/def :kaleidoscope.albums/albums
  (s/coll-of :kaleidoscope.albums/album))

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

  (gen/sample (s/gen :kaleidoscope.albums/album))

  (s/valid? :kaleidoscope.albums/album example-album)
  )
